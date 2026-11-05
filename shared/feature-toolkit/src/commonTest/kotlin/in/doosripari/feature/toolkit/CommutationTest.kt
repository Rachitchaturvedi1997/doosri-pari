package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.CommutationInput
import `in`.doosripari.core.model.CommutationTable
import `in`.doosripari.core.model.PayBasis
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Commutation, against plan section 6.5's four lines:
 *
 * ```
 * factor = COMMUTATION_TABLE[age_next_birthday]
 * lump_sum = basic_pension × (percent_commuted / 100) × 12 × factor    # percent ≤ 40
 * reduced_pension = basic_pension × (1 − percent_commuted / 100)
 * restoration_date = commutation_date + 15 years
 * ```
 *
 * The table below is banded on age, as the plan says the table is. The field
 * it is stored in is called `serviceYears` because that is what core-model
 * named it — see the file comment in Commutation.kt. If that field is ever
 * renamed to what it actually keys on, these tests change in one place.
 */
class CommutationTest {

    private fun at(iso: String): Instant = Instant.parse("${iso}T00:00:00Z")

    /** Table C as it stood from 2000: older pensioners buy fewer months. */
    private val table2000 = listOf(
        CommutationTable(40.0, 10.0, at("2000-01-01"), sourceDocId = 7),
        CommutationTable(50.0, 9.0, at("2000-01-01"), sourceDocId = 7),
        CommutationTable(60.0, 8.0, at("2000-01-01"), sourceDocId = 7),
    )

    /** A later order that lowers every factor. Must not touch a 2019 retirement. */
    private val table2024 = listOf(
        CommutationTable(40.0, 9.5, at("2024-01-01"), sourceDocId = 19),
        CommutationTable(50.0, 8.5, at("2024-01-01"), sourceDocId = 19),
        CommutationTable(60.0, 7.5, at("2024-01-01"), sourceDocId = 19),
    )

    private fun input(
        ceiling: Double = 1.0 / 3.0,
        post2000: Boolean = false,
        years: Double = 20.0,
        age: Int = 60,
    ) = CommutationInput(
        pay = PayBasis(basicPay = 30_000.0, da = 18_000.0),
        serviceYears = years,
        ageAtRetirement = age,
        ceilingFraction = ceiling,
        isPost2000Pensioner = post2000,
    )

    // ------------------------------------------------------- the ceiling does not bind --

    @Test
    fun `a pre-2000 pensioner commutes a third and the ceiling is not reported as binding`() {
        val result = CommutationCalculator.calculate(
            input(), table2000, asOf = at("2019-01-01"),
        )

        assertFalse(result.wasCapped, "a third is under the 40% bound, so nothing capped it")
        assertEquals(10_000.0, result.commutedPension, "a third of 30,000")
        assertEquals(20_000.0, result.residualPension)
        // 10,000 a month × 12 months × the factor of 8 for the 60 band.
        assertEquals(960_000.0, result.lumpSum)
        assertEquals("₹9,60,000", result.displayLumpSum)
    }

    // ------------------------------------------------------------ the ceiling binds --

    @Test
    fun `a post-2000 pensioner is capped at forty per cent and told so`() {
        val detail = CommutationCalculator.detail(
            input(ceiling = 1.0 / 2.0, post2000 = true), table2000, asOf = at("2019-01-01"),
        )

        assertTrue(detail.result.wasCapped, "a half is over the 40% bound")
        assertEquals(50.0, detail.percent.rulePercent, "the retirement rule allows a half")
        assertEquals(40.0, detail.percent.percent, "the bound is what was used")
        assertEquals(12_000.0, detail.result.commutedPension, "40% of 30,000")
        assertEquals(18_000.0, detail.result.residualPension, "60% is left behind")
        assertEquals(1_152_000.0, detail.result.lumpSum, "12,000 × 12 × 8")
    }

    @Test
    fun `commuted and residual always add back to last basic pay`() {
        for (ceiling in listOf(1.0 / 4.0, 1.0 / 3.0, 0.40, 1.0 / 2.0)) {
            for (post2000 in listOf(false, true)) {
                val r = CommutationCalculator.calculate(
                    input(ceiling = ceiling, post2000 = post2000), table2000, at("2019-01-01"),
                )
                assertEquals(
                    30_000.0,
                    r.commutedPension + r.residualPension,
                    "ceiling $ceiling, post2000 $post2000 must not leak pay",
                )
            }
        }
    }

    // ------------------------------------------------------------------ the quarter rule --

    @Test
    fun `under ten years of service on the pre-2000 rules a quarter is all that may be commuted`() {
        val detail = CommutationCalculator.detail(
            input(ceiling = 1.0 / 3.0, post2000 = false, years = 6.0),
            table2000, asOf = at("2019-01-01"),
        )

        assertTrue(detail.percent.usedQuarterRule)
        assertEquals(25.0, detail.percent.percent, "not the third the ceiling would allow")
        assertEquals(7_500.0, detail.result.commutedPension)
        assertEquals(22_500.0, detail.result.residualPension)
        assertFalse(detail.result.wasCapped, "25% is under the bound; the quarter rule is not a cap")
    }

    @Test
    fun `the quarter rule does not apply to a post-2000 pensioner`() {
        val detail = CommutationCalculator.detail(
            input(ceiling = 1.0 / 2.0, post2000 = true, years = 6.0),
            table2000, asOf = at("2019-01-01"),
        )
        assertFalse(detail.percent.usedQuarterRule)
        assertEquals(40.0, detail.percent.percent)
    }

    // -------------------------------------------------------------------- the table --

    @Test
    fun `the band is the longest one the pensioner reaches`() {
        // 45 falls in the 40 band, so factor 10.
        val young = CommutationCalculator.calculate(input(age = 45), table2000, at("2019-01-01"))
        assertEquals(1_200_000.0, young.lumpSum, "10,000 × 12 × 10")
    }

    @Test
    fun `a pensioner below the first band still gets the first band`() {
        val detail = CommutationCalculator.detail(input(age = 30), table2000, at("2019-01-01"))
        assertEquals(10.0, detail.factor, "the table has nothing below 40, so 40 applies")
    }

    @Test
    fun `a table published after retirement does not change the answer`() {
        val before = CommutationCalculator.calculate(input(), table2000 + table2024, at("2019-01-01"))
        assertEquals(960_000.0, before.lumpSum, "the 2024 order was not in force in 2019")
    }

    @Test
    fun `the 2024 order does apply to a retirement after it`() {
        val after = CommutationCalculator.calculate(input(age = 60), table2000 + table2024, at("2025-01-01"))
        assertEquals(900_000.0, after.lumpSum, "10,000 × 12 × 7.5")
        assertTrue(after.tableUsed.contains("19"), "the answer says which order it used")
    }

    @Test
    fun `no table in force yields zero and says so rather than guessing`() {
        val result = CommutationCalculator.calculate(input(), table2000, asOf = at("1990-01-01"))
        assertEquals(0.0, result.lumpSum)
        assertEquals(0.0, result.commutedPension)
        assertEquals(30_000.0, result.residualPension, "nothing commuted, nothing lost")
        assertEquals(CommutationCalculator.NO_TABLE, result.tableUsed)
    }

    @Test
    fun `an empty table is treated the same as one that does not reach back`() {
        val detail = CommutationCalculator.detail(input(), emptyList(), at("2019-01-01"))
        assertNull(detail.factor)
        assertEquals(0.0, detail.result.lumpSum)
    }

    // -------------------------------------------------------------------- restoration --

    @Test
    fun `the full pension comes back fifteen years after commutation`() {
        val commutedOn = at("2020-03-15")
        assertEquals(
            LocalDate(2035, 3, 15),
            CommutationCalculator.restorationDate(commutedOn),
        )
    }

    @Test
    fun `the restoration reminder is three months before that date`() {
        assertEquals(
            LocalDate(2034, 12, 15),
            CommutationCalculator.restorationReminderDate(at("2020-03-15")),
        )
    }

    @Test
    fun `a leap day does not lose a restoration date`() {
        // 15 years is not a fixed span, and a 15 × 365-day guess is a day wrong
        // in every leap year. The plan's rule is calendar years.
        assertEquals(
            LocalDate(2041, 2, 28),
            CommutationCalculator.restorationDate(at("2026-02-29")),
        )
    }

    // ------------------------------------------------------------------ the sentence --

    @Test
    fun `the explanation names the rule that decided the percentage`() {
        assertEquals(
            "33.3% of basic pay.",
            CommutationCalculator.percentFor(input()).explanation,
        )
        assertEquals(
            "Under 10 years of service on the pre-2000 rules, so a quarter of basic pay.",
            CommutationCalculator.percentFor(input(years = 6.0)).explanation,
        )
        assertEquals(
            "The post-2000 rules allow a half, cut to 40% by the 40% bound.",
            CommutationCalculator.percentFor(input(ceiling = 1.0 / 2.0, post2000 = true)).explanation,
        )
    }

    @Test
    fun `a ceiling can be asked for without a table`() {
        // The commonest question in the toolkit: "how much can I commute at all?"
        // It needs no Table C, only the rule, so it must not require one.
        assertEquals(1.0 / 3.0 * 100.0, CommutationCalculator.CommutationCeiling.percentFor(input()).percent)
        assertEquals(0.5, CommutationCalculator.CommutationCeiling.forPensioner(true))
        assertEquals(1.0 / 3.0, CommutationCalculator.CommutationCeiling.forPensioner(false))
    }

    @Test
    fun `an implausible retirement age is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            CommutationInput(pay = PayBasis(basicPay = 1.0), serviceYears = 1.0, ageAtRetirement = 90)
        }
        assertFailsWith<IllegalArgumentException> {
            CommutationInput(pay = PayBasis(basicPay = 1.0), serviceYears = 1.0, ageAtRetirement = 12)
        }
    }
}
