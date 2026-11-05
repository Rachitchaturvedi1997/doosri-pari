package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.DrRate
import `in`.doosripari.core.model.PayBasis
import `in`.doosripari.core.model.ServiceType
import `in`.doosripari.core.model.percentageOf
import `in`.doosripari.core.model.roundToRupees
import `in`.doosripari.core.model.toRupees
import kotlinx.datetime.Instant

/**
 * What a month of pay adds up to: basic, dearness allowance at the rate in
 * force, and whatever allowances the pensioner actually draws.
 *
 * **Plan section 6.5 has no net-pay formula.** It lists "basic, DR, commutation,
 * medical and net" as the fields on-device OCR reads off a pension slip, and
 * then never says how they compose. So this file is deliberately the shortest
 * in the module: it adds up what is on the slip and checks one thing, and it
 * does not attempt deductions.
 *
 * The check is the one the plan actually names, in the slip reader's step 4:
 * **"was the DR applied from the right month?"** A pensioner whose DA was
 * frozen at an old rate wants to know why before they want anything else, and
 * the answer is arithmetic — the rate in force today times basic pay, against
 * the DA actually drawn. [PayrollResult.daShortfall] is that difference, and a
 * pensioner who finds a few hundred rupees of shortfall has something concrete
 * to take to the Accounts Office.
 *
 * Two things are deliberately absent, and saying why is the point of noting
 * them.
 *
 * **Deductions.** Recoveries, income tax at source, subscriptions, club
 * subscriptions and loan repayments all reduce what lands in a bank account,
 * and none of them can be computed from a pay basis — they need the
 * pensioner's own ledger. A "net pay" that silently omitted them would be
 * worse than no net pay, because the number would be wrong in a way that looks
 * authoritative. What this returns is gross, and says so.
 *
 * **Gratuity.** [PayBasis.gratuity] is a fixed component paid once, and
 * [PayBasis.daPay] in core-model already excludes it from the dearness pay
 * base. It is therefore not in a *monthly* figure, and adding it would make
 * this a retirement-benefit calculation wearing a payroll name.
 */
data class Allowance(
    /**
     * A stable short name for the allowance — "hra", "transport", "medical" —
     * because the label changes with the service and the code is what a
     * pensioner would recognise on a slip from a different department.
     */
    val code: String,
    /** What the slip calls it, in the service's own words. */
    val label: String = code,
    val amount: Double,
) {
    init {
        require(amount >= 0) { "allowance $code cannot be negative" }
    }
}

/**
 * One month of pay, gross.
 *
 * [drawnDa] is what [PayBasis.da] says was actually paid and
 * [dearnessPay] is what the rate in force says it should be. They are kept
 * apart because the whole reason to run this calculation is the gap between
 * them, and collapsing them would destroy the only useful thing it produces.
 */
data class PayrollResult(
    val basicPay: Double,
    /** Basic pay times the DA rate in force. What should be drawn. */
    val dearnessPay: Double,
    /** What [PayBasis.da] says was drawn. */
    val drawnDa: Double,
    /**
     * [dearnessPay] minus [drawnDa]. Positive means the rate in force says the
     * pensioner is being underpaid; negative means DA has been paid at a rate
     * higher than the table says. Either is worth a question at the Accounts
     * Office, and neither is adjusted here — this is a calculator, not a
     * payroll system that could correct a slip.
     */
    val daShortfall: Double,
    val allowances: List<Allowance>,
    val allowanceTotal: Double,
    /** Basic plus dearness plus allowances. Gross; see the file comment. */
    val grossPay: Double,
) {
    val displayGross: String get() = grossPay.roundToRupees()

    /** True when the DA drawn does not match the rate in force. */
    val isDaMismatch: Boolean get() = daShortfall != 0.0
}

object PayrollCalculator {

    /**
     * Add up one month of pay.
     *
     * @param daRatePercent the rate in force, as a percentage of basic pay.
     *   Taken from [ArrearsCalculator]'s table rather than from a constant,
     *   because it is order-set and the module already carries the table.
     * @param allowances whatever is actually drawn. Empty is normal and not an
     *   error: most pension components are basic plus DA and nothing else.
     */
    fun grossPay(
        pay: PayBasis,
        daRatePercent: Double,
        allowances: List<Allowance> = emptyList(),
    ): PayrollResult {
        require(daRatePercent >= 0) { "a DA rate cannot be negative, was $daRatePercent" }

        val dearnessPay = percentageOf(pay.basicPay, daRatePercent)
        val allowanceTotal = allowances.sumOf { it.amount }

        return PayrollResult(
            basicPay = pay.basicPay,
            // Not rounded: the shortfall below is the difference of two
            // unrounded figures, and rounding each side first would put a
            // rupee of noise into a number a pensioner may take to a counter.
            dearnessPay = dearnessPay,
            drawnDa = pay.da,
            daShortfall = (dearnessPay - pay.da).toRupees(),
            allowances = allowances,
            allowanceTotal = allowanceTotal,
            grossPay = (pay.basicPay + dearnessPay + allowanceTotal).toRupees(),
        )
    }

    /**
     * The DA rate in force for a service on a given day, for callers that have
     * the rates table but not the caller's own intent.
     *
     * A one-liner so that the payroll screen and the arrears screen cannot
     * disagree about which rate is current — they read the same table through
     * the same function, and there is no second place to update.
     */
    fun currentDaRatePercent(
        rates: List<DrRate>,
        serviceType: ServiceType,
        on: Instant,
    ): Double = ArrearsCalculator.rateInForceAt(rates, serviceType, on)?.ratePercent ?: 0.0
}
