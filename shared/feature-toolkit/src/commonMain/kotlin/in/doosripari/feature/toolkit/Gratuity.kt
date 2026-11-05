package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.GratuityInput
import `in`.doosripari.core.model.GratuityResult
import `in`.doosripari.core.model.toRupees
import kotlin.math.roundToLong

/**
 * Gratuity, and the ex-gratia that goes with a death in service.
 *
 * **Plan section 6.5 does not contain a gratuity formula.** The word appears
 * once in the whole plan, in the section 6.4 corpus table, as a topic the CCS
 * (Pension) Rules 2021 cover. Everything below is the CCS (Retirement Benefits)
 * Rules provision that corpus row points at, and it carries the plan's own
 * instruction at the head of section 6.5 — "Every formula and table must be
 * checked against the current official rules before launch. Results are
 * labelled 'estimate only'." Nothing here should be treated as checked, and the
 * module report says so.
 *
 * The rule itself: a month of service is worth [MONTHS_FACTOR] of a month's
 * pay, so a pensioner with *n* years of service gets `last pay × 7/26 × n`. The
 * 7/26 is not arbitrary — it is the fraction of a month's pay that the
 * government's contribution buys, and it is the number the rules fix. Below five
 * years the same arithmetic is halved, because the rule computes short-service
 * gratuity at monthly rates over a two-year window.
 *
 * Two figures come out and they are not alternatives to each other. Gratuity is
 * what retiring earns; ex-gratia is fifteen days' pay to the family of somebody
 * who died in service, and it is payable *in addition*, so a colleague who died
 * after seven years' service is owed both. [GratuityResult] carries them
 * separately and [GratuityResult.total] is their sum, which is the only reading
 * under which having two fields is not a lie.
 */
object GratuityCalculator {

    /**
     * Portion of a month's pay that one month's service earns, as a fraction.
     * Thirty days' pay, two of which are the employee's, divided by a month.
     */
    const val WEEKS_FACTOR_NUMERATOR: Int = 7

    /** Days in the pay period the [WEEKS_FACTOR_NUMERATOR] is measured against. */
    const val WEEKS_FACTOR_DENOMINATOR: Int = 26

    /** Below this much service, the full arithmetic is halved. */
    const val SHORT_SERVICE_YEARS: Double = 5.0

    /**
     * The short-service multiplier. The rules express it as 12/24 — a year
     * against two — which is a half; it is written as the fraction the rules
     * use so that a future amendment to a different ratio is a recognisable
     * edit.
     */
    const val SHORT_SERVICE_MONTHS: Int = 12
    const val SHORT_SERVICE_MONTHS_SPAN: Int = 24

    /** Ex-gratia for a death in service, in days of last drawn pay. */
    const val EX_GRATIA_DAYS: Int = 15

    /** The divisor that turns monthly pay into daily pay. */
    const val DAYS_IN_MONTH: Int = 30

    /**
     * Gratuity for one pensioner, plus the ex-gratia if they died in service.
     *
     * @param roundingRate rupees to round the gratuity down to, or null for no
     *   rounding. The rules settle gratuity on a multiple of a dearness
     *   allowance step rather than on an exact rupee, and which step is set by
     *   order — so it is an argument. It is reported back on
     *   [GratuityResult.roundedToDaRate] so the UI can say the figure is
     *   rounded rather than let a pensioner assume it is exact to the paisa.
     */
    fun calculate(input: GratuityInput, roundingRate: Double? = null): GratuityResult {
        require(input.lastDrawnPay >= 0) { "lastDrawnPay cannot be negative" }
        if (roundingRate != null) {
            require(roundingRate > 0) { "roundingRate must be a positive multiple, was $roundingRate" }
        }

        val service = totalServiceYears(input)
        val fullService = input.lastDrawnPay *
            WEEKS_FACTOR_NUMERATOR.toDouble() / WEEKS_FACTOR_DENOMINATOR * service

        // Short service is computed at monthly rates, which the rules express
        // as 12/24 of the full-service figure. Applying it to the whole
        // service length rather than to the first five years is deliberate:
        // the rules halve the rate, they do not halve the years.
        val shortService = service < SHORT_SERVICE_YEARS
        val raw = if (shortService) {
            fullService * SHORT_SERVICE_MONTHS / SHORT_SERVICE_MONTHS_SPAN
        } else {
            fullService
        }

        val gratuity = roundDownTo(raw, roundingRate)

        val exGratia = if (input.isExGratia) {
            (input.lastDrawnPay / DAYS_IN_MONTH * EX_GRATIA_DAYS).toRupees()
        } else {
            0.0
        }

        return GratuityResult(
            gratuity = gratuity,
            exGratia = exGratia,
            total = gratuity + exGratia,
            roundedToDaRate = if (roundingRate != null && gratuity > 0.0) roundingRate else null,
        )
    }

    /**
     * Service in years, the unit the 7/26 rule is applied to.
     *
     * Months are twelfths and days are three-hundred-and-sixty-fifths, which is
     * not calendar-exact. It does not need to be: the part-month rounding rule
     * that *is* calendar-exact lives in [ServiceLength], and the two figures
     * are not required to agree, because they answer different questions — one
     * is a prorata for a gratuity formula, the other is a service record a
     * pensioner can be shown.
     */
    fun totalServiceYears(input: GratuityInput): Double =
        input.serviceYears +
            input.serviceMonths / MONTHS_IN_YEAR +
            input.serviceDays / DAYS_PER_YEAR

    /**
     * Round down to a multiple, the direction that under-pays rather than
     * over-pays.
     *
     * Nearest-multiple would be the obvious choice and is the wrong one: the
     * claimant is the pensioner, and an Accounts Office that rounds a claim up
     * overpays, while one that rounds down is merely conservative. The
     * rounding is reported so it is never invisible.
     */
    private fun roundDownTo(value: Double, multiple: Double?): Double {
        if (multiple == null) return value.toRupees()
        if (value <= 0.0) return 0.0
        return (value / multiple).let { kotlin.math.floor(it) } * multiple
    }

    private const val MONTHS_IN_YEAR: Double = 12.0
    private const val DAYS_PER_YEAR: Double = 365.0
}

/**
 * Ex-gratia alone — fifteen days' last drawn pay, for a family.
 *
 * Separate from [GratuityCalculator.calculate] because a bereaved family
 * asking about their member is not asking for a gratuity estimate, and
 * showing them a gratuity figure first is the wrong thing to put on screen.
 */
fun exGratiaFor(lastDrawnPay: Double): Double =
    (lastDrawnPay / GratuityCalculator.DAYS_IN_MONTH * GratuityCalculator.EX_GRATIA_DAYS)
        .roundToLong().toDouble()
