package `in`.doosripari.feature.health

import `in`.doosripari.core.model.DoseLog
import `in`.doosripari.core.model.DoseStatus
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The two-miss guardian rule, plan section 6.10 and section 12.
 *
 * "After 2 misses in a row, if allowed." Three words in that sentence do all the
 * work: **2**, **in a row**, and **if allowed**. Each has its own test below,
 * and the tests that are most likely to catch a real regression are the ones
 * about what does *not* count — because a rule that is too eager telephones
 * somebody's husband, and a rule that is too lazy lets a person get worse
 * unnoticed.
 */
class DoseTrackerTest {

    /** 14 October 2026, 08:00 UTC. A dose due at this time. */
    private val eightAm = Instant.parse("2026-10-14T08:00:00Z")
    private val eightPm = Instant.parse("2026-10-14T20:00:00Z")
    private val afterGrace = eightAm + DoseTracker.GRACE + 1.minutes

    private fun dose(
        id: Int,
        dueAt: Instant,
        status: DoseStatus = DoseStatus.Missed,
        takenAt: Instant? = null,
    ) = DoseLog(
        id = id,
        medicineId = 1,
        medicineName = "Metformin",
        dueAt = dueAt,
        status = status,
        takenAt = takenAt,
    )

    // ---- only misses count ----

    @Test
    fun `a taken dose is not a miss`() {
        val rows = listOf(
            dose(1, eightAm, DoseStatus.Taken, takenAt = eightAm + 4.minutes),
            dose(2, eightPm, DoseStatus.Missed),
        )

        assertEquals(1, DoseTracker.consecutiveMisses(rows, eightPm + 2.hours))
    }

    @Test
    fun `a skipped dose is not a miss and it is not an either`() {
        val rows = listOf(
            dose(1, eightAm, DoseStatus.Skipped),
            dose(2, eightPm, DoseStatus.Missed),
        )

        // The walk stops at the skip: one miss in a row after it, not two. A
        // user who deliberately stopped a medicine is not a user whose husband
        // should be telephoned.
        assertEquals(1, DoseTracker.consecutiveMisses(rows, eightPm + 2.hours))
    }

    @Test
    fun `a dose still inside its grace window does not count and stops the walk`() {
        val rows = listOf(
            dose(1, eightAm),
            dose(2, eightPm),
        )

        // The first is a settled miss; the second is still takeable, so this is
        // one miss, not two.
        assertEquals(1, DoseTracker.consecutiveMisses(rows, eightPm + 10.minutes))
    }

    @Test
    fun `a status the app does not recognise stops the walk rather than counting`() {
        val rows = listOf(
            dose(1, eightAm, DoseStatus.Taken, takenAt = eightAm),
            dose(2, eightPm),
        )

        assertEquals(0, DoseTracker.consecutiveMisses(rows, eightPm + 2.hours))
    }

    // ---- the threshold ----

    @Test
    fun `one miss does not alert`() {
        val rows = listOf(dose(1, eightAm))

        assertNull(
            DoseTracker.guardianAlert(rows, afterGrace, sharingAllowed = true),
            "a single missed tablet is a large share of ordinary life",
        )
    }

    @Test
    fun `two misses in a row alert`() {
        val rows = listOf(
            dose(1, eightAm),
            dose(2, eightPm),
        )

        val alert = DoseTracker.guardianAlert(rows, eightPm + 2.hours, sharingAllowed = true)
        assertNotNull(alert, "two in a row is the point at which forgot and unwell stop looking alike")
        assertEquals(2, alert.missCount)
        assertEquals(1, alert.medicineId)
        assertEquals("Metformin", alert.medicineName)
        assertEquals(2, alert.missedDoses.size)
        assertEquals(eightAm, alert.firstMissedAt)
        assertEquals(eightPm, alert.lastMissedAt)
    }

    @Test
    fun `the alert fires once, on the crossing, and not on every later run`() {
        val rows = listOf(dose(1, eightAm), dose(2, eightPm))
        val at = eightPm + 2.hours

        val first = DoseTracker.guardianAlert(rows, at, sharingAllowed = true, previousMissCount = 0)
        assertNotNull(first)

        // The dose_followup job runs every five minutes. This is the same run,
        // told what it already told.
        val second = DoseTracker.guardianAlert(rows, at.plus(5.minutes), sharingAllowed = true, previousMissCount = 2)
        assertNull(second, "an alert that re-fires every five minutes gets the app uninstalled")
    }

    @Test
    fun `a third miss in a row does not alert again`() {
        val rows = listOf(
            dose(1, eightAm),
            dose(2, eightPm),
            dose(3, eightPm + 12.hours),
        )

        val alert = DoseTracker.guardianAlert(
            rows = rows,
            at = eightPm + 13.hours,
            sharingAllowed = true,
            previousMissCount = 2,
        )

        assertNull(alert, "the second one already did, and the user has not stopped")
    }

    // ---- the reset ----

    @Test
    fun `a check-in between two misses resets the run`() {
        val rows = listOf(
            dose(1, eightAm),
            dose(2, eightPm, DoseStatus.Taken, takenAt = eightPm + 3.minutes),
            dose(3, eightPm + 12.hours),
        )

        assertEquals(1, DoseTracker.consecutiveMisses(rows, eightPm + 13.hours))
    }

    @Test
    fun `one miss, a reset, then one miss again, does not alert`() {
        val rows = listOf(
            dose(1, eightAm),
            dose(2, eightPm, DoseStatus.Taken, takenAt = eightPm + 3.minutes),
            dose(3, eightPm + 12.hours),
        )

        assertNull(
            DoseTracker.guardianAlert(rows, eightPm + 13.hours, sharingAllowed = true),
            "the counter is misses in a row, not misses this month",
        )
    }

    @Test
    fun `the rows may be given in any order`() {
        val forwards = DoseTracker.consecutiveMisses(
            listOf(dose(1, eightAm), dose(2, eightPm)),
            eightPm + 2.hours,
        )
        val backwards = DoseTracker.consecutiveMisses(
            listOf(dose(2, eightPm), dose(1, eightAm)),
            eightPm + 2.hours,
        )

        assertEquals(forwards, backwards)
    }

    // ---- consent ----

    @Test
    fun `two misses with no consent to share alert nobody`() {
        val rows = listOf(dose(1, eightAm), dose(2, eightPm))

        assertNull(DoseTracker.guardianAlert(rows, eightPm + 2.hours, sharingAllowed = false))
    }

    @Test
    fun `a user who declined sharing is not re-prompted, because nothing is sent`() {
        val rows = listOf(dose(1, eightAm), dose(2, eightPm))
        val policy = GuardianAlertPolicy(sharingAllowed = false)

        repeat(5) {
            assertNull(DoseTracker.guardianAlert(rows, eightPm + 2.hours, policy))
        }
    }

    @Test
    fun `the policy overload behaves the same as the parameters`() {
        val rows = listOf(dose(1, eightAm), dose(2, eightPm))
        val viaPolicy = DoseTracker.guardianAlert(
            rows = rows,
            at = eightPm + 2.hours,
            policy = GuardianAlertPolicy(sharingAllowed = true),
        )
        val viaParams = DoseTracker.guardianAlert(rows, eightPm + 2.hours, sharingAllowed = true)

        assertEquals(viaParams?.missCount, viaPolicy?.missCount)
        assertEquals(viaParams?.missedDoses, viaPolicy?.missedDoses)
    }

    @Test
    fun `the threshold is explicit and can be changed without touching the rule`() {
        val rows = listOf(dose(1, eightAm), dose(2, eightPm))

        val alert = DoseTracker.guardianAlert(
            rows = rows,
            at = eightPm + 2.hours,
            policy = GuardianAlertPolicy(sharingAllowed = true, threshold = 1),
        )

        assertNotNull(alert)
        assertEquals(DoseTracker.MISS_ALERT_THRESHOLD, 2)
    }

    // ---- edge cases ----

    @Test
    fun `no rows never alerts and never throws`() {
        assertNull(DoseTracker.guardianAlert(emptyList(), eightPm, sharingAllowed = true))
    }

    @Test
    fun `an unknown status on the wire does not crash the job`() {
        val rows = listOf(
            DoseLog(id = 1, medicineId = 1, medicineName = "Metformin", dueAt = eightAm),
            DoseLog(id = 2, medicineId = 1, medicineName = "Metformin", dueAt = eightPm),
        )

        // Nothing to assert beyond "it returns"; the point is that the follow-up
        // job must not die at two in the morning over an unparseable string.
        assertEquals(2, DoseTracker.consecutiveMisses(rows, eightPm + 2.hours))
    }

    // ---- the state machine ----

    @Test
    fun `taking a dose records when it was actually taken, not when it was due`() {
        val taken = DoseTracker.markTaken(dose(1, eightAm), eightAm + 95.minutes)

        assertEquals(DoseStatus.Taken, taken.status)
        assertEquals(eightAm + 95.minutes, taken.takenAt)
        assertTrue(!taken.isOpen)
    }

    @Test
    fun `skipping clears any taken time so the two cannot both be true`() {
        val taken = DoseTracker.markTaken(dose(1, eightAm), eightAm + 2.minutes)
        val skipped = DoseTracker.markSkipped(taken)

        assertEquals(DoseStatus.Skipped, skipped.status)
        assertNull(skipped.takenAt)
    }

    @Test
    fun `settling does nothing inside the grace window`() {
        val open = dose(1, eightAm)
        assertSame(open, DoseTracker.settle(open, eightAm + 30.minutes))
    }

    @Test
    fun `settling does nothing to a dose that was already taken`() {
        val taken = DoseTracker.markTaken(dose(1, eightAm), eightAm + 2.minutes)
        assertSame(taken, DoseTracker.settle(taken, eightPm))
    }

    @Test
    fun `settling does nothing to a dose that was deliberately skipped`() {
        val skipped = DoseTracker.markSkipped(dose(1, eightAm))
        assertSame(skipped, DoseTracker.settle(skipped, eightPm))
    }

    @Test
    fun `settling an open dose past its grace marks it missed`() {
        val settled = DoseTracker.settle(dose(1, eightAm), eightPm)

        assertEquals(DoseStatus.Missed, settled.status)
        assertNull(settled.takenAt)
    }

    // ---- snoozing ----

    @Test
    fun `snoozing an open dose pushes it back by the default fifteen minutes`() {
        val snoozed = DoseTracker.snooze(dose(1, eightAm), eightAm + 5.minutes)

        assertEquals(eightAm + DoseTracker.SNOOZE, snoozed.dueAt)
        assertEquals(15, DoseTracker.SNOOZE.inWholeMinutes.toInt())
    }

    @Test
    fun `a snooze can never erase a miss that has already settled`() {
        // This is the load-bearing one. If a settled miss could be pushed back,
        // a stray tap or a bug would quietly remove a real miss from a
        // guardian's count.
        val settled = dose(1, eightAm)
        val result = DoseTracker.snooze(settled, eightPm)

        assertEquals(settled.dueAt, result.dueAt)
        assertSame(settled, result)
    }

    @Test
    fun `a taken dose cannot be snoozed back into being open`() {
        val taken = DoseTracker.markTaken(dose(1, eightAm), eightAm + 2.minutes)
        assertSame(taken, DoseTracker.snooze(taken, eightAm + 5.minutes))
    }

    @Test
    fun `snoozing repeatedly walks the dose forward until the window closes`() {
        var row = dose(1, eightAm)
        var now = eightAm
        repeat(3) {
            row = DoseTracker.snooze(row, now)
            now += DoseTracker.SNOOZE
        }

        assertEquals(eightAm + DoseTracker.SNOOZE * 3, row.dueAt)
    }

    @Test
    fun `a snoozed dose that is never taken still becomes a miss`() {
        val snoozed = DoseTracker.snooze(dose(1, eightAm), eightAm + 5.minutes)
        val settled = DoseTracker.settle(snoozed, snoozed.dueAt + DoseTracker.GRACE + 1.minutes)

        assertEquals(DoseStatus.Missed, settled.status)
        assertEquals(
            1,
            DoseTracker.consecutiveMisses(listOf(settled), settled.dueAt + 2.hours),
            "snoozing is not a retraction",
        )
    }
}
