package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.MobilityCheck
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The two timed tests from plan section 7.1's "Senior Fitness Test" — thirty
 * seconds of chair stands and a single-leg hold — and what they are allowed to
 * change.
 *
 * ## The one thing this file decides
 *
 * [balanceFor] — whether a standing balance is offered, and with what to hold
 * on to. That is the entire purpose of the test, and the reason it is in the
 * app rather than only in a clinic: a fall is the realistic injury in a class
 * of 60-year-olds on a mat, plan section 21 lists it as its own risk row, and
 * the only reliable predictor available on a phone is the person trying it
 * once, safely, and telling the truth about it.
 *
 * ## Three answers, not two
 *
 * This is the part that is easy to collapse and must not be:
 *
 *  - **[BalanceVerdict.UnsupportedWall]** — both numbers comfortable. The pose
 *    as plan section 7.1 wrote it.
 *  - **[BalanceVerdict.Supported]** — the middle case, and the one the app
 *    exists for. Somebody who cannot manage five seconds unaided can very often
 *    manage thirty with a fingertip on a doorframe, and taking the pose away
 *    from them over that is the same mistake as withholding a modified forward
 *    bend.
 *  - **[BalanceVerdict.ChairOnly]** — no standing pose.
 *
 * A boolean would leave the middle case with nowhere to go, and the middle case
 * is most of the people who ever see this screen.
 *
 * ## What the numbers are not
 *
 * Not a diagnosis, not a score out of ten, and not a comparison with anybody
 * else's. Plan section 14's "general wellness information only" applies here as
 * much as it does to blood pressure, and a screen that said "below average for
 * your age" would be making a claim about a population this app has no data
 * for. [MobilityTrend] reports deltas and nothing else.
 */
object MobilityCheckRunner {

    // -------------------------------------------------------------- the two tests --

    /**
     * Plan section 7.1's chair stand: sit down, stand up, thirty times in thirty
     * seconds, from an ordinary chair without arms.
     */
    const val CHAIR_STAND_SECONDS: Int = 30

    /** Plan section 7.1's single-leg stand, also timed. */
    const val SINGLE_LEG_HOLD_SECONDS: Int = 30

    /**
     * Below this many chair stands, no standing pose is offered even with a wall.
     *
     * Four, not zero. Somebody who cannot stand four times in thirty seconds
     * cannot reliably get out of a chair, which makes every standing pose in
     * the catalogue a fall rather than an exercise.
     */
    const val MIN_CHAIR_STANDS_SUPPORTED: Int = 4

    /**
     * The middle case's floor. A hold this long is a balance with a hand on a
     * wall, which is most of what a wall is for.
     */
    const val SUPPORTED_HOLD_SECONDS: Int = 5

    /** At or above this, the pose is offered as plan section 7.1 wrote it. */
    const val COMFORTABLE_CHAIR_STANDS: Int = 12

    const val COMFORTABLE_HOLD_SECONDS: Int = 15

    /**
     * How often to ask again.
     *
     * Eight weeks, and not more. A test somebody does twice a month is a test
     * they stop doing, and the first time somebody is asked to stand up thirty
     * times in thirty seconds having done it last Tuesday is the first time they
     * feel bad about themselves.
     */
    val REPEAT_AFTER: Duration = 8 * 7.days

    // ------------------------------------------------------------------ the test --

    /**
     * A check, ready to display, built from the two timed numbers and nothing
     * else.
     *
     * Takes [measuredAt] rather than reading a clock so the whole file stays
     * testable, and clamps both numbers into their possible ranges: a mis-tap
     * on a counter that reaches forty in thirty seconds should not read as
     * superb fitness, and a negative number should not read as a fall risk and
     * take somebody off the standing class.
     */
    fun of(
        id: Int,
        chairStandsIn30s: Int,
        singleLegHoldSeconds: Int,
        measuredAt: Instant,
    ): MobilityCheck = MobilityCheck(
        id = id,
        chairStandsIn30s = chairStandsIn30s.coerceIn(0, 200),
        singleLegHoldSeconds = singleLegHoldSeconds.coerceIn(0, 600),
        measuredAt = measuredAt,
    )

    /**
     * The verdict, and the only thing in this module that may take a pose away.
     *
     * A knee is not a reason to stop standing; a weak hip is. The difference is
     * in the numbers and not in a flag the user has to set, which is why this is
     * its own answer type rather than another [SafetyGate] flag.
     */
    fun balanceFor(check: MobilityCheck?): BalanceVerdict {
        // No check yet. Plan section 7.1's first-session flow produces one before
        // a balance class, so a null here means the flow has not run — and the
        // cautious answer is a hand on a wall, not a free-standing pose.
        if (check == null) return BalanceVerdict.Supported

        val stands = check.chairStandsIn30s
        val hold = check.singleLegHoldSeconds

        return when {
            !check.isAtFallRisk && stands >= COMFORTABLE_CHAIR_STANDS && hold >= COMFORTABLE_HOLD_SECONDS ->
                BalanceVerdict.UnsupportedWall
            stands >= MIN_CHAIR_STANDS_SUPPORTED && hold >= SUPPORTED_HOLD_SECONDS ->
                BalanceVerdict.Supported
            else -> BalanceVerdict.ChairOnly
        }
    }

    /**
     * Whether to ask for another check.
     *
     * Never within [REPEAT_AFTER] of the last one, and never again once two
     * consecutive checks agree. Both rules exist to stop this becoming a nag:
     * the check is only useful while the answer is changing, and a pensioner
     * asked to prove they are still capable every fortnight is being told the
     * app does not believe them.
     */
    fun shouldAsk(previous: List<MobilityCheck>, now: Instant): Boolean {
        if (previous.isEmpty()) return true
        val latestTwo = previous.sortedByDescending { it.measuredAt }.take(2)
        val last = latestTwo.first()
        if (now - last.measuredAt < REPEAT_AFTER) return false
        if (latestTwo.size < 2) return true
        return latestTwo[0].isAtFallRisk != latestTwo[1].isAtFallRisk
    }

    /**
     * The trend, for the user's own report and for plan section 19's monthly
     * family report.
     *
     * Three numbers and no verdicts: where each test is now, which way it moved,
     * and how long ago it was taken. No percentage, no fitness score, and no
     * sentence about whether the change is good — "is this good" is a question
     * a physiotherapist answers, and plan section 20 puts a physiotherapist in
     * charge of this content rather than a device.
     *
     * Null when there is nothing to report, which is different from reporting
     * zeroes.
     */
    fun trend(checks: List<MobilityCheck>, now: Instant): MobilityTrend? {
        val ordered = checks.sortedBy { it.measuredAt }
        val latest = ordered.lastOrNull() ?: return null
        val previous = ordered.getOrNull(ordered.size - 2)
        return MobilityTrend(
            latest = latest,
            previous = previous,
            standsDelta = previous?.let { latest.chairStandsIn30s - it.chairStandsIn30s },
            holdDelta = previous?.let { latest.singleLegHoldSeconds - it.singleLegHoldSeconds },
            daysSince = (now - latest.measuredAt).inWholeDays,
        )
    }
}

/**
 * What may be offered, given the last check.
 *
 * [isSafeToStandWithoutSupport] exists so that a caller cannot accidentally
 * treat [UnsupportedWall] and [Supported] as one, which is how a wall-support
 * cue goes missing and somebody attempts a balance with the phone across the
 * room.
 */
enum class BalanceVerdict(val code: String) {
    /** Both numbers comfortable. The pose as plan section 7.1 wrote it. */
    UnsupportedWall("unsupported_wall"),

    /**
     * A wall or a chair back is required, and the screen says so **before** the
     * class rather than during it.
     */
    Supported("supported"),

    /**
     * Chair-based only. Not a verdict on the person — a verdict, from a
     * thirty-second test on a phone, about the floor they would be standing on.
     */
    ChairOnly("chair_only");

    /** Whether a standing balance may be attempted with nothing to hold. */
    val isSafeToStandWithoutSupport: Boolean get() = this == UnsupportedWall

    /** Whether a standing pose may be offered at all, with or without support. */
    val allowsStanding: Boolean get() = this != ChairOnly

    /**
     * Whether this class needs somewhere to hold on to. A different question from
     * [allowsStanding] and answered separately, so a screen does not have to
     * know the rest of the rule to ask it.
     */
    val requiresHandhold: Boolean get() = this == Supported

    companion object {
        /**
         * A stored verdict from a future version, or one written by hand, must
         * not read as permission to stand free. Anything unrecognised becomes
         * [Supported] — the middle case — because that is the answer that cannot
         * put somebody on the floor.
         */
        fun fromCode(code: String?): BalanceVerdict =
            entries.firstOrNull { it.code == code } ?: Supported
    }
}

/**
 * The user's own mobility history, in the only terms this app uses.
 *
 * Plan section 7.1 records the two numbers because they are the ones a doctor
 * asks for. Plan section 19's monthly family report is built from them, and is
 * shared only on permission — see [mayShareWithFamily], where the default is no.
 */
data class MobilityTrend(
    val latest: MobilityCheck,
    val previous: MobilityCheck?,
    /** Stands gained or lost since the last check. Null on the first one only. */
    val standsDelta: Int?,
    val holdDelta: Int?,
    val daysSince: Int,
) {
    val isFirstCheck: Boolean get() = previous == null

    /**
     * Whether anything changed, as a number and never as a judgement.
     *
     * One stand is not a trend and three is not a decline. Below the thresholds
     * this returns false, so the screen says nothing rather than saying
     * something small and worrying every eight weeks.
     */
    val hasChanged: Boolean
        get() = (standsDelta?.let { abs(it) >= CHANGE_THRESHOLD } ?: false) ||
            (holdDelta?.let { abs(it) >= HOLD_CHANGE_THRESHOLD } ?: false)

    /**
     * Whether this may go into a relative's monthly report.
     *
     * The parameter is the user's own consent and the caller has to pass it
     * explicitly. Plan section 19's family report is the one place in the app
     * where numbers about somebody's body leave their phone without them being
     * present, and a default of true would make every one of those reports
     * something the user has to remember to switch off.
     */
    fun mayShareWithFamily(familySharingAllowed: Boolean): Boolean =
        familySharingAllowed && daysSince <= SHARING_STALE_AFTER_DAYS

    companion object {
        /** Two stands, or three seconds of hold, before "something changed" is worth saying. */
        const val CHANGE_THRESHOLD: Int = 2
        const val HOLD_CHANGE_THRESHOLD: Int = 3

        /** A check older than this is not worth reporting to a relative. */
        const val SHARING_STALE_AFTER_DAYS: Long = 45
    }
}

/**
 * The prompts, as catalogue keys.
 *
 * Keys only, as everywhere in this module. The chair-stand instruction in
 * particular is the kind of sentence that has to be right in Tamil and in
 * Devanagari by somebody who has watched a 72-year-old attempt it, and plan
 * section 20's review process runs on content the translators and a
 * physiotherapist have seen — not on strings invented in a Kotlin file.
 */
object MobilityPrompts {

    /** "Sit down, stand up, thirty times in thirty seconds, from a chair without arms." */
    const val CHAIR_STAND_INSTRUCTION: String = "kendra.mobility.chair_stand_instruction"

    /** "Stand on one leg. Hold a wall if you need to." */
    const val SINGLE_LEG_INSTRUCTION: String = "kendra.mobility.single_leg_instruction"

    /** "Keep a hand on the wall for this one." Shown before the class, not during. */
    const val HOLD_THE_WALL: String = "kendra.mobility.hold_the_wall"

    /** "We will do this one sitting down." Said once, without apology. */
    const val SEATED_THIS_TIME: String = "kendra.mobility.seated_this_time"

    /** "Here are your numbers, and where they have been going." */
    const val YOUR_NUMBERS: String = "kendra.mobility.your_numbers"

    /** "These are your own numbers, for you and your doctor." Plan section 14. */
    const val NOT_A_COMPARISON: String = "kendra.mobility.not_a_comparison"

    /**
     * Both tests, in the order they are administered, so a screen never has to
     * decide the order itself and get it subtly wrong on one platform.
     */
    val both: List<Phrase> = listOf(
        Phrase(CHAIR_STAND_INSTRUCTION),
        Phrase(SINGLE_LEG_INSTRUCTION),
    )
}
