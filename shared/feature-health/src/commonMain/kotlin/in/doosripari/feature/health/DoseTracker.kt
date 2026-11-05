package `in`.doosripari.feature.health

import `in`.doosripari.core.model.DoseLog
import `in`.doosripari.core.model.DoseStatus
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The state machine for a single dose, and the two-miss guardian rule.
 * Plan sections 6.10 and 12.
 *
 * ```
 *                 ┌───── taken (the user, or a scan)  ─── terminal
 *   due ──────────┼───── skipped (deliberate)          ─── terminal
 *                 └───── missed  ◀── the grace window closed with nothing
 *                          │
 *                          ├── snoozed: a terminal missed row that a new
 *                          │   un-taken row replaced. The miss is already
 *                          │   counted; the snooze is a *new* pending dose,
 *                          │   not a retraction.
 *                          └── checked: a Taken or Skipped arrived  ─── reset
 * ```
 *
 * Pure functions over [DoseLog]. The `dose_followup` job (plan section 12,
 * every five minutes) applies the answers, and both apps call the same code to
 * draw the Today card, so a phone with no network and a job with a clock reach
 * the same answer from the same rows.
 *
 * ## Why the state machine is three states and not a boolean
 *
 * A boolean "taken" cannot distinguish a tablet swallowed at 8:02 from one
 * deliberately left on the table because the user has decided, with a doctor or
 * with themselves, that tonight is different. The second is not a failure. The
 * whole guardian feature rests on not confusing the two, so the state is a
 * closed set of three and [DoseStatus.Skipped] is never quietly rewritten into
 * [DoseStatus.Missed] by a background job.
 */
object DoseTracker {

    /**
     * How long a dose may sit unclaimed before it becomes a miss.
     *
     * Sixty minutes. Long enough that somebody who was out walking, or who had
     * the phone in another room, comes back and still sees an open dose rather
     * than a red one. Short enough that a guardian is not told on Monday about
     * something the user could still have taken on Monday morning. A shorter
     * window manufactures misses; a longer one delays the one alert that matters.
     */
    val GRACE: Duration = 60.minutes

    /**
     * "Later (15 min)", from plan section 6.10. Fifteen minutes is about one
     * television advert or one chai, which is the length of wait a 70-year-old
     * will actually put up with before either taking the tablet or giving up.
     */
    val SNOOZE: Duration = 15.minutes

    /**
     * Misses in a row before a guardian is told. Plan section 6.10 ("the
     * guardian is told after 2 misses in a row, if allowed") and section 12
     * ("alerts guardians after 2 misses if allowed").
     *
     * Two and not one: a single missed tablet is a large share of ordinary life
     * — a wedding, a train, a Tuesday. One alert per fortnight trains a family
     * to ignore the alert that matters. Two in a row is the point at which
     * "forgot" and "something is wrong" stop looking the same.
     */
    const val MISS_ALERT_THRESHOLD: Int = 2

    // ------------------------------------------------------------------ transitions --

    /**
     * The user took it, or a strip was scanned. Terminal.
     *
     * [takenAt] is separate from [at] on purpose: the row's own [DoseLog.dueAt]
     * is the schedule, and a dose taken late on purpose at 9:10 for an 8:00
     * tablet is a dose taken at 9:10, not at 8:00. Falsifying the taken time to
     * make the history look tidy is how a log stops being worth reading.
     */
    fun markTaken(log: DoseLog, at: Instant): DoseLog =
        log.copy(status = DoseStatus.Taken, takenAt = at)

    /**
     * The user deliberately is not taking it. Terminal, and never a miss.
     *
     * There is no reason field on [DoseLog] and this does not invent one: a
     * missed-then-skipped pair still tells the story through the event log the
     * caller writes, and putting a free-text "why did you skip your insulin"
     * question in front of a 70-year-old is not this module's business.
     */
    fun markSkipped(log: DoseLog): DoseLog =
        log.copy(status = DoseStatus.Skipped, takenAt = null)

    /**
     * Push an open dose back by [by] (default [SNOOZE]). Plan section 6.10's
     * "Later (15 min)".
     *
     * **Only an open dose can be snoozed.** A dose whose grace window has
     * already closed is a settled miss, and there is no version of "later" that
     * undoes one: letting a missed row be pushed forward would let a stray tap,
     * or a bug, erase a real miss from a guardian's count. The screen offers
     * "Take now" and "Skip" on a missed row instead, and this returns the row
     * unchanged so that a caller which does the wrong thing fails visibly
     * rather than quietly rewriting the history.
     */
    fun snooze(log: DoseLog, now: Instant, by: Duration = SNOOZE): DoseLog {
        if (log.status != DoseStatus.Missed || log.takenAt != null) return log
        if (now >= log.dueAt.plus(GRACE)) return log
        return log.copy(dueAt = log.dueAt.plus(by), takenAt = null)
    }

    /**
     * The window closed. Called from the `dose_followup` job rather than
     * guessed, so that the two agree.
     *
     * A row that is already [DoseStatus.Taken] or [DoseStatus.Skipped] is
     * returned untouched. A background job that could flip a taken dose to
     * missed would be a bug that erases the one fact the user gave us.
     */
    fun settle(log: DoseLog, now: Instant): DoseLog {
        if (log.status == DoseStatus.Taken || log.status == DoseStatus.Skipped) return log
        if (log.takenAt != null) return log
        if (now < log.dueAt.plus(GRACE)) return log
        return log.copy(status = DoseStatus.Missed, takenAt = null)
    }

    // ------------------------------------------------------------------ the counter --

    /**
     * Misses in a row for one medicine, counting back from the latest row.
     *
     * The walk stops at the first row that is not a settled miss. A [Taken] is
     * a reset — the counter is "misses in a row" from section 6.10, not "misses
     * this month". A [Skipped] also stops the walk and does not count itself:
     * a user who has deliberately stopped a medicine is not a user whose
     * guardian should be telephoned, and counting the skip would put a
     * deliberate decision on the other side of a phone call.
     *
     * An **open** row — one still inside its grace window — stops the walk
     * without counting, because it has not been decided yet. A chain of one miss
     * followed by a dose that is still takeable is not two misses.
     *
     * [rows] may be given in any order; they are sorted by due time first.
     */
    fun consecutiveMisses(rows: List<DoseLog>, at: Instant): Int {
        val settled = rows.sortedBy { it.dueAt }
        var count = 0
        for (row in settled) {
            if (row.status == DoseStatus.Taken) break
            if (row.status == DoseStatus.Skipped) break
            if (row.takenAt != null) break
            if (row.status != DoseStatus.Missed) break
            if (at < row.dueAt.plus(GRACE)) break
            count++
        }
        return count
    }

    /**
     * The alert the `dose_followup` job dispatches, or null.
     *
     * Returns null when nothing is due, and — just as important — returns null
     * again on every subsequent run once the alert has been raised, because the
     * counter only *reaches* the threshold on the transition. The job runs every
     * five minutes; an alert that re-fired on every one of them would be the
     * fastest way to have a family switch the app off.
     *
     * @param previousMissCount the count already alerted on for this medicine,
     *   from the caller's own record. Zero when nothing has been sent.
     * @param sharingAllowed the user's separate consent for guardian health
     *   sharing (plan section 14). Without it the counter still runs and the
     *   Today card still says two doses were missed; only the telephone call
     *   is withheld. A user who declined sharing must not be re-prompted on
     *   every miss.
     */
    fun guardianAlert(
        rows: List<DoseLog>,
        at: Instant,
        sharingAllowed: Boolean,
        threshold: Int = MISS_ALERT_THRESHOLD,
        previousMissCount: Int = 0,
    ): GuardianAlert? {
        if (rows.isEmpty()) return null
        val misses = consecutiveMisses(rows, at)
        if (misses < threshold) return null
        // Strictly greater than what was last told: the threshold is crossed
        // once. A fourth miss in a row does not telephone anybody again; the
        // second one already did, and the user has not stopped missing them.
        if (previousMissCount >= misses) return null
        if (!sharingAllowed) return null

        val medicineId = rows.first().medicineId
        val name = rows.firstOrNull { it.medicineName.isNotBlank() }?.medicineName
        val missed = rows
            .sortedBy { it.dueAt }
            .filter { it.status == DoseStatus.Missed }
            .takeLast(misses)
        return GuardianAlert(
            medicineId = medicineId,
            medicineName = name,
            missedDoses = missed,
            at = at,
            missCount = misses,
        )
    }

    /**
     * The same rule with the consent check left to the caller.
     *
     * The consent lives in [GuardianAlertPolicy] rather than in a parameter so
     * that the *rule* — two misses, only misses, reset on a check-in — and the
     * *permission* — has this user agreed to be telephoned about medicines —
     * can be tested separately. They are separate decisions and they change for
     * separate reasons.
     */
    fun guardianAlert(
        rows: List<DoseLog>,
        at: Instant,
        policy: GuardianAlertPolicy,
        previousMissCount: Int = 0,
    ): GuardianAlert? = guardianAlert(
        rows = rows,
        at = at,
        sharingAllowed = policy.sharingAllowed,
        threshold = policy.threshold,
        previousMissCount = previousMissCount,
    )
}

/**
 * Whether this user has agreed to be told about missed medicines.
 *
 * Plan section 14 requires *separate* consent for health data and for guardian
 * sharing, so this is its own flag and it defaults to false. Defaulting it to
 * true would be the kind of quiet default that gets an app removed from a
 * store and gets a son-in-law an unwelcome phone call.
 */
data class GuardianAlertPolicy(
    val sharingAllowed: Boolean = false,
    val threshold: Int = DoseTracker.MISS_ALERT_THRESHOLD,
) {
    init {
        require(threshold >= 1) { "an alert threshold below 1 would never fire" }
    }
}

/**
 * What a guardian is told, and nothing more.
 *
 * The list of missed rows is carried rather than a rendered sentence so that the
 * caller can put it into a language it already has a catalogue for, and so that
 * the server can tell the difference between "two doses at 8:00 and 20:00" and
 * "one dose at 8:00" when the user says the alert was wrong.
 */
data class GuardianAlert(
    val medicineId: Int,
    val medicineName: String?,
    val missedDoses: List<DoseLog>,
    val at: Instant,
    val missCount: Int,
) {
    val firstMissedAt: Instant? get() = missedDoses.minOfOrNull { it.dueAt }
    val lastMissedAt: Instant? get() = missedDoses.maxOfOrNull { it.dueAt }
}
