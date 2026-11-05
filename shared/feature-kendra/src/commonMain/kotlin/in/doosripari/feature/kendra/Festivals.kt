package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.Festival
import `in`.doosripari.core.model.FestivalKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Festival and vrat lookup by region. Plan section 7.2 item 7.
 *
 * ## This module computes no dates
 *
 * **There is no lunar calendar in this file, and there will not be one.** Every
 * date in [Festivals] came off the wire in a [FestivalSnapshot] that some
 * licensed source produced. This module filters, orders, ages and reminds. That
 * is the whole of its job, and the boundary is drawn here deliberately.
 *
 * The reasoning, because it is the kind of thing that gets "helpfully" improved:
 *
 *  - **A tithi is not a date.** The Hindu and Jain calendars place a day by the
 *    position of the moon against the sun, and the two bodies are observed, not
 *    derived. Whether Ekadashi falls on a given morning depends on where you
 *    are standing and on the ephemeris the source used. Reimplementing that
 *    means shipping an astronomy library, and plan section 7.2 says in as many
 *    words: "tithi and panchang from a licensed panchang data source (check the
 *    licence of any astronomy library before use)".
 *  - **Ramzan, Gurpurab and Paryushan are not even lunar in the same sense.**
 *    They are observed from a sighted moon, and the announcement is made by
 *    somebody, on a day, in a place. No formula produces it.
 *  - **A calendar that is confidently wrong by a day is worse than no calendar
 *    at all for somebody keeping a fast.** This is the whole argument and it is
 *    worth being blunt about, because a day early on a fast is a day of eating
 *    that somebody believed was a day of not eating. A screen that got it wrong
 *    once would be disbelieved for years, and the person who would be harmed by
 *    that is the person it was built for.
 *  - **A wrong date is not detectable by the user.** They have nothing to check
 *    it against. That is the specific danger of this feature: every other date
 *    in the app can be sanity-checked by looking at a calendar.
 *
 * So the failure mode this module must never have is a computed date. Every
 * [Festival.startsOn] is a value the server sent, and [FestivalSnapshot.source]
 * is carried through to the UI so the user can see where it came from.
 *
 * ## What it does instead when the data is old
 *
 * [FestivalSnapshot.isStaleAt] and the staleness the screens show are the answer
 * to "what if the snapshot is wrong". It does not paper over it: an eight-month
 * -old panchang says so on every row, and the app would rather say "we do not
 * have a current list, here is the last one we were given" than show a date it
 * cannot stand behind.
 */
object Festivals {

    /**
     * A festival list older than this says so on every row.
     *
     * Six months. A yearly snapshot refreshed a few times a year is normal for
     * festival data, and a month — the figure `feature-health` uses for hospital
     * lists — would put a permanent amber on this screen and teach people to
     * stop reading it. Eight months is past the point where the next annual
     * list ought to have arrived.
     */
    const val STALE_AFTER_DAYS: Long = 240

    /**
     * When the "evening before" reminder is sent.
     *
     * Nineteen hundred, and shared with the practice reminder in
     * [PracticeReminders.EVENING_BEFORE] for the same reason: one evening
     * reminder, at a time when somebody is awake and at home, and not a second
     * one at a different hour that teaches people to swipe them away.
     *
     * Not dawn. Dawn is when a fast begins and it is far too early to tell
     * somebody about it the night before in any case.
     */
    val EVENING_BEFORE: LocalTime = LocalTime(19, 0)

    /**
     * A fast's first day begins the evening before, and this is why.
     *
     * It is the single most consequential piece of domain knowledge in the
     * module, and it is the reason [upcoming] is not a simple date comparison.
     * [Festival.startsOn] is the *observance* — the morning of the day people
     * say it is — and for a fast the eating window closes on the evening of the
     * day before.
     *
     * Only applied to [FestivalKind.FastingDay] and [FestivalKind.Vrat]. A
     * festival that is not a fast has no such evening, and pretending otherwise
     * would put a fasting instruction on a Diwali card.
     */
    val SUNSET_BEFORE: Duration = 4.hours

    /**
     * What is coming up, for somebody in a region, in a language.
     *
     * Reads the snapshot. Computes nothing. The ordering is by the instant the
     * server supplied, which is the only ordering this module is entitled to.
     */
    fun upcoming(
        snapshot: FestivalSnapshot,
        region: String?,
        now: Instant,
        timeZone: TimeZone,
        language: AppLanguage? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<FestivalEntry> {
        val horizon = now + LOOK_AHEAD
        return snapshot.festivals
            .filter { festival -> region == null || festival.matchesRegion(region) }
            .filter { festival -> language == null || festival.languages.isEmpty() || language in festival.languages }
            .filter { festival -> festival.hasNotEndedAt(now) && festival.startsOn < horizon }
            .sortedBy { it.startsOn }
            .take(limit)
            .map { festival -> toEntry(festival, now, timeZone) }
    }

    /**
     * How far ahead to look.
     *
     * Sixty days. Long enough for a Navratri that has not started and a Ramadan
     * month away, short enough that the list stays a list. A year-long calendar
     * is a different screen and the plan does not ask for one.
     */
    val LOOK_AHEAD: Duration = 60.days

    /**
     * Twenty. A month of festivals is already more than anybody reads, and the
     * ones that matter are at the top.
     */
    const val DEFAULT_LIMIT: Int = 20

    /**
     * One row, and the thing that makes this a fast rather than an event.
     *
     * [fastBeginsOn] is null for anything that is not a fast, and a screen must
     * show nothing rather than showing the observance date as though it were
     * the date to stop eating. The two are the same date for most festivals and
     * different by one day for every fast, which is precisely the difference
     * that matters.
     */
    fun toEntry(festival: Festival, now: Instant, timeZone: TimeZone): FestivalEntry {
        val isFast = festival.isVrat || festival.kind == FestivalKind.FastingDay
        return FestivalEntry(
            festival = festival,
            isFast = isFast,
            observanceDate = festival.startsOn.toLocalDateTime(timeZone).date,
            fastBeginsOn = if (isFast) eveningBefore(festival.startsOn, timeZone) else null,
            isImminent = festival.startsOn < now + IMMINENT,
        )
    }

    /**
     * The evening a fast begins: the sunset before the observance.
     *
     * A fixed four hours before the supplied instant, not a computed sunset.
     * Computing a real sunset means an ephemeris, and the ephemeris is exactly
     * the thing [Festivals] has committed not to do. Four hours before dawn is
     * the customary gap in the subcontinent and the error against a real sunset
     * is a few minutes either way — minutes, not the day that a tithi error
     * would cost.
     */
    fun eveningBefore(observance: Instant, timeZone: TimeZone): Instant =
        observance - SUNSET_BEFORE

    /**
     * The evening-before reminder, or null.
     *
     * Only for a fast, and only if it has not already been sent. Null rather
     * than a time in the past, so a screen that asks for this at 9pm on the day
     * of the fast gets nothing instead of a reminder two hours late about
     * something that started at sunset.
     */
    fun eveningReminderFor(
        entry: FestivalEntry,
        now: Instant,
        timeZone: TimeZone,
        alreadySent: Boolean = false,
    ): FestivalReminder? {
        if (!entry.isFast) return null
        if (!PracticeReminders.isWanted(alreadySent)) return null
        val begin = entry.fastBeginsOn ?: return null
        // The day before the fast begins, at seven in the evening, in the user's
        // own timezone. Built from the user's date rather than from the
        // observance instant, so somebody whose observance instant falls on a
        // different calendar date still gets their reminder on the evening they
        // will actually be awake.
        val date = begin.toLocalDateTime(timeZone).date.minus(1, DateTimeUnit.DAY)
        val at = LocalDateTime(date, EVENING_BEFORE).toInstant(timeZone)
        return if (at > now) FestivalReminder(entry, at, ReminderBasis.EveningBefore) else null
    }

    /** How close counts as "soon", for the card that needs a reminder more than a glance. */
    val IMMINENT: Duration = 3.days

    /**
     * Whether this festival has not finished at [now].
     *
     * A festival with no end date is treated as lasting its day, which is what a
     * one-day festival is. Treating an open-ended festival as never ending would
     * put a thirty-day fast on the same card every day for a month.
     */
    private fun Festival.hasNotEndedAt(now: Instant): Boolean =
        endsOn == null || now < endsOn + 1.days

    /**
     * Whether this festival is offered in [region].
     *
     * A festival with no region is offered everywhere, which is right for a
     * national holiday and wrong for a date that only exists in one calendar —
     * so the content job is expected to set the region. Matching is
     * case-insensitive because one of the two strings is a user preference and
     * the other came off a server.
     */
    private fun Festival.matchesRegion(region: String): Boolean =
        this.region == null || this.region.equals(region, ignoreCase = true)
}

/**
 * A loaded festival list, and where it came from.
 *
 * [source] and [sourceLicence] are carried all the way to the UI. Plan section
 * 7.2's instruction to "check the licence of any astronomy library before use"
 * means somebody has to be able to see which source the app is trusting; a
 * calendar with no visible provenance is a calendar nobody can check.
 */
data class FestivalSnapshot(
    val festivals: List<Festival>,
    /** When the snapshot was fetched. Not when any festival is. */
    val loadedAt: Instant,
    /** A region, when the snapshot was loaded for one. Null means several. */
    val region: String? = null,
    /** The name of the licensed panchang or calendar source, as the credits page shows it. */
    val source: String? = null,
    val sourceLicence: ContentLicence = ContentLicence.Unverified,
) {
    val isEmpty: Boolean get() = festivals.isEmpty()

    /**
     * Whether the list is old enough that every row should say so.
     *
     * Staleness is a reason to warn, never a reason to hide. An eight-month-old
     * list is still the best information available, and "we last refreshed this
     * on 3 January" is honest where a confident tick would not be.
     */
    fun isStaleAt(now: Instant, staleAfterDays: Long = Festivals.STALE_AFTER_DAYS): Boolean =
        now - loadedAt > staleAfterDays.days
}

/**
 * One row of the calendar, in the terms the screen needs.
 *
 * [isFast] and [fastBeginsOn] are separate from [observanceDate] on purpose.
 * A screen that shows one date for both is wrong about every fast in the app.
 */
data class FestivalEntry(
    val festival: Festival,
    val isFast: Boolean,
    /** The date the observance falls on, in the user's timezone. */
    val observanceDate: LocalDate,
    /**
     * The evening the fast begins, or null when this is not a fast.
     *
     * Four hours before the observance instant, which is the customary gap
     * before dawn — see [Festivals.eveningBefore] for why it is not a computed
     * sunset.
     */
    val fastBeginsOn: Instant?,
    /** Close enough to need a reminder rather than a glance. */
    val isImminent: Boolean,
) {
    val name: String get() = festival.name
    val kind: FestivalKind get() = festival.kind
    val isVrat: Boolean get() = festival.isVrat

    /**
     * Whether the app can honestly draw a fasting instruction for this row.
     *
     * Needs both a kind and a time. A festival that arrived without a kind is
     * shown by name and nothing more, because a screen that guesses "this is a
     * fast" from a name it happens to recognise is a screen that will be wrong
     * the one time it costs somebody a day.
     */
    val canShowFastingDetail: Boolean get() = isFast && fastBeginsOn != null
}

/** Why a reminder exists, so a screen can label it and a log can explain it. */
enum class ReminderBasis(val code: String) {
    /** Plan section 7.2: "reminders the evening before". */
    EveningBefore("evening_before");

    companion object {
        fun fromCode(code: String?): ReminderBasis? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A reminder about a fast, and when it fires.
 *
 * It carries [ReminderBasis] and nothing that would let a caller raise it to an
 * urgent or full-screen alert. Plan section 6.15 allows at most four
 * non-urgent notifications a day and requires the screen not to be woken for a
 * routine notice, and the cheapest way to guarantee that is for a fast's
 * reminder to have no knob to turn. `feature-health`'s [ReminderPolicy] is the
 * authority and this type simply cannot ask for more than a passive notice.
 */
data class FestivalReminder(
    val entry: FestivalEntry,
    val at: Instant,
    val basis: ReminderBasis,
) {
    /** Always. See the KDoc. Plan section 6.15's lowest interruption level. */
    val isPassive: Boolean get() = true

    val title: Phrase get() = Phrase(KEYS.EVENING_BEFORE_TITLE, mapOf("name" to entry.name))

    companion object {
        object KEYS {
            /** "Tomorrow is {name}. Your fast begins this evening." */
            const val EVENING_BEFORE_TITLE: String = "kendra.festival.evening_before"

            /** "Dates are from {source}, last refreshed on {date}." */
            const val SOURCE_LINE: String = "kendra.festival.source_line"

            /** "This list is from {date}. It may be out of date." */
            const val STALE_LINE: String = "kendra.festival.stale_line"

            /** "We do not have a list for your region yet." */
            const val NO_DATA: String = "kendra.festival.no_data"
        }
    }
}
