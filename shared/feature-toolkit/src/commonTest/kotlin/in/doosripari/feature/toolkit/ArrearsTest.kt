package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.DrRate
import `in`.doosripari.core.model.ServiceType
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arrears, checked by hand against plan section 6.5's four lines:
 *
 * ```
 * months = months_between(effective_from, paid_from)
 * monthly_difference = basic_pension × (new_DR − old_DR) / 100
 * arrears = monthly_difference × months
 * new_pension = basic_pension × (1 + new_DR / 100)
 * ```
 *
 * Every rate is placed at midnight UTC so the day counts are exact integers and
 * the expected figures can be recomputed on paper.
 */
class ArrearsTest {

    private fun at(iso: String): Instant = Instant.parse("${iso}T00:00:00Z")

    private fun rate(percent: Double, from: String, service: ServiceType = ServiceType.CentralCivil) =
        DrRate(serviceType = service, ratePercent = percent, effectiveFrom = at(from))

    /** 100% from 2014, 125% from 2016, 150% from 2018. Three revisions, six years. */
    private val centralRates = listOf(
        rate(100.0, "2014-01-01"),
        rate(125.0, "2016-01-01"),
        rate(150.0, "2018-01-01"),
    )

    private fun input(
        rates: List<DrRate> = centralRates,
        from: String = "2014-01-01",
        to: String = "2020-01-01",
        basic: Double = 10_000.0,
        missed: List<Instant> = emptyList(),
        asOf: Instant? = null,
    ) = ArrearsInput(
        serviceType = ServiceType.CentralCivil,
        basicPay = basic,
        employedFrom = at(from),
        employedTo = at(to),
        rates = rates,
        missedRevisions = missed,
        asOf = asOf,
    )

    // ------------------------------------------------------------ the rule that matters --

    @Test
    fun `each period is valued at its own rate and not at today's`() {
        val details = ArrearsCalculator.breakdown(input())

        assertEquals(3, details.size, "three revisions in, three periods out")

        // 2014-2016: the first rate a pensioner ever draws. The "old" rate is
        // zero, so the whole 100 points is the increase.
        assertEquals(0.0, details[0].previousRatePercent)
        assertEquals(100.0, details[0].deltaPercent)
        assertEquals(730, details[0].days, "2014 and 2015 are two ordinary years")
        assertEquals(24_000.0, details[0].amount, "10000 × 100% × 24 months")

        // 2016-2018: 2016 is a leap year, so 731 days and a pro-rated 24.033
        // months. Valued at the *increase* of 25 points, not at 125.
        assertEquals(100.0, details[1].previousRatePercent)
        assertEquals(25.0, details[1].deltaPercent)
        assertEquals(731, details[1].days, "2016 is a leap year")
        assertEquals(60_082.0, details[1].amount, "10000 × 25% × 731/365×12 months")

        // 2018-2020: back to an even 24 months.
        assertEquals(125.0, details[2].previousRatePercent)
        assertEquals(25.0, details[2].deltaPercent)
        assertEquals(730, details[2].days)
        assertEquals(60_000.0, details[2].amount)
    }

    @Test
    fun `the rate in force is reported alongside the delta`() {
        val details = ArrearsCalculator.breakdown(input())
        assertEquals(listOf(100.0, 125.0, 150.0), details.map { it.ratePercent })
        assertEquals(details.map { it.ratePercent }, details.map { it.period.ratePercent })
    }

    @Test
    fun `the new pension after a revision follows the plan's last line`() {
        val details = ArrearsCalculator.breakdown(input())
        // basic_pension × (1 + new_DR / 100)
        assertEquals(20_000.0, details[0].newPension(10_000.0))
        assertEquals(22_500.0, details[1].newPension(10_000.0))
        assertEquals(25_000.0, details[2].newPension(10_000.0))
    }

    @Test
    fun `the total is the sum of the displayed lines and nothing else`() {
        val result = ArrearsCalculator.calculate(input())
        assertEquals(144_082.0, result.total)
        assertEquals(result.periods.sumOf { it.amount }, result.total)
        assertEquals("₹1,44,082", result.displayTotal)
        assertFalse(result.isEstimate)
    }

    /**
     * The error this file exists to prevent, stated as a number. Valuing six
     * years at today's 150% would pay 90,000 against a correct 144,082 — and
     * would still look like a plausible figure on a screen.
     */
    @Test
    fun `valuing every period at today's rate would give a different and wrong total`() {
        val result = ArrearsCalculator.calculate(input())
        val naive = 10_000.0 * 1.50 * 6.0

        assertEquals(90_000.0, naive)
        assertTrue(
            result.total > naive,
            "the correct figure is larger because the earlier years were already being paid",
        )
    }

    // ------------------------------------------------------------------- boundaries --

    @Test
    fun `a rate takes effect on its own day and the previous one ends there`() {
        val details = ArrearsCalculator.breakdown(input())
        assertEquals(at("2014-01-01"), details[0].period.from)
        assertEquals(at("2016-01-01"), details[0].period.effectiveTo)
        assertEquals(at("2016-01-01"), details[1].period.from, "no gap and no overlap")
    }

    @Test
    fun `a rate that begins after service ended buys nothing`() {
        val late = centralRates + rate(200.0, "2021-01-01")
        assertEquals(3, ArrearsCalculator.breakdown(input(rates = late)).size)
    }

    @Test
    fun `a rate already in force when service began starts the first period`() {
        // 100% predates the 2014 join by four years. The first period must still
        // start at the joining date, not at the rate's effective date.
        val details = ArrearsCalculator.breakdown(input(from = "2018-03-01", to = "2020-01-01"))
        assertEquals(1, details.size)
        assertEquals(at("2018-03-01"), details[0].period.from)
        assertEquals(at("2020-01-01"), details[0].period.effectiveTo)
    }

    @Test
    fun `the old rate for the first period comes from outside the employment span`() {
        // Joined in 2016, so the 2014-2016 period is not theirs — but the 125%
        // they were already drawing is still the "old" rate their first period
        // is measured against, and it is 100%.
        val details = ArrearsCalculator.breakdown(input(from = "2016-01-01", to = "2018-01-01"))
        assertEquals(1, details.size)
        assertEquals(100.0, details[0].previousRatePercent)
        assertEquals(25.0, details[0].deltaPercent)
    }

    @Test
    fun `rates for another service are ignored`() {
        val mixed = centralRates + rate(300.0, "2014-01-01", ServiceType.Defence)
        val result = ArrearsCalculator.calculate(input(rates = mixed))
        assertEquals(144_082.0, result.total, "a defence rate must not price a civil claim")
    }

    // ------------------------------------------------------------ claim versus estimate --

    @Test
    fun `a serving employee gets an estimate and no claimable periods`() {
        val result = ArrearsCalculator.calculate(
            input(to = "2026-09-29", asOf = at("2026-09-29")),
        )
        assertTrue(result.isEstimate, "an open-ended span is a projection, not a claim")
        assertTrue(
            result.periods.all { it.isEstimate && !it.period.isClaimable },
            "every line of a projection is labelled as one, and none is a claim",
        )
    }

    @Test
    fun `an estimate stops at today and never runs past it`() {
        val result = ArrearsCalculator.calculate(
            input(to = "2030-01-01", asOf = at("2026-09-29")),
        )
        assertEquals(at("2026-09-29"), result.periods.last().period.effectiveTo)
    }

    @Test
    fun `only the revisions actually missed are marked claimable`() {
        val result = ArrearsCalculator.calculate(
            input(missed = listOf(at("2016-01-01"))),
        )
        assertEquals(3, result.periods.size, "the figure still covers every period")
        assertEquals(
            listOf(false, true, false),
            result.periods.map { it.period.isClaimable },
            "only the 2016 revision was missed",
        )
    }

    // -------------------------------------------------------------------- degenerate --

    @Test
    fun `no rates means nothing is owed rather than a crash`() {
        val result = ArrearsCalculator.calculate(input(rates = emptyList()))
        assertEquals(0.0, result.total)
        assertTrue(result.periods.isEmpty())
    }

    @Test
    fun `a zero length span is empty`() {
        val result = ArrearsCalculator.calculate(input(from = "2019-01-01", to = "2019-01-01"))
        assertEquals(0.0, result.total)
        assertTrue(result.periods.isEmpty())
    }

    @Test
    fun `the rate in force is the latest one not after the day asked about`() {
        assertEquals(100.0, ArrearsCalculator.rateInForceAt(centralRates, ServiceType.CentralCivil, at("2015-06-01"))?.ratePercent)
        assertEquals(125.0, ArrearsCalculator.rateInForceAt(centralRates, ServiceType.CentralCivil, at("2016-01-01"))?.ratePercent)
        assertEquals(125.0, ArrearsCalculator.rateInForceAt(centralRates, ServiceType.CentralCivil, at("2017-12-31"))?.ratePercent)
        assertNull(ArrearsCalculator.rateInForceAt(centralRates, ServiceType.CentralCivil, at("2013-12-31")))
    }

    @Test
    fun `months are pro-rated so a partial period is not paid as zero`() {
        // 1 July 2018 to 1 August 2018 is 31 days. Whole-month arithmetic would
        // round this to zero months and pay nothing at all.
        val july = ArrearsCalculator.breakdown(
            input(rates = listOf(rate(150.0, "2018-07-01")), from = "2018-07-01", to = "2018-08-01"),
        )
        assertEquals(1, july.size)
        assertEquals(31, july[0].days)
        assertTrue(july[0].amount > 0.0, "31 days at 150% is a claim, not a zero")
    }

    @Test
    fun `the pro-rata divisor agrees with whole months at a whole-year boundary`() {
        assertEquals(12.0, ArrearsCalculator.monthsProRata(365), "one ordinary year")
        assertEquals(24.0, ArrearsCalculator.monthsProRata(730), "two ordinary years")
        assertEquals(0.0, ArrearsCalculator.monthsProRata(0), "nothing is nothing")
    }
}
