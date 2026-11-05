package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.LeaveEncashmentInput
import `in`.doosripari.core.model.LeaveEncashmentResult
import `in`.doosripari.core.model.roundToRupees
import `in`.doosripari.core.model.toRupees

/**
 * Leave encashment: what the balance of earned leave is worth on retirement.
 *
 * **Plan section 6.5 does not mention leave encashment at all** — no formula,
 * no ceiling, no mention of the leave-accumulation rule. The rules below are
 * the CCS (Leave) Rules provision and the office memoranda on leave
 * accumulation, and they arrive here as a requirement rather than as a
 * citation. The plan's own preamble stands: every formula must be checked
 * against the current official rules before launch, and results are labelled
 * "estimate only".
 *
 * The rule that catches people is the **ceiling on accumulation, not on
 * service**. Earned leave does not accrue without limit: a non-LR employee's
 * balance tops out at [NON_LR_CEILING_DAYS] days, and a leave-encashment
 * eligible employee's at [LR_CEILING_DAYS]. A pensioner with twenty-five years
 * of service and a balance of four hundred days does not get four hundred
 * days' pay; they get the ceiling, and [LeaveEncashmentResult.wasCapped] is
 * how the UI says so rather than leaving them to work out why the figure is
 * smaller than the balance on their slip.
 *
 * The base is [LeaveEncashmentInput.lastDrawnPay], which by its name already
 * includes dearness allowance. Leave is encashed at the pay actually drawn, not
 * at basic pay, so a DA revision two years ago changes this figure and the
 * pensioner is right to expect it to.
 */
object LeaveEncashmentCalculator {

    /**
     * Accumulation ceiling for a leave-encashment *eligible* employee — the
     * category usually written "LR" for Leave Recrement, and misread by half
     * the people who see the letters as an initial.
     */
    const val LR_CEILING_DAYS: Int = 300

    /**
     * Accumulation ceiling for everybody else.
     *
     * Thirty days is also the annual accrual for a non-eligible employee, so
     * the ceiling and the annual rate are the same number. That is not a
     * coincidence in the rules and it is worth stating, because it means a
     * non-eligible employee can never carry a balance above one year's accrual
     * and the calculator never has to ask how much of the balance is
     * grandfathered.
     */
    const val NON_LR_CEILING_DAYS: Int = 30

    /** The divisor that turns monthly pay into daily pay. */
    const val DAYS_IN_MONTH: Int = 30

    /**
     * The accumulation ceiling for one input.
     *
     * Exposed because the single most common question in the toolkit is "why
     * is my encashment less than my leave balance", and the answer is this
     * function.
     */
    fun ceilingDays(isLrEntitled: Boolean): Int =
        if (isLrEntitled) LR_CEILING_DAYS else NON_LR_CEILING_DAYS

    /**
     * Encash the balance, subject to the accumulation ceiling.
     *
     * [LeaveEncashmentInput.serviceYears] is carried by core-model so the UI can
     * show the balance against years of service. It deliberately does not enter
     * the amount: the cap is on accumulated leave, not on how long it took to
     * accumulate it, and inventing a service-proportional limit that the rules
     * do not state would shrink a pensioner's figure for no stated reason.
     */
    fun calculate(input: LeaveEncashmentInput): LeaveEncashmentResult {
        require(input.leaveDays >= 0) { "leaveDays cannot be negative" }
        require(input.lastDrawnPay >= 0) { "lastDrawnPay cannot be negative" }

        val ceiling = ceilingDays(input.isLrEntitled)
        val wasCapped = input.leaveDays > ceiling
        val daysCounted = if (wasCapped) ceiling else input.leaveDays

        return LeaveEncashmentResult(
            daysCounted = daysCounted,
            amount = (input.lastDrawnPay / DAYS_IN_MONTH * daysCounted).toRupees(),
            wasCapped = wasCapped,
        )
    }

    /**
     * The sentence to print under a capped figure.
     *
     * "Your balance is N days and N of those can be encashed" is a thing a
     * pensioner can act on. Showing the capped figure alone is not, and the
     * difference is the whole reason [LeaveEncashmentResult] carries
     * [LeaveEncashmentResult.daysCounted] as well as the flag.
     */
    fun capExplanation(input: LeaveEncashmentInput): String? {
        val ceiling = ceilingDays(input.isLrEntitled)
        if (input.leaveDays <= ceiling) return null
        val who = if (input.isLrEntitled) "LR employees" else "non-LR employees"
        return "You have ${input.leaveDays} days. Leave can only accumulate to " +
            "$ceiling days for $who, so $ceiling days are encashed."
    }
}

/** Indian-grouped encashment value, for a screen that has no better string. */
fun LeaveEncashmentResult.display(): String = amount.roundToRupees()
