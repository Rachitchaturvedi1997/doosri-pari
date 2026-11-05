package `in`.doosripari.core.model

import kotlinx.datetime.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Pension toolkit — calculators. Plan section 6.5.
 *
 * Everything in this file is a PURE FUNCTION. No I/O, no clock, no network.
 * That is not tidiness, it is the requirement: a pensioner will act on the
 * number this produces, so it has to be checkable against a worked example in
 * the plan without standing anything up. `feature-toolkit` implements the
 * arithmetic; this file is the vocabulary it speaks and the types its results
 * come back in.
 */

/**
 * A dearness allowance rate, as an official order fixes it.
 *
 * Rates are [ratePercent] (58 means 58% of basic pay) and apply from
 * [effectiveFrom] until the next rate for the same service type supersedes
 * them. A rate is never edited: a correction is a new row, so that an arrears
 * figure calculated last year still reproduces today.
 */
data class DrRate(
    val serviceType: ServiceType,
    val ratePercent: Double,
    val effectiveFrom: Instant,
    val sourceDocId: Int? = null,
) {
    init {
        require(ratePercent in 0.0..300.0) { "implausible DA rate: $ratePercent" }
    }
}

/** The pay a calculation starts from. All three components, pre-retirement. */
data class PayBasis(
    /** Last basic pay drawn before retirement. */
    val basicPay: Double,
    /** Dearness pay at the relevant point in service. */
    val da: Double = 0.0,
    /** Gratuity, and anything else that is a fixed component. */
    val gratuity: Double = 0.0,
) {
    init {
        require(basicPay >= 0 && da >= 0 && gratuity >= 0) {
            "pay components cannot be negative"
        }
    }

    /** What the DA is applied to. Gratuity does not attract dearness allowance. */
    val daPay: Double get() = basicPay + da
}

// ------------------------------------------------------------------ arrears --

/**
 * Arrears: what a missed DA revision is worth.
 *
 * The rule that matters is that each period is valued at the rate in force
 * *during* that period, not at today's rate. A pensioner who retired in 2015
 * and missed four revisions is owed four different calculations, and using the
 * current rate for all of them is the single most common way these figures are
 * got wrong.
 */
data class ArrearsPeriod(
    val from: Instant,
    /** The day the revision took effect. Not the day it was announced. */
    val effectiveTo: Instant,
    val ratePercent: Double,
    /** False for the period the user is still accruing. */
    val isClaimable: Boolean = true,
)

/** What the arrears figure came out at, and how. */
data class ArrearsResult(
    /** Per-period breakdown, so the user can see which revision is which. */
    val periods: List<ArrearsLine>,
    val total: Double,
    /** True when the result is a projection rather than a claim. */
    val isEstimate: Boolean = false,
) {
    /** A rounded-to-rupee figure for display; the exact one is [total]. */
    val displayTotal: String get() = total.roundToRupees()
}

/** One revision's worth. */
data class ArrearsLine(
    val period: ArrearsPeriod,
    val days: Int,
    val amount: Double,
    val isEstimate: Boolean = false,
)

// -------------------------------------------------------------- commutation --

/**
 * Commutation: the lump sum for giving up the final pension.
 *
 * Table C of the commutation rules is not in this file on purpose — the rates
 * are set by order and change, so they come from the server as a [CommutationTable].
 * What lives here is the factor, which is arithmetic and not policy.
 */
data class CommutationInput(
    val pay: PayBasis,
    val serviceYears: Double,
    val serviceMonths: Int = 0,
    val ageAtRetirement: Int,
    /** Commutation ceiling as a fraction of basic pay, 1/3 or 1/2. */
    val ceilingFraction: Double = 1.0 / 3.0,
    /** Whether the ceiling is the 1990 or the post-2000 rule. */
    val isPost2000Pensioner: Boolean = true,
) {
    init {
        require(serviceYears >= 0) { "service cannot be negative" }
        require(ageAtRetirement in 18..75) { "implausible retirement age: $ageAtRetirement" }
    }

    val totalServiceYears: Double get() = serviceYears + serviceMonths / 12.0
}

data class CommutationResult(
    /** The commuted portion, at the table factor. */
    val commutedPension: Double,
    /** The pension left behind. */
    val residualPension: Double,
    /** The whole thing as a lump sum. */
    val lumpSum: Double,
    /** The ceiling was binding, so the figure is capped and this says so. */
    val wasCapped: Boolean = false,
    val tableUsed: String,
) {
    val displayLumpSum: String get() = lumpSum.roundToRupees()
}

/** A commutation factor table, fetched from the server. */
data class CommutationTable(
    val serviceYears: Double,
    /** Years of commuted pension bought per year's service. */
    val factor: Double,
    val effectiveFrom: Instant,
    val sourceDocId: Int? = null,
)

// --------------------------------------------------------------- gratuity --

data class GratuityInput(
    val lastDrawnPay: Double,
    val serviceYears: Double,
    val serviceMonths: Int = 0,
    val serviceDays: Int = 0,
    /** Ex-gratia is a flat 15 days' pay for a death in service. */
    val isExGratia: Boolean = false,
)

data class GratuityResult(
    val gratuity: Double,
    val exGratia: Double,
    val total: Double,
    /** Which dearness allowance the calculation was rounded by. */
    val roundedToDaRate: Double?,
) {
    val displayTotal: String get() = total.roundToRupees()
}

// -------------------------------------------------------------- leave encashment --

data class LeaveEncashmentInput(
    /** Earned leave in days. */
    val leaveDays: Int,
    val lastDrawnPay: Double,
    val serviceYears: Double,
    val isLrEntitled: Boolean = false,
)

data class LeaveEncashmentResult(
    val daysCounted: Int,
    val amount: Double,
    /** True when a ceiling cut the figure. */
    val wasCapped: Boolean = false,
) {
    val displayAmount: String get() = amount.roundToRupees()
}

// ------------------------------------------------------------------ helpers --

/**
 * Rupees, Indian grouping, no decimals.
 *
 * ₹4,86,000 rather than 486000. This app is for people whose first language
 * for numbers may be lakhs and crores, and a western-grouped figure is not
 * merely ugly — it is read as a different quantity by somebody who has never
 * seen one.
 */
fun Double.roundToRupees(): String {
    val negative = this < 0
    val digits = kotlin.math.abs(this).roundToLong().toString()

    // Indian grouping: the last three digits, then pairs. 486000 → 4,86,000,
    // not 486,000. The pairs are built right-to-left and flipped back, because
    // that is the only order a right-to-left walk can produce.
    val grouped = buildString {
        if (digits.length > 3) {
            val head = digits.dropLast(3).reversed().chunked(2).joinToString(",")
            append(head.reversed())
            append(',')
        }
        append(digits.takeLast(3))
    }
    return buildString {
        if (negative) append('-')
        append('₹')
        append(grouped)
    }
}

/** Whole months between two dates, which is how service is counted. */
fun monthsBetween(from: Instant, to: Instant): Int {
    val a = kotlinx.datetime.LocalDateTime.fromEpochSeconds(from.epochSeconds)
    val b = kotlinx.datetime.LocalDateTime.fromEpochSeconds(to.epochSeconds)
    return (b.year - a.year) * 12 + (b.monthNumber - a.monthNumber)
}

/** Round to whole rupees, the unit every pension figure is paid in. */
fun Double.toRupees(): Double = roundToLong().toDouble()

/** Percentage of a base, for the rate-at-period rule in arrears. */
fun percentageOf(base: Double, ratePercent: Double): Double = base * ratePercent / 100.0

/** Days between two instants, counting the start day and not the end. */
fun daysBetween(from: Instant, to: Instant): Int =
    ((to.epochSeconds - from.epochSeconds) / 86_400L).toInt()
