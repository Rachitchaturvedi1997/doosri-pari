package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.PostKind
import `in`.doosripari.core.model.ReportReason
import `in`.doosripari.core.model.ReportStatus
import `in`.doosripari.core.model.ReportTarget
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * The report flow, plan sections 15 and 6.6.
 *
 * The routing rule is the point: a reason that `core-model` marks
 * `requiresImmediateAction` goes to a person, and everything else goes to the
 * queue. The response clock is tested around the desk's hours because that is
 * where the interesting behaviour lives — a report raised at eleven at night
 * is still raised *immediately*, and only the promise moves.
 */
class ReportsTest {

    private val ist = TimeZone.of("Asia/Kolkata")
    private val utc = TimeZone.of("UTC")

    private fun at(hour: Int, minute: Int = 0) =
        Instant.parse("2026-10-14T%02d:%02d:00Z".format(hour, minute))

    private fun draft(
        reason: ReportReason? = ReportReason.Spam,
        postKind: PostKind? = PostKind.Text,
        transcriptAvailable: Boolean = true,
        detail: String = "",
        target: ReportTarget = ReportTarget.Post,
    ) = ReportDraft(
        reporterId = 7,
        target = target,
        targetId = 42,
        postKind = postKind,
        transcriptAvailable = transcriptAvailable,
        reason = reason,
        detail = detail,
    )

    // ---------------------------------------------------------------- routing --

    @Test
    fun `every reason core-model marks immediate goes to a person`() {
        // The rule is read off the enum rather than re-listed, so a reason
        // added to `core-model` with the flag set cannot be forgotten here.
        for (reason in ReportReason.entries.filter { it.requiresImmediateAction }) {
            assertEquals(ReportLane.Human, ReportLane.forReason(reason), "$reason must be human")
        }
    }

    @Test
    fun `everything else goes to the queue`() {
        for (reason in ReportReason.entries.filterNot { it.requiresImmediateAction }) {
            assertEquals(ReportLane.Queue, ReportLane.forReason(reason), "$reason must be queued")
        }
    }

    @Test
    fun `the two lanes keep section 15's response times`() {
        assertEquals(2, ReportLane.Human.responseTargetHours)
        assertEquals(24, ReportLane.Queue.responseTargetHours)
    }

    @Test
    fun `a report raised in the middle of the day is answered within the promised hours`() {
        val noon = at(6, 0) // 11:30 in Kolkata, comfortably inside the desk's day.
        val route = ReportRouter.route(ReportReason.Scam, noon, ist)

        assertEquals(ReportLane.Human, route.lane)
        assertEquals(noon + 2.hours, route.responseDueBy)
        assertFalse(route.rolledToNextDay)
    }

    @Test
    fun `a late report is still raised now, and only the promise rolls to the morning`() {
        // The distinction the section turns on. Conflating "raise" with
        // "promise" is how a night-time report sits in a queue until breakfast.
        val lateEvening = at(16, 30) // 22:00 in Kolkata: the desk has just closed.
        val route = ReportRouter.route(ReportReason.Scam, lateEvening, ist)

        assertEquals(ReportLane.Human, route.lane)
        assertEquals(lateEvening, route.raisedAt)
        assertTrue(route.rolledToNextDay)
        // Next morning at seven in Kolkata — 01:30 UTC — plus the two hours
        // section 15 promises.
        assertEquals(Instant.parse("2026-10-15T03:30:00Z"), route.responseDueBy)
    }

    @Test
    fun `a queued report is promised in twenty four hours whatever the hour`() {
        val threeInTheMorning = at(21, 30)
        val route = ReportRouter.route(ReportReason.Spam, threeInTheMorning, ist)

        assertEquals(ReportLane.Queue, route.lane)
        assertEquals(threeInTheMorning + 24.hours, route.responseDueBy)
        assertFalse(route.rolledToNextDay)
    }

    @Test
    fun `the desk hours are seven to twenty two in local time`() {
        // 02:30 UTC is 08:00 in Kolkata: open. 17:30 UTC is 23:00: shut.
        assertTrue(ReportRouter.isWithinDeskHours(at(2, 30), ist))
        assertFalse(ReportRouter.isWithinDeskHours(at(17, 30), ist))
    }

    @Test
    fun `the same instant gives different answers in two timezones`() {
        // The desk is a local-time promise. A report must not be answered on
        // somebody's shift because the server happens to run in UTC.
        val instant = at(14, 0)
        assertTrue(ReportRouter.isWithinDeskHours(instant, utc))
        assertFalse(ReportRouter.isWithinDeskHours(instant, ist))
    }

    // ------------------------------------------------------------- validation --

    @Test
    fun `a report with no reason chosen is not sent`() {
        val attempt = ReportFlow.submit(draft(reason = null), at(10), utc)
        val blocked = assertIs<ReportAttempt.Blocked>(attempt)
        assertTrue(blocked.problems.contains(ReportProblem.NoReasonChosen))
    }

    @Test
    fun `a voice note cannot be reported before the words exist`() {
        // Section 6.6 requires the transcript before a note can be reported on.
        // Accepting the report anyway would send a moderator to a recording
        // with a "this is a scam" label they cannot check.
        val attempt = ReportFlow.submit(
            draft(reason = ReportReason.Scam, postKind = PostKind.Voice, transcriptAvailable = false),
            at(10),
            utc,
        )
        val blocked = assertIs<ReportAttempt.Blocked>(attempt)
        assertTrue(blocked.problems.contains(ReportProblem.VoiceNotTranscribed))
        assertTrue(blocked.problems.single().message.isNotBlank())
    }

    @Test
    fun `a voice note with its transcript can be reported`() {
        val attempt = ReportFlow.submit(
            draft(reason = ReportReason.Scam, postKind = PostKind.Voice, transcriptAvailable = true),
            at(10),
            utc,
        )
        assertIs<ReportAttempt.Raise>(attempt)
    }

    @Test
    fun `a comment or a room is reported without a post kind`() {
        for (target in listOf(ReportTarget.Comment, ReportTarget.VoiceRoom, ReportTarget.User)) {
            val attempt = ReportFlow.submit(
                draft(reason = ReportReason.Abuse, postKind = null, target = target),
                at(10),
                utc,
            )
            assertIs<ReportAttempt.Raise>(attempt)
        }
    }

    @Test
    fun `an over long explanation is refused with the limit in the sentence`() {
        val attempt = ReportFlow.submit(
            draft(detail = "x".repeat(ReportFlow.MAX_DETAIL_CHARS + 1)),
            at(10),
            utc,
        )
        val problem = assertIs<ReportProblem.DetailTooLong>(
            assertIs<ReportAttempt.Blocked>(attempt).problems.first()
        )
        assertEquals(ReportFlow.MAX_DETAIL_CHARS, problem.limit)
    }

    // ---------------------------------------------------------------- the flow --

    @Test
    fun `raising a report hands back the route so the screen can promise something`() {
        // "We have your report" means nothing. "A moderator will look at this
        // within 2 hours" is a promise the app can keep.
        val attempt = ReportFlow.submit(draft(reason = ReportReason.Scam), at(6), ist)
        val raise = assertIs<ReportAttempt.Raise>(attempt)

        assertEquals(ReportLane.Human, raise.route.lane)
        assertIs<ReportSubmitState.Submitting>(raise.draft.submit)
    }

    @Test
    fun `a failed submission keeps the reason the user chose`() {
        // Somebody who has just worked out that a post is a scam is reading it
        // on whatever connection they happen to have, which is usually a bad
        // one.
        val original = draft(reason = ReportReason.Scam, detail = "asks for my OTP")
        val failed = ReportFlow.submitFailed(original, "no connection")

        assertEquals(ReportReason.Scam, failed.reason)
        assertEquals("asks for my OTP", failed.detail)
        assertEquals(ReportTarget.Post, failed.target)
        assertEquals(42, failed.targetId)
        assertIs<ReportSubmitState.Failed>(failed.submit)
    }

    @Test
    fun `a successful submission carries back the id and the deadline`() {
        val sent = ReportFlow.submitSucceeded(
            draft(reason = ReportReason.Scam), reportId = 9001, now = at(6), timeZone = ist,
        )
        val submitted = assertIs<ReportSubmitState.Submitted>(sent.submit)

        assertEquals(9001, submitted.reportId)
        assertEquals(ReportLane.Human, submitted.route.lane)
        assertEquals(ReportReason.Scam, sent.reason)
    }

    @Test
    fun `the server row is built from the draft and invents no status or timestamp`() {
        // Section 10's audit trail is what moderators read. A client that
        // guessed a status would put a second version of the truth in the app.
        val report = ReportFlow.toReport(draft(reason = ReportReason.HateSpeech), reportId = 12)

        assertEquals(12, report.id)
        assertEquals(ReportTarget.Post, report.targetType)
        assertEquals(42, report.targetId)
        assertEquals(7, report.reporterId)
        assertEquals(ReportReason.HateSpeech, report.reason)
        assertEquals(ReportStatus.Open, report.status)
        assertEquals(null, report.createdAt)
    }

    @Test
    fun `the reasons are offered most serious first`() {
        val offered = ReportFlow.offeredReasons()

        assertEquals(ReportReason.Scam, offered.first())
        // Everything in the enum is offered — an unlisted reason is a reason
        // nobody can report, and the whole screen is the point of the flow.
        assertEquals(ReportReason.entries.toSet(), offered.toSet())
        // The ones a person handles come before the queue, so a hurried
        // reporter picks correctly by position.
        assertTrue(
            offered.indexOf(ReportReason.Scam) < offered.indexOf(ReportReason.Spam)
        )
    }

    // ----------------------------------------------------------------- strikes --

    @Test
    fun `the strike ladder is warning, pause, longer pause, removal`() {
        // Section 15's four rungs.
        assertEquals(ReportStrikes.Level.Warning, ReportStrikes.levelFor(1, ReportReason.Spam))
        assertEquals(ReportStrikes.Level.ShortPause, ReportStrikes.levelFor(2, ReportReason.Spam))
        assertEquals(ReportStrikes.Level.LongPause, ReportStrikes.levelFor(3, ReportReason.Spam))
        assertEquals(ReportStrikes.Level.Removal, ReportStrikes.levelFor(4, ReportReason.Spam))
        assertEquals(7, ReportStrikes.Level.ShortPause.suspensionDays)
        assertEquals(30, ReportStrikes.Level.LongPause.suspensionDays)
    }

    @Test
    fun `a scam skips the ladder entirely`() {
        // Section 15: "scams mean immediate removal". The cost of being slow is
        // a pensioner who has just been defrauded; the cost of being wrong is a
        // week of somebody's life online.
        assertEquals(ReportStrikes.Level.Removal, ReportStrikes.levelFor(1, ReportReason.Scam))
    }

    @Test
    fun `three upheld reports stop somebody hosting a room for thirty days`() {
        // Section 6.7, stated flatly and not hedged by the scam rule.
        assertFalse(ReportStrikes.hostingBannedFor(2))
        assertTrue(ReportStrikes.hostingBannedFor(3))
        assertTrue(ReportStrikes.hostingBannedFor(9))
        assertEquals(3, ReportStrikes.HOSTING_BAN_AFTER)
        assertEquals(30, ReportStrikes.HOSTING_BAN_DAYS)
    }

    @Test
    fun `a moderator decision maps to a report status`() {
        assertEquals(ReportStatus.Actioned, ReportStrikes.statusFor(upheld = true))
        assertEquals(ReportStatus.Dismissed, ReportStrikes.statusFor(upheld = false))
    }

    @Test
    fun `an unknown strike level falls back to the mildest one`() {
        assertEquals(ReportStrikes.Level.Warning, ReportStrikes.Level.fromCode("something_new"))
        assertEquals(ReportStrikes.Level.Warning, ReportStrikes.Level.fromCode(null))
    }

    @Test
    fun `a promise that would land exactly on closing time rolls to the morning`() {
        // 19:30 in Kolkata, plus the two hours section 15 promises, is 21:30 —
        // inside the desk's day, so it is kept. Half an hour later the same
        // promise ends at 22:00, which is closing time and not a moment the
        // desk is open, so the clock restarts at seven.
        val justInside = ReportRouter.route(ReportReason.Scam, at(14, 0), ist)
        assertFalse(justInside.rolledToNextDay)
        assertEquals(Instant.parse("2026-10-14T16:00:00Z"), justInside.responseDueBy)

        val onTheLine = ReportRouter.route(ReportReason.Scam, at(14, 30), ist)
        assertTrue(onTheLine.rolledToNextDay)
        assertEquals(Instant.parse("2026-10-15T03:30:00Z"), onTheLine.responseDueBy)
    }

    @Test
    fun `the next desk opening is always strictly in the future`() {
        // One minute before seven in Kolkata, the desk opens in a minute.
        assertEquals(
            Instant.parse("2026-10-14T01:30:00Z"),
            ReportRouter.nextDeskOpen(at(1, 29), ist),
        )
        // One minute after seven, it is already open, so the next opening is
        // tomorrow morning. Asking for an opening in the past would promise a
        // response that has already been missed.
        assertEquals(
            Instant.parse("2026-10-15T01:30:00Z"),
            ReportRouter.nextDeskOpen(at(1, 31), ist),
        )
    }
}
