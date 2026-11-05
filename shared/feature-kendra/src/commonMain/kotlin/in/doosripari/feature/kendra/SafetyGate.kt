package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.KendraProfile
import `in`.doosripari.core.model.KendraProgramme
import `in`.doosripari.core.model.KendraSession
import `in`.doosripari.core.model.Pose
import `in`.doosripari.core.model.ProgrammeKind
import `in`.doosripari.core.model.SafetyFlag

/**
 * Decides which programmes and which movements a practitioner may be offered.
 * Plan section 7.1, "Safety first".
 *
 * This is the most important file in `feature-kendra`, and the reason is not the
 * code — it is the audience. The people using this are 60 and over, on a mat, and
 * the realistic outcome of getting it wrong is a fall. Plan section 21 has it as
 * its own risk row ("an injury during yoga") and plan section 20 requires every
 * programme to be reviewed by a certified yoga therapist *and* a physiotherapist
 * before release. The gate is what makes that review enforceable rather than
 * advisory.
 *
 * ## The two rules, and the difference between them is the whole design
 *
 *  - **A pose with a modification is offered MODIFIED, not withheld.** The plan's
 *    argument is that a 68-year-old should still be able to do a forward bend
 *    in some form. Withholding it would be safe and wrong: it teaches a woman
 *    with a bad knee that her body has stopped being capable of things, and it
 *    is the reason somebody stops coming to class.
 *  - **A pose with no modification and a non-modifiable contraindication is
 *    withheld entirely.** [SafetyFlag.canBeModified] is what separates the two.
 *    A knee can be worked around; a fall that has already happened twice cannot,
 *    and offering unassisted standing balance to somebody at risk of falling is
 *    not encouragement.
 *
 * ## Why the reason is typed and travels with the result
 *
 * A screen must be able to say *"we have left this one out because of your
 * knee"*, not silently show a shorter list. A silent list is indistinguishable
 * from an app that has lost data, and a pensioner who cannot tell which it is
 * stops trusting the whole screen. So [WithheldPose] carries an
 * [ExclusionReason] with a [ExclusionCause] and a catalogue key, and
 * [ProgrammeSafety] never returns a programme whose withheld poses have nowhere
 * to put that.
 *
 * ## Two places the data is trusted and one place it is not
 *
 * `Pose.contraindications` and `KendraProgramme.targets` come from the reviewed
 * catalogue and are believed. [structuralBlock] is the one place the gate looks
 * at the *shape* of a pose rather than its tags, because a fall risk against a
 * standing pose is not something to discover at the call site, and a catalogue
 * with one row missing a tag is a catalogue that will eventually be wrong.
 */
object SafetyGate {

    /**
     * [KendraProfile.flags] with [SafetyFlag.None] removed.
     *
     * `None` is the "no flag" member of an enum that also carries real ones, and
     * it is *not* harmless to leave in a set: `SafetyFlag.None.canBeModified` is
     * false, so a profile that came back from the server with `{None, FallRisk}`
     * in it makes `KendraProfile.needsDoctorNote` true and would put a
     * "check with your doctor" banner over somebody who has already been checked
     * with. The enum is a wire type and this is where the wire type is
     * reconciled with the domain.
     */
    fun effectiveFlags(profile: KendraProfile): Set<SafetyFlag> =
        profile.flags.filterTo(LinkedHashSet()) { it != SafetyFlag.None }

    /**
     * The gate's decision about one programme and one practitioner.
     *
     * Pure, and the same function runs on the phone when the Kendra screen opens
     * and in the `kendra_session` write path, so a session that was safe when it
     * was offered cannot be recorded as something else later.
     */
    fun evaluate(programme: KendraProgramme, profile: KendraProfile): ProgrammeSafety {
        val flags = effectiveFlags(profile)
        val mostRestrictive = mostRestrictive(flags)

        val allowed = ArrayList<OfferedPose>(programme.poses.size)
        val withheld = ArrayList<WithheldPose>()

        for (pose in programme.poses) {
            when (val decision = decidePose(pose, flags)) {
                is PoseDecision.Offer -> allowed += decision.pose
                is PoseDecision.Withhold -> withheld += decision.reason
            }
        }

        // A programme is withheld whole only when it is aimed at a flag the
        // practitioner has and that flag cannot be worked around. `targets` is
        // read the way [KendraProgramme.isSafeFor] reads it — as the flags that
        // rule a programme out — because that is the only reading under which
        // chair yoga is offered to a fall-risk practitioner, which is the
        // behaviour core-model's own KDoc promises.
        val programmeReason = if (programme.isSafeFor(flags)) {
            null
        } else {
            ProgrammeWithheld(
                cause = ProgrammeCause.RuledOutByAFlagItNeeds,
                flag = mostRestrictive,
                phrase = Phrase(
                    key = KEYS.PROGRAMME_WITHHELD,
                    args = mapOf("flag" to flagKey(mostRestrictive)),
                ),
            )
        }

        val isOffered = programmeReason == null && allowed.isNotEmpty()

        return ProgrammeSafety(
            programme = programme,
            isOffered = isOffered,
            allowed = allowed,
            withheld = withheld,
            programmeReason = if (programmeReason == null && allowed.isEmpty()) {
                // Nothing survived the gate. That is a catalogue fault rather
                // than a practitioner fault, and it is reported as such instead
                // of being shown as an empty class.
                ProgrammeWithheld(
                    cause = ProgrammeCause.NothingLeftToOffer,
                    flag = mostRestrictive,
                    phrase = Phrase(KEYS.PROGRAMME_EMPTY),
                )
            } else {
                programmeReason
            },
            mostRestrictive = mostRestrictive,
            needsDoctorNote = needsDoctorNote(flags),
        )
    }

    /** Every programme, decided, in the order given. Nothing is filtered out here. */
    fun evaluateAll(programmes: List<KendraProgramme>, profile: KendraProfile): List<ProgrammeSafety> =
        programmes.map { evaluate(it, profile) }

    /**
     * Only the programmes this practitioner may be offered.
     *
     * The list is never silently shorter, which is why [evaluateAll] exists and
     * why the screens are expected to render [WithheldPose.reason] rather than
     * the difference between two lengths.
     */
    fun offerable(programmes: List<KendraProgramme>, profile: KendraProfile): List<ProgrammeSafety> =
        evaluateAll(programmes, profile).filter { it.isOffered }

    /**
     * Plan section 7.1's first-session health check.
     *
     * The check happens **once**, before the first session, and its questions
     * are what set the flags. A practitioner who has already practised has
     * already answered them, so asking again on every visit is the app saying it
     * does not believe the user.
     */
    fun needsFirstSessionCheck(profile: KendraProfile, sessions: List<KendraSession>): Boolean {
        // A profile carrying a non-modifiable flag is one that came from a
        // health check or from a doctor, so the check has happened by definition.
        if (needsDoctorNote(effectiveFlags(profile))) return false
        return sessions.none { it.userId == profile.userId }
    }

    // ------------------------------------------------------------------- internals --

    /**
     * The flag that gates the most, from a set that has already had `None` taken
     * out of it.
     *
     * [KendraProfile.mostRestrictive] does the same job over the raw set. This
     * copy exists because that one cannot be used here — see [effectiveFlags] —
     * and the ranking is the same as core-model's private `severity`.
     * `SafetyGateSeverityTest` asserts that the two agree for every flag, so a
     * one-sided edit fails the build rather than quietly reordering who gets
     * offered what.
     */
    fun mostRestrictive(flags: Set<SafetyFlag>): SafetyFlag =
        flags.maxByOrNull { severityOf(it) } ?: SafetyFlag.None

    /**
     * Where a flag sits between "adaptable" and "do not attempt". Mirrors
     * core-model's private `SafetyFlag.severity`; see [mostRestrictive].
     */
    fun severityOf(flag: SafetyFlag): Int = when (flag) {
        SafetyFlag.None -> 0
        SafetyFlag.KneeOrBackIssue, SafetyFlag.LimitedMobility, SafetyFlag.Pregnancy -> 1
        SafetyFlag.RecentSurgery, SafetyFlag.HeartCondition -> 2
        SafetyFlag.FallRisk -> 3
    }

    /**
     * Whether this profile must have a doctor's note.
     *
     * `needsDoctorNote` in core-model is `flags.any { !it.canBeModified }`, which
     * is true for [SafetyFlag.None] and would put a doctor's-note requirement
     * over every user the server ever sent. Filtering `None` out first is the
     * whole fix, and it is the reason the gate never reads that property
     * directly.
     */
    fun needsDoctorNote(flags: Set<SafetyFlag>): Boolean =
        flags.any { it != SafetyFlag.None && !it.canBeModified }

    private fun decidePose(pose: Pose, flags: Set<SafetyFlag>): PoseDecision {
        // Rule 1. The reviewed catalogue says this movement must not be
        // attempted with this flag. Whether that is a verdict about the user or
        // a gap in the catalogue depends on *why*, so the two are told apart
        // rather than lumped together — a therapist writing a chair version of a
        // pose is a fix, and the screen should say so instead of implying the
        // user cannot do it.
        if (pose.isContraindicated(flags)) {
            val blocking = pose.contraindications.intersect(flags)
            val flag = mostRestrictive(blocking)
            val cause = if (blocking.any { !it.canBeModified }) {
                ExclusionCause.UnmodifiableContraindication
            } else {
                ExclusionCause.NoModificationAvailable
            }
            return PoseDecision.Withhold(
                WithheldPose(pose = pose, reason = ExclusionReason(flag, cause, phraseFor(flag, cause))),
            )
        }

        // Rule 2. The catalogue did not say, and the shape of the pose says it
        // anyway. This is the only place the gate second-guesses the data, and
        // it is deliberately narrow.
        val structural = structuralBlock(pose, flags)
        if (structural != null) {
            return PoseDecision.Withhold(structural)
        }

        // Rule 3. Adaptable. Offered, in its gentler form, with the reason
        // attached so the screen can say why it looks different today.
        val blocking = pose.contraindications.intersect(flags)
        if (blocking.isEmpty()) return PoseDecision.Offer(OfferedPose.of(pose, modified = false, because = null))
        val because = mostRestrictive(blocking)
        return PoseDecision.Offer(OfferedPose.of(pose, modified = true, because = because))
    }

    /**
     * The backstop, for when the catalogue is wrong.
     *
     * Two cases and no more:
     *
     *  - **A standing pose against a fall risk.** core-model's own KDoc says a
     *    pose that requires standing is contraindicated by [SafetyFlag.FallRisk]
     *    "everywhere in the data" so that there is one place the rule can be got
     *    wrong. This is that place being checked anyway: a catalogue with one
     *    row missing a tag is a catalogue that will eventually be wrong, and the
     *    cost of being wrong here is a person on the floor.
     *  - **Floor work against low mobility or a fall risk.** Getting down to the
     *    floor and back up again is the single most common way a fit 70-year-old
     *    loses a morning. A chair is not a compromise here, it is the answer, so
     *    a pose with a modification is offered modified and one without is
     *    withheld.
     *
     * A modification rescues both cases, which is the same rule as everywhere
     * else: the plan's argument is that somebody should still be able to do a
     * forward bend in some form.
     */
    private fun structuralBlock(pose: Pose, flags: Set<SafetyFlag>): WithheldPose? {
        if (pose.modification != null) return null
        val cause = when {
            pose.requiresStanding && SafetyFlag.FallRisk in flags -> ExclusionCause.StandingWithoutSupport
            pose.requiresFloor &&
                (SafetyFlag.LimitedMobility in flags || SafetyFlag.FallRisk in flags) -> ExclusionCause.FloorWorkWithoutSupport
            else -> return null
        }
        val flag = when (cause) {
            ExclusionCause.StandingWithoutSupport -> SafetyFlag.FallRisk
            else -> mostRestrictive(flags)
        }
        return WithheldPose(pose = pose, reason = ExclusionReason(flag, cause, phraseFor(flag, cause)))
    }

    /**
     * The sentence a screen shows. Key plus the flag's code, and nothing else.
     *
     * The flag code rather than a translated adjective, because a translator
     * needs a noun they can put in a sentence they control ("because of your
     * knee") and not a fragment this module has already committed to.
     */
    private fun phraseFor(flag: SafetyFlag, cause: ExclusionCause): Phrase = Phrase(
        key = KEYS.POSE_WITHHELD,
        args = mapOf(
            "flag" to flagKey(flag),
            "cause" to cause.code,
        ),
    )

    private fun flagKey(flag: SafetyFlag): String = "kendra.safety.flag.${flag.code}"

    /** Catalogue keys. Owned by each platform's string catalogue. */
    object KEYS {
        /**
         * "We have left this one out because of your {flag}." The single most
         * important sentence in the module.
         */
        const val POSE_WITHHELD: String = "kendra.safety.pose_withheld"

        /** A whole programme is not right for this profile. */
        const val PROGRAMME_WITHHELD: String = "kendra.safety.programme_withheld"

        /** Nothing in this programme survived the gate. A catalogue fault. */
        const val PROGRAMME_EMPTY: String = "kendra.safety.programme_empty"

        /** "Shown in a gentler form because of your {flag}." */
        const val POSE_MODIFIED: String = "kendra.safety.pose_modified"

        /**
         * Plan section 7.1, shown on the first screen and not dismissible into
         * oblivion: "Check with your doctor before starting a new exercise
         * programme."
         */
        const val CHECK_WITH_DOCTOR: String = "kendra.safety.check_with_doctor"

        /**
         * Plan section 7.1, spoken at the start of every session: "Stop if you
         * feel pain or dizziness."
         */
        const val STOP_IF_PAIN: String = "kendra.safety.stop_if_pain"
    }
}

// ------------------------------------------------------------------ decisions --

private sealed interface PoseDecision {
    data class Offer(val pose: OfferedPose) : PoseDecision
    data class Withhold(val reason: WithheldPose) : PoseDecision
}

/**
 * A movement, as it will actually be offered.
 *
 * [text] is the catalogue's own string — `pose.instruction` normally,
 * `pose.modification` when [isModified]. The catalogue carries it because plan
 * section 7.1's pose library is content and content is translated and reviewed
 * like content; this module chooses between two strings and authors neither.
 */
data class OfferedPose(
    val pose: Pose,
    val text: String,
    val isModified: Boolean,
    /** The flag that made the modification necessary, for the caption. Null when as authored. */
    val because: SafetyFlag?,
    val isSeated: Boolean,
    /** Standing with a chair or wall behind it. */
    val isSupported: Boolean,
    val holdSeconds: Int?,
    val reps: Int?,
    val breathingCue: String?,
) {
    val id: Int get() = pose.id

    /**
     * The caption, when this one looks different from what the user has seen
     * before. A modification with no caption is a modification the user will
     * assume is a mistake, and will stop doing.
     */
    val modificationPhrase: Phrase?
        get() = if (isModified && because != null) {
            Phrase(KEYS.POSE_MODIFIED, mapOf("flag" to "kendra.safety.flag.${because.code}"))
        } else {
            null
        }

    companion object {
        fun of(pose: Pose, modified: Boolean, because: SafetyFlag?): OfferedPose = OfferedPose(
            pose = pose,
            text = if (modified) pose.modification ?: pose.instruction else pose.instruction,
            isModified = modified,
            because = because,
            isSeated = !pose.requiresStanding && !pose.requiresFloor,
            isSupported = !pose.requiresStanding,
            holdSeconds = pose.holdSeconds,
            reps = pose.reps,
            breathingCue = pose.breathingCue,
        )
    }
}

/**
 * A movement that is not being offered, and why — in a form the screen can put
 * into a sentence.
 *
 * Withholding a pose silently is how a safety feature becomes a data-loss bug
 * the user cannot report, so this is never discarded and never logged only.
 */
data class WithheldPose(
    val pose: Pose,
    val reason: ExclusionReason,
)

/**
 * Why a movement was left out.
 *
 * [flag] is the user's own, so the sentence can name it. [cause] is for the
 * screen and for the log: two poses left out for the same flag want different
 * wording, because one had a gentler form and one did not.
 */
data class ExclusionReason(
    val flag: SafetyFlag,
    val cause: ExclusionCause,
    val phrase: Phrase,
) {
    /**
     * Whether a different programme could still cover the same ground.
     *
     * True for a withheld *pose* whose programme is otherwise running, and the
     * honest thing for a screen to say next to it: "we have replaced this with
     * the chair version" rather than a bare subtraction.
     */
    val hasAlternative: Boolean get() = cause != ExclusionCause.NoModificationAvailable
}

/**
 * Why a movement was left out.
 *
 * An enum rather than a sealed hierarchy because nothing varies by cause except
 * the sentence the screen builds and the fact worth logging, and a [code] per
 * value is what the string catalogue and the `kendra_sessions` log both need.
 */
enum class ExclusionCause(val code: String) {
    /**
     * The catalogue says no and the flag cannot be worked around. A heart
     * condition against a non-modifiable movement is the plan's own example, and
     * this is the one case where withholding is not up for discussion.
     */
    UnmodifiableContraindication("unmodifiable_contraindication"),

    /**
     * Standing with nothing to hold, for somebody at risk of falling. Raised by
     * the backstop rather than by the catalogue, so it also means "a row in the
     * catalogue is missing a tag" and is worth logging as a content bug.
     */
    StandingWithoutSupport("standing_without_support"),

    /** Getting to the floor and back up again, for somebody who should not. */
    FloorWorkWithoutSupport("floor_work_without_support"),

    /**
     * Ruled out by a flag that *could* have been adapted, but no modification
     * has been written for this pose. A gap in the catalogue rather than a
     * verdict about the user, and a therapist writing the chair version of this
     * pose is the fix.
     */
    NoModificationAvailable("no_modification_available");

    /** Whether this looks like a mistake in the catalogue rather than a real exclusion. */
    val isProbablyACatalogueGap: Boolean get() = this == NoModificationAvailable

    companion object {
        fun fromCode(code: String?): ExclusionCause? = entries.firstOrNull { it.code == code }
    }
}

/** Why a whole programme is not being offered. */
enum class ProgrammeCause(val code: String) {
    /** `targets` names a non-modifiable flag the practitioner has. */
    RuledOutByAFlagItNeeds("ruled_out_by_a_flag_it_needs"),

    /** Every pose was withheld. A catalogue fault, not a verdict on the user. */
    NothingLeftToOffer("nothing_left_to_offer"),

    /** There is nothing in the catalogue to run. */
    EmptyCatalogue("empty_catalogue");

    companion object {
        fun fromCode(code: String?): ProgrammeCause =
            entries.firstOrNull { it.code == code } ?: NothingLeftToOffer
    }
}

/** A programme and one practitioner's standing in it. */
data class ProgrammeWithheld(
    val cause: ProgrammeCause,
    val flag: SafetyFlag?,
    val phrase: Phrase,
)

/**
 * The result of gating one programme for one practitioner.
 *
 * [withheld] is kept even when [isOffered] is true, because the screen has to
 * be able to explain the shorter list. That is the difference between a safety
 * feature and a mystery.
 */
data class ProgrammeSafety(
    val programme: KendraProgramme,
    val isOffered: Boolean,
    val allowed: List<OfferedPose>,
    val withheld: List<WithheldPose>,
    /** Non-null whenever [isOffered] is false. */
    val programmeReason: ProgrammeWithheld?,
    val mostRestrictive: SafetyFlag,
    val needsDoctorNote: Boolean,
) {
    val kind: ProgrammeKind get() = programme.kind

    val isChairBased: Boolean get() = programme.isChairBased

    /**
     * Whether the opening line should be said.
     *
     * Plan section 7.1 speaks "stop if you feel pain or dizziness" at the start
     * of *every* session, not only the first, because the audience includes
     * people who will not remember the first one. It is also the cheapest safety
     * mechanism in the app and it costs two seconds.
     */
    val saysStopIfYouFeelPain: Boolean get() = true

    /** The first-screen doctor's advice, plan section 7.1 step 3. */
    val checkWithDoctorPhrase: Phrase get() = Phrase(KEYS.CHECK_WITH_DOCTOR)

    /** Whether any offered pose is a modified form, so the screen can say so up front. */
    val hasModifications: Boolean get() = allowed.any { it.isModified }

    /** Poses grouped by the flag that changed them, for a single "we changed
     *  these because of your knee" line instead of three. */
    fun modificationsByFlag(): Map<SafetyFlag, List<OfferedPose>> =
        allowed.filter { it.isModified && it.because != null }
            .groupBy { it.because!! }
}
