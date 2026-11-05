package `in`.doosripari.feature.health

import `in`.doosripari.core.model.Reminder
import `in`.doosripari.core.model.ReminderKind
import `in`.doosripari.core.model.ReminderSchedule
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan section 6.15's notification policy, enforced.
 *
 * The rule this file exists for is one sentence long and the whole app depends
 * on it: **a circle post must never be able to ring the phone.** Everything
 * else here — the daily cap, quiet hours, channels — is in service of that, and
 * of the second sentence in the plan that is just as quotable: the app must not
 * become the thing a pensioner uninstalls.
 */
class ReminderPolicyTest {

    private val utc = TimeZone.of("UTC")
    private val date = LocalDate(2026, 10, 14)

    private fun at(hour: Int, minute: Int = 0): Instant =
        Instant.parse("2026-10-14T%02d:%02d:00Z".format(hour, minute))

    private fun reminder(
        id: Int,
        kind: ReminderKind,
        hour: Int,
        minute: Int = 0,
        schedule: ReminderSchedule = ReminderSchedule.DailyAt(LocalTime(hour, minute)),
    ) = Reminder(
        id = id,
        kind = kind,
        schedule = schedule,
        nextAt = at(hour, minute),
    )

    // ---- a quiet reminder cannot ask for an urgent alert ----

    @Test
    fun `a circle event cannot request an urgent alert`() {
        assertFalse(
            ReminderPolicy.mayRequestUrgent(ReminderKind.CircleEvent),
            "this is the rule the whole module exists to enforce",
        )
    }

    @Test
    fun `no quiet kind can request an urgent alert, whatever it is`() {
        ReminderKind.entries
            .filter { it.isQuiet }
            .forEach { kind ->
                assertFalse(
                    ReminderPolicy.mayRequestUrgent(kind),
                    "$kind is quiet in core-model and must be quiet here",
                )
            }
    }

    @Test
    fun `a quiet kind's loudest possible level cannot break through anything`() {
        ReminderKind.entries
            .filter { it.isQuiet }
            .forEach { kind ->
                val ceiling = ReminderPolicy.ceilingFor(kind)
                assertFalse(
                    ceiling.isUrgent,
                    "$kind may be posted as $ceiling and that must not be urgent",
                )
            }
    }

    @Test
    fun `a medicine may be time-sensitive`() {
        assertTrue(ReminderPolicy.mayRequestUrgent(ReminderKind.Medicine))
        assertEquals(InterruptionLevel.TimeSensitive, ReminderPolicy.ceilingFor(ReminderKind.Medicine))
    }

    @Test
    fun `a missed check-in is the only thing allowed to be critical`() {
        assertEquals(InterruptionLevel.Critical, ReminderPolicy.ceilingFor(ReminderKind.CheckIn))
    }

    @Test
    fun `an appointment is an ordinary banner, not a time-sensitive one`() {
        // Plan section 6.15's time-sensitive list is medicine, check-in and life
        // certificate. core-model marks Appointment as not quiet, which permits
        // an active banner; it does not permit a break-through. The stricter
        // reading wins and the disagreement is a reported finding.
        assertEquals(InterruptionLevel.Active, ReminderPolicy.ceilingFor(ReminderKind.Appointment))
    }

    // ---- the daily cap ----

    @Test
    fun `at most four non-urgent notifications go out on a day`() {
        val reminders = (1..7).map { id ->
            reminder(id = id, kind = ReminderKind.Digest, hour = 8, minute = id)
        }

        val plan = ReminderPolicy.planDay(date, reminders, utc)

        assertEquals(
            ReminderPolicy.DAILY_NON_URGENT_LIMIT,
            plan.deliveries.size,
            "eleven notifications a day until a pensioner uninstalls the app",
        )
        assertEquals(3, plan.bundled.size)
    }

    @Test
    fun `bundled notifications are summarised, never discarded`() {
        val reminders = (1..6).map { id ->
            reminder(id = id, kind = ReminderKind.Digest, hour = 9, minute = id)
        }

        val plan = ReminderPolicy.planDay(date, reminders, utc)

        assertEquals(6, plan.deliveries.size + plan.bundled.size)
    }

    @Test
    fun `a part-handled day does not get a fresh budget of four`() {
        val reminders = (1..2).map { id ->
            reminder(id = id, kind = ReminderKind.Digest, hour = 9, minute = id)
        }

        val plan = ReminderPolicy.planDay(date, reminders, utc, alreadyDelivered = 3)

        assertEquals(1, plan.deliveries.size)
        assertEquals(1, plan.bundled.size)
    }

    @Test
    fun `urgent reminders do not count against the non-urgent cap`() {
        val reminders = buildList {
            repeat(6) { id -> add(reminder(id, ReminderKind.Digest, hour = 9, minute = id)) }
            add(reminder(99, ReminderKind.CheckIn, hour = 10))
            add(reminder(100, ReminderKind.Medicine, hour = 10, minute = 30))
        }

        val plan = ReminderPolicy.planDay(date, reminders, utc)

        assertEquals(2, plan.urgent.size, "a medicine must not be what gets suppressed")
        assertEquals(4, plan.deliveries.count { !it.level.isUrgent })
        assertEquals(2, plan.bundled.size)
    }

    @Test
    fun `a delivery that is not urgent can never be posted full-screen`() {
        val reminders = (1..5).map { id ->
            reminder(id = id, kind = ReminderKind.CircleEvent, hour = 9, minute = id)
        }

        val plan = ReminderPolicy.planDay(date, reminders, utc)

        plan.deliveries.forEach {
            assertFalse(it.mayPostFullScreen, "${it.reminder.kind} must not take the lock screen")
        }
    }

    // ---- quiet hours ----

    @Test
    fun `a non-exempt reminder in the evening is held to the morning`() {
        val reminder = reminder(1, ReminderKind.Digest, hour = 22)

        val held = ReminderPolicy.holdUntil(reminder, utc)
        assertNotNull(held)
        assertEquals(at(7), held, "21:00 to 07:00, plan section 6.15")
    }

    @Test
    fun `a reminder at two in the morning belongs to the window that opened the night before`() {
        // Getting this wrong is how a two-in-the-morning circle digest gets
        // through, which is the single thing section 6.15 exists to prevent.
        val reminder = reminder(1, ReminderKind.Digest, hour = 2)

        val held = ReminderPolicy.holdUntil(reminder, utc)
        assertNotNull(held)
        assertEquals(at(7), held)
    }

    @Test
    fun `the quiet window reported at two in the morning opened yesterday evening`() {
        val window = ReminderPolicy.quietWindowContaining(at(2), utc)
        assertNotNull(window)
        assertEquals(Instant.parse("2026-10-13T21:00:00Z"), window.first)
        assertEquals(at(7), window.second)
    }

    @Test
    fun `nothing is held during the day`() {
        assertNull(ReminderPolicy.quietWindowContaining(at(13), utc))
        assertNull(ReminderPolicy.holdUntil(reminder(1, ReminderKind.Digest, hour = 13), utc))
    }

    @Test
    fun `medicines and check-ins ignore quiet hours`() {
        assertTrue(ReminderPolicy.ignoresQuietHours(ReminderKind.Medicine))
        assertTrue(ReminderPolicy.ignoresQuietHours(ReminderKind.CheckIn))
        assertNull(ReminderPolicy.holdUntil(reminder(1, ReminderKind.Medicine, hour = 22), utc))
    }

    @Test
    fun `a life certificate is not quietly exempt, because the plan did not say so`() {
        assertFalse(
            ReminderPolicy.ignoresQuietHours(ReminderKind.LifeCertificate),
            "a certificate deadline is normally days away; holding it costs nothing",
        )
    }

    @Test
    fun `a deadline is never held`() {
        // Holding a notification until after the thing it warns about has passed
        // is the same failure as dropping it.
        val deadline = reminder(
            id = 1,
            kind = ReminderKind.LifeCertificate,
            hour = 23,
            schedule = ReminderSchedule.Deadline(at(23)),
        )

        assertNull(ReminderPolicy.holdUntil(deadline, utc))
    }

    @Test
    fun `a deadline that was late says it was late rather than pretending otherwise`() {
        val deadline = reminder(
            id = 1,
            kind = ReminderKind.LifeCertificate,
            hour = 23,
            schedule = ReminderSchedule.Deadline(at(23)),
        )
        val plan = ReminderPolicy.planDay(date, listOf(deadline), utc)
        val delivery = plan.deliveries.single()

        // Not held and not moved, so the card has nothing to apologise about.
        assertFalse(delivery.isHeld)
    }

    // ---- channels ----

    @Test
    fun `only the safety channel cannot be switched off`() {
        assertFalse(ReminderPolicy.channelFor(ReminderKind.CheckIn).canBeDisabled)
        ReminderChannel.entries
            .filter { it != ReminderChannel.Safety }
            .forEach { assertTrue(it.canBeDisabled, "${it.code} must be switchable") }
    }

    @Test
    fun `a circle post goes to the circles channel and a class to practice`() {
        assertEquals(ReminderChannel.Circles, ReminderPolicy.channelFor(ReminderKind.CircleEvent))
        assertEquals(ReminderChannel.Practice, ReminderPolicy.channelFor(ReminderKind.YogaSession))
    }

    // ---- enums ----

    @Test
    fun `an unknown interruption level falls back to the quietest one`() {
        assertEquals(InterruptionLevel.Passive, InterruptionLevel.fromCode("alarm"))
        assertEquals(InterruptionLevel.Passive, InterruptionLevel.fromCode(null))
    }

    @Test
    fun `only critical and time-sensitive count as urgent`() {
        assertTrue(InterruptionLevel.Critical.isUrgent)
        assertTrue(InterruptionLevel.TimeSensitive.isUrgent)
        assertFalse(InterruptionLevel.Active.isUrgent)
        assertFalse(InterruptionLevel.Passive.isUrgent)
    }

    // ---- the day's shape ----

    @Test
    fun `a day with nothing due has nothing to show`() {
        val plan = ReminderPolicy.planDay(date, emptyList(), utc)
        assertTrue(plan.isNothingToShow)
    }

    @Test
    fun `a reminder due on another day is not part of today`() {
        val tomorrow = Reminder(
            id = 1,
            kind = ReminderKind.Digest,
            schedule = ReminderSchedule.DailyAt(LocalTime(9, 0)),
            nextAt = Instant.parse("2026-10-15T09:00:00Z"),
        )

        assertTrue(ReminderPolicy.planDay(date, listOf(tomorrow), utc).isNothingToShow)
    }

    @Test
    fun `a reminder already done is not due`() {
        val done = Reminder(
            id = 1,
            kind = ReminderKind.Digest,
            schedule = ReminderSchedule.DailyAt(LocalTime(9, 0)),
            nextAt = at(9),
            doneAt = at(9, 5),
        )

        assertTrue(ReminderPolicy.planDay(date, listOf(done), utc).isNothingToShow)
    }

    @Test
    fun `the evening-before moment is the previous day at seven`() {
        val moment = ReminderPolicy.eveningBefore(LocalDate(2026, 10, 15), utc)
        assertEquals(Instant.parse("2026-10-14T19:00:00Z"), moment)
    }
}
