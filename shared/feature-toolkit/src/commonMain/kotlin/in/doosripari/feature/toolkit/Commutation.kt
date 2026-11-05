package `in`.doosripari.feature.toolkit

import `in`.doosripari.core.model.CommutationInput
import `in`.doosripari.core.model.CommutationResult
import `in`.doosripari.core.model.CommutationTable
import `in`.doosripari.core.model.roundToRupees
import `in`.doosripari.core.model.toRupees
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Commutation: the lump sum for giving up part of the final pension.
 * Plan section 6.5.
 *
 * The plan's arithmetic, in full:
 *
 * ```
 * factor = COMMUTATION_TABLE[age_next_birthday]      # official table
 * lump_sum = basic_pension × (percent_commuted / 100) × 12 × factor    # percent ≤ 40
 * reduced_pension = basic_pension × (1 − percent_commuted / 100)
 * restoration_date = commutation_date + 15 years
 * ```
 *
 * Three decisions in this file are worth stating up front, because the plan
 * and core-model disagree about them and somebody will have to settle which is
 * right.
 *
 * **The factor table is an argument, never a constant.** core-model says so
 * directly — the table is "set by order and change, so they come from the
 * server as a `CommutationTable`". A hardcoded factor would mean that when a
 * new order lands the app keeps producing the old figure with full confidence,
 * and the pensioner finds out at the counter. So [CommutationCalculator.factor]
 * takes the table and returns zero rather than a guess when it cannot read it.
 *
 * **The band key is an argument too, because the two sources disagree about
 * what it is.** The plan keys the table by `age_next_birthday`; core-model's
 * field is named `CommutationTable.serviceYears`. This calculator follows the
 * plan and bands on age, and takes the key as
 * [CommutationCalculator.calculate]'s `bandKey` parameter so that renaming the
 * field to something honest is a one-line change rather than a rewrite. This is
 * the single most likely thing in the module to be wrong; see the module
 * report.
 *
 * **`wasCapped` is about the ceiling only.** The plan writes `percent ≤ 40` as
 * a bound on the formula, and core-model puts the *rule's* ceiling on the
 * input as [CommutationInput.ceilingFraction]. Both are honoured, the rule's
 * first and the plan's backstop second, and `wasCapped` says whether the
 * backstop bit. A pre-2000 pensioner at one third is under both and is not
 * capped; a post-2000 pensioner at one half is over the plan's 40% and is, and
 * the UI says so rather than showing a number and a wrong reason.
 */
object CommutationCalculator {

    /**
     * Ceiling for a pensioner who retired before the 1 January 2000 rules: a
     * third of basic pay, so that at least two thirds of the pension is never
     * commutable. This is also core-model's default for
     * [CommutationInput.ceilingFraction], deliberately the stricter of the two
     * — when the rule is not known, understate what may be commuted.
     */
    val PRE_2000_CEILING: Double = 1.0 / 3.0

    /** Ceiling under the post-2000 rules: a half of last drawn pay. */
    val POST_2000_CEILING: Double = 1.0 / 2.0

    /**
     * Service under which a pre-2000 pensioner may commute only a quarter.
     *
     * Not in the plan, and not in core-model. It is the CCS (Commutation of
     * Pension) Rules provision the plan's corpus table points at by name, and
     * without it a four-year service pensioner is shown a third of basic pay —
     * an entitlement the rule does not grant. [CommutationDetail.usedQuarterRule]
     * reports when it applied, so a wrong constant is visible rather than
     * silent.
     */
    const val QUARTER_RULE_MAX_YEARS: Double = 10.0

    /**
     * The ceiling a pre-2000 pensioner with under
     * [QUARTER_RULE_MAX_YEARS] of service may commute, as a fraction.
     */
    val QUARTER_RULE_CEILING: Double = 0.25

    /**
     * Plan section 6.5's own backstop: `percent ≤ 40`.
     *
     * Applied after the rule's ceiling, so it can only ever pull the figure
     * down. The two rules agree for the pre-2000 ceiling and disagree for the
     * post-2000 one, which is why [CommutationResult.wasCapped] exists.
     */
    const val PLAN_MAX_PERCENT: Double = 40.0

    /** Plan section 6.5: restoration comes fifteen years after commutation. */
    const val RESTORATION_YEARS: Int = 15

    /**
     * Plan section 6.5's reminder schedule: "3 months before the 15-year date".
     */
    const val RESTORATION_REMINDER_MONTHS: Int = 3

    /** Shown when no table row applies. Says so rather than implying a zero. */
    const val NO_TABLE: String = "no commutation table available"

    /**
     * How the ceiling is chosen. Here rather than inferred inside
     * [calculate] so the *caller* picks the rule — a service with a house rule
     * can pass a third value into [CommutationInput.ceilingFraction].
     */
    object CommutationCeiling {
        fun forPensioner(isPost2000Pensioner: Boolean): Double =
            if (isPost2000Pensioner) POST_2000_CEILING else PRE_2000_CEILING

        /**
         * The whole ceiling chain for one input, without a table.
         *
         * Useful on its own: the most common Sahayak question in this area is
         * "how much of my pension can I commute?", and it should not need a
         * Table C fetch to answer.
         */
        fun percentFor(input: CommutationInput): Double {
            val rule = if (
                !input.isPost2000Pensioner && input.totalServiceYears < QUARTER_RULE_MAX_YEARS
            ) {
                QUARTER_RULE_CEILING
            } else {
                input.ceilingFraction
            }
            return minOf(rule * 100.0, PLAN_MAX_PERCENT)
        }
    }

    /**
     * The percentage [calculate] would use, before any table is consulted.
     *
     * Separated from [CommutationCeiling.percentFor] only by intent: that one
     * answers "what is the ceiling", this one "what did this input actually
     * get, and why", which is the sentence a pensioner needs.
     */
    fun percentFor(input: CommutationInput): CommutationPercent = CommutationPercent(
        rulePercent = ruleCeiling(input),
        percent = CommutationCeiling.percentFor(input),
        wasCapped = ruleCeiling(input) * 100.0 > PLAN_MAX_PERCENT,
        usedQuarterRule = !input.isPost2000Pensioner &&
            input.totalServiceYears < QUARTER_RULE_MAX_YEARS,
    )

    /**
     * Commute the maximum this pensioner may commute, subject to the ceiling.
     *
     * The plan's `percent_commuted` is a given in its formula. The most that
     * can ever be given is the ceiling, so that is what this computes and what
     * "commuted pension" means on the result. A pensioner who commutes less
     * gets a smaller number from the identical arithmetic, so a UI offering a
     * choice would pass the chosen percentage in rather than change the
     * function.
     *
     * @param table every Table C row the server knows, any order. Rows
     *   effective after [asOf] are ignored, so a table published this year does
     *   not change the answer for somebody who retired in 1998.
     * @param asOf the date the commutation takes effect, normally the
     *   retirement date. It is the only way to choose between two versions of
     *   the table, and it is an argument rather than a clock read for the same
     *   reason the rest of this module takes no clock.
     * @param bandKey the table's band key. Plan section 6.5 says the age at
     *   next birthday; [CommutationInput] has no such field, so it defaults to
     *   the age at retirement and the caller can pass the real thing.
     */
    fun calculate(
        input: CommutationInput,
        table: List<CommutationTable>,
        asOf: Instant,
        bandKey: Double = input.ageAtRetirement.toDouble(),
    ): CommutationResult {
        val detail = detail(input, table, asOf, bandKey)
        return detail.result
    }

    /**
     * The same calculation with the intermediates kept.
     *
     * core-model's [CommutationResult] reports whether the ceiling bit but not
     * which ceiling, and a pensioner who is short of the one they expected
     * deserves to be told which rule stopped them. [CommutationDetail] is that
     * sentence in data form; [calculate] is the same arithmetic with it
     * discarded.
     */
    fun detail(
        input: CommutationInput,
        table: List<CommutationTable>,
        asOf: Instant,
        bandKey: Double = input.ageAtRetirement.toDouble(),
    ): CommutationDetail {
        val percent = percentFor(input)
        val basic = input.pay.basicPay

        val row = factorRow(table, bandKey, asOf)
            // A table that does not reach down to this band is a data problem,
            // and the safe direction to fail in is zero: "you may commute ₹0"
            // sends the pensioner to ask a person, whereas a wrong non-zero
            // figure sends them to a counter.
            ?: return CommutationDetail(
                result = CommutationResult(
                    commutedPension = 0.0,
                    residualPension = basic,
                    lumpSum = 0.0,
                    wasCapped = percent.wasCapped,
                    tableUsed = NO_TABLE,
                ),
                percent = percent,
                factor = null,
                bandKey = bandKey,
            )

        val commutedPension = (basic * percent.percent / 100.0).toRupees()

        return CommutationDetail(
            result = CommutationResult(
                commutedPension = commutedPension,
                // The plan's `reduced_pension` line. Basic pay minus what was
                // taken, measured against the same base the ceiling is measured
                // against — so the two always add back to last basic pay, an
                // identity the tests assert.
                residualPension = (basic * (1.0 - percent.percent / 100.0)).toRupees(),
                // The plan's `lump_sum` line.
                lumpSum = (commutedPension * MONTHS_IN_YEAR * row.factor).toRupees(),
                wasCapped = percent.wasCapped,
                tableUsed = labelFor(row),
            ),
            percent = percent,
            factor = row.factor,
            bandKey = bandKey,
        )
    }

    /**
     * Plan section 6.5: the date the full pension comes back, fifteen years
     * after commutation. Uses calendar years rather than a [kotlin.time.Duration]
     * because fifteen of those are not a fixed span and a 15 × 365-day guess is
     * wrong by two days in a leap year — and a pensioner who is told the wrong
     * restoration date will believe it.
     */
    fun restorationDate(commutedOn: Instant, timeZone: TimeZone = TimeZone.UTC): LocalDate =
        commutedOn.toLocalDateTime(timeZone).date.plus(RESTORATION_YEARS, DateTimeUnit.YEAR)

    /** Plan section 6.5's reminder: three months before the restoration date. */
    fun restorationReminderDate(
        commutedOn: Instant,
        timeZone: TimeZone = TimeZone.UTC,
    ): LocalDate = restorationDate(commutedOn, timeZone)
        .minus(RESTORATION_REMINDER_MONTHS, DateTimeUnit.MONTH)

    /**
     * The rule's own ceiling as a fraction, before the plan's backstop.
     *
     * Kept separate from [CommutationPercent.percent] so the UI can say "the
     * rule allows a third, the bound is 40%, so the rule wins" rather than
     * showing a number with no provenance.
     */
    private fun ruleCeiling(input: CommutationInput): Double =
        if (!input.isPost2000Pensioner && input.totalServiceYears < QUARTER_RULE_MAX_YEARS) {
            QUARTER_RULE_CEILING
        } else {
            input.ceilingFraction
        }

    /**
     * The table row for a [bandKey] band, on [asOf].
     *
     * Filter to the version in force, then take the longest band reached — a
     * table is a step function and interpolating between two bands would
     * produce a factor no order ever published. A band below the first row
     * uses the first row, because the table already says what a short-service
     * pensioner gets and the ceiling applies on top of it.
     *
     * Where two orders both cover the same band and both are in force, the
     * later one wins. That is not a tie-break, it is the point: a new order
     * does not delete the rows of the old one, so "the highest band" returns
     * whichever row happens to be first in the list, and a pensioner retiring
     * the year after a reissue would be paid on the superseded factor without
     * anything in the result looking wrong.
     */
    private fun factorRow(
        table: List<CommutationTable>,
        bandKey: Double,
        asOf: Instant,
    ): CommutationTable? {
        val inForce = table.filter { it.effectiveFrom <= asOf }
        if (inForce.isEmpty()) return null

        val reached = inForce.filter { it.serviceYears <= bandKey }
        if (reached.isEmpty()) return inForce.minByOrNull { it.serviceYears }

        val band = reached.maxOf { it.serviceYears }
        return reached
            .filter { it.serviceYears == band }
            .maxByOrNull { it.effectiveFrom }
    }

    /**
     * What to print under the answer.
     *
     * The doc id when there is one, because that is what a pensioner can look
     * up or challenge; the effective date always, because two orders can be
     * issued the same week and the date is the only thing that separates them.
     */
    private fun labelFor(row: CommutationTable): String {
        val doc = row.sourceDocId?.let { "order #$it" } ?: "commutation table"
        return "$doc in force from ${row.effectiveFrom}"
    }

    private const val MONTHS_IN_YEAR: Double = 12.0
}

/**
 * How much of the pension was commuted, and which rule decided it.
 *
 * [percent] is what was used. [rulePercent] is what the retirement rule alone
 * allows, before plan section 6.5's 40% bound. They differ only for a
 * post-2000 pensioner, and when they do the difference is the ceiling binding
 * — which is the fact the UI is obliged to say out loud.
 */
data class CommutationPercent(
    /** The retirement rule's own ceiling, as a percentage of basic pay. */
    val rulePercent: Double,
    /** What was actually used, after the plan's bound. */
    val percent: Double,
    /** True when the bound cut the rule's figure down. */
    val wasCapped: Boolean,
    /** True when the under-ten-years quarter rule applied instead of the ceiling. */
    val usedQuarterRule: Boolean,
) {
    /** The sentence a pensioner can check, and the UI can print. */
    val explanation: String
        get() = when {
            usedQuarterRule && wasCapped ->
                "Under 10 years of service on the pre-2000 rules, so a quarter of " +
                    "basic pay, cut further to ${percent.roundToInt2()}% by the 40% bound."
            usedQuarterRule ->
                "Under 10 years of service on the pre-2000 rules, so a quarter of basic pay."
            wasCapped ->
                "The post-2000 rules allow a half, cut to ${percent.roundToInt2()}% by the 40% bound."
            else -> "${percent.roundToInt2()}% of basic pay."
        }
}

/**
 * A commutation answer with the working kept.
 *
 * [factor] is null exactly when no table row applied, which is also the case
 * where [result.lumpSum] is zero and [result.tableUsed] is
 * [CommutationCalculator.NO_TABLE]. The three always agree; the tests assert
 * it.
 */
data class CommutationDetail(
    val result: CommutationResult,
    val percent: CommutationPercent,
    /** The table factor, or null when no row applied. */
    val factor: Double?,
    /** The band the table was asked for — an age, per plan section 6.5. */
    val bandKey: Double,
) {
    /** Indian-grouped residual, which core-model does not provide a getter for. */
    fun residualDisplay(): String = result.residualPension.roundToRupees()
}

private fun Double.roundToInt2(): String {
    val scaled = kotlin.math.round(this * 10.0) / 10.0
    return if (scaled == scaled.toLong().toDouble()) "${scaled.toLong()}" else "$scaled"
}
