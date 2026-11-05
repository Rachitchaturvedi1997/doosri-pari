package `in`.doosripari.feature.checkin

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The escalation timings from plan section 6.3, checked with a fixed clock.
 *
 * Everything runs in UTC so the assertions read as plain times. The engine
 * takes the timezone from [CheckInSettings], which also covers the fact that a
 * check-in window is a *local* window, not a UTC one.
 */
class EscalationEngineTest {

    private val utc = TimeZone.of("UTC")
    private val day = LocalDate(2026, 10, 14)
    private val settings = CheckInSettings(timeZone = utc)

    /** 09:00 UTC — the moment the default window opens. */
    private val opens: Instant = Instant.parse("2026-10-14T09:00:00Z")

    /** 12:00 UTC — window start plus the default three hours. */
    private val closes: Instant = Instant.parse("2026-10-14T12:00:00Z")

    private fun mins(n: Int): Duration = n.minutes
    private fun hrs(n: Int): Duration = n.hours
    private fun days(n: Int): Duration = n.days

    private fun recordAt(
        stage: CheckInStage,
        enteredAt: Instant = opens,
        callAttempts: Int = 0,
        lastCallAt: Instant? = null,
    ) = CheckInRecord(
        day = day,
        stage = stage,
        stageEnteredAt = enteredAt,
        callAttempts = callAttempts,
        lastCallAt = lastCallAt,
    )

    // ------------------------------------------------------------ window maths --

    @Test
    fun `window opens at 0900 and closes three hours later by default`() {
        assertEquals(LocalTime(9, 0), settings.windowStart)
        assertEquals(180, settings.windowMinutes)
        assertEquals(opens, settings.windowOpensAt(day))
        assertEquals(closes, settings.windowClosesAt(day))
    }

    @Test
    fun `the window follows the user's timezone, not UTC`() {
        val ist = CheckInSettings(timeZone = TimeZone.of("Asia/Kolkata"))
        // 09:00 in Kolkata is 03:30 UTC.
        assertEquals(Instant.parse("2026-10-14T03:30:00Z"), ist.windowOpensAt(day))
        assertEquals(Instant.parse("2026-10-14T06:30:00Z"), ist.windowClosesAt(day))
    }

    @Test
    fun `settings reject a window, guardian or helper count out of range`() {
        assertFailsWith<IllegalArgumentException> { CheckInSettings(windowMinutes = 0) }
        assertFailsWith<IllegalArgumentException> { CheckInSettings(windowMinutes = 1_440 + 1) }
        assertFailsWith<IllegalArgumentException> { CheckInSettings(guardianCount = 6) }
        assertFailsWith<IllegalArgumentException> { CheckInSettings(helperCount = 4) }
    }

    // ---------------------------------------------------------------- Waiting --

    @Test
    fun `before the window opens nothing happens`() {
        val r = recordAt(CheckInStage.Waiting, enteredAt = Instant.parse("2026-10-14T00:00:00Z"))
        val decision = EscalationEngine.advance(Instant.parse("2026-10-14T06:00:00Z"), settings, r)
        assertIs<CheckInDecision.Pending>(decision)
        assertEquals(opens, decision.nextChangeAt)
    }

    @Test
    fun `one hour into the window with no check-in the user is reminded`() {
        val r = recordAt(CheckInStage.Waiting, enteredAt = opens)
        val at = opens.plus(hrs(1))
        assertEquals(
            CheckInDecision.Advance(
                CheckInStage.Waiting, CheckInStage.Reminded,
                EscalationAction.RemindUser, at,
            ),
            EscalationEngine.advance(at, settings, r),
        )
    }

    @Test
    fun `inside the first hour the user is not reminded yet`() {
        val r = recordAt(CheckInStage.Waiting, enteredAt = opens)
        val decision = EscalationEngine.advance(opens.plus(mins(59)), settings, r)
        assertIs<CheckInDecision.Pending>(decision)
        assertEquals(opens.plus(hrs(1)), decision.nextChangeAt)
    }

    // --------------------------------------------------------------- Reminded --

    @Test
    fun `the window closing unanswered starts the automated calls`() {
        val r = recordAt(CheckInStage.Reminded, enteredAt = opens.plus(hrs(1)))
        assertEquals(
            CheckInDecision.Advance(
                CheckInStage.Reminded, CheckInStage.Called,
                EscalationAction.PlaceIvrCall, closes,
            ),
            EscalationEngine.advance(closes, settings, r),
        )
    }

    @Test
    fun `a reminded user is left alone until the window closes`() {
        val r = recordAt(CheckInStage.Reminded, enteredAt = opens.plus(hrs(1)))
        val decision = EscalationEngine.advance(closes.minus(hrs(1)), settings, r)
        assertIs<CheckInDecision.Pending>(decision)
        assertEquals(closes, decision.nextChangeAt)
    }

    // ----------------------------------------------------------------- Called --

    @Test
    fun `the first automated call is placed straight away`() {
        val r = recordAt(CheckInStage.Called, enteredAt = closes, callAttempts = 0)
        val decision = EscalationEngine.advance(closes, settings, r)
        assertIs<CheckInDecision.Advance>(decision)
        assertEquals(CheckInStage.Called, decision.to)
        assertEquals(EscalationAction.PlaceIvrCall, decision.action)
    }

    @Test
    fun `the second call waits the ten minute gap`() {
        val first = closes.plus(mins(2))
        val r = recordAt(CheckInStage.Called, enteredAt = closes, callAttempts = 1, lastCallAt = first)

        val tooSoon = EscalationEngine.advance(first.plus(mins(9)), settings, r)
        assertIs<CheckInDecision.Pending>(tooSoon)
        assertEquals(first.plus(mins(10)), tooSoon.nextChangeAt)

        val ready = EscalationEngine.advance(first.plus(mins(10)), settings, r)
        assertIs<CheckInDecision.Advance>(ready)
        assertEquals(EscalationAction.PlaceIvrCall, ready.action)
    }

    @Test
    fun `guardians are told only after both calls go unanswered for ten minutes`() {
        val second = closes.plus(mins(12))
        val r = recordAt(
            stage = CheckInStage.Called, enteredAt = closes,
            callAttempts = 2, lastCallAt = second,
        )

        assertIs<CheckInDecision.Pending>(EscalationEngine.advance(second.plus(mins(9)), settings, r))

        val at = second.plus(mins(10))
        assertEquals(
            CheckInDecision.Advance(
                CheckInStage.Called, CheckInStage.GuardiansAlerted,
                EscalationAction.AlertGuardians, at,
            ),
            EscalationEngine.advance(at, settings, r),
        )
    }

    // ------------------------------------------------------- GuardiansAlerted --

    @Test
    fun `helpers are told when no guardian acknowledges within an hour`() {
        val alerted = closes.plus(mins(22))
        val r = recordAt(CheckInStage.GuardiansAlerted, enteredAt = alerted)

        val before = EscalationEngine.advance(alerted.plus(mins(59)), settings, r)
        assertIs<CheckInDecision.Pending>(before)
        assertEquals(alerted.plus(mins(60)), before.nextChangeAt)

        val at = alerted.plus(mins(60))
        assertEquals(
            CheckInDecision.Advance(
                CheckInStage.GuardiansAlerted, CheckInStage.HelpersAlerted,
                EscalationAction.AlertHelpers, at,
            ),
            EscalationEngine.advance(at, settings, r),
        )
    }

    @Test
    fun `once helpers are told the machine only waits for a person`() {
        val r = recordAt(CheckInStage.HelpersAlerted, enteredAt = closes.plus(mins(82)))
        val decision = EscalationEngine.advance(closes.plus(hrs(5)), settings, r)
        assertIs<CheckInDecision.Pending>(decision)
        assertNull(decision.nextChangeAt)
    }

    // ---------------------------------------------------------------- terminal --

    @Test
    fun `a terminal stage never moves again`() {
        for (stage in listOf(CheckInStage.CheckedIn, CheckInStage.Resolved)) {
            val r = recordAt(stage, enteredAt = opens)
            assertEquals(
                CheckInDecision.NoChange,
                EscalationEngine.advance(closes.plus(hrs(8)), settings, r),
                "$stage must be terminal",
            )
        }
    }

    // ------------------------------------------------------------------- pause --

    @Test
    fun `a paused user is never reminded, called or alerted`() {
        val paused = CheckInSettings(timeZone = utc, pausedUntil = opens.plus(days(30)))
        for (stage in listOf(
            CheckInStage.Waiting, CheckInStage.Reminded, CheckInStage.Called,
            CheckInStage.GuardiansAlerted, CheckInStage.HelpersAlerted,
        )) {
            val r = recordAt(stage, enteredAt = opens, callAttempts = 2, lastCallAt = opens)
            assertEquals(
                CheckInDecision.NoChange,
                EscalationEngine.advance(opens.plus(hrs(10)), paused, r),
                "must not escalate while paused, from $stage",
            )
        }
    }

    @Test
    fun `the pause runs out and escalation resumes`() {
        val resume = opens.plus(hrs(1))
        val paused = CheckInSettings(timeZone = utc, pausedUntil = resume)
        val r = recordAt(CheckInStage.Waiting, enteredAt = opens)

        assertEquals(
            CheckInDecision.NoChange,
            EscalationEngine.advance(resume.minus(1.milliseconds), paused, r),
        )
        assertIs<CheckInDecision.Advance>(EscalationEngine.advance(resume, paused, r))
    }

    // --------------------------------------------------------------- check-ins --

    @Test
    fun `a tapped check-in is terminal and tells everyone all fine`() {
        val r = recordAt(CheckInStage.Waiting, enteredAt = opens)
        val at = opens.plus(mins(10))
        val done = EscalationEngine.checkIn(at, r, CheckInMethod.Tapped)

        assertEquals(CheckInStage.CheckedIn, done.stage)
        val last = done.events.last()
        assertEquals(EscalationAction.NotifyAllFine, last.action)
        assertEquals(CheckInStage.CheckedIn, last.stage)

        assertEquals(
            CheckInDecision.NoChange,
            EscalationEngine.advance(closes.plus(hrs(8)), settings, done),
        )
    }

    @Test
    fun `a late check-in after guardians were told still lands and notifies`() {
        val alerted = closes.plus(mins(22))
        val r = recordAt(CheckInStage.GuardiansAlerted, enteredAt = alerted)
        val done = EscalationEngine.checkIn(alerted.plus(mins(5)), r, CheckInMethod.Passive)

        assertEquals(CheckInStage.CheckedIn, done.stage)
        assertEquals(EscalationAction.NotifyAllFine, done.events.last().action)
    }

    @Test
    fun `answering the automated call and pressing 1 counts as a check-in`() {
        val r = recordAt(CheckInStage.Called, enteredAt = closes, callAttempts = 1)
        val done = EscalationEngine.checkIn(closes.plus(mins(1)), r, CheckInMethod.Ivr)
        assertEquals(CheckInStage.CheckedIn, done.stage)
    }

    @Test
    fun `a guardian reaching the user resolves the alert`() {
        val r = recordAt(CheckInStage.GuardiansAlerted, enteredAt = closes)
        val done = EscalationEngine.resolve(closes.plus(hrs(1)), r, "reached by daughter")
        assertEquals(CheckInStage.Resolved, done.stage)
        assertEquals("reached by daughter", done.events.last().note)
    }

    // ------------------------------------------------------------- idempotence --

    @Test
    fun `asking twice at the same instant does not escalate twice`() {
        val r = recordAt(CheckInStage.Waiting, enteredAt = opens)
        val tick = opens.plus(hrs(1))

        val first = EscalationEngine.advance(tick, settings, r)
        assertIs<CheckInDecision.Advance>(first)

        // The job applies the decision and writes the new stage in, then the
        // next tick must be quiet. This is what keeps an every-minute job from
        // reminding everyone on every minute.
        val applied = r.copy(stage = first.to, stageEnteredAt = first.at)
        val second = EscalationEngine.advance(tick, settings, applied)
        assertIs<CheckInDecision.Pending>(second)
        // Reminded does not get a second hour. It waits out the rest of the
        // window, and the calls start when the window closes.
        assertEquals(settings.windowClosesAt(r.day), second.nextChangeAt)
    }

    // ------------------------------------------------------------------ events --

    @Test
    fun `openRecord starts the day in Waiting with a logged first event`() {
        val r = EscalationEngine.openRecord(day, opens)
        assertEquals(CheckInStage.Waiting, r.stage)
        assertEquals(opens, r.stageEnteredAt)
        assertEquals(1, r.events.size)
        assertEquals(CheckInStage.Waiting, r.events.single().stage)
    }

    @Test
    fun `every escalation appends to the audit trail rather than replacing it`() {
        val start = recordAt(CheckInStage.Waiting, enteredAt = opens)

        val reminded = EscalationEngine.advance(opens.plus(hrs(1)), settings, start)
        assertIs<CheckInDecision.Advance>(reminded)
        val afterRemind = start.copy(stage = reminded.to, stageEnteredAt = reminded.at)

        val called = EscalationEngine.advance(closes, settings, afterRemind)
        assertIs<CheckInDecision.Advance>(called)
        val afterCall = afterRemind.copy(stage = called.to, stageEnteredAt = called.at)

        val done = EscalationEngine.checkIn(closes.plus(hrs(1)), afterCall, CheckInMethod.Tapped)
        assertTrue(done.events.size > afterCall.events.size)
        assertEquals(CheckInStage.CheckedIn, done.events.last().stage)
    }
}
