package `in`.doosripari.feature.checkin

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The check-in state machine, exactly as drawn in plan section 6.3.
 *
 * ```
 * Waiting ──check-in──▶ CheckedIn
 *    │
 *    ├─window start + 1h──▶ Reminded ──check-in──▶ CheckedIn
 *    │                          │
 *    │                          └─window end──▶ Called
 *    │                                                 │
 *    │                            ┌─answers the IVR and presses 1──▶ CheckedIn
 *    │                            └─no answer after 2 tries, 10 min apart──▶ GuardiansAlerted
 *    │                                                                       │
 *    │                                              ┌─guardian taps "I've reached them"──▶ Resolved
 *    │                                              └─no acknowledgement in 60 min──▶ HelpersAlerted
 *    │                                                                                     │
 *    │                                                                ┌─helper confirms in person──▶ Resolved
 * ```
 *
 * The whole machine is pure data plus [EscalationEngine], so the same code runs
 * in the `checkin_escalate` job (every minute), in the Android app for a live
 * countdown, and in the iOS app behind a widget. Plan section 6.3 is explicit
 * that escalation runs on the *server* — neither Android nor iOS guarantees
 * exact background timing — so this module never decides to alert anyone; it
 * only says which stage applies and what the server should dispatch.
 */

/** Where a day's check-in has got to. */
@Serializable
enum class CheckInStage {
    /** The window is open and nobody has checked in yet. */
    Waiting,

    /** Window start + 1 h with no check-in; the user has been nudged. */
    Reminded,

    /** The window has closed unanswered; the automated call is going out. */
    Called,

    /** Guardians have been told and have not yet acknowledged. */
    GuardiansAlerted,

    /** Guardians have not responded in time; nearby helpers have been told. */
    HelpersAlerted,

    /** The user said they are fine. Terminal. */
    CheckedIn,

    /** Somebody reached the user in person. Terminal. */
    Resolved;

    /** Terminal stages end escalation; nothing further is dispatched. */
    val isTerminal: Boolean get() = this == CheckedIn || this == Resolved

    companion object {
        /**
         * The wire name, which is the enum constant's own name.
         *
         * These strings are what the server sends and what every installed
         * build of both apps has already seen. Renaming a constant without
         * keeping the wire name would silently break them, so the two are the
         * same string on purpose. python/models.py STAGE_* must match, and
         * python/tests/test_parity.py checks that they do.
         */
        val wireNames: List<String> get() = entries.map { it.name }

        /**
         * Parse a wire name.
         *
         * Returns null rather than throwing: a server that grew a stage we do
         * not know about should leave the user looking at a stale but sane
         * screen, not crash the app while they are trying to say they are fine.
         */
        fun fromWire(value: String): CheckInStage? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * What the server should actually send when the stage changes. The plan puts
 * these on separate channels because one can fail: a push can be missed, a call
 * can go unanswered, an SMS can be filtered (plan section 21, risk 2).
 */
@Serializable
enum class EscalationAction {
    /** Nothing to dispatch. */
    None,

    /** Push, plus a full-screen notification on Android 10+ / time-sensitive on iOS. */
    RemindUser,

    /** One IVR attempt: "If you are fine, press 1." */
    PlaceIvrCall,

    /** Push + SMS + WhatsApp to guardians in order, with the last check-in time. */
    AlertGuardians,

    /** Alert the up to three opt-in nearby helpers. */
    AlertHelpers,

    /** Tell everyone the user is fine — sent the moment a late check-in lands. */
    NotifyAllFine,
}

/** How a check-in was recorded. Passive signals only ever produce "checked in". */
@Serializable
enum class CheckInMethod {
    Tapped,
    /** Any app use, two unlocks, or 200+ steps. */
    Passive,
    /** The user answered the automated call and pressed 1. */
    Ivr,
    /** Somebody reached them in person. */
    InPerson,
    ;

    companion object {
        /** Parse a wire name, or null if the server sent something new. */
        fun fromWire(value: String): CheckInMethod? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** One immutable step in the audit trail. The plan requires every step logged. */
@Serializable
data class CheckInEvent(
    val at: Instant,
    val stage: CheckInStage,
    val action: EscalationAction = EscalationAction.None,
    val note: String? = null,
)

/** A single day's check-in row, as the `checkins` table holds it. */
@Serializable
data class CheckInRecord(
    val day: LocalDate,
    val stage: CheckInStage,
    /** When the current stage was entered — every timeout below is relative to this. */
    val stageEnteredAt: Instant,
    val events: List<CheckInEvent> = emptyList(),
    /** How many of the two IVR attempts have been placed, 0–2. */
    val callAttempts: Int = 0,
    /** When the current IVR attempt was placed, used for the 10-minute gap. */
    val lastCallAt: Instant? = null,
    val location: GeoPoint? = null,
)

/** Only ever used to show a guardian where to look, and only with permission. */
@Serializable
data class GeoPoint(val lat: Double, val lng: Double, val accuracyM: Double? = null)

/**
 * Per-user check-in settings (`checkin_settings`).
 *
 * Defaults are the plan's: a 09:00 window three hours long.
 */
@Serializable
data class CheckInSettings(
    /** Local time the window opens. Default 09:00. */
    val windowStart: LocalTime = DEFAULT_WINDOW_START,
    /** How long the window stays open. Default 180 minutes. */
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    /** Count app use, unlocks and steps as a check-in. Off by default. */
    val passive: Boolean = false,
    /** Send location to guardians when a check-in is missed. Off by default. */
    val shareLocationOnMiss: Boolean = false,
    /** Travel or hospital cover; guardians are told (plan section 6.3). */
    val pausedUntil: Instant? = null,
    /** Accepted guardians, 1–5. */
    val guardianCount: Int = 0,
    /** Opt-in nearby helpers, up to 3. */
    val helperCount: Int = 0,
    /** The user's timezone; the window is a local-time window. */
    val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    init {
        require(windowMinutes in 1..MAX_WINDOW_MINUTES) {
            "windowMinutes must be 1..$MAX_WINDOW_MINUTES, was $windowMinutes"
        }
        require(guardianCount in 0..MAX_GUARDIANS) {
            "guardianCount must be 0..$MAX_GUARDIANS, was $guardianCount"
        }
        require(helperCount in 0..MAX_HELPERS) {
            "helperCount must be 0..$MAX_HELPERS, was $helperCount"
        }
    }

    /** Instant the window opens on [day] in the user's own timezone. */
    fun windowOpensAt(day: LocalDate): Instant =
        LocalDateTime(day, windowStart).toInstant(timeZone)

    /** Instant the window closes on [day]. This is when the IVR calls start. */
    fun windowClosesAt(day: LocalDate): Instant =
        windowOpensAt(day).plus(windowMinutes.minutes)

    /** True while the user is paused, and the pause has not run out. */
    fun isPausedAt(now: Instant): Boolean = pausedUntil?.let { now < it } ?: false

    /**
     * Whether a check-in at [now] counts. Paused days never escalate, so a
     * check-in on a paused day is recorded but changes nothing.
     */
    fun acceptsCheckInAt(now: Instant): Boolean = now >= windowOpensAt(now.localDay(timeZone))

    companion object {
        val DEFAULT_WINDOW_START: LocalTime = LocalTime(9, 0)
        const val DEFAULT_WINDOW_MINUTES: Int = 180
        const val MAX_WINDOW_MINUTES: Int = 24 * 60

        const val MAX_GUARDIANS: Int = 5
        const val MAX_HELPERS: Int = 3

        /** Window start + 1 h, no check-in. */
        val REMIND_AFTER: Duration = 1.hours

        /** Between the two IVR attempts. */
        val IVR_RETRY_GAP: Duration = 10.minutes

        /** How many automated calls before guardians are told. */
        const val IVR_ATTEMPTS: Int = 2

        /** No guardian acknowledgement in this long escalates to helpers. */
        val HELPER_ESCALATION_AFTER: Duration = 60.minutes

        /** Travel or hospital pause, 1–30 days (plan section 6.3). */
        const val MIN_PAUSE_DAYS: Int = 1
        const val MAX_PAUSE_DAYS: Int = 30
    }
}

/**
 * What [EscalationEngine.advance] decided. [NoChange] is the common case: the
 * job runs every minute, so most ticks change nothing and must not write.
 */
@Serializable
sealed interface CheckInDecision {
    /** Nothing to do this tick. */
    data object NoChange : CheckInDecision

    /**
     * Move to [to] and dispatch [action]. [at] is when the stage was entered,
     * which is the anchor for the next timeout.
     */
    data class Advance(
        val from: CheckInStage,
        val to: CheckInStage,
        val action: EscalationAction,
        val at: Instant,
    ) : CheckInDecision

    /** A non-terminal state, for the phone's live view and the guardian dashboard. */
    data class Pending(
        val record: CheckInRecord,
        /** At the earliest, the next stage change happens. */
        val nextChangeAt: Instant?,
    ) : CheckInDecision
}

/** True when [now] is at or after this instant. Kept here so tests read clearly. */
internal fun Instant.reached(now: Instant): Boolean = now >= this

/** Today in the user's timezone. */
internal fun Instant.localDay(timeZone: TimeZone): LocalDate = toLocalDateTime(timeZone).date

/** Midnight starting [day], used when a day has no row yet. */
@Suppress("unused")
internal fun LocalDate.midnightIn(timeZone: TimeZone): Instant = atStartOfDayIn(timeZone)
