package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.GratuityInput
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
// LocalDate.plus, LocalDate.minus and LocalDate.daysUntil are top-level
// extensions on LocalDate in kotlinx-datetime, not members, so all three need
// importing. Nothing here reads the epoch.
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Length of service, as the rules count it: whole years, whole months, and a
 * remainder that is either kept or thrown away.
 *
 * **Plan section 6.5 has no service-length rule**, no part-month rule and no
 * qualifying-service computation. This is the requirement arriving without its
 * citation, and it is the rule that quietly decides a gratuity, a commutation
 * entitlement and a family-pension rate, so getting the rounding right is worth
 * more than any single formula in the module.
 *
 * Two decisions carry the weight.
 *
 * **Calendar arithmetic, in a timezone, not epoch arithmetic.** A pensioner
 * who joined on 1 March 1985 and retired on 28 February 2026 has served
 * forty-one years minus a day, and the difference is decided by the calendar
 * and by which calendar — joining and retiring are local dates to the person
 * concerned, and IST's +05:30 offset means the UTC instants are on the
 * *previous* day. `in.doosripari.core.model.monthsBetween` and
 * `daysBetween` both read the epoch in UTC, which puts every service length
 * computed with them a day out at both ends for an Indian pensioner. That is
 * why this file uses [LocalDate] and a [TimeZone] and does not reuse them.
 * [daysBetween] *is* used in [ArrearsCalculator], where the periods are DA
 * revision boundaries and a day-aligned UTC count is what the Accounts Office
 * does.
 *
 * **A part-month is rounded up from half.** Fifteen days or more counts as a
 * whole month; anything less is dropped. The direction is deliberate and it is
 * not neutral: rounding down systematically understates service, and service
 * understates every pension figure derived from it. Rounding up means a
 * pensioner with nine months and sixteen days is shown ten months, and the
 * alternative would be showing them nine.
 */
data class ServiceLength(
    val from: LocalDate,
    val to: LocalDate,
    val years: Int,
    val months: Int,
    /**
     * The remainder in days, which is always zero when
     * [wasRoundedUp] — the rounding consumed it.
     */
    val days: Int,
    /** Years and months as one number. What a gratuity formula is applied to. */
    val totalMonths: Int,
    /** Every day between the dates, before any rounding. */
    val totalDays: Int,
    /** True when the part-month rule fired and the remainder became a month. */
    val wasRoundedUp: Boolean,
    /** [totalMonths] as a fraction. Twelve months is one year, exactly. */
    val decimalYears: Double,
) {
    /**
     * "40 years 11 months" — what a service record says, and what a pensioner
     * will compare against the letter they were sent.
     *
     * A zero length is written as "less than one month" rather than "0 years 0
     * months 0 days", which is a real answer for somebody who has just joined
     * and would read as a bug if it were not.
     */
    val display: String
        get() = when {
            years == 0 && months == 0 -> "less than one month"
            months == 0 -> "$years ${plural(years, "year")}"
            years == 0 -> "$months ${plural(months, "month")}"
            else -> "$years ${plural(years, "year")} $months ${plural(months, "month")}"
        }
}

/**
 * Service between two instants.
 *
 * [asOf] is the "now" for a serving employee; a null [to] would otherwise mean
 * "unknown" and this function has no clock, so the caller must supply the
 * retirement date or today's date explicitly. That is the same contract as the
 * rest of the module and for the same reason: plan section 20 asks for a fake
 * clock in the tests, and a function that reads `Clock.System.now()` cannot
 * have one.
 */
object ServiceLengthCalculator {

    /**
     * A part-month of this many days or more counts as a whole month.
     *
     * Half a month, which is the rule as it is written. It is a named constant
     * rather than a literal because it is the kind of number that gets amended,
     * and because a test that names it reads as a statement about the rule
     * rather than a coincidence of arithmetic.
     */
    const val PART_MONTH_ROUNDS_UP_DAYS: Int = 15

    /**
     * Count service, rounding the final part-month up.
     *
     * @param roundPartMonthUp pass false for the strict count with the
     *   remainder kept in [ServiceLength.days]. Both are wanted: the strict
     *   count is what a service record shows, and the rounded one is what a
     *   gratuity or commutation entitlement is computed on.
     */
    fun between(
        from: Instant,
        to: Instant,
        timeZone: TimeZone = TimeZone.UTC,
        roundPartMonthUp: Boolean = true,
    ): ServiceLength {
        require(to >= from) { "service cannot end before it began: $from → $to" }

        val start = from.toLocalDateTime(timeZone).date
        val end = to.toLocalDateTime(timeZone).date
        val totalDays = start.daysUntil(end)

        if (start == end) {
            return ServiceLength(
                from = start, to = end, years = 0, months = 0, days = 0,
                totalMonths = 0, totalDays = totalDays, wasRoundedUp = false,
                decimalYears = 0.0,
            )
        }

        // Whole years first. The candidate is a year on from the joining
        // anniversary, and one is taken off if the end date has not reached it
        // — which also puts a 29 February joiner on 28 February in a
        // non-leap year rather than throwing the whole year out.
        var years = end.year - start.year
        if (end < start.plus(years, DateTimeUnit.YEAR)) years--

        val anniversary = start.plus(years, DateTimeUnit.YEAR)

        // Then whole months, at most eleven, since the years are already out.
        var months = 0
        while (
            months < MONTHS_PER_YEAR - 1 &&
            anniversary.plus(months + 1, DateTimeUnit.MONTH) <= end
        ) {
            months++
        }

        val monthMark = anniversary.plus(months, DateTimeUnit.MONTH)
        val remainder = monthMark.daysUntil(end)

        val roundedUp = roundPartMonthUp && remainder >= PART_MONTH_ROUNDS_UP_DAYS
        var finalMonths = if (roundedUp) months + 1 else months
        val finalDays = if (roundedUp) 0 else remainder

        // Rounding a final part-month can carry the service into another year.
        if (finalMonths >= MONTHS_PER_YEAR) {
            years += 1
            finalMonths -= MONTHS_PER_YEAR
        }

        val totalMonths = years * MONTHS_PER_YEAR + finalMonths

        return ServiceLength(
            from = start,
            to = end,
            years = years,
            months = finalMonths,
            days = finalDays,
            totalMonths = totalMonths,
            totalDays = totalDays,
            wasRoundedUp = roundedUp,
            decimalYears = totalMonths.toDouble() / MONTHS_PER_YEAR,
        )
    }

    /**
     * The same count, as the [in.doosripari.core.model.GratuityInput] a
     * gratuity calculation wants.
     *
     * A convenience and a hazard, both of them stated: the rounding rule and
     * the gratuity's own pro-rata disagree by up to half a month, because the
     * rules apply the part-month rule to service counting and plain fractions
     * to the gratuity formula. This routes through the rules. Callers who want
     * the other figure build the input themselves.
     */
    fun asGratuityInput(
        from: Instant,
        to: Instant,
        lastDrawnPay: Double,
        timeZone: TimeZone = TimeZone.UTC,
    ): GratuityInput = between(from, to, timeZone).let { length ->
        GratuityInput(
            lastDrawnPay = lastDrawnPay,
            serviceYears = length.years.toDouble(),
            serviceMonths = length.months,
            serviceDays = length.days,
        )
    }

    private const val MONTHS_PER_YEAR = 12
}

private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"
