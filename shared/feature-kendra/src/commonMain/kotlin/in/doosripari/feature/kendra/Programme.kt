package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.InstructorStyle
import `in`.doosripari.core.model.KendraProfile
import `in`.doosripari.core.model.KendraProgramme
import `in`.doosripari.core.model.KendraSession
import `in`.doosripari.core.model.ProgrammeKind
import `in`.doosripari.core.model.SafetyFlag
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Sequencing a programme, progressing through it, and choosing what the Today
 * screen offers. Plan section 7.1.
 *
 * Three things live here, and they are three different jobs:
 *
 *  1. **[plan]** turns a gated [ProgrammeSafety] into an ordered run of steps.
 *     Plan section 7.1 fixes the shape — "settle and breathe → warm-up → main →
 *     cool-down → breath → rest" — and the shape is not decoration: a class that
 *     opens with a forward bend has not warmed anybody up, and a class that ends
 *     on a twist is what people remember as uncomfortable.
 *  2. **[PracticeProgress]** counts what has been done, in the plan's own kind
 *     and gentle. "You practised 4 days this week" is the entire report; there is
 *     no streak to lose and nothing to compare with anybody.
 *  3. **[todaysPractice]** picks the one card on the Today screen.
 *
 * ## Chair yoga is offered to everyone
 *
 * Including somebody at risk of falling, including somebody who has fallen
 * twice, including somebody whose heart condition rules out half the catalogue.
 * Sitting down is the safest thing in the building and chair yoga is the one
 * programme that asks for nothing but a chair, so [isOfferedToEveryone] is the
 * single place that says so and the Today screen asks it before it looks at
 * anything else. Withholding chair yoga from a fall-risk practitioner would be
 * the app's most confidently wrong decision: it would remove the only class they
 * can safely attend, in order to protect them from a class they were never going
 * to be offered.
 */
object Programme {

    /**
     * Plan section 7.1: "every movement at 1.5× Mohalla's time, with longer
     * holds and a slow exit."
     *
     * The multiplier is applied to holds and to the seconds between steps, not
     * to the number of repetitions. An older body needs longer in a position and
     * more time to come out of one; it does not need to be asked to do twice as
     * many knee bends.
     */
    const val TIME_SCALE: Double = 1.5

    /** The time a step needs before the next one, so nothing snaps. */
    val TRANSITION_SECONDS: Int = 4

    /**
     * Whether this programme is safe enough for anybody, with no conditions.
     *
     * Chair yoga is the case the plan cares about and the one people get wrong,
     * so it is a named predicate rather than something inferred from an empty
     * `targets` set. It is also the answer to a fall-risk practitioner: yes, you
     * can do this.
     */
    fun isOfferedToEveryone(programme: KendraProgramme): Boolean =
        programme.isChairBased && programme.totalMinutes in 5..20

    /**
     * "Is there a class this person can actually attend today?"
     *
     * ## The rule, stated once so a screen cannot get it wrong
     *
     * The answer is true for a practitioner whose profile carries
     * [SafetyFlag.FallRisk], [SafetyFlag.HeartCondition] or
     * [SafetyFlag.RecentSurgery]. It stays true. [profile] is a parameter and not
     * a condition precisely so that the flags cannot quietly start mattering
     * here: the only thing that can make this false is the catalogue having no
     * chair programme in it at all, which is a content bug and shows up in
     * [ProgrammePlan.isEmpty] rather than in a shorter list the user cannot
     * explain.
     *
     * The mistake this prevents is the plausible one. Fall risk is severity 3
     * in core-model — the most restrictive flag there is — and a screen written
     * by somebody being careful will apply it to the programme as well as to
     * the pose. That would leave a fall-risk practitioner with no standing
     * balance, no floor work and no chair class, i.e. no class, on a screen
     * whose entire job is to get them onto a mat.
     */
    fun isChairYogaAvailableTo(
        profile: KendraProfile,
        decisions: List<ProgrammeSafety>,
    ): Boolean = decisions.any { it.isOffered && isOfferedToEveryone(it.programme) }

    /**
     * Turn a gated programme into an ordered class.
     *
     * Withheld poses are simply not steps. They are not marked or skipped in
     * the flow, because a runner that pauses to say "we are skipping this one"
     * every ninety seconds turns a class into a list of apologies.
     */
    fun plan(safety: ProgrammeSafety): ProgrammePlan {
        val steps = safety.allowed.mapIndexed { index, pose ->
            ProgrammeStep(
                pose = pose,
                stage = stageFor(index, safety.allowed.size, safety.programme.kind),
                seconds = secondsFor(pose),
            )
        }
        return ProgrammePlan(
            programme = safety.programme,
            steps = steps,
            totalSeconds = steps.sumOf { it.seconds + TRANSITION_SECONDS },
            withheld = safety.withheld,
        )
    }

    /**
     * How many seconds to spend in one movement.
     *
     * A pose with a hold gets the hold scaled and a generous exit. A pose with
     * repetitions gets each rep its own seconds plus the same exit, because
     * "ten times" with no time in between is a different instruction from "ten
     * times, slowly", and the slow version is the one the plan asks for.
     */
    fun secondsFor(pose: OfferedPose): Int {
        val base = when {
            pose.holdSeconds != null -> pose.holdSeconds
            pose.reps != null -> pose.reps * SECONDS_PER_REP
            else -> DEFAULT_SECONDS
        }
        val scaled = (base * TIME_SCALE).toInt()
        return if (scaled < MIN_SECONDS) MIN_SECONDS else scaled + EXIT_SECONDS
    }

    /**
     * Where a pose sits in the class, from its position and the programme's kind.
     *
     * The plan's own six, in order. A pranayama programme is mostly breath and a
     * meditation programme is mostly rest, so both skip the bands that would
     * make no sense in them rather than stretching to fill the six.
     */
    fun stageFor(index: Int, count: Int, kind: ProgrammeKind): ProgrammeStage {
        if (count == 0) return ProgrammeStage.Rest
        val at = index.toDouble() / count
        return when (kind) {
            ProgrammeKind.Pranayama -> when {
                at < 0.2 -> ProgrammeStage.Settle
                at < 0.85 -> ProgrammeStage.Breath
                else -> ProgrammeStage.Rest
            }
            ProgrammeKind.Meditation -> when {
                at < 0.2 -> ProgrammeStage.Settle
                else -> ProgrammeStage.Rest
            }
            else -> when {
                at < 0.1 -> ProgrammeStage.Settle
                at < 0.3 -> ProgrammeStage.WarmUp
                at < 0.7 -> ProgrammeStage.Main
                at < 0.85 -> ProgrammeStage.CoolDown
                at < 0.95 -> ProgrammeStage.Breath
                else -> ProgrammeStage.Rest
            }
        }
    }

    /**
     * The card on the Today screen, which plan section 6.2 asks for as "a
     * 10-minute yoga or breathing session".
     *
     * The order of the rules is the argument:
     *
     *  1. **Chair yoga, for anybody who needs it.** Not as a fallback — as the
     *     first answer. A fall risk and a heart condition both make every other
     *     programme a worse choice, and both are answered by sitting down.
     *  2. **Whatever programme they are part-way through.** A person three weeks
     *     into a course who is offered something else every morning will not
     *     finish the course.
     *  3. **The shortest thing, if they have practised nothing this week.** Ten
     *     minutes of something easy beats a plan. The plan's own 10-minute
     *     length is the target.
     *  4. **Otherwise, the shortest programme they are allowed.** Shortest
     *     because it is the Today screen and not a course enrolment, and
     *     because the class the user cannot fit in is the class they skip.
     *
     * Returns null when the catalogue offers this practitioner nothing, which is
     * a content fault and is logged rather than papered over.
     */
    fun todaysPractice(
        decisions: List<ProgrammeSafety>,
        progress: PracticeProgress,
    ): PracticeSuggestion? {
        val offerable = decisions.filter { it.isOffered }
        if (offerable.isEmpty()) return null

        // 1. Chair yoga first, and for everybody who can do it. `profile` is
        //    already reflected in `decisions` — SafetyGate decided each of these
        //    against this profile — so this does not re-derive the flags and
        //    risk disagreeing with the gate that already ran.
        offerable.firstOrNull { isOfferedToEveryone(it.programme) }?.let { chair ->
            return suggestion(chair, PracticeReason.ChairYogaIsAlwaysAvailable)
        }
        // 2. Whatever they are part-way through.
        progress.currentProgrammeId()?.let { inProgress ->
            offerable.firstOrNull { it.programme.id == inProgress }?.let {
                return suggestion(it, PracticeReason.ContinuingAProgramme)
            }
        }

        // 3. Nothing done this week, so the smallest thing on offer.
        if (progress.daysThisWeek == 0) {
            offerable.minByOrNull { it.programme.totalMinutes }?.let {
                return suggestion(it, PracticeReason.SomethingShortToStart)
            }
        }

        // 4. Otherwise the shortest thing they are allowed.
        return offerable
            .minByOrNull { it.programme.totalMinutes }
            ?.let { suggestion(it, PracticeReason.ShortestThatIsSafe) }
    }

    private fun suggestion(safety: ProgrammeSafety, reason: PracticeReason): PracticeSuggestion =
        PracticeSuggestion(
            programmeId = safety.programme.id,
            title = Phrase(
                key = KEYS.PROGRAMME_TITLE,
                args = mapOf("title" to safety.programme.title),
            ),
            minutes = safety.programme.totalMinutes,
            kind = safety.programme.kind,
            reason = reason,
            safety = safety,
        )

    /** Catalogue keys. Owned by each platform's string catalogue. */
    object KEYS {
        const val PROGRAMME_TITLE: String = "kendra.programme.title"

        /** "You practised {n} days this week." Plan section 7.1's whole report. */
        const val PRACTISED_DAYS: String = "kendra.progress.practised_days"

        /** "You practised {n} minutes this week." */
        const val PRACTISED_MINUTES: String = "kendra.progress.practised_minutes"

        /** Dadi and Dada. Plan section 7.1 asks for both, by name. */
        const val INSTRUCTOR_DADI: String = "kendra.instructor.dadi"
        const val INSTRUCTOR_DADA: String = "kendra.instructor.dada"
    }

    /** Roughly how long one slow repetition takes at 1.5× before the exit. */
    private const val SECONDS_PER_REP: Int = 4

    /** A step is never shorter than this; a two-second hold is not a hold. */
    const val MIN_SECONDS: Int = 10

    /** A slow exit, which the plan asks for on every movement. */
    const val EXIT_SECONDS: Int = 3

    /** A pose with neither a hold nor reps. */
    const val DEFAULT_SECONDS: Int = 20
}

/** The six parts every programme is built from. Plan section 7.1. */
enum class ProgrammeStage(val code: String) {
    Settle("settle"),
    WarmUp("warm_up"),
    Main("main"),
    CoolDown("cool_down"),
    Breath("breath"),
    Rest("rest");

    /** Whether the instructor should be visibly breathing rather than moving. */
    val isStill: Boolean get() = this == Rest || this == Breath || this == Settle

    companion object {
        fun fromCode(code: String?): ProgrammeStage? = entries.firstOrNull { it.code == code }
    }
}

/** One movement with its place in the class and its length. */
data class ProgrammeStep(
    val pose: OfferedPose,
    val stage: ProgrammeStage,
    val seconds: Int,
) {
    val isModified: Boolean get() = pose.isModified
}

/**
 * A runnable class.
 *
 * [withheld] travels with the plan so the screen can put a single "we have left
 * two out because of your knee" line above the class instead of silently
 * running something shorter than the title promises.
 */
data class ProgrammePlan(
    val programme: KendraProgramme,
    val steps: List<ProgrammeStep>,
    val totalSeconds: Int,
    val withheld: List<WithheldPose> = emptyList(),
) {
    val isEmpty: Boolean get() = steps.isEmpty()

    /** The real length, which is not the catalogue's number once poses are out. */
    val actualMinutes: Int get() = (totalSeconds + 59) / 60

    /** Whether the class is materially shorter than advertised, so the card says so. */
    val isShorterThanAdvertised: Boolean
        get() = actualMinutes + 1 < programme.totalMinutes
}

/** Why the Today screen picked what it picked. Surfaced so the card can say. */
enum class PracticeReason(val code: String) {
    /**
     * Chair yoga. Offered to everybody including a fall risk, because sitting
     * down is the safest thing in the building.
     */
    ChairYogaIsAlwaysAvailable("chair_yoga_is_always_available"),

    ContinuingAProgramme("continuing_a_programme"),

    /** Nothing done this week, so: the smallest thing on offer. */
    SomethingShortToStart("something_short_to_start"),

    /** Not a course, not a first day — just the shortest safe class. */
    ShortestThatIsSafe("shortest_that_is_safe");

    companion object {
        fun fromCode(code: String?): PracticeReason =
            entries.firstOrNull { it.code == code } ?: ShortestThatIsSafe
    }
}

/** The one card on the Today screen, plan section 6.2. */
data class PracticeSuggestion(
    val programmeId: Int,
    val title: Phrase,
    val minutes: Int,
    val kind: ProgrammeKind,
    val reason: PracticeReason,
    val safety: ProgrammeSafety,
) {
    /** Whether this card opens a class directly or a programme description first. */
    val startsImmediately: Boolean get() = kind != ProgrammeKind.SafetyCheck
}

/**
 * What has actually been practised. Plan section 7.1, "Tracking progress".
 *
 * ## No streaks, and nothing to lose
 *
 * The plan's only sentence on this is "minutes per week and days practised this
 * week, shown kindly: 'You practised 4 days this week'". That is the entire
 * feature. There is deliberately no streak counter, no "you are falling behind",
 * and no comparison with anybody else, because a pensioner who misses two days
 * of a ten-day plan and is then told they have lost their streak will not come
 * back, and a person who does not practise is worse off than a person with a
 * broken streak.
 */
data class PracticeProgress(
    val sessions: List<KendraSession>,
    /** The current local date, so "this week" means the user's week and not UTC's. */
    val today: LocalDate,
    val timeZone: TimeZone,
) {
    /**
     * This week, Monday to today. Plan section 7.1 counts a week, and a week
     * that starts on Sunday makes the number jump around for half the users.
     */
    private fun weekStart(): LocalDate {
        val offset = (today.dayOfWeek.value - 1).coerceIn(0, 6)
        return today.minus(offset, DateTimeUnit.DAY)
    }

    private fun inThisWeek(session: KendraSession): Boolean {
        val date = session.startedAt.toLocalDateTime(timeZone).date
        return date >= weekStart() && date <= today
    }

    val sessionsThisWeek: List<KendraSession>
        get() = sessions.filter { it.completed && inThisWeek(it) }

    /** Distinct days practised, which is the number the plan actually reports. */
    val daysThisWeek: Int
        get() = sessionsThisWeek.map { it.startedAt.toLocalDateTime(timeZone).date }.distinct().size

    val minutesThisWeek: Int get() = sessionsThisWeek.sumOf { it.minutes }

    /**
     * Whether today has been practised. Drives the small tick on the card, and
     * nothing else — a user who has practised today is not asked to do more.
     */
    val hasPractisedToday: Boolean
        get() = sessions.any {
            it.completed && it.startedAt.toLocalDateTime(timeZone).date == today
        }

    /**
     * The programme the user is part-way through, or null.
     *
     * The most recent completed session's programme, which is how a course is
     * "in progress" without a separate enrolment table the plan does not
     * describe.
     */
    fun currentProgrammeId(): Int? =
        sessions.filter { it.completed }
            .maxByOrNull { it.startedAt }
            ?.programmeId

    /**
     * How far through a programme the user is, in whole classes.
     *
     * There is no "level up" and nothing is unlocked. Plan section 7.1 lists a
     * "Steady" level for three of the programmes, and the progression between
     * levels is a matter for the instructor and the physiotherapist who review
     * the catalogue — not for arithmetic over a session log.
     */
    fun classesDone(programmeId: Int): Int =
        sessions.count { it.completed && it.programmeId == programmeId }

    /** The line the Today card shows, as a key and a number. */
    val practisedDaysPhrase: Phrase
        get() = Phrase(Programme.KEYS.PRACMISED_DAYS, mapOf("n" to daysThisWeek.toString()))

    val practisedMinutesPhrase: Phrase
        get() = Phrase(Programme.KEYS.PRACMISED_MINUTES, mapOf("n" to minutesThisWeek.toString()))

    companion object {
        /** An empty history, for a practitioner who has never opened the app. */
        fun empty(today: LocalDate, timeZone: TimeZone) =
            PracticeProgress(emptyList(), today, timeZone)
    }
}

/**
 * Which instructor is drawn and spoken, and in what voice.
 *
 * Plan section 7.1 asks for two — Dadi and Dada — "so users see someone like
 * themselves", and the difference is not cosmetic: the same movement is taught
 * unhurried and seated without English, or more formally with the Sanskrit
 * names. Choosing the wrong one is not a small thing, so the choice is a
 * remembered preference and not a default that flips.
 */
data class Instructor(
    val style: InstructorStyle,
    /** Whether the instructor moves as a mirror. Plan section 7.1's mirror mode. */
    val mirrored: Boolean,
) {
    val namePhrase: Phrase
        get() = Phrase(
            key = when (style) {
                InstructorStyle.Dadi -> Programme.KEYS.INSTRUCTOR_DADI
                InstructorStyle.Dada -> Programme.KEYS.INSTRUCTOR_DADA
            },
        )

    /** Plan section 7.1: speech rate 0.85, bilingual, captions word for word. */
    val speechRate: Float get() = SPEECH_RATE

    companion object {
        /**
         * Slower than a wellness app. 0.85 against the platform's 1.0, and the
         * point is not that the words are long — it is that the pause between
         * them is long enough for somebody whose ears are 70 years old to
         * register the end of a sentence before the next one starts. Captions
         * are on word for word regardless, because an instruction that can only
         * be heard is an instruction some of this audience will never get.
         */
        const val SPEECH_RATE: Float = 0.85f

        fun forProfile(profile: KendraProfile): Instructor = Instructor(
            style = profile.instructorStyle ?: InstructorStyle.Dadi,
            mirrored = profile.mirror,
        )
    }
}
