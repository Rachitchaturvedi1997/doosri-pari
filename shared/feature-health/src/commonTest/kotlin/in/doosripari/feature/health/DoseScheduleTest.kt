package `in`.doosripari.feature.health

import `in`.doosripari.core.model.DoseLog
import `in`.doosripari.core.model.DoseStatus
import `in`.doosripari.core.model.Medicine
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four awkward cases plan section 6.10's dose list actually contains.
 *
 * Each of them is the case a naive schedule gets wrong, and three of the four
 * get it wrong in the direction of telling somebody's family they did not take
 * a tablet.
 */
class DoseScheduleTest {

    private val utc = TimeZone.of("UTC")
    private val date = LocalDate(2026, 10, 14)

    private fun at(hour: Int, minute: Int = 0): Instant =
        Instant.parse("2026-10-14T%02d:%02d:00Z".format(hour, minute))

    private fun Instant.onDayIn(zone: TimeZone) = toLocalDateTime(zone).date

    private fun medicine(
        id: Int = 1,
        times: List<LocalTime> = listOf(LocalTime(8, 0)),
        isActive: Boolean = true,
        withFood: Boolean = false,
        stock: Int = 30,
    ) = Medicine(
        id = id,
        name = "Metformin",
        dose = "500 mg",
        times = times,
        withFood = withFood,
        stock = stock,
        isActive = isActive,
    )

    // ---- a medicine added mid-day, after its own time of day has gone ----

    @Test
    fun `a medicine whose slot already passed today is marked, not silently missed`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(14, 0),
            windows = mapOf(1 to DoseWindow(firstDoseOn = date)),
        )

        val dose = plan.doses.single()
        assertEquals(DoseNote.AddedAfterTheSlotPassed, dose.note)
        assertEquals(DoseOrigin.PhoneOnly, dose.origin)
    }

    @Test
    fun `a dose nobody was ever reminded about does not count against a guardian`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(14, 0),
            windows = mapOf(1 to DoseWindow(firstDoseOn = date)),
        )

        // Two hours past an 08:00 dose, so it is well outside the grace window
        // and genuinely un-taken as far as the user is concerned.
        val summary = plan.summaryAt(at(14, 0))
        assertEquals(1, summary.missed, "the user's own card shows it as missed")
        assertEquals(0, summary.guardianCount, "but it must not telephone anybody")
        assertEquals(1, summary.unremindedMisses)
    }

    @Test
    fun `the same medicine added before its slot is an ordinary dose`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(6, 0),
            windows = mapOf(1 to DoseWindow(firstDoseOn = date)),
        )

        val dose = plan.doses.single()
        assertNull(dose.note, "the slot had not gone by, so nothing to explain")
        assertEquals(DoseOrigin.PhoneOnly, dose.origin)
        assertEquals(0, plan.summaryAt(at(6, 0)).missed)
    }

    // ---- a time that has already passed ----

    @Test
    fun `a dose inside its grace window is open, not missed`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(8, 30),
        )

        val summary = plan.summaryAt(at(8, 30))
        assertEquals(0, summary.missed, "thirty minutes late is still takeable")
        assertEquals(1, summary.open)
    }

    @Test
    fun `a dose past its grace window is a miss`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(8, 30),
        )

        val summary = plan.summaryAt(at(9, 1))
        assertEquals(1, summary.missed)
        assertEquals(0, summary.open)
    }

    @Test
    fun `a past slot stays on today and is not rolled forward`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(20, 0),
        )

        assertEquals(date, plan.doses.single().log.dueAt.onDayIn(utc))
    }

    // ---- a dose deliberately skipped ----

    @Test
    fun `a skipped dose is not a miss`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(20, 0),
        )
        val skipped = DoseTracker.markSkipped(plan.doses.single().log)
        val marked = plan.copy(doses = listOf(plan.doses.single().copy(log = skipped)))

        val summary = marked.summaryAt(at(20, 0))
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.missed, "a deliberate decision is not a failure")
        assertEquals(0, summary.guardianCount)
        assertTrue(summary.isSettled, "the day is finished; there is nothing waiting")
    }

    @Test
    fun `a skipped dose still leaves core-model's day looking incomplete`() {
        // Recorded because it is the defect DoseDaySummary exists to work
        // around, and because a future core-model fix must not silently change
        // what this module counts.
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(20, 0),
        )
        val skipped = DoseTracker.markSkipped(plan.doses.single().log)
        val day = plan.copy(doses = listOf(plan.doses.single().copy(log = skipped))).day

        assertFalse(day.isComplete, "core-model counts skipped by neither term")
        assertEquals(1, day.remaining)
    }

    // ---- an inactive medicine ----

    @Test
    fun `an inactive medicine produces no doses at all`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(isActive = false)),
            timeZone = utc,
            now = at(20, 0),
        )

        assertTrue(plan.doses.isEmpty(), "a switched-off medicine has no doses")
        assertEquals(0, plan.summaryAt(at(20, 0)).total)
    }

    @Test
    fun `an inactive medicine is explained rather than just absent`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(isActive = false)),
            timeZone = utc,
            now = at(20, 0),
        )

        val omitted = plan.omitted.single()
        assertEquals(DoseNote.InactiveMedicine, omitted.reason)
        assertTrue(omitted.reason.isWorthTelling)
    }

    @Test
    fun `an inactive medicine can never produce a guardian alert`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(isActive = false)),
            timeZone = utc,
            now = at(20, 0),
        )

        assertEquals(0, plan.summaryAt(at(20, 0)).guardianCount)
    }

    @Test
    fun `a medicine with no times saved is a half-finished form, not a schedule`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(times = emptyList())),
            timeZone = utc,
            now = at(20, 0),
        )

        assertTrue(plan.doses.isEmpty())
        assertEquals(DoseNote.NoTimesRecorded, plan.omitted.single().reason)
    }

    // ---- schedule windows ----

    @Test
    fun `a course that has not started produces nothing on an earlier date`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(20, 0),
            windows = mapOf(1 to DoseWindow(firstDoseOn = LocalDate(2026, 10, 20))),
        )

        assertTrue(plan.doses.isEmpty())
        assertEquals(DoseNote.OutsideScheduleWindow, plan.omitted.single().reason)
    }

    @Test
    fun `a finished course produces nothing after its last date`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(20, 0),
            windows = mapOf(1 to DoseWindow(lastDoseOn = LocalDate(2026, 10, 10))),
        )

        assertTrue(plan.doses.isEmpty())
    }

    // ---- merging the two copies ----

    @Test
    fun `a phone-only row is always negative so it cannot be mistaken for a server id`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(6, 0),
        )

        assertTrue(plan.doses.single().log.id < 0, "server ids are autoincrement and positive")
    }

    @Test
    fun `the same slot on the same day always produces the same local id`() {
        val first = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(6, 0))
        val second = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(6, 0))

        assertEquals(first.doses.single().log.id, second.doses.single().log.id)
    }

    @Test
    fun `a server row for the same slot is merged rather than duplicated`() {
        val server = DoseLog(
            id = 4711,
            medicineId = 1,
            medicineName = "Metformin",
            dueAt = at(8, 0),
            status = DoseStatus.Taken,
            takenAt = at(8, 4),
        )
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(9, 0),
            serverLogs = listOf(server),
        )

        val dose = plan.doses.single()
        assertEquals(4711, dose.log.id, "the server's copy is the row we keep")
        assertEquals(DoseStatus.Taken, dose.log.status)
        assertEquals(DoseOrigin.BothCopies, dose.origin)
        assertTrue(dose.origin.canCountAsRealMiss)
    }

    @Test
    fun `a server row and a phone row for one slot make one dose, not two`() {
        val server = DoseLog(id = 99, medicineId = 1, medicineName = "Metformin", dueAt = at(8, 0))
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(9, 0),
            serverLogs = listOf(server),
        )

        assertEquals(1, plan.doses.size, "neither copy may be the only copy, and neither may double it")
    }

    // ---- ordering and the day view ----

    @Test
    fun `doses come back in time order whatever order the medicines came in`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(
                medicine(id = 2, times = listOf(LocalTime(20, 0))),
                medicine(id = 1, times = listOf(LocalTime(8, 0), LocalTime(13, 0))),
            ),
            timeZone = utc,
            now = at(6, 0),
        )

        assertEquals(
            listOf(8, 13, 20),
            plan.doses.map { it.slot.hour },
        )
    }

    @Test
    fun `the day view carries the same rows`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(times = listOf(LocalTime(8, 0), LocalTime(20, 0)))),
            timeZone = utc,
            now = at(6, 0),
        )

        assertEquals(2, plan.day.doses.size)
        assertEquals(date, plan.day.date)
    }

    @Test
    fun `with food is carried from the medicine, not invented per row`() {
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine(withFood = true)),
            timeZone = utc,
            now = at(6, 0),
        )

        assertTrue(plan.doses.single().withFood)
    }

    // ---- rolling to tomorrow ----

    @Test
    fun `rolling to tomorrow settles today's row as missed rather than hiding it`() {
        val plan = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(6, 0))
        val rolled = DoseSchedule.rollToNextDay(plan.doses.single().log, utc)

        assertEquals(LocalDate(2026, 10, 15), rolled.dueAt.onDayIn(utc))
        assertEquals(DoseStatus.Missed, rolled.status)
        assertNull(rolled.takenAt)
    }

    @Test
    fun `a day in the past is distinguishable from one in the future`() {
        assertTrue(DoseSchedule.isPast(LocalDate(2026, 10, 13), at(8, 0), utc))
        assertFalse(DoseSchedule.isPast(date, at(8, 0), utc))
        assertFalse(DoseSchedule.isPast(LocalDate(2026, 10, 15), at(8, 0), utc))
    }

    @Test
    fun `a future day produces rows that can never be late yet`() {
        val plan = DoseSchedule.dayFor(
            date = LocalDate(2026, 10, 20),
            medicines = listOf(medicine()),
            timeZone = utc,
            now = at(8, 0),
        )

        assertEquals(1, plan.doses.size)
        assertEquals(0, plan.summaryAt(at(8, 0)).missed)
    }

    @Test
    fun `without a clock no slot is judged as already passed`() {
        // A month view of a past month has no "now". Guessing that a slot had
        // gone by would mark doses the user may well have taken, and a marked
        // dose is the thing that reaches a guardian.
        val plan = DoseSchedule.dayFor(
            date = date,
            medicines = listOf(medicine()),
            timeZone = utc,
            now = null,
            windows = mapOf(1 to DoseWindow(firstDoseOn = date)),
        )

        assertNull(plan.doses.single().note)
    }

    @Test
    fun `a dose asked about directly at 8pm is a miss`() {
        val plan = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(8, 0))

        assertEquals(1, plan.summaryAt(at(20, 0)).missed)
    }

    // ---- enum fallbacks ----

    @Test
    fun `an unknown origin falls back to the one that cannot alert anybody`() {
        assertEquals(DoseOrigin.PhoneOnly, DoseOrigin.fromCode("something_new"))
        assertEquals(DoseOrigin.PhoneOnly, DoseOrigin.fromCode(null))
        assertEquals(DoseOrigin.BothCopies, DoseOrigin.fromCode("both_copies"))
    }

    @Test
    fun `a merged server row is the one note not worth telling the user about`() {
        assertFalse(DoseNote.ServerCopyMerged.isWorthTelling)
        assertTrue(DoseNote.AddedAfterTheSlotPassed.isWorthTelling)
    }

    @Test
    fun `a planned dose that is taken does not count as a miss`() {
        val plan = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(8, 0))
        val dose = plan.doses.single()
        val taken = dose.copy(log = DoseTracker.markTaken(dose.log, at(8, 5)))

        assertFalse(taken.countsAsRealMiss(at(20, 0), DoseTracker.GRACE))
        assertNotNull(taken.log.takenAt)
    }

    @Test
    fun `a phone-only miss is excluded even when it is very late`() {
        val plan = DoseSchedule.dayFor(date, listOf(medicine()), utc, at(8, 0))
        val dose = plan.doses.single()

        assertFalse(
            dose.countsAsRealMiss(at(23, 0), DoseTracker.GRACE),
            "no reminder ever existed that could have saved this one",
        )
    }
}
