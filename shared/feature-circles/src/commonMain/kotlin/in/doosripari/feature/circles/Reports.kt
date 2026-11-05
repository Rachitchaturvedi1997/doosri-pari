package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.PostKind
import `in`.doosripari.core.model.Report
import `in`.doosripari.core.model.ReportReason
import `in`.doosripari.core.model.ReportStatus
import `in`.doosripari.core.model.ReportTarget
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The report flow. Plan sections 15, 6.6 and 6.7.
 *
 * The rule this file exists for is one line of section 15: a report is
 * [routed to a person or it is queued][ReportLane], and the split is decided
 * by `core-model`'s [ReportReason.requiresImmediateAction] rather than by
 * whatever a client feels like. A scam, a hate speech, an abuse report and
 * somebody's phone number sitting in a post all get a human. Spam does not,
 * and that is not a lesser concern so much as a smaller one — a queue is the
 * right answer for spam precisely because there is a lot of it and none of it
 * is urgent.
 *
 * Two things follow from that, and both are load-bearing.
 *
 *  - **The report cannot be lost.** A report that fails to submit keeps what
 *    the user picked, exactly as the composer keeps what was typed. Somebody
 *    who has just worked out that a post is a scam is at their most likely to
 *    be on a bad connection, because that is when they are reading it.
 *  - **A voice note cannot be reported before it is transcribed.** Section 6.6
 *    requires the transcript before a note can be reported on, so the flow
 *    refuses the submission and says why, rather than accepting one a
 *    moderator would have to listen to before acting on a "scam" label.
 */
enum class ReportLane(
    val code: String,
    /** Hours the plan promises, section 15. */
    val responseTargetHours: Int,
) {
    /** A person, now. Scam, abuse, hate speech, personal information. */
    Human("human", 2),

    /** The moderation queue. Spam and misinformation. */
    Queue("queue", 24);

    companion object {
        /**
         * The routing rule, and the only place it is written down.
         *
         * `core-model` puts `requiresImmediateAction` on the reason itself
         * precisely so that the two apps, the notification worker and this
         * screen cannot each form their own opinion about what is urgent.
         */
        fun forReason(reason: ReportReason): ReportLane =
            if (reason.requiresImmediateAction) Human else Queue
    }
}

/**
 * Where a report is going and by when a human will look at it.
 *
 * [responseDueBy] is a *promise to the user*, so it is computed here rather
 * than left to the server to discover. A screen that says "we'll look at this"
 * with no time attached is a promise nobody is keeping.
 */
data class ReportRoute(
    val lane: ReportLane,
    val raisedAt: Instant,
    val responseDueBy: Instant,
    /**
     * True when the promise fell outside the desk's hours and had to roll to
     * the next morning. The UI says so rather than quietly showing a due date
     * twenty hours away and looking broken.
     */
    val rolledToNextDay: Boolean = false,
)

/**
 * Turns a reason and a clock into a lane and a deadline.
 *
 * Section 15 gives the hours the desk is staffed — 07:00 to 22:00 — and the
 * two-hour promise for scams sits inside them. Outside them the report is
 * *still raised immediately*; only the clock on the response rolls. Those are
 * different decisions and conflating them is how a night-time report ends up
 * sitting in a queue until breakfast.
 */
object ReportRouter {

    /** Section 15: "scam reports within 2 hours (7:00–22:00)". */
    val DESK_OPEN: LocalTime = LocalTime(7, 0)
    val DESK_CLOSE: LocalTime = LocalTime(22, 0)

    fun route(reason: ReportReason, now: Instant, timeZone: TimeZone): ReportRoute {
        val lane = ReportLane.forReason(reason)
        val target = now + lane.responseTargetHours.hours
        if (lane == ReportLane.Queue) {
            return ReportRoute(lane, raisedAt = now, responseDueBy = target)
        }
        if (isWithinDeskHours(target, timeZone)) {
            return ReportRoute(lane, raisedAt = now, responseDueBy = target)
        }
        // The two hours ran out while the desk was shut. Start again when it
        // opens, and say that this is what happened.
        return ReportRoute(
            lane = lane,
            raisedAt = now,
            responseDueBy = nextDeskOpen(target, timeZone) + lane.responseTargetHours.hours,
            rolledToNextDay = true,
        )
    }

    /** Whether a human is on the desk at [at], in the circle's local time. */
    fun isWithinDeskHours(at: Instant, timeZone: TimeZone): Boolean {
        val local = at.toLocalDateTime(timeZone).time
        return local >= DESK_OPEN && local < DESK_CLOSE
    }

    /** Midnight-starting the next moment the desk is open, strictly after [after]. */
    fun nextDeskOpen(after: Instant, timeZone: TimeZone): Instant {
        val local = after.toLocalDateTime(timeZone)
        val sameDay = local.date.atStartOfDayIn(timeZone) + DESK_OPEN.sinceMidnight()
        return if (sameDay > after) sameDay
        else local.date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone) + DESK_OPEN.sinceMidnight()
    }

    private fun LocalTime.sinceMidnight(): Duration = hour.hours + minute.minutes
}

/**
 * The user's in-progress report.
 *
 * A draft rather than a form's worth of separate fields, so that a failed
 * submission is a single value that comes back whole.
 */
data class ReportDraft(
    val reporterId: Int,
    val target: ReportTarget,
    val targetId: Int,
    /**
     * Set when the reported thing is a post. Carried so the flow can refuse a
     * voice note that has not been transcribed, per section 6.6.
     */
    val postKind: PostKind? = null,
    val transcriptAvailable: Boolean = true,
    /** Null until the user picks one. There is deliberately no "other". */
    val reason: ReportReason? = null,
    val detail: String = "",
    val submit: ReportSubmitState = ReportSubmitState.Idle,
) {
    val problems: List<ReportProblem> get() = ReportFlow.validate(this)
}

/**
 * A report being sent, sent, or failed.
 *
 * The failure arm keeps [ReportDraft] attached so the caller can hand the
 * whole thing straight back to the screen with the user's reason still ticked.
 */
sealed interface ReportSubmitState {
    data object Idle : ReportSubmitState

    data object Submitting : ReportSubmitState

    data class Failed(val message: String, val retryable: Boolean = true) : ReportSubmitState

    data class Submitted(val reportId: Int, val route: ReportRoute) : ReportSubmitState
}

/** Why a report cannot be sent yet. Typed, and each one says what to do. */
sealed interface ReportProblem {
    val message: String

    data object NoReasonChosen : ReportProblem {
        override val message: String get() = "Choose what is wrong with this."
    }

    /**
     * Section 6.6: a voice note has to be transcribed before it can be
     * reported on. The message says to wait rather than offering a button,
     * because there is no action the user can take to make it go faster.
     */
    data object VoiceNotTranscribed : ReportProblem {
        override val message: String
            get() = "The words for this voice note are still being written. Try again in a moment."
    }

    data class DetailTooLong(val limit: Int) : ReportProblem {
        override val message: String get() = "Keep the explanation under $limit characters."
    }

    data object AlreadyReported : ReportProblem {
        override val message: String get() = "You have already reported this."
    }
}

/** What [ReportFlow.submit] decided. */
sealed interface ReportAttempt {
    data class Blocked(val problems: List<ReportProblem>) : ReportAttempt

    /**
     * Go and tell the user what that means.
     *
     * The route comes back with the draft rather than being recomputed by the
     * caller: the deadline depends on the clock and the reason, and letting
     * two places derive it is how a screen ends up promising four hours while
     * the notification worker promised two.
     */
    data class Raise(val draft: ReportDraft, val route: ReportRoute) : ReportAttempt
}

/**
 * The report flow's rules, as pure functions.
 */
object ReportFlow {

    /**
     * How much a user may add beyond the reason.
     *
     * A house number, not a plan one. The reason is what a moderator acts on;
     * the free text is context, and section 4.4's card rule is three lines, so
     * anything longer is decoration rather than evidence.
     */
    const val MAX_DETAIL_CHARS: Int = 500

    /**
     * The reasons, in the order the screen offers them.
     *
     * Ordered most-serious first so the top of a list is the one that gets a
     * person, and so somebody in a hurry picks correctly by position. The
     * order is a property of the *screen*, not of the enum, which stays in
     * `core-model`'s declaration order and carries the wire codes.
     */
    fun offeredReasons(): List<ReportReason> = listOf(
        ReportReason.Scam,
        ReportReason.Abuse,
        ReportReason.HateSpeech,
        ReportReason.PersonalInfo,
        ReportReason.Spam,
        ReportReason.Misinformation,
    )

    fun validate(draft: ReportDraft): List<ReportProblem> {
        val problems = mutableListOf<ReportProblem>()
        if (draft.reason == null) problems += ReportProblem.NoReasonChosen
        if (draft.postKind == PostKind.Voice && !draft.transcriptAvailable) {
            problems += ReportProblem.VoiceNotTranscribed
        }
        if (draft.detail.length > MAX_DETAIL_CHARS) {
            problems += ReportProblem.DetailTooLong(MAX_DETAIL_CHARS)
        }
        return problems
    }

    /**
     * Raise the report.
     *
     * Returns the [ReportRoute] alongside the draft so the caller can tell
     * the user the truth about what happens next — "a moderator will look at
     * this within 2 hours" versus "this goes into the queue" — instead of a
     * single thank-you that means nothing either way.
     */
    fun submit(draft: ReportDraft, now: Instant, timeZone: TimeZone): ReportAttempt {
        val problems = validate(draft)
        if (problems.isNotEmpty()) return ReportAttempt.Blocked(problems)
        val reason = draft.reason!!
        return ReportAttempt.Raise(
            draft = draft.copy(submit = ReportSubmitState.Submitting),
            route = ReportRouter.route(reason, now, timeZone),
        )
    }

    /** The send failed. Everything the user chose is still in the returned draft. */
    fun submitFailed(draft: ReportDraft, message: String, retryable: Boolean = true): ReportDraft =
        draft.copy(submit = ReportSubmitState.Failed(message, retryable))

    /** The send worked. The server's id and our own deadline come back together. */
    fun submitSucceeded(
        draft: ReportDraft,
        reportId: Int,
        now: Instant,
        timeZone: TimeZone,
    ): ReportDraft {
        val route = draft.reason?.let { ReportRouter.route(it, now, timeZone) }
        return draft.copy(
            submit = route?.let { ReportSubmitState.Submitted(reportId, it) }
                ?: ReportSubmitState.Idle,
        )
    }

    /**
     * Build the `core-model` row once the server has assigned an id.
     *
     * The client does not set [Report.status] or [Report.createdAt] — the
     * server owns both, and a client that guessed would make the audit trail
     * in section 10 disagree with the one the moderators read.
     */
    fun toReport(draft: ReportDraft, reportId: Int): Report = Report(
        id = reportId,
        targetType = draft.target,
        targetId = draft.targetId,
        reporterId = draft.reporterId,
        reason = draft.reason!!,
    )
}

/**
 * Consequences of a report, once it is upheld.
 *
 * Plan section 15's ladder is warning, 7-day pause, 30-day pause, removal —
 * with scams skipping straight to removal. Section 6.7 adds its own rule for
 * rooms: three upheld reports and no hosting for 30 days. Both are here
 * because both are a count with a threshold, and a threshold implemented
 * twice in two screens will differ.
 */
object ReportStrikes {

    /** Section 6.7: "3 valid reports means no hosting for 30 days". */
    const val HOSTING_BAN_AFTER: Int = 3
    const val HOSTING_BAN_DAYS: Int = 30

    enum class Level(val code: String, val suspensionDays: Int) {
        Warning("warning", 0),
        ShortPause("short_pause", 7),
        LongPause("long_pause", 30),
        Removal("removal", Int.MAX_VALUE);

        companion object {
            fun fromCode(code: String?): Level =
                entries.firstOrNull { it.code == code } ?: Warning
        }
    }

    /**
     * The level for somebody's [upheldReports]th upheld report.
     *
     * A scam is [Level.Removal] however many there are. Section 15 says so in
     * one line ("scams mean immediate removal") and it is the one place the
     * ladder is skipped: the appeal cost of being wrong here is a week of
     * somebody's life online, and the cost of being slow is a pensioner who
     * has just been defrauded.
     */
    fun levelFor(upheldReports: Int, latestReason: ReportReason): Level = when {
        latestReason == ReportReason.Scam -> Level.Removal
        upheldReports <= 1 -> Level.Warning
        upheldReports == 2 -> Level.ShortPause
        upheldReports == 3 -> Level.LongPause
        else -> Level.Removal
    }

    /**
     * Whether this many upheld reports stops somebody hosting a room.
     *
     * Section 6.7 states it flatly, and it is not hedged by the scam rule:
     * three upheld reports about somebody's *behaviour in rooms* is enough on
     * its own, whatever the reasons were.
     */
    fun hostingBannedFor(upheldReports: Int): Boolean = upheldReports >= HOSTING_BAN_AFTER

    /** The report row's status, given what the moderator decided. */
    fun statusFor(upheld: Boolean): ReportStatus =
        if (upheld) ReportStatus.Actioned else ReportStatus.Dismissed
}
