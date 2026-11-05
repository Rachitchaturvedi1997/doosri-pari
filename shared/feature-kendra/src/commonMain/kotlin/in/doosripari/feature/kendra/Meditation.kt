package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.KendraProfile
import `in`.doosripari.core.model.SafetyFlag
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The meditation coach: the glowing orb, the breathing it follows, and the
 * sounds it plays under. Plan section 7.1, "Meditation coach", reusing what
 * Moholla already draws.
 *
 * ## The pace is slower than a wellness app, on purpose
 *
 * A wellness app breathes at 4 in / 6 out, or on a 5-5 box, and calls a minute
 * a minute. Everything here is slower: [BreathPattern.SlowSettle] is 5 in / 7
 * out, and the longest pattern holds neither phase long enough to become work.
 *
 * Three reasons, and the third is the one that matters:
 *
 *  1. **The lungs are slower than the metronome.** A box-breathing timer is set
 *     by somebody in their thirties who measures their own comfort in seconds.
 *     The people this app is for cannot reliably make their breath change on a
 *     cue, so a faster cue produces a phase change they did not choose — a
 *     quick top-up of air on the way out of an exhale, which is exactly the
 *     thing that sets somebody light-headed.
 *  2. **Vertigo is in the flag list.** Plan section 7.1 lists vertigo and
 *     dizziness as things the health check asks about. Rapid breath changes are
 *     a common trigger, and a coach that rushes the transition from inhale to
 *     exhale is a coach that can set somebody off.
 *  3. **There is nowhere to hide if it is too fast.** A person alone in a room
 *     at 8pm who begins to feel strange has no one to say so to and nothing to
 *     press except a screen. Slower costs them ninety seconds. Faster costs
 *     somebody a fall.
 *
 * ## Nothing here holds the breath for some people, and the rule is enforced here
 *
 * Plan section 7.1: high blood pressure or a heart condition means "no
 * kapalbhati, no breath holding". A retained breath raises intrathoracic
 * pressure and venous return, and for somebody with uncontrolled hypertension
 * or a cardiac condition that is not a mild thing. So [BreathPattern.forProfile]
 * takes the profile and returns a pattern with both hold phases at zero, and
 * [BreathPattern.isBreathHolding] is what a screen checks before it draws a
 * "hold" caption — because a caption that says HOLD to somebody for whom
 * holding is contraindicated is the failure, not a missing one.
 *
 * ## The orb is the interface, and it is never the only cue
 *
 * The orb swells on the inhale and settles on the exhale. It is the primary
 * thing on screen, but every phase also produces a written cue and a spoken
 * one, because the orb is useless to somebody with low vision and the written
 * cue is useless to somebody who cannot read the script quickly. There is
 * always a third channel. That is not redundancy for its own sake; it is the
 * difference between a session that works for everybody in the room and one that
 * works for whoever the designer tested with.
 */
object Meditation {

    /**
     * Plan section 7.1's session list, in the order given there.
     *
     * [MeditationSession.code] is stable and appears in the `kendra_sessions`
     * log, so it is a code and not an index.
     */
    val sessions: List<MeditationSession> = listOf(
        MeditationSession(
            code = "sleep",
            titleKey = "kendra.meditation.sleep",
            length = 12.minutes,
            pattern = BreathPattern.SlowSettle,
            soundscape = Soundscape.Rain,
        ),
        MeditationSession(
            code = "worry",
            titleKey = "kendra.meditation.worry",
            length = 8.minutes,
            pattern = BreathPattern.LongExhale,
            soundscape = Soundscape.River,
        ),
        MeditationSession(
            code = "gratitude",
            titleKey = "kendra.meditation.gratitude",
            length = 6.minutes,
            pattern = BreathPattern.LongExhale,
            soundscape = null,
        ),
        MeditationSession(
            // Written gently for widowed users, per the plan. Recorded as a
            // flag rather than as a different session type, because it is the
            // same session with a different script and the audio is credited
            // separately.
            code = "grief",
            titleKey = "kendra.meditation.grief",
            length = 10.minutes,
            pattern = BreathPattern.SlowSettle,
            soundscape = Soundscape.Tanpura,
            isForBereavement = true,
        ),
        MeditationSession(
            code = "gentle_pain_focus",
            titleKey = "kendra.meditation.gentle_pain_focus",
            length = 8.minutes,
            pattern = BreathPattern.LongExhale,
            soundscape = Soundscape.River,
            // Plan section 14 again. The session is a focus of attention on
            // discomfort; it says nothing about what the discomfort is or what
            // will help it, and the copy has to be written so it cannot be read
            // as treatment.
            isNotMedicalAdvice = true,
        ),
        MeditationSession(
            code = "loving_kindness",
            titleKey = "kendra.meditation.loving_kindness",
            length = 8.minutes,
            pattern = BreathPattern.LongExhale,
            soundscape = null,
        ),
        MeditationSession(
            code = "mantra",
            titleKey = "kendra.meditation.mantra",
            length = 10.minutes,
            pattern = BreathPattern.LongExhale,
            soundscape = Soundscape.TempleBells,
            usesMantra = true,
        ),
    )

    /**
     * A session, for a given profile, or null.
     *
     * Null only when the session is missing from the catalogue, never because of
     * a safety flag. Nothing in a breathing session is excluded to somebody on
     * the grounds of their body — [BreathPattern.forProfile] adapts the pattern
     * instead, and that is a different thing from saying no.
     */
    fun session(code: String, profile: KendraProfile): MeditationSession? =
        sessions.firstOrNull { it.code == code }?.withPattern(BreathPattern.forProfile(profile))

    /**
     * The soundscape to play, with the plan's "screen-off playback" case
     * handled here rather than in the platform layer.
     *
     * Null when the session is silent by design. A silence is a choice in this
     * module and not an absence: [MeditationSession.soundscape] is null for
     * gratitude and loving kindness because those are the two that are easier
     * to do in a silent room, and putting rain under them would be decorating
     * somebody's grief-adjacent practice.
     */
    fun soundscapeFor(session: MeditationSession): Soundscape? = session.soundscape

    /**
     * The stop timer, in whole minutes, or null for none.
     *
     * Only sessions long enough to outlast sleep have a timer offered at all.
     * A stop timer on a six-minute gratitude practice is a control with nothing
     * to control.
     */
    fun timerFor(session: MeditationSession): StopTimer? =
        if (session.length >= SLEEP_TIMER_MINIMUM) StopTimer.of(session.length) else null

    /** A timer needs something to cut off. Twelve minutes, the sleep session's length. */
    val SLEEP_TIMER_MINIMUM: Duration = 12.minutes

    /**
     * The orb at [elapsed] into a pattern's round, as a scale between 0 and 1.
     *
     * A sine over the whole round rather than a straight interpolation between
     * two values. The difference is visible: a linearly expanding orb has
     * corners at the turn, and a corner is a thing the eye reads as a change of
     * direction, which fights the breath instead of describing it.
     *
     * Continuous, and it never reaches 0 or 1 — [ORB_MIN_SCALE] and
     * [ORB_MAX_SCALE] — so the orb is a breathing shape rather than a pulse.
     */
    fun orbScale(elapsed: Duration, pattern: BreathPattern): Float {
        val wave = (1f - sin(phaseRadians(positionIn(elapsed, pattern)) * PI_2)) / 2f
        return ORB_MIN_SCALE + wave * (ORB_MAX_SCALE - ORB_MIN_SCALE)
    }

    /**
     * The cue for [elapsed], as a key plus how much of the phase is left.
     *
     * Always returns a phase, even at 0 and at the end, because a screen that
     * has nothing to draw between phases shows a flicker. The zero-length
     * phases are skipped by [BreathPhase.isHeld] rather than by the caller.
     */
    fun cueAt(elapsed: Duration, pattern: BreathPattern): BreathCue {
        val position = positionIn(elapsed, pattern)
        return when {
            position < pattern.inhaleSeconds -> BreathCue(
                phase = BreathPhase.Inhale,
                remaining = pattern.inhaleSeconds - position,
                total = pattern.inhaleSeconds,
            )

            position < pattern.inhaleSeconds + pattern.holdInSeconds -> BreathCue(
                phase = BreathPhase.HoldIn,
                remaining = pattern.inhaleSeconds + pattern.holdInSeconds - position,
                total = pattern.holdInSeconds,
            )

            position < pattern.inhaleSeconds + pattern.holdInSeconds + pattern.exhaleSeconds -> BreathCue(
                phase = BreathPhase.Exhale,
                remaining = pattern.inhaleSeconds + pattern.holdInSeconds + pattern.exhaleSeconds - position,
                total = pattern.exhaleSeconds,
            )

            else -> BreathCue(
                phase = BreathPhase.HoldOut,
                remaining = pattern.roundSeconds - position,
                total = pattern.holdOutSeconds,
            )
        }
    }

    /** Where in the round, wrapping. A round that repeats forever is the design. */
    private fun positionIn(elapsed: Duration, pattern: BreathPattern): Duration {
        val round = pattern.roundSeconds
        if (round <= Duration.ZERO) return Duration.ZERO
        var position = elapsed % round
        if (position < Duration.ZERO) position += round
        return position
    }

    /** Where in the orb's own twelve-second swell cycle this instant falls, 0..1. */
    private fun phaseRadians(position: Duration): Float {
        val seconds = position.inWholeMilliseconds.toFloat() / 1000f
        return (seconds / ORB_PERIOD_SECONDS) % 1f
    }

    /** The orb's full swell-and-settle cycle. Slower than the breath, deliberately. */
    const val ORB_PERIOD_SECONDS: Float = 12f

    const val ORB_MIN_SCALE: Float = 0.35f
    const val ORB_MAX_SCALE: Float = 1f

    /** A full turn. `Math.PI` is JVM-only and this module also compiles for iOS. */
    private const val PI_2: Float = 6.2831855f
}

/**
 * One breathing pattern: a round of inhale, hold, exhale, hold, and then again.
 *
 * The default is the slow one. A pattern is a data value rather than a
 * coroutine so a session is a pure function of its configuration, which is what
 * makes [cueAt] and [Meditation.orbScale] testable without a clock.
 */
data class BreathPattern(
    val code: String,
    val inhaleSeconds: Int,
    val holdInSeconds: Int,
    val exhaleSeconds: Int,
    val holdOutSeconds: Int,
    /** Silence between rounds, so the pattern is not a treadmill. */
    val restSeconds: Int = 0,
) {
    init {
        require(inhaleSeconds > 0) { "a breath has to come in somewhere" }
        require(exhaleSeconds > 0) { "a breath has to go out somewhere" }
        require(holdInSeconds >= 0 && holdOutSeconds >= 0) { "a hold cannot be negative" }
    }

    val roundSeconds: Duration
        get() = (inhaleSeconds + holdInSeconds + exhaleSeconds + holdOutSeconds).seconds

    val restDuration: Duration get() = restSeconds.seconds

    /**
     * Whether this pattern asks the user to stop breathing.
     *
     * The single most important predicate in this file. Plan section 7.1 rules
     * breath holding out for high blood pressure and for a heart condition, and
     * the rule is only worth having if it is checked somewhere — so
     * [forProfile] removes the holds, and a screen can assert this rather than
     * trusting that somebody remembered.
     */
    val isBreathHolding: Boolean get() = holdInSeconds > 0 || holdOutSeconds > 0

    /** The ratio a screen can use to size the orb. Never divides by zero. */
    val inOutRatio: Float
        get() = exhaleSeconds.toFloat() / inhaleSeconds.toFloat()

    /**
     * The pattern this profile may use.
     *
     * Holding is removed for [SafetyFlag.HeartCondition] and
     * [SafetyFlag.RecentSurgery], and the exhale is lengthened for
     * [SafetyFlag.LimitedMobility] and [SafetyFlag.FallRisk] because a longer
     * out-breath is the one thing everybody can do and the one thing that
     * reliably slows somebody down. Nothing is refused.
     */
    fun forProfile(profile: KendraProfile): BreathPattern {
        val flags = SafetyGate.effectiveFlags(profile)
        val noHolding = flags.any { it == SafetyFlag.HeartCondition || it == SafetyFlag.RecentSurgery }
        val longerOut = flags.any { it == SafetyFlag.LimitedMobility || it == SafetyFlag.FallRisk }

        return copy(
            // A distinct code so an adapted pattern is never mistaken for the
            // stock one in a log or an analytics event.
            code = if (noHolding) "$code-no_hold" else code,
            holdInSeconds = if (noHolding) 0 else holdInSeconds,
            holdOutSeconds = if (noHolding) 0 else holdOutSeconds,
            exhaleSeconds = if (longerOut) exhaleSeconds + 1 else exhaleSeconds,
        )
    }

    companion object {
        /**
         * The default: 5 in, no hold, 7 out. Slower than any wellness app.
         * See the object KDoc for why, which is three paragraphs long and worth
         * reading before this is ever shortened.
         */
        val SlowSettle: BreathPattern = BreathPattern(
            code = "slow_settle",
            inhaleSeconds = 5,
            holdInSeconds = 0,
            exhaleSeconds = 7,
            holdOutSeconds = 0,
        )

        /**
         * 4 in, 7 out, no holds. The one used when the aim is to come down
         * rather than to settle, which is what "worry" is for in the plan's
         * list. The out-breath is nearly twice the in-breath on purpose.
         */
        val LongExhale: BreathPattern = BreathPattern(
            code = "long_exhale",
            inhaleSeconds = 4,
            holdInSeconds = 0,
            exhaleSeconds = 7,
            holdOutSeconds = 0,
        )

        /**
         * The only pattern with a hold, and the only one gated.
         *
         * It exists so that the [isBreathHolding] path is real rather than
         * theoretical: without it, `forProfile` would strip holds that nothing
         * in the catalogue ever set, and the rule would be untested code that
         * quietly rotted. A 4-count in, a 2-count hold, a 6-count out, a 2-count
         * rest — the shortest holds that are still a hold.
         */
        val BoxWithLightHold: BreathPattern = BreathPattern(
            code = "box_light_hold",
            inhaleSeconds = 4,
            holdInSeconds = 2,
            exhaleSeconds = 6,
            holdOutSeconds = 2,
            restSeconds = 4,
        )

        fun fromCode(code: String?): BreathPattern? =
            listOf(SlowSettle, LongExhale, BoxWithLightHold).firstOrNull { it.code == code }
    }
}

/** Which part of the breath the user is in. */
enum class BreathPhase(val code: String) {
    Inhale("inhale"),
    HoldIn("hold_in"),
    Exhale("exhale"),
    HoldOut("hold_out");

    /**
     * Whether this phase is a held breath.
     *
     * A screen must not draw or speak a "hold" caption for a phase whose
     * duration is zero, whatever the pattern's code says — [Meditation.cueAt]
     * can return [HoldIn] with a zero total when a hold has been stripped for a
     * heart condition, and this is what turns that into "skip it" rather than
     * "hold for nothing".
     */
    val isHeld: Boolean get() = this == HoldIn || this == HoldOut

    companion object {
        fun fromCode(code: String?): BreathPhase? = entries.firstOrNull { it.code == code }
    }
}

/** What the screen shows and the voice says right now. */
data class BreathCue(
    val phase: BreathPhase,
    val remaining: Duration,
    val total: Duration,
) {
    /** A phase with no time in it is a phase to draw nothing for. */
    val isDrawable: Boolean get() = total > Duration.ZERO

    /** 0 at the start of the phase, 1 at its end. */
    val progress: Float
        get() = if (total <= Duration.ZERO) 1f
        else (1f - remaining.inWholeMilliseconds.toFloat() / total.inWholeMilliseconds.toFloat()).coerceIn(0f, 1f)

    /** The written cue. The third channel, when the orb and the voice both fail. */
    val phrase: Phrase
        get() = Phrase(
            key = "kendra.meditation.phase.${phase.code}",
            args = mapOf("seconds" to remaining.inWholeSeconds.coerceAtLeast(0).toString()),
        )
}

/**
 * One session from plan section 7.1's meditation list.
 *
 * A data value, not a class with behaviour. The list is content the plan lists
 * explicitly and the content pipeline owns; this module holds the codes and the
 * timings it needs to drive the orb and picks nothing else.
 */
data class MeditationSession(
    val code: String,
    val titleKey: String,
    val length: Duration,
    val pattern: BreathPattern,
    val soundscape: Soundscape?,
    /**
     * Plan section 7.1 writes the grief session "written gently for widowed
     * users". Carried so the platform can load the right credited recording; the
     * script itself is not in this module.
     */
    val isForBereavement: Boolean = false,
    /**
     * The mantra session, plan section 7.1, "with the Adhyatm Kendra's mantras".
     * The mantras themselves are the user's own and come from `Spiritual.kt`;
     * nothing is supplied here.
     */
    val usesMantra: Boolean = false,
    /**
     * True for the gentle pain focus. It exists so that the disclaimer in the
     * copy cannot be dropped by a translator or a designer without a type error.
     */
    val isNotMedicalAdvice: Boolean = false,
) {
    val title: Phrase get() = Phrase(titleKey)

    val minutes: Int get() = length.inWholeMinutes.toInt()

    /**
     * Roughly how many breaths the session contains.
     *
     * Shown as "about 40 breaths" and always with "about", because the count
     * depends on how slowly somebody actually breathes and a precise figure
     * would be a small lie.
     */
    val estimatedBreaths: Int
        get() = (length.inWholeSeconds / pattern.roundSeconds.inWholeSeconds.coerceAtLeast(1)).toInt()

    fun withPattern(newPattern: BreathPattern): MeditationSession = copy(pattern = newPattern)
}

/**
 * Plan section 7.1's four soundscapes.
 *
 * The recordings are licensed assets credited in the app's credits screen, not
 * files in this repository. What lives here is the identity of the sound and
 * whether it may keep playing with the screen off, because that is a decision
 * and not an asset.
 */
enum class Soundscape(val code: String, val playsWithScreenOff: Boolean) {
    Rain("rain", playsWithScreenOff = true),
    River("river", playsWithScreenOff = true),
    TempleBells("temple_bells", playsWithScreenOff = false),
    Tanpura("tanpura", playsWithScreenOff = true);

    companion object {
        fun fromCode(code: String?): Soundscape? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The sleep timer, in whole minutes.
 *
 * Only ever a whole number of minutes and never zero, because a timer that can
 * be set to "off" by accident is a session that stops mid-breath while somebody
 * is asleep. Off is [NO_TIMER], not a value.
 */
data class StopTimer(val minutes: Int) {
    init {
        require(minutes > 0) { "a stop timer is either on or absent, never zero" }
    }

    val duration: Duration get() = minutes.minutes

    fun remainingAt(elapsed: Duration): Duration = (duration - elapsed).coerceAtLeast(Duration.ZERO)

    fun hasExpired(elapsed: Duration): Boolean = elapsed >= duration

    companion object {
        /** Off. Not a duration, and not representable as a bad [StopTimer]. */
        val NO_TIMER: StopTimer? = null

        /**
         * The timer's length, snapped down to a whole minute and never past the
         * session: a 12-minute sleep practice with a 20-minute timer would play
         * silence for eight minutes at the end, which is a worse ending than no
         * timer at all.
         */
        fun of(sessionLength: Duration): StopTimer {
            val minutes = sessionLength.inWholeMinutes.toInt().coerceAtLeast(1)
            return StopTimer(minutes)
        }
    }
}

/**
 * How far through the whole session somebody is, for the ring that closes.
 *
 * Separate from the orb on purpose: the orb is one breath and this is the
 * session, and a screen that confuses them ends a twelve-minute sleep practice
 * at the first exhale.
 */
object MeditationProgress {

    fun fractionAt(elapsed: Duration, session: MeditationSession): Float {
        val total = session.length.inWholeMilliseconds
        if (total <= 0L) return 1f
        return (elapsed.inWholeMilliseconds.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Whether the sound should still be playing.
     *
     * Audio ducks rather than stops while the orb is expanding, because a sound
     * that cuts out on every inhale turns a meditation into a metronome with
     * ducks in it.
     */
    fun isSoundStillPlaying(
        session: MeditationSession,
        timer: StopTimer?,
        elapsed: Duration,
    ): Boolean {
        if (timer != null && timer.hasExpired(elapsed)) return false
        return elapsed < session.length
    }
}
