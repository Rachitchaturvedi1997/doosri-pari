package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AvailabilityWindow
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Availability, plan section 6.8, and the one fact the whole file rests on:
 * **a retirement is not a working day.**
 *
 * The tests are ordered from the general to the claim that matters. The last
 * group is the one worth reading — it is what would catch somebody "fixing" a
 * default to 09:00–17:00 because that is what a working person's day looks
 * like.
 */
class AvailabilityTest {

    // ------------------------------------------------------------- segments --

    @Test
    fun `an afternoon window is a single run on one day`() {
        val slot = TimeSlot(RetiredHours.MONDAY, LocalTime(16, 0), LocalTime(18, 0))
        assertEquals(120, AvailabilityMatcher.overlapMinutes(listOf(Fixtures.window(1, 16, 18)), slot))
    }

    @Test
    fun `a window that runs past midnight covers the small hours of the next day`() {
        // 22:00 to 06:00 is a single thing a person is awake for, and the
        // 06:00 belongs to the next day. Comparing it against a slot on that
        // day is the whole reason `segments()` exists.
        val overnight = listOf(AvailabilityWindow(3, LocalTime(22, 0), LocalTime(6, 0)))
        val thursdayDawn = TimeSlot(4, LocalTime(5, 0), LocalTime(7, 0))

        assertTrue(AvailabilityMatcher.covers(overnight, thursdayDawn))
    }

    @Test
    fun `a slot that runs past midnight is split the same way`() {
        val overnight = listOf(AvailabilityWindow(3, LocalTime(23, 0), LocalTime(7, 0)))
        val slot = TimeSlot(3, LocalTime(22, 0), LocalTime(8, 0))

        assertTrue(slot.spansMidnight)
        // 22:00 to midnight is inside the Wednesday half; midnight to 08:00
        // is compared with the Thursday half, and six of those eight hours
        // are covered — 23:00 to 07:00.
        assertEquals(8 * 60, AvailabilityMatcher.overlapMinutes(overnight, slot))
    }

    @Test
    fun `sunday wraps back to monday rather than running off the end of the week`() {
        val sundayNight = listOf(AvailabilityWindow(7, LocalTime(22, 0), LocalTime(6, 0)))
        val mondayDawn = TimeSlot(1, LocalTime(4, 0), LocalTime(6, 0))

        assertTrue(AvailabilityMatcher.covers(sundayNight, mondayDawn))
    }

    @Test
    fun `a weekday outside one to seven is refused rather than silently wrapped`() {
        // `AvailabilityWindow` requires 1..7 and so does `TimeSlot`. Both
        // refuse, which is better than one of them guessing — a slot that
        // silently became a Sunday would be invisible in the app.
        assertFailsWith<IllegalArgumentException> { TimeSlot(0, LocalTime(9, 0), LocalTime(10, 0)) }
        assertFailsWith<IllegalArgumentException> { TimeSlot(8, LocalTime(9, 0), LocalTime(10, 0)) }
    }

    // ------------------------------------------------- cover versus overlap --

    @Test
    fun `a morning and an evening do not make a working day`() {
        // The distinction the file exists for. Two free windows that add up to
        // eight hours are not the same as being free for eight unbroken
        // hours, and a booking screen that adds them together is putting
        // somebody in a room at two in the afternoon who said they were not
        // there.
        val split = RetiredHours.typical(RetiredHours.MONDAY)
        val workingDay = TimeSlot(
            RetiredHours.MONDAY,
            RetiredHours.WORKING_HOURS.first,
            RetiredHours.WORKING_HOURS.second,
        )

        assertFalse(AvailabilityMatcher.covers(split, workingDay))
        // Four hours of it do overlap — three in the morning and one at four
        // in the afternoon — which is the number a *score* should see, and is
        // the reason the two questions are not the same function.
        assertEquals(4 * 60, AvailabilityMatcher.overlapMinutes(split, workingDay))
    }

    @Test
    fun `an unbroken window does cover the working day`() {
        val always = listOf(Fixtures.window(RetiredHours.MONDAY, 8, 19))
        val workingDay = TimeSlot(
            RetiredHours.MONDAY,
            RetiredHours.WORKING_HOURS.first,
            RetiredHours.WORKING_HOURS.second,
        )

        assertTrue(AvailabilityMatcher.covers(always, workingDay))
    }

    @Test
    fun `any overlap at all is a yes, and a partial one says so`() {
        // A provider free for one hour of a four-hour request is a worse
        // match than one free for all four, which is why `overlapMinutes`
        // counts minutes rather than returning a boolean.
        val busy = listOf(Fixtures.window(RetiredHours.MONDAY, 10, 11))
        val request = TimeSlot(RetiredHours.MONDAY, LocalTime(10, 0), LocalTime(14, 0))

        assertTrue(AvailabilityMatcher.overlaps(busy, request))
        assertEquals(60, AvailabilityMatcher.overlapMinutes(busy, request))
        assertEquals(0.25, AvailabilityMatcher.coverage(busy, listOf(Fixtures.window(1, 10, 14))))
    }

    @Test
    fun `coverage is measured against what the seeker offered, not what the provider is free for`() {
        // Asking what fraction of the provider's week can be used rewards
        // somebody available twenty-four hours a day, which is the opposite of
        // a good match.
        val availableAllWeek = (1..7).map { Fixtures.window(it, 6, 23) }
        val seekerOffersFourHours = listOf(Fixtures.window(RetiredHours.MONDAY, 7, 11))
        val narrow = listOf(Fixtures.window(RetiredHours.MONDAY, 7, 8))

        assertEquals(1.0, AvailabilityMatcher.coverage(availableAllWeek, seekerOffersFourHours))
        assertEquals(0.25, AvailabilityMatcher.coverage(narrow, seekerOffersFourHours))
    }

    @Test
    fun `a seeker with no free hours does not drag a provider's score to zero`() {
        // The same "nobody has said" rule `SevaMatcher` applies. Scoring zero
        // for a field nobody filled in makes leaving a form blank look like
        // being unavailable, and people learn to fill forms in to avoid being
        // ranked down for honesty. The neutral decision is `SevaMatcher`'s;
        // here it just means this function declines to answer.
        val generous = listOf(Fixtures.window(RetiredHours.MONDAY, 6, 22))
        assertEquals(0.0, AvailabilityMatcher.coverage(generous, emptyList()))
    }

    @Test
    fun `the best window is the one that actually overlaps, and null when none does`() {
        val windows = listOf(
            Fixtures.window(RetiredHours.MONDAY, 9, 10),
            Fixtures.window(RetiredDays.Tuesday, 17, 19),
        )
        val evening = TimeSlot(RetiredDays.Tuesday, LocalTime(18, 0), LocalTime(19, 0))

        assertEquals(windows[1], AvailabilityMatcher.bestWindow(windows, evening))
        assertNull(
            AvailabilityMatcher.bestWindow(
                windows,
                TimeSlot(RetiredDays.Wednesday, LocalTime(2, 0), LocalTime(3, 0)),
            ),
        )
    }

    // ------------------------------------------------------- retired hours --

    @Test
    fun `a retired weekday morning is available`() {
        val typical = RetiredHours.typical(RetiredDays.Wednesday)
        val morning = TimeSlot(RetiredDays.Wednesday, LocalTime(7, 0), LocalTime(9, 0))

        assertTrue(AvailabilityMatcher.covers(typical, morning))
        // 09:00 to 12:00 is inside it — the morning runs to noon, which is
        // where section 6.3's check-in window closes.
        assertTrue(
            AvailabilityMatcher.covers(
                typical,
                TimeSlot(RetiredDays.Wednesday, LocalTime(9, 0), LocalTime(12, 0)),
            ),
        )
        // And the middle of the working day is not.
        assertFalse(
            AvailabilityMatcher.covers(
                typical,
                TimeSlot(RetiredDays.Wednesday, LocalTime(13, 0), LocalTime(15, 0)),
            ),
        )
    }

    @Test
    fun `nine till five does not match a retired person's actual hours`() {
        // The requirement, stated as a test. If somebody ever defaults the
        // board's availability to office hours, this is the test that fails.
        val retired = RetiredHours.typical(RetiredDays.Wednesday)
        val officeDay = TimeSlot(
            RetiredDays.Wednesday,
            RetiredHours.WORKING_HOURS.first,
            RetiredHours.WORKING_HOURS.second,
        )

        assertFalse(AvailabilityMatcher.covers(retired, officeDay))
        assertTrue(RetiredHours.looksLikeOfficeHours(officeDay))
        // Nine in the morning on its own is fine. It is the whole day that is
        // the problem, and a test that only checked the start time would let
        // the regression through.
        assertTrue(
            AvailabilityMatcher.covers(
                retired,
                TimeSlot(RetiredDays.Wednesday, LocalTime(9, 0), LocalTime(10, 0)),
            ),
        )
    }

    @Test
    fun `sunday runs longer than a weekday`() {
        // Section 6.7 puts "Yaadon ki baat" on a Sunday evening, so the day
        // has to be there for it.
        val sunday = RetiredHours.sunday()
        val weekday = RetiredHours.typical(RetiredDays.Sunday)
        val evening = TimeSlot(RetiredDays.Sunday, LocalTime(20, 0), LocalTime(22, 0))

        assertTrue(AvailabilityMatcher.covers(sunday, evening))
        assertFalse(AvailabilityMatcher.covers(weekday, evening))
    }

    @Test
    fun `the morning filter is a morning and not a whole day`() {
        val slot = RetiredHours.morningSlot(RetiredDays.Wednesday)
        assertEquals(LocalTime(7, 0), slot.from)
        assertEquals(LocalTime(9, 0), slot.to)
        assertTrue(AvailabilityMatcher.covers(RetiredHours.typical(RetiredDays.Wednesday), slot))
    }

    @Test
    fun `a slot built from an instant is that instant's local weekday`() {
        // The booking screen asks "how long", not "until when", and builds the
        // slot from the moment the user tapped. Getting the weekday wrong here
        // books somebody for a Sunday on a Wednesday, silently.
        val slot = TimeSlot.startingAt(Fixtures.NOW, TimeZone.UTC, 60)

        assertEquals(3, slot.weekday)
        assertEquals(LocalTime(9, 0), slot.from)
        assertEquals(LocalTime(10, 0), slot.to)
    }

    @Test
    fun `a slot that would run past midnight wraps rather than producing an impossible time`() {
        val slot = TimeSlot.starting(3, LocalTime(23, 30), 90)

        assertEquals(LocalTime(23, 30), slot.from)
        assertEquals(LocalTime(1, 0), slot.to)
        assertTrue(slot.spansMidnight)
    }
}

/** ISO weekdays as names, so the tests above read as sentences. */
private object RetiredDays {
    const val Monday = 1
    const val Tuesday = 2
    const val Wednesday = 3
}
