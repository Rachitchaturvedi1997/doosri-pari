package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.ArrearsLine
import `in`.doosripari.core.model.ArrearsPeriod
import `in`.doosripari.core.model.ArrearsResult
import `in`.doosripari.core.model.DrRate
import `in`.doosripari.core.model.ServiceType
import `in`.doosripari.core.model.daysBetween
import `in`.doosripari.core.model.percentageOf
import `in`.doosripari.core.model.toRupees
import kotlinx.datetime.Instant

/**
 * DA / DR arrears: what a missed revision is worth, worked out revision by
 * revision. Plan section 6.5.
 *
 * The plan gives the arithmetic in four lines:
 *
 * ```
 * months = months_between(effective_from, paid_from)
 * monthly_difference = basic_pension × (new_DR − old_DR) / 100
 * arrears = monthly_difference × months
 * new_pension = basic_pension × (1 + new_DR / 100)
 * ```
 *
 * Two things follow from that, and both are the reason this file exists.
 *
 * **The claim is a difference, not a total.** A pensioner who was already
 * drawing 125% and then missed a revision to 150% is owed the 25 points, not
 * the 150. Paying the full rate overstates the claim by the whole of what they
 * were already receiving, and the Accounts Office rejects it — which costs them
 * a trip to make it. The word "old_DR" in the plan's second line is doing the
 * real work.
 *
 * **Each period keeps its own rate.** There is no single "current" rate to
 * apply. A pensioner who served across four revisions is owed four sums, each
 * computed at its own delta, and valuing the lot at today's rate is the single
 * most common way these figures are got wrong. The previous rate for a period
 * is read from the *full* rate history rather than from the periods that fall
 * inside the employment span: somebody who joined in 2016 is owed the gap
 * between the 2014 and 2016 rates even though the 2014 period is not theirs.
 *
 * Two places where this departs from a literal reading of the plan, both
 * deliberate and both explained at the function that does it.
 *
 * The plan's `months` is [in.doosripari.core.model.monthsBetween] — whole
 * months, and zero for a partial one. Paying zero for the fifteen days a claim
 * happens to end on is not defensible, so months are pro-rated by day count
 * instead; see [monthsProRata].
 *
 * The plan opens section 6.5 with "Every formula and table must be checked
 * against the current official rules before launch. Results are labelled
 * 'estimate only'." That sentence is why [ArrearsResult.isEstimate] is wired
 * all the way through, and why nothing in this file rounds to a rupee the
 * Accounts Office could dispute twice.
 */
data class ArrearsInput(
    /**
     * Which service's rates apply. Rates for other services are filtered out
     * rather than rejected, because the server hands over one list for every
     * service type and a caller should not have to pre-filter it to avoid a
     * defence pensioner being valued at the central government's percentage.
     */
    val serviceType: ServiceType,
    /**
     * Basic pension as it stood across the periods being claimed. Plan
     * section 6.5's `basic_pension` — for an arrears calculation after
     * retirement this is the basic *pension*, not the last basic *pay*, and
     * the caller has to make that choice, because the two differ and the plan
     * does not say which it means.
     */
    val basicPay: Double,
    val employedFrom: Instant,
    /**
     * Last day of service, or today for a serving employee. Clamped to [asOf]
     * when that is set, so a projection can never run past the date it was
     * made for.
     */
    val employedTo: Instant,
    /**
     * Every DA rate for any service type, in any order. A rate applies from its
     * own [DrRate.effectiveFrom] until the next rate for the *same* service
     * type supersedes it, so this is a history, not a current value: a rate is
     * never edited in place, which is what lets a figure calculated last year
     * still reproduce today. Plan section 6.5 makes the maintenance of this
     * table an admin job, revised twice a year for central pensioners.
     */
    val rates: List<DrRate> = emptyList(),
    /**
     * Effective dates of the revisions this pensioner actually missed. Empty
     * means "all of them", which is the right default for a first-use estimate
     * and the wrong one for a claim — hence the parameter.
     */
    val missedRevisions: List<Instant> = emptyList(),
    /**
     * Today, when [employedTo] is an open end rather than a retirement. Its
     * presence is what makes the result an estimate instead of a claim: a
     * serving employee's last period is still accruing, and telling somebody
     * they are owed a figure for a period that has not finished is how a
     * calculator loses a user's trust.
     */
    val asOf: Instant? = null,
) {
    init {
        require(basicPay >= 0) { "basicPay cannot be negative" }
    }

    /** The instant the arithmetic stops. Never later than [asOf]. */
    val until: Instant get() = asOf?.let { if (employedTo < it) employedTo else it } ?: employedTo
}

/**
 * One revision's worth, with the two rates that produced it.
 *
 * core-model's [ArrearsLine] can only carry the rate in force, which is the
 * number a pensioner is shown but not the number that explains it. "You were
 * already on 125% and this revision moved you to 150%, so it is worth 25 points"
 * is the sentence that makes the figure checkable by hand. The UI needs all
 * three, and adding them to a core-model type is not this module's call.
 */
data class ArrearsPeriodDetail(
    val period: ArrearsPeriod,
    val days: Int,
    /** The rate already being drawn when this period began. */
    val previousRatePercent: Double,
    /** The new rate. [period.ratePercent] says the same thing; both are kept
     *  because one is read off the line and one off the delta. */
    val ratePercent: Double,
    /** `ratePercent - previousRatePercent`. The whole claim is the sum of these. */
    val deltaPercent: Double,
    val amount: Double,
    val isEstimate: Boolean,
) {
    /** What the pension would be after this revision, per the plan's last line. */
    fun newPension(basicPension: Double): Double =
        basicPension * (1 + ratePercent / 100.0)
}

/**
 * Arrears, as an estimate or as a claim.
 *
 * Every function here is a pure function of its arguments. That is the
 * requirement, not tidiness: plan section 6.5's figures have to be checkable
 * against a worked example without standing up the app, a database or a clock.
 */
object ArrearsCalculator {

    /**
     * Days in a year, the divisor that turns a day count into pro-rated months.
     *
     * A DA rate is an annual percentage of basic pay, so a period of N days is
     * worth `basic × delta% × N/365 × 12`. The constant exists so the choice is
     * one visible line rather than a number buried in an expression, and so a
     * correction to 365.25 is a one-line diff rather than an audit.
     */
    const val DAYS_PER_YEAR: Double = 365.0

    /**
     * Months a day count is worth, pro-rated.
     *
     * The plan's own `months_between` returns whole months and is zero for a
     * partial one, so a claim ending on 20 July would pay nothing for the
     * revision that started on 1 July. A pensioner does not experience DA as
     * starting on the first — they experience it as accruing — and a figure
     * that silently drops the last three weeks is a figure they will find
     * wrong at the counter. This is a deliberate, documented departure from the
     * plan's literal formula; the two agree exactly at a whole-year boundary,
     * which is the case an audit is most likely to check.
     */
    fun monthsProRata(days: Int): Double = days * MONTHS_IN_YEAR / DAYS_PER_YEAR

    private const val MONTHS_IN_YEAR: Double = 12.0

    /**
     * The per-period breakdown, with the deltas.
     *
     * [calculate] is a thin wrapper over this; the detail exists because a
     * pensioner checking our arithmetic by hand needs the *previous* rate, and
     * that number is not in core-model's [ArrearsPeriod].
     */
    fun breakdown(input: ArrearsInput): List<ArrearsPeriodDetail> {
        val spanEnd = input.until
        if (input.employedFrom >= spanEnd) return emptyList()

        // The full history, not just the part inside the span: the previous rate
        // for the first in-span period usually belongs to a period this
        // pensioner was never in.
        val history = input.rates
            .filter { it.serviceType == input.serviceType }
            .sortedBy { it.effectiveFrom }

        val isEstimate = input.asOf != null

        return history.mapIndexedNotNull { index, rate ->
            // A rate that begins after the span ends buys nothing.
            if (rate.effectiveFrom >= spanEnd) return@mapIndexedNotNull null

            val nextStart = history.getOrNull(index + 1)?.effectiveFrom ?: spanEnd

            // Clamp to the employment span at both ends. The lower clamp stops
            // a 1996 rate claiming arrears for somebody who joined in 2016;
            // the upper clamp stops the open-ended last period running past
            // today.
            val from = maxOf(rate.effectiveFrom, input.employedFrom)
            val to = minOf(nextStart, spanEnd)

            // A rate effective on the very day the next one starts buys
            // nothing. Skipping it rather than emitting a zero-day line keeps
            // the breakdown to lines a user can read.
            val days = daysBetween(from, to)
            if (days <= 0) return@mapIndexedNotNull null

            val previous = history.getOrNull(index - 1)?.ratePercent ?: 0.0
            val delta = rate.ratePercent - previous
            val months = monthsProRata(days)

            ArrearsPeriodDetail(
                period = ArrearsPeriod(
                    from = from,
                    effectiveTo = to,
                    ratePercent = rate.ratePercent,
                    isClaimable = !isEstimate && isMissed(input.missedRevisions, from),
                ),
                days = days,
                previousRatePercent = previous,
                ratePercent = rate.ratePercent,
                deltaPercent = delta,
                // The plan's second and third lines, with pro-rated months.
                // Rounded to whole rupees because a revision is settled in
                // rupees, and rounded *before* the sum so that `total` is
                // exactly the sum of the displayed lines — a pensioner adding
                // up our own table has to land on our own number.
                amount = (percentageOf(input.basicPay, delta) * months).toRupees(),
                isEstimate = isEstimate,
            )
        }
    }

    /**
     * The claim.
     *
     * [ArrearsResult.total] is the sum of the per-line amounts, and each line
     * is whole rupees, so the two agree exactly rather than to within float
     * noise. [ArrearsResult.displayTotal] is the Indian-grouped form.
     */
    fun calculate(input: ArrearsInput): ArrearsResult {
        val details = breakdown(input)
        return ArrearsResult(
            periods = details.map { detail ->
                ArrearsLine(
                    period = detail.period,
                    days = detail.days,
                    amount = detail.amount,
                    isEstimate = detail.isEstimate,
                )
            },
            total = details.sumOf { it.amount },
            isEstimate = input.asOf != null,
        )
    }

    /**
     * The rate in force at [at] for [serviceType], or null if the table does
     * not reach back that far.
     *
     * Exposed because "which rate applied to my period" is the question a
     * pensioner asks an Accounts Office clerk, and answering it from the same
     * table the period was priced from removes any chance of the two
     * disagreeing.
     */
    fun rateInForceAt(rates: List<DrRate>, serviceType: ServiceType, at: Instant): DrRate? =
        rates
            .filter { it.serviceType == serviceType && it.effectiveFrom <= at }
            .maxByOrNull { it.effectiveFrom }

    /**
     * Whether a period beginning at [revisionOn] is one the user actually
     * missed.
     *
     * A period's start is by construction a revision's effective date, so
     * matching on it is exact rather than approximate. An empty
     * [ArrearsInput.missedRevisions] matches everything: an estimate that
     * assumed the worst is more useful to somebody checking a figure than a
     * precise zero, and the estimate is labelled as an estimate.
     */
    private fun isMissed(missed: List<Instant>, revisionOn: Instant): Boolean =
        missed.isEmpty() || missed.any { it == revisionOn }
}
