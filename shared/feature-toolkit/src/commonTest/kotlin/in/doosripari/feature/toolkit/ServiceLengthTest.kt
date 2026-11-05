package `in`.doosripari.feature.toolkit

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Length of service, including the part-month rule.
 *
 * Two things are being pinned here. The first is that a part-month of fifteen
 * days counts as a whole month and fourteen does not — the boundary is
 * off-by-one-sensitive and getting it wrong shifts every figure derived from
 * service. The second is that joining and retiring are **local** dates: an
 * instant one hour after midnight in India is still the previous day in UTC,
 * and a UTC-naive count puts a pensioner's whole service a day out at both ends.
 */
class ServiceLengthTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    private val utc = TimeZone.of("UTC")
    private val ist = TimeZone.of("Asia/Kolkata")

    // ------------------------------------------------------------ the part-month rule --

    @Test
    fun `a part-month of exactly fifteen days rounds up to a whole month`() {
        // 1 Jan to 16 Feb is one month and fifteen days.
        val length = ServiceLengthCalculator.between(at("2020-01-01T00:00:00Z"), at("2020-02-16T00:00:00Z"), utc)

        assertEquals(0, length.years)
        assertEquals(2, length.months)
        assertEquals(0, length.days, "the remainder was consumed by the rounding")
        assertTrue(length.wasRoundedUp)
        assertEquals(2, length.totalMonths)
    }

    @Test
    fun `a part-month of fourteen days does not`() {
        val length = ServiceLengthCalculator.between(at("2020-01-01T00:00:00Z"), at("2020-02-15T00:00:00Z"), utc)

        assertEquals(0, length.years)
        assertEquals(1, length.months)
        assertEquals(14, length.days)
        assertFalse(length.wasRoundedUp)
        assertEquals(1, length.totalMonths)
    }

    @Test
    fun `the strict count keeps the remainder the rounding would have eaten`() {
        val same = at("2020-01-01T00:00:00Z") to at("2020-02-16T00:00:00Z")
        val strict = ServiceLengthCalculator.between(same.first, same.second, utc, roundPartMonthUp = false)

        assertEquals(1, strict.months)
        assertEquals(15, strict.days)
        assertEquals(46, strict.totalDays)
        assertFalse(strict.wasRoundedUp)
    }

    // --------------------------------------------------------------------- the years --

    @Test
    fun `a rounding part-month carries the service into the next year`() {
        // 1 Mar 2008 to 28 Feb 2026 is 17 years 11 months 27 days, which the
        // rule calls 18 years.
        val length = ServiceLengthCalculator.between(at("2008-03-01T00:00:00Z"), at("2026-02-28T00:00:00Z"), utc)

        assertEquals(18, length.years)
        assertEquals(0, length.months)
        assertEquals(0, length.days)
        assertTrue(length.wasRoundedUp)
        assertEquals(216, length.totalMonths)
        assertEquals(18.0, length.decimalYears, "216 months is 18 years exactly")
        assertEquals("18 years", length.display)
    }

    @Test
    fun `the same span without the rule is a year short`() {
        val length = ServiceLengthCalculator.between(
            at("2008-03-01T00:00:00Z"), at("2026-02-28T00:00:00Z"), utc, roundPartMonthUp = false,
        )
        assertEquals(17, length.years)
        assertEquals(11, length.months)
        assertEquals(27, length.days)
        assertEquals(215, length.totalMonths)
    }

    @Test
    fun `a whole number of years is not rounded at all`() {
        val length = ServiceLengthCalculator.between(at("2000-04-01T00:00:00Z"), at("2020-04-01T00:00:00Z"), utc)
        assertEquals(20, length.years)
        assertEquals(0, length.months)
        assertEquals(0, length.days)
        assertFalse(length.wasRoundedUp)
        assertEquals("20 years", length.display)
    }

    @Test
    fun `a day short of the anniversary is a whole part-month short of nothing`() {
        // 19 years 11 months 30 days. The strict count stops a day short, and
        // the part-month rule does not care about the day — 30 days is more
        // than fifteen, so the service is twenty years. This is the rule
        // working in the pensioner's favour, and it is why the rounded and
        // strict counts have to both exist.
        val rounded = ServiceLengthCalculator.between(
            at("2000-04-01T00:00:00Z"), at("2020-03-31T00:00:00Z"), utc,
        )
        assertEquals(20, rounded.years)
        assertEquals(0, rounded.months)
        assertTrue(rounded.wasRoundedUp)

        val strict = ServiceLengthCalculator.between(
            at("2000-04-01T00:00:00Z"), at("2020-03-31T00:00:00Z"), utc, roundPartMonthUp = false,
        )
        assertEquals(19, strict.years)
        assertEquals(11, strict.months)
        assertEquals(30, strict.days)
    }

    // ------------------------------------------------------------------ leap days --

    @Test
    fun `a 29 February joiner keeps the year on a 28 February retirement`() {
        val length = ServiceLengthCalculator.between(at("2020-02-29T00:00:00Z"), at("2021-02-28T00:00:00Z"), utc)
        assertEquals(1, length.years)
        assertEquals(0, length.months)
        assertEquals(0, length.days)
        assertEquals(12, length.totalMonths)
    }

    // ----------------------------------------------------------------- local dates --

    @Test
    fun `joining and retiring are the local dates, not the UTC ones`() {
        // 00:30 on 1 February in Kolkata is still 31 January in UTC.
        val from = at("1985-01-31T19:00:00Z")
        val to = at("2026-01-30T19:00:00Z")

        val local = ServiceLengthCalculator.between(from, to, ist)
        assertEquals(LocalDate(1985, 2, 1), local.from, "joined on the first of the month, locally")
        assertEquals(LocalDate(2026, 1, 31), local.to)

        val utcLength = ServiceLengthCalculator.between(from, to, utc)
        assertEquals(LocalDate(1985, 1, 31), utcLength.from, "which UTC calls the day before")
        assertEquals(LocalDate(2026, 1, 30), utcLength.to)
    }

    // -------------------------------------------------------------------- degenerate --

    @Test
    fun `the day somebody joins is no service yet`() {
        val length = ServiceLengthCalculator.between(at("2020-01-01T00:00:00Z"), at("2020-01-01T00:00:00Z"), utc)
        assertEquals(0, length.totalMonths)
        assertEquals(0, length.totalDays)
        assertEquals("less than one month", length.display)
    }

    @Test
    fun `service cannot end before it began`() {
        assertFailsWith<IllegalArgumentException> {
            ServiceLengthCalculator.between(at("2020-01-01T00:00:00Z"), at("2019-01-01T00:00:00Z"), utc)
        }
    }

    // ------------------------------------------------------------------ integration --

    @Test
    fun `a service length can be handed straight to the gratuity calculator`() {
        val input = ServiceLengthCalculator.asGratuityInput(
            at("2000-04-01T00:00:00Z"), at("2020-04-01T00:00:00Z"),
            lastDrawnPay = 50_000.0, timeZone = utc,
        )
        assertEquals(20.0, input.serviceYears)
        assertEquals(0, input.serviceMonths)

        // 50,000 × 7/26 × 20 = 269,230.77…
        assertEquals(269_231.0, GratuityCalculator.calculate(input).gratuity)
    }

    @Test
    fun `the display reads like a service record`() {
        val three = ServiceLengthCalculator.between(at("2020-01-01T00:00:00Z"), at("2020-01-16T00:00:00Z"), utc)
        assertEquals("1 month", three.display)

        val oneYear = ServiceLengthCalculator.between(at("2019-01-01T00:00:00Z"), at("2020-03-01T00:00:00Z"), utc)
        assertEquals("1 year 2 months", oneYear.display)
    }
}
