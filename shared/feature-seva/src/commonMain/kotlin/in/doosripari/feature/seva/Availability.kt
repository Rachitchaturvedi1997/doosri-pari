package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AvailabilityWindow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * When somebody is actually free. Plan section 6.8.
 *
 * This file exists because of one fact about the audience, and every rule in
 * it follows from that fact: **a retirement is not a working day.**
 *
 * `core-model` says so on [AvailabilityWindow] — "an availability window of
 * 09:00–17:00 would exclude almost the entire audience of this app" — and it is
 * worth taking seriously rather than treating as a note. A listing posted with
 * office hours is not a listing with a slightly inconvenient schedule; it is a
 * listing with *no availability at all* for the people it is aimed at, and it
 * will sit in the board scoring zero for a reason nobody can see.
 *
 * So there are two ideas kept apart on purpose.
 *
 *  - **[overlaps]** — "do these have any time in common?" This is a scoring
 *    signal. Partial overlap is worth points.
 *  - **[covers]** — "can this person be there for the whole time asked?" This
 *    is a booking rule. Half a morning plus half an evening is *not* a cover
 *    for 09:00 to 17:00, and gluing a person's morning and evening together to
 *    make a working day out of them is precisely the mistake [RetiredHours]
 *    exists to make impossible.
 *
 * ## Weekdays
 *
 * ISO throughout, 1 = Monday to 7 = Sunday, matching [AvailabilityWindow] and
 * `java.time.DayOfWeek`. This is the one place in the app where getting it
 * wrong is silent — a provider free on Sunday shows as free on Monday and
 * nothing anywhere reports a fault.
 */
data class TimeSlot(
    val weekday: Int,
    val from: LocalTime,
    val to: LocalTime,
) {
    init {
        require(weekday in 1..7) { "weekday must be 1..7, was $weekday" }
    }

    /** A slot that runs past midnight, e.g. 22:00 to 06:00. */
    val spansMidnight: Boolean get() = to <= from

    companion object {
        /**
         * A slot of a given length starting at the local time of [instant].
         *
         * A booking screen asks "how long", not "until when", and every
         * provider on the board has said when they are free rather than how
         * long they can give — so the slot is built from the instant and the
         * requested duration together.
         */
        fun startingAt(
            instant: Instant,
            timeZone: TimeZone,
            minutes: Int,
        ): TimeSlot {
            val local = instant.toLocalDateTime(timeZone)
            return starting(local.date.dayOfWeek.isoDayNumber, local.time, minutes)
        }

        /**
         * A slot of a given length starting at a local time.
         *
         * A slot that runs past midnight is kept as a single overnight slot
         * rather than being split here, so the caller can see what was asked
         * for; the split into [Segment]s happens once, at comparison time.
         */
        fun starting(weekday: Int, from: LocalTime, minutes: Int): TimeSlot = TimeSlot(
            weekday = weekday,
            from = from,
            to = from.plusMinutes(minutes),
        )

        private fun LocalTime.plusMinutes(minutes: Int): LocalTime {
            val total = (hour * 60 + minute + minutes) % (24 * 60)
            return LocalTime(total / 60, total % 60)
        }
    }
}

/**
 * A run of minutes inside one day, used to compare windows and slots without
 * dragging [LocalTime] arithmetic through every line.
 *
 * [weekday] travels with the range so that a window crossing midnight can be
 * split across two days and still line up with a slot that does.
 */
private data class Segment(
    val weekday: Int,
    val fromMinute: Int,
    val toMinute: Int,
) {
    val length: Int get() = toMinute - fromMinute
}

/** Midnight-starting the next day of the same week. 7 wraps to 1. */
private fun nextWeekday(weekday: Int): Int = if (weekday == 7) 1 else weekday + 1

private fun LocalTime.minuteOfDay(): Int = hour * 60 + minute

private const val END_OF_DAY: Int = 24 * 60

/**
 * Split a window into the same-day runs it actually occupies.
 *
 * A 22:00-to-06:00 window becomes 22:00-to-midnight on the Wednesday and
 * midnight-to-06:00 on the Thursday. Without the split, the tail would be
 * compared against slots on the wrong day and a night-shift provider would
 * appear to be free at six on the *morning* they are not.
 */
private fun AvailabilityWindow.segments(): List<Segment> {
    val from = from.minuteOfDay()
    val to = to.minuteOfDay()
    return if (to <= from) {
        listOf(
            Segment(weekday, from, END_OF_DAY),
            Segment(nextWeekday(weekday), 0, to),
        )
    } else {
        listOf(Segment(weekday, from, to))
    }
}

private fun TimeSlot.segments(): List<Segment> {
    val from = from.minuteOfDay()
    val to = to.minuteOfDay()
    return if (spansMidnight) {
        listOf(
            Segment(weekday, from, END_OF_DAY),
            Segment(nextWeekday(weekday), 0, to),
        )
    } else {
        listOf(Segment(weekday, from, to))
    }
}

private fun List<Segment>.covers(slot: List<Segment>): Boolean =
    slot.all { wanted -> any { range -> range.contains(wanted) } }

private fun Segment.contains(wanted: Segment): Boolean =
    weekday == wanted.weekday &&
        fromMinute <= wanted.fromMinute &&
        toMinute >= wanted.toMinute

private fun Segment.intersects(other: Segment): Int {
    if (weekday != other.weekday) return 0
    return minOf(toMinute, other.toMinute) - maxOf(fromMinute, other.fromMinute)
}

/**
 * Comparing [AvailabilityWindow]s with [TimeSlot]s.
 *
 * All pure. The board, the booking screen and the match scorer all go through
 * here, so "are these times compatible" has exactly one answer in the app.
 */
object AvailabilityMatcher {

    /**
     * Is the whole slot inside one of the windows?
     *
     * This is the booking rule, and it is deliberately stricter than
     * [overlaps]. Asking somebody to be available from nine to five is not
     * satisfied by a person who is free until noon and again after four; it is
     * a request for eight unbroken hours, and if the two halves are stitched
     * together the request reads as cover that does not exist.
     */
    fun covers(windows: List<AvailabilityWindow>, slot: TimeSlot): Boolean {
        val wanted = slot.segments()
        return windows.flatMap { it.segments() }.covers(wanted)
    }

    /** Is there any time in common at all? A scoring signal, not a booking rule. */
    fun overlaps(windows: List<AvailabilityWindow>, slot: TimeSlot): Boolean =
        overlapMinutes(windows, slot) > 0

    /**
     * Minutes of common time.
     *
     * Returned as a count rather than a boolean because a provider who is free
     * for one hour of a four-hour request is a worse match than one who is free
     * for all four, and "yes, partly" cannot express that.
     *
     * If a caller passes two windows that overlap each other the same minutes
     * can be counted twice. The provider picks their own windows, so this is
     * their mistake rather than a scoring artefact, and [coverage] clamps the
     * result to 1.0 rather than letting a double count inflate a score.
     */
    fun overlapMinutes(windows: List<AvailabilityWindow>, slot: TimeSlot): Int {
        val ranges = windows.flatMap { it.segments() }
        return slot.segments().sumOf { wanted ->
            ranges.sumOf { it.intersects(wanted) }
        }
    }

    /**
     * How much of [available] is covered, as 0.0 to 1.0.
     *
     * The denominator is what the *seeker* is offering, not what the provider
     * is offering. Asking "what fraction of the provider's free time can this
     * person use" rewards somebody who is available 24 hours a day, which is
     * the opposite of what makes a good match.
     */
    fun coverage(available: List<AvailabilityWindow>, wanted: List<AvailabilityWindow>): Double {
        val offeredMinutes = wanted.sumOf { it.segments().sumOf(Segment::length) }
        if (offeredMinutes == 0) return 0.0
        val covered = wanted.sumOf { wantedWindow ->
            overlapMinutes(available, slotOf(wantedWindow))
        }
        return (covered.toDouble() / offeredMinutes).coerceIn(0.0, 1.0)
    }

    /** The single window that best fits a slot, for "when exactly?" on a card. */
    fun bestWindow(windows: List<AvailabilityWindow>, slot: TimeSlot): AvailabilityWindow? =
        windows.maxByOrNull { overlapMinutes(listOf(it), slot) }
            ?.takeIf { overlapMinutes(listOf(it), slot) > 0 }

    private fun slotOf(window: AvailabilityWindow) =
        TimeSlot(window.weekday, window.from, window.to)
}

/**
 * What a retired person's day actually looks like.
 *
 * Every number here is taken from something else in the plan rather than
 * invented, because a default that contradicts the rest of the app is worse
 * than no default at all:
 *
 *  - **06:00** is early enough to catch "Subah ki khabar", which section 6.7
 *    schedules for eight in the morning.
 *  - **12:00** is where section 6.3's default check-in window closes. That
 *    window is three hours from 09:00, so the plan already treats noon as the
 *    end of a working morning.
 *  - **16:00 to 21:00** covers "Sham ke bhajan" at half past six, also
 *    section 6.7.
 *
 * The gap in the middle is not an oversight. It is the point: it is why
 * [AvailabilityMatcher.covers] refuses a 09:00-to-17:00 request, and a board
 * that quietly filled that gap would be telling a pensioner that somebody is
 * free all day when they are not.
 */
object RetiredHours {

    /** ISO weekdays. Named so the tests read as sentences. */
    const val MONDAY: Int = 1
    const val TUESDAY: Int = 2
    const val SATURDAY: Int = 6
    const val SUNDAY: Int = 7

    /**
     * The office hours the plan implicitly assumes, kept here so a request
     * built from them can be recognised and refused.
     */
    val WORKING_HOURS: Pair<LocalTime, LocalTime> = LocalTime(9, 0) to LocalTime(17, 0)

    /** A typical weekday for somebody who has finished work for good. */
    fun typical(weekday: Int = MONDAY): List<AvailabilityWindow> = listOf(
        AvailabilityWindow(weekday, LocalTime(6, 0), LocalTime(12, 0)),
        AvailabilityWindow(weekday, LocalTime(16, 0), LocalTime(21, 0)),
    )

    /** Sundays are for "Yaadon ki baat" (section 6.7), so the day runs longer. */
    fun sunday(): List<AvailabilityWindow> = listOf(
        AvailabilityWindow(SUNDAY, LocalTime(6, 0), LocalTime(13, 0)),
        AvailabilityWindow(SUNDAY, LocalTime(16, 0), LocalTime(22, 0)),
    )

    /**
     * The slot a filter chip means by "I have time in the mornings".
     *
     * A first-class filter on the board, and a morning rather than "any time",
     * because "what a retired person has time for" is the question the whole
     * feature is for and it is not the same as "whenever you are working".
     */
    fun morningSlot(weekday: Int = MONDAY, minutes: Int = 120): TimeSlot =
        TimeSlot.starting(weekday, LocalTime(7, 0), minutes)

    /**
     * Does this slot look like somebody copied office hours onto a form?
     *
     * A request that happens to span the whole working day is almost always a
     * listing that was written by somebody who has not thought about who is
     * going to answer it. The board shows a note on those rather than quietly
     * dropping them, so the poster learns why nobody is applying.
     */
    fun looksLikeOfficeHours(slot: TimeSlot): Boolean =
        slot.from <= WORKING_HOURS.first && slot.to >= WORKING_HOURS.second
}
