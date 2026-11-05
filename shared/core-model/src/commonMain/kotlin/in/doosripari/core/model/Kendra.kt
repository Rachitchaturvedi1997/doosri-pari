package `in`.doosripari.core.model

import kotlinx.datetime.Instant

/**
 * Yog Kendra and Adhyatm Kendra. Plan section 7.
 *
 * Two quite different things under one name, and the model keeps them apart:
 *
 *  - **Yog Kendra (§7.1)** is exercise with a safety problem. The audience is
 *    60+, on a mat, and a fall is a genuine risk. So [SafetyFlag] is not
 *    decoration — [KendraProfile.flags] gates which movements are even
 *    offered, and [Pose.isContraindicated] is checked before a pose is shown,
 *    not after somebody has attempted it.
 *  - **Adhyatm Kendra (§7.2)** is practice with no safety problem at all. It
 *    has no flags, no doctor note, and no safety check, and borrowing the
 *    language of the yoga side for it would be both wrong and insulting.
 */

// ------------------------------------------------------------------ yoga --

/**
 * A movement that somebody must not attempt unaided.
 *
 * The distinction in the first two is the whole design. [KneeOrBackIssue] can
 * be worked around — a modified forward bend still opens the spine. [FallRisk]
 * cannot, and offering a standing balance to somebody who has already fallen
 * twice is not encouragement.
 */
enum class SafetyFlag(val code: String, val canBeModified: Boolean) {
    None("none", false),
    KneeOrBackIssue("knee_or_back_issue", true),
    /** Mobility is low enough that floor work needs a chair. */
    LimitedMobility("limited_mobility", true),
    HeartCondition("heart_condition", false),
    RecentSurgery("recent_surgery", false),
    FallRisk("fall_risk", false),
    Pregnancy("pregnancy", false),
    ;

    companion object {
        fun fromCode(code: String?): SafetyFlag =
            entries.firstOrNull { it.code == code } ?: None
    }
}

/**
 * A practitioner's profile, and the safety flags that gate their sessions.
 *
 * [doctorNote] is free text a doctor typed. [instructor] is a boolean for a
 * reason that is easy to miss: the same pose is taught differently by a
 * ["dadi"] (grandmother, unhurried, seated, no English needed) than by a
 * ["dada"] (grandfather, more formal, often with the Sanskrit names). Plan
 * section 7.1 asks for both, and the distinction is the difference between
 * content that lands and content that does not.
 */
data class KendraProfile(
    val userId: Int,
    val flags: Set<SafetyFlag> = emptySet(),
    val doctorNote: String? = null,
    val instructorStyle: InstructorStyle? = null,
    /** Left / right / both. Decides which side a pose is demonstrated on. */
    val mirror: Boolean = true,
    val voiceLanguages: List<AppLanguage> = listOf(AppLanguage.Hindi),
) {
    /**
     * The highest flag the practitioner has, which is what gates the most.
     *
     * Taken as the most restrictive rather than as a set membership test
     * because a practitioner with both [LimitedMobility] and [FallRisk] must
     * be offered the [FallRisk] treatment, not the mobility one.
     */
    val mostRestrictive: SafetyFlag
        get() = flags.maxByOrNull { it.severity } ?: SafetyFlag.None

    val needsDoctorNote: Boolean
        get() = flags.any { !it.canBeModified }
}

enum class InstructorStyle(val code: String) {
    Dadi("dadi"),
    Dada("dada");

    companion object {
        fun fromCode(code: String?): InstructorStyle? =
            entries.firstOrNull { it.code == code }
    }
}

/** Where a flag sits between "adaptable" and "do not attempt". */
private val SafetyFlag.severity: Int
    get() = when (this) {
        SafetyFlag.None -> 0
        SafetyFlag.KneeOrBackIssue, SafetyFlag.LimitedMobility, SafetyFlag.Pregnancy -> 1
        SafetyFlag.RecentSurgery, SafetyFlag.HeartCondition -> 2
        SafetyFlag.FallRisk -> 3
    }

/** A programme: chair yoga, mobility, pranayama, safety check. Plan §7.1. */
data class KendraProgramme(
    val id: Int,
    val title: String,
    val description: String,
    val kind: ProgrammeKind,
    val totalMinutes: Int,
    val poses: List<Pose>,
    /** Tags used to match a programme to a profile's flags. */
    val targets: Set<SafetyFlag> = emptySet(),
) {
    val isChairBased: Boolean get() = kind == ProgrammeKind.ChairYoga

    /**
     * Whether this programme is safe to offer a practitioner with [flags].
     *
     * A programme is withheld only when it targets a flag the practitioner has
     * AND that flag cannot be worked around. Chair yoga is offered to
     * everybody, including somebody with a fall risk — that is the point of
     * it, and sitting down is the safest thing in the building.
     */
    fun isSafeFor(flags: Set<SafetyFlag>): Boolean =
        flags.none { it in targets && !it.canBeModified }
}

/**
 * One movement in a programme.
 *
 * [contraindicated] is the list of flags that rule this pose out, and it is
 * checked by [isContraindicated] before the pose is offered. A pose that
 * requires standing is contraindicated by [SafetyFlag.FallRisk] everywhere in
 * the data rather than being filtered at the call site, so there is one place
 * where the rule can be got wrong and it has a test.
 */
data class Pose(
    val id: Int,
    val name: String,
    /** What the user does, in one line, in their language. Not Sanskrit. */
    val instruction: String,
    val holdSeconds: Int? = null,
    val reps: Int? = null,
    val requiresStanding: Boolean = false,
    val requiresFloor: Boolean = false,
    val contraindications: Set<SafetyFlag> = emptySet(),
    /** A gentler form. Offer this when the pose itself is ruled out. */
    val modification: String? = null,
    /** Reminder text, e.g. "Breathe out as you go." */
    val breathingCue: String? = null,
) {
    /**
     * Whether this pose must not be offered to a practitioner with [flags].
     *
     * A pose with a modification is offered modified rather than withheld,
     * because the plan's argument is that a 68-year-old should still be able
     * to do a forward bend in some form. A pose with no modification and a
     * contraindication is withheld entirely.
     */
    fun isContraindicated(flags: Set<SafetyFlag>): Boolean {
        val blocking = contraindications.intersect(flags)
        if (blocking.isEmpty()) return false
        return blocking.any { !it.canBeModified } || modification == null
    }
}

enum class ProgrammeKind(val code: String) {
    ChairYoga("chair_yoga"),
    Mobility("mobility"),
    Pranayama("pranayama"),
    SafetyCheck("safety_check"),
    Meditation("meditation");

    companion object {
        fun fromCode(code: String?): ProgrammeKind? =
            entries.firstOrNull { it.code == code }
    }
}

/** A completed session, and the mobility check that goes with it. */
data class KendraSession(
    val id: Int,
    val userId: Int,
    val programmeId: Int,
    val startedAt: Instant,
    val minutes: Int,
    val completed: Boolean = false,
    val mobilityCheckId: Int? = null,
)

/**
 * The mobility check. Plan section 7.1 asks for it because a fall is the
 * realistic risk, and the two numbers below are the ones a doctor asks for.
 *
 * Both are timed, and both are recorded because they are for the user's own
 * doctor — the families' monthly mobility report (plan section 19) is built
 * from these and is shared only with permission.
 */
data class MobilityCheck(
    val id: Int,
    val chairStandsIn30s: Int,
    val singleLegHoldSeconds: Int,
    val measuredAt: Instant,
) {
    /** Below this, a standing balance is not offered unassisted. */
    val isAtFallRisk: Boolean get() = chairStandsIn30s < 8 || singleLegHoldSeconds < 5

    fun deltaFrom(previous: MobilityCheck): Int = chairStandsIn30s - previous.chairStandsIn30s
}

/** A yoga pairing: two people who work out together. Plan section 7.1. */
data class YogaPair(
    val id: Int,
    val userA: Int,
    val userB: Int,
    val createdAt: Instant? = null,
) {
    fun involves(userId: Int): Boolean = userId == userA || userId == userB
}

// ------------------------------------------------------------- spiritual --

/**
 * The paths of the Adhyatm Kendra. Plan section 7.2.
 *
 * This is a *practice* section, not a belief section. Nothing here instructs,
 * corrects or claims; the app offers a way to keep track of what somebody
 * already does. That constraint is why [SpiritualState] is a record of the
 * user's own choices and holds no content of its own.
 */
enum class SpiritualPath(val code: String) {
    None("none"),
    Shaiva("shaiva"),
    Vaishnava("vaishnava"),
    Shakta("shakta"),
    Sikh("sikh"),
    Jain("jain"),
    Buddhist("buddhist"),
    /** Kept generic on purpose: "spiritual but not religious" is a real answer. */
    General("general");

    companion object {
        fun fromCode(code: String?): SpiritualPath =
            entries.firstOrNull { it.code == code } ?: None
    }
}

/** A mantra being counted, with a japa mala. Plan section 7.2. */
data class Japa(
    val mantra: String,
    val targetCount: Int,
    val count: Int = 0,
    /** 108 for a full mala, 1 for a single. */
    val rounds: Int = 108,
) {
    val isComplete: Boolean get() = count >= targetCount
    val progress: Float get() = if (targetCount > 0) count.toFloat() / targetCount else 0f
}

/** A sankalp — an intention kept for a period. */
data class Sankalp(
    val text: String,
    val startedAt: Instant,
    val until: Instant? = null,
) {
    fun isActiveAt(now: Instant): Boolean =
        now >= startedAt && (until == null || now < until)
}

/**
 * A festival or vrat date, for the user's region.
 *
 * [region] matters and is not cosmetic: Ganesh Chaturthi is not the same day
 * everywhere, and a calendar that is confidently wrong by a day is worse than
 * no calendar at all for somebody keeping a fast. The `festival_calendar` job
 * loads this from a licensed source rather than computing it, because a lunar
 * calendar is not something to reimplement.
 */
data class Festival(
    val id: Int,
    val name: String,
    val startsOn: Instant,
    val endsOn: Instant? = null,
    val region: String? = null,
    val kind: FestivalKind = FestivalKind.Festival,
    val languages: List<AppLanguage> = emptyList(),
) {
    val isVrat: Boolean get() = kind == FestivalKind.Vrat
}

enum class FestivalKind(val code: String) {
    Festival("festival"),
    Vrat("vrat"),
    Ekadashi("ekadashi"),
    FastingDay("fasting_day");

    companion object {
        fun fromCode(code: String?): FestivalKind? =
            entries.firstOrNull { it.code == code }
    }
}

/** The user's own practice, saved across sessions. */
data class SpiritualState(
    val paths: Set<SpiritualPath> = emptySet(),
    val deity: String? = null,
    val japa: Japa? = null,
    val sankalp: Sankalp? = null,
    val savedContentIds: Set<Int> = emptySet(),
    val festivalsRegion: String? = null,
)
