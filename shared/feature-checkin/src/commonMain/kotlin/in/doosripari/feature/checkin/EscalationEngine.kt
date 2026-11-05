package `in`.doosripari.feature.checkin

import kotlinx.datetime.Instant

/**
 * The check-in state machine, as a pure function.
 *
 * This is the piece plan section 21 calls out as a top risk ("a missed check-in
 * alert fails"), so it is deliberately side-effect free: it takes the current
 * record and the time, and returns what should happen. The `checkin_escalate`
 * job applies the answer and writes the event log. The Android and iOS apps
 * call the same code to show a live countdown without having to be awake at
 * exactly the right minute.
 *
 * Being pure also makes the timing rules testable with a fake clock, which plan
 * section 20 asks for.
 */
object EscalationEngine {

    /**
     * Decide what should happen to [record] at [now].
     *
     * Idempotent: calling it twice with the same [now] and record returns
     * [CheckInDecision.NoChange] the second time, because the caller applies
     * the result before asking again. The job runs every minute against every
     * open row, so this is the property that keeps it from paging everyone.
     */
    fun advance(
        now: Instant,
        settings: CheckInSettings,
        record: CheckInRecord,
    ): CheckInDecision {
        // A finished day never moves again. This also covers a late check-in
        // that lands after guardians were already told.
        if (record.stage.isTerminal) return CheckInDecision.NoChange

        // Travel or hospital: no reminders, no calls, no alerts.
        if (settings.isPausedAt(now)) return CheckInDecision.NoChange

        return when (record.stage) {
            CheckInStage.Waiting -> advanceFromWaiting(now, settings, record)
            CheckInStage.Reminded -> advanceFromReminded(now, settings, record)
            CheckInStage.Called -> advanceFromCalled(now, record)
            CheckInStage.GuardiansAlerted ->
                advanceFromGuardiansAlerted(now, record)
            CheckInStage.HelpersAlerted ->
                // Nothing left but a helper confirming in person, which is a
                // user action, not a timeout.
                pending(record, nextChangeAt = null)

            CheckInStage.CheckedIn, CheckInStage.Resolved -> CheckInDecision.NoChange
        }
    }

    /**
     * Record a check-in. This is the only path into the terminal [CheckInStage.CheckedIn]
     * state, and it is the one that tells guardians "all fine" — including a
     * late check-in, which plan section 6.3 requires to reach guardians
     * immediately rather than at the next stage boundary.
     */
    fun checkIn(
        now: Instant,
        record: CheckInRecord,
        method: CheckInMethod,
    ): CheckInRecord = record.copy(
        stage = CheckInStage.CheckedIn,
        stageEnteredAt = now,
        events = record.events + CheckInEvent(
            at = now,
            stage = CheckInStage.CheckedIn,
            action = EscalationAction.NotifyAllFine,
            note = "checked in via ${method.name.lowercase()}",
        ),
    )

    /**
     * Close an alert because somebody reached the user. A guardian tapping
     * "I've reached them", or a helper confirming in person.
     */
    fun resolve(now: Instant, record: CheckInRecord, note: String? = null): CheckInRecord =
        record.copy(
            stage = CheckInStage.Resolved,
            stageEnteredAt = now,
            events = record.events + CheckInEvent(
                at = now,
                stage = CheckInStage.Resolved,
                note = note,
            ),
        )

    // ---------------------------------------------------------------- Waiting --

    private fun advanceFromWaiting(
        now: Instant,
        settings: CheckInSettings,
        record: CheckInRecord,
    ): CheckInDecision {
        val closes = settings.windowClosesAt(record.day)

        // Before the window even opens there is nothing to do. Reaching this
        // means the job ran early; the checkin_open job owns creating the row.
        if (now < settings.windowOpensAt(record.day)) {
            return pending(record, settings.windowOpensAt(record.day))
        }

        val remind = record.stageEnteredAt.plus(CheckInSettings.REMIND_AFTER)
        if (now < remind) {
            return pending(record, if (closes < remind) closes else remind)
        }

        return advanceTo(
            record, from = CheckInStage.Waiting, to = CheckInStage.Reminded,
            action = EscalationAction.RemindUser, at = now,
        )
    }

    // --------------------------------------------------------------- Reminded --

    private fun advanceFromReminded(
        now: Instant,
        settings: CheckInSettings,
        record: CheckInRecord,
    ): CheckInDecision {
        val closes = settings.windowClosesAt(record.day)
        if (now < closes) {
            return pending(record, closes)
        }

        return advanceTo(
            record, from = CheckInStage.Reminded, to = CheckInStage.Called,
            action = EscalationAction.PlaceIvrCall, at = now,
        )
    }

    // ----------------------------------------------------------------- Called --

    private fun advanceFromCalled(
        now: Instant,
        record: CheckInRecord,
    ): CheckInDecision {
        // No answer after both attempts → guardians. The plan puts a 10-minute
        // gap between the two calls, so we only alert once that gap has passed
        // and the attempts are spent.
        if (record.callAttempts < CheckInSettings.IVR_ATTEMPTS) {
            val last = record.lastCallAt
            if (last == null) {
                return advanceTo(
                    record, from = CheckInStage.Called, to = CheckInStage.Called,
                    action = EscalationAction.PlaceIvrCall, at = now,
                )
            }
            val retry = last.plus(CheckInSettings.IVR_RETRY_GAP)
            if (now < retry) return pending(record, retry)

            return advanceTo(
                record, from = CheckInStage.Called, to = CheckInStage.Called,
                action = EscalationAction.PlaceIvrCall, at = now,
            )
        }

        val last = record.lastCallAt
        if (last == null) {
            // Attempts are marked spent but the timestamp is missing; treat the
            // gap as satisfied rather than stalling the alert forever.
            return advanceTo(
                record, from = CheckInStage.Called, to = CheckInStage.GuardiansAlerted,
                action = EscalationAction.AlertGuardians, at = now,
            )
        }

        val afterGap = last.plus(CheckInSettings.IVR_RETRY_GAP)
        if (now < afterGap) return pending(record, afterGap)

        return advanceTo(
            record, from = CheckInStage.Called, to = CheckInStage.GuardiansAlerted,
            action = EscalationAction.AlertGuardians, at = now,
        )
    }

    // ------------------------------------------------------- GuardiansAlerted --

    private fun advanceFromGuardiansAlerted(
        now: Instant,
        record: CheckInRecord,
    ): CheckInDecision {
        val escalate = record.stageEnteredAt.plus(CheckInSettings.HELPER_ESCALATION_AFTER)
        if (now < escalate) return pending(record, escalate)

        return advanceTo(
            record, from = CheckInStage.GuardiansAlerted, to = CheckInStage.HelpersAlerted,
            action = EscalationAction.AlertHelpers, at = now,
        )
    }

    // ------------------------------------------------------------------ util --

    private fun advanceTo(
        record: CheckInRecord,
        from: CheckInStage,
        to: CheckInStage,
        action: EscalationAction,
        at: Instant,
    ): CheckInDecision = CheckInDecision.Advance(from, to, action, at)

    private fun pending(record: CheckInRecord, nextChangeAt: Instant?): CheckInDecision =
        CheckInDecision.Pending(record, nextChangeAt)

    /**
     * The row the `checkin_open` job creates when a window opens, so both the
     * job and the app agree on where a day starts.
     */
    fun openRecord(day: kotlinx.datetime.LocalDate, at: Instant): CheckInRecord =
        CheckInRecord(
            day = day,
            stage = CheckInStage.Waiting,
            stageEnteredAt = at,
            events = listOf(CheckInEvent(at = at, stage = CheckInStage.Waiting)),
        )
}
