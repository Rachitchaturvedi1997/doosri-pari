package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.GratuityInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gratuity and ex-gratia, worked by hand.
 *
 * The rule: a month of service is worth 7/26 of a month's pay, so
 * `last pay × 7/26 × years`. Below five years the same arithmetic is halved.
 * Ex-gratia on a death in service is fifteen days of last drawn pay, paid
 * *in addition* rather than instead — which is the only reading under which
 * `GratuityResult` having both fields is not a lie.
 *
 * Plan section 6.5 has no gratuity formula in it. These numbers follow the CCS
 * (Retirement Benefits) Rules, and the plan's own preamble requires them to be
 * checked before launch.
 */
class GratuityTest {

    // ----------------------------------------------------------------- normal path --

    @Test
    fun `thirty years of service at the full rate`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 30.0),
        )
        // 50,000 × 7/26 × 30 = 403,846.15…, settled in whole rupees.
        assertEquals(403_846.0, result.gratuity)
        assertEquals(0.0, result.exGratia)
        assertEquals(403_846.0, result.total)
        assertNull(result.roundedToDaRate, "no rounding was asked for, so none is reported")
    }

    @Test
    fun `part years and months are pro-rated into the same arithmetic`() {
        val full = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 30.0),
        )
        val withParts = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 29.0, serviceMonths = 6),
        )
        // 29 years 6 months is 29.5 of the same 50,000 × 7/26 a year.
        assertEquals(397_115.0, withParts.gratuity)
        assertTrue(
            withParts.gratuity < full.gratuity,
            "six months short of thirty years is less money, not the same money",
        )
    }

    @Test
    fun `days are carried into the same figure`() {
        val year = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 365_000.0, serviceYears = 1.0),
        )
        val yearAndDay = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 365_000.0, serviceYears = 1.0, serviceDays = 365),
        )
        // 365,000 × 7/26 × 1 = 98,269.23…, so 365 days of service is a year.
        assertEquals(98_269.0, year.gratuity)
        assertEquals(196_538.0, yearAndDay.gratuity, "one year plus 365 days is two years")
    }

    // ---------------------------------------------------------------- ex-gratia --

    @Test
    fun `a death in service pays fifteen days on top of the gratuity`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 7.0, isExGratia = true),
        )
        // Seven years is over the short-service threshold, so the full rate:
        // 50,000 × 7/26 × 7 = 94,230.77….
        assertEquals(94_231.0, result.gratuity)
        // 50,000 / 30 × 15 = 25,000 exactly.
        assertEquals(25_000.0, result.exGratia)
        assertEquals(119_231.0, result.total, "the family is owed both")
    }

    @Test
    fun `without ex-gratia the same member's figure is the gratuity alone`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 7.0, isExGratia = false),
        )
        assertEquals(94_231.0, result.gratuity)
        assertEquals(0.0, result.exGratia)
        assertEquals(result.gratuity, result.total)
    }

    @Test
    fun `ex-gratia alone does not need a service length`() {
        // A family asking about a member who died in their first month gets a
        // figure, not a refusal, and not a gratuity estimate in its place.
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 40_000.0, serviceYears = 0.0, isExGratia = true),
        )
        assertEquals(0.0, result.gratuity)
        assertEquals(20_000.0, result.exGratia, "40,000 / 30 × 15")
        assertEquals(20_000.0, result.total)
        assertEquals(20_000.0, exGratiaFor(40_000.0))
    }

    // ------------------------------------------------------------ short service --

    @Test
    fun `under five years the arithmetic is halved`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 3.0),
        )
        // 50,000 × 7/26 × 3 = 40,384.62…, halved = 20,192.31….
        assertEquals(20_192.0, result.gratuity)
    }

    @Test
    fun `the threshold is exactly five years`() {
        val four = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 100_000.0, serviceYears = 4.9),
        )
        val five = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 100_000.0, serviceYears = 5.0),
        )
        // 100,000 × 7/26 × 5 = 134,615.38…; 4.9 years is 131,923.08…, halved.
        assertEquals(134_615.0, five.gratuity)
        assertEquals(65_962.0, four.gratuity)
        assertTrue(five.gratuity > four.gratuity)
    }

    // ----------------------------------------------------------------- rounding --

    @Test
    fun `rounding goes down to a multiple and is reported`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 3.0),
            roundingRate = 100.0,
        )
        assertEquals(20_100.0, result.gratuity, "20,192.31 rounded down to the nearest hundred")
        assertEquals(100.0, result.roundedToDaRate, "and the UI is told it was rounded")
    }

    @Test
    fun `a figure already on the multiple is left alone`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 100_000.0, serviceYears = 5.0),
            roundingRate = 1.0,
        )
        assertEquals(134_615.0, result.gratuity)
    }

    @Test
    fun `a non-positive rounding multiple is refused`() {
        assertFailsWith<IllegalArgumentException> {
            GratuityCalculator.calculate(
                GratuityInput(lastDrawnPay = 10_000.0, serviceYears = 10.0), roundingRate = 0.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GratuityCalculator.calculate(
                GratuityInput(lastDrawnPay = 10_000.0, serviceYears = 10.0), roundingRate = -50.0,
            )
        }
    }

    @Test
    fun `negative pay is refused rather than paid out`() {
        assertFailsWith<IllegalArgumentException> {
            GratuityCalculator.calculate(GratuityInput(lastDrawnPay = -1.0, serviceYears = 1.0))
        }
    }

    // ------------------------------------------------------------- display shape --

    @Test
    fun `the total is grouped the way a pensioner reads it`() {
        val result = GratuityCalculator.calculate(
            GratuityInput(lastDrawnPay = 50_000.0, serviceYears = 30.0, isExGratia = true),
        )
        // 403,846 + 25,000 = 428,846 → ₹4,28,846, not ₹428,846.
        assertEquals("₹4,28,846", result.displayTotal)
    }
}
