package `in`.doosripari.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** Whether the phone can reach the server, and what to say about it. */
sealed interface Connection {
    data object Checking : Connection

    /** Live, or live-enough: the feed came back. */
    data object Online : Connection

    /**
     * Plan section 13 lists Today in the offline column, so this is a state the
     * screen renders in full rather than an error it renders over. [savedAt] is
     * when the cache was written, and it is shown verbatim: "showing what was
     * saved at 6:40 this morning" is a thing the user can act on, a spinner that
     * never stops is not.
     */
    data class Offline(val savedAt: Instant?) : Connection
}

/**
 * The check-in button's state, as Today needs it.
 *
 * Today does **not** own check-in. The state machine, the escalation and the
 * optimistic tap live in `in.doosripari.feature.checkin`, and the only thing
 * this package does is narrow that ViewModel's much larger state down to the four
 * things a button on this screen needs. Re-implementing the transition here is
 * how two screens end up disagreeing about whether somebody is safe.
 */
data class TodayCheckIn(
    /** Non-null once today's row is done; the design-system button swaps to a tick. */
    val checkedAtLabel: String? = null,
    val sending: Boolean = false,
    val failed: Boolean = false,
    val paused: Boolean = false,
    /** Plan 6.2 card 2: the button is hidden entirely if check-in is off. */
    val enabled: Boolean = true,
)

/** What the Today screen shows. One state object; the card body is a `when` in the composable. */
data class TodayUiState(
    val loading: Boolean = true,
    val addressAs: String = "",
    val dateLabel: String = "",
    val vratLabel: String? = null,
    /** Section ids in the order plan 6.2's logic decided on. */
    val order: List<TodaySection> = emptyList(),
    val hidden: Set<TodaySection> = emptySet(),

    val pension: Loadable<PensionCard> = Loadable.Loading,
    val reminders: Loadable<List<ReminderItem>> = Loadable.Loading,
    val practice: Loadable<PracticeItem?> = Loadable.Loading,
    val circles: Loadable<List<CircleHighlight>> = Loadable.Loading,
    val news: Loadable<List<NewsItem>> = Loadable.Loading,
    val weather: Loadable<WeatherSnapshot?> = Loadable.Loading,
    val scam: Loadable<ScamAlert?> = Loadable.Loading,
    val seva: Loadable<SevaMatch?> = Loadable.Loading,
    val liveRoom: Loadable<LiveRoom?> = Loadable.Loading,

    val creditPrompt: CreditPrompt = CreditPrompt.NotToday,
    val connection: Connection = Connection.Checking,
    /** Non-null when even the cache could not be read; the screen offers a retry. */
    val error: String? = null,
) {
    fun loadableFor(section: TodaySection): Loadable<*>? = when (section) {
        TodaySection.Pension -> pension
        TodaySection.Reminders -> reminders
        TodaySection.Practice -> practice
        TodaySection.Circles -> circles
        TodaySection.News -> news
        TodaySection.Weather -> weather
        TodaySection.Scam -> scam
        TodaySection.Seva -> seva
        TodaySection.LiveRoom -> liveRoom
        // Check-in is driven by its own ViewModel and the greeting is not a card.
        TodaySection.CheckIn -> null
    }
}

/** How many "No" answers open the grievance helper. Plan 6.2. */
const val GRIEVANCE_OFFER_AFTER_NO = 5

class TodayViewModel(
    private val feed: TodayFeedRepository,
    /** The phone's half. See the boundary note in [TodayModels]. */
    private val onDevice: OnDevicePensionSource,
    /** Injected, as in the check-in slice, so a test can pin the day. */
    private val today: String = LocalDay.today(),
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    /** Consecutive "not yet" answers, kept in memory for this process. */
    private var noStreak: Int = 0

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // The two sources are independent and neither blocks the other: a
            // failure to read the on-device safe must not stop the news card, and
            // vice versa. Plan 6.2's cards are peers, not a waterfall.
            val remote = async { runCatching { feed.load() } }
            val local = async { runCatching { onDevice.read() } }

            val remoteResult = remote.await()
            val localSnapshot = local.await().getOrNull()

            remoteResult
                .onSuccess { snapshot -> apply(snapshot, localSnapshot, fromCache = false) }
                .onFailure { error ->
                    // Plan 13: Today is offline-first. A cache-first repository
                    // usually still returns here with old data, so this branch is
                    // the "no network AND no cache" case. Say so plainly instead
                    // of spinning: a pensioner who thinks the app has frozen will
                    // uninstall it, and a pensioner who thinks their pension card
                    // is broken will call their daughter.
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Could not load today",
                            connection = Connection.Offline(savedAt = null),
                        )
                    }
                }
        }
    }

    /** "Try again" on the offline banner. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { feed.refresh() }
                .onSuccess { snapshot -> apply(snapshot, onDevice.read(), fromCache = false) }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Still no connection",
                            connection = Connection.Offline(savedAt = it.connection.savedAtOrNull()),
                        )
                    }
                }
        }
    }

    /** Plan 6.2's "Edit Today". */
    fun setHidden(hidden: Boolean, section: TodaySection) {
        val previous = _state.value.hidden
        _state.update {
            it.copy(
                hidden = if (hidden) previous + section else previous - section,
            )
        }
        viewModelScope.launch { runCatching { feed.setHidden(hidden, section) } }
    }

    /**
     * The credit-day prompt. Yes clears the streak; No adds to it, and five
     * consecutive No's swap the prompt for the grievance helper.
     */
    fun answerCredit(credited: Boolean) {
        val day = today
        noStreak = if (credited) 0 else noStreak + 1
        _state.update {
            it.copy(
                creditPrompt = if (noStreak >= GRIEVANCE_OFFER_AFTER_NO) {
                    CreditPrompt.OfferGrievance(noStreak)
                } else {
                    CreditPrompt.Answered(saidNo = !credited)
                },
            )
        }
        viewModelScope.launch { runCatching { feed.recordCreditAnswer(credited, day) } }
    }

    /** A fresh open of the screen, e.g. after returning from another tab. */
    fun onShown() = refresh()

    private fun apply(
        snapshot: TodayFeed,
        local: PensionSnapshot?,
        fromCache: Boolean,
    ) {
        val source = if (fromCache) FeedSource.Cache else FeedSource.Live
        val cachedAt = snapshot.cachedAt
        val isCreditDayToday = isCreditDay(snapshot.creditDayOfMonth, today)
        // Plan 6.2 pins the pension card to the top on credit day and in a DR
        // revision week. A revision week is a property of the rate row's own
        // effective date, not a flag the server has to remember to send.
        val isDrRevisionWeek = isRevisionWeek(snapshot.dr.effectiveFrom, today)

        _state.update { current ->
            val card = PensionCard(
                creditDayOfMonth = snapshot.creditDayOfMonth,
                nextCreditDate = nextCreditDate(snapshot.creditDayOfMonth, today),
                isCreditDayToday = isCreditDayToday,
                dr = snapshot.dr,
                headline = snapshot.headline,
                local = local,
            )
            current.copy(
                loading = false,
                addressAs = snapshot.addressAs,
                dateLabel = snapshot.dateLabel,
                vratLabel = snapshot.vratLabel,
                pension = Loadable.Ready(card, source, cachedAt),
                reminders = snapshot.reminders.loadable(source, cachedAt),
                practice = snapshot.practice.loadable(source, cachedAt),
                circles = snapshot.circles.loadable(source, cachedAt),
                news = snapshot.news.loadable(source, cachedAt),
                weather = snapshot.weather.loadable(source, cachedAt),
                scam = snapshot.scamAlert.loadable(source, cachedAt),
                seva = snapshot.sevaMatch.loadable(source, cachedAt),
                liveRoom = snapshot.liveRoom.loadable(source, cachedAt),
                order = TodayRanker.rank(
                    opens = snapshot.opensIn14Days,
                    lastTapped = snapshot.lastTapped,
                    isCreditDayToday = isCreditDayToday,
                    isDrRevisionWeek = isDrRevisionWeek,
                    today = today,
                ),
                creditPrompt = if (isCreditDayToday && noStreak < GRIEVANCE_OFFER_AFTER_NO) {
                    CreditPrompt.Due
                } else {
                    current.creditPrompt
                },
                connection = if (fromCache) Connection.Offline(cachedAt) else Connection.Online,
                error = null,
            )
        }
    }

    private fun Connection.savedAtOrNull(): Instant? = (this as? Connection.Offline)?.savedAt
}

/**
 * Plan section 6.2's order logic, in one pure function.
 *
 * A later edit that drops this and renders the sections in enum order would not
 * fail a test; it would quietly stop the pension card rising to the top in the
 * two weeks when a pensioner most needs it, which is the whole reason the rule
 * exists. Keep it pure and keep it tested.
 */
object TodayRanker {

    /** "Cards never tapped in 30 days move down." */
    const val DEMOTE_AFTER_DAYS: Long = 30

    fun rank(
        opens: Map<TodaySection, Int>,
        lastTapped: Map<TodaySection, Instant>,
        isCreditDayToday: Boolean,
        isDrRevisionWeek: Boolean,
        today: String,
    ): List<TodaySection> {
        val ranked = CARDS.sortedWith(
            compareBy(
                // Pinned: pension on credit day and in a revision week.
                { if (it == TodaySection.Pension && (isCreditDayToday || isDrRevisionWeek)) 0 else 1 },
                // Then, never-tapped-in-30-days goes last.
                { if (isStale(it, lastTapped, today)) 1 else 0 },
                // Then the plan's actual signal: opens in the last 14 days.
                { -(opens[it] ?: 0) },
            ),
        )
        return ranked
    }

    /**
     * Whether the card has been untouched for [DEMOTE_AFTER_DAYS].
     *
     * Never tapped counts as stale: a card the user has never opened has no
     * evidence either way, and plan 6.2's default is to let the ones they do open
     * rise above it.
     */
    private fun isStale(section: TodaySection, lastTapped: Map<TodaySection, Instant>, today: String): Boolean {
        val last = lastTapped[section] ?: return true
        val days = daysBetween(last, today.toInstantOrNull() ?: return false)
        return days > DEMOTE_AFTER_DAYS
    }

    private val CARDS = listOf(
        TodaySection.Pension,
        TodaySection.Reminders,
        TodaySection.Practice,
        TodaySection.Circles,
        TodaySection.LiveRoom,
        TodaySection.Seva,
        TodaySection.Scam,
        TodaySection.Weather,
        TodaySection.News,
    )
}

/** Today, as the local calendar day. A UTC default would be the wrong day east or west of Greenwich. */
object LocalDay {
    fun today(): String = kotlinx.datetime.Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
}

private fun isCreditDay(creditDayOfMonth: Int, today: String): Boolean {
    val day = today.substringAfterLast('-').toIntOrNull() ?: return false
    return day == creditDayOfMonth.coerceIn(1, 28)
}

/**
 * The next date the pension is due, worked out from the credit day the user
 * entered (plan 6.2's first pension line).
 *
 * Clamped to the 28th: a pensioner who entered the 31st because "the money comes
 * at the end of the month" must not see a card claiming a credit date that does
 * not exist in some months, and a card that is confidently wrong about when the
 * money lands is worse than a card that says "you told us the 1st".
 */
private fun nextCreditDate(creditDayOfMonth: Int, today: String): Instant? {
    val zone = TimeZone.currentSystemDefault()
    val date = today.toLocalDateOrNull() ?: return null
    val day = creditDayOfMonth.coerceIn(1, 28)
    val thisMonth = runCatching { LocalDate(date.year, date.monthNumber, day) }.getOrNull()
        ?: return null
    val next = if (thisMonth >= date) thisMonth else thisMonth.plus(1, DateTimeUnit.MONTH)
    return next.atStartOfDayIn(zone)
}

private fun isRevisionWeek(effectiveFrom: Instant, today: String): Boolean {
    // A revision week is the seven days from the rate's own effective date. The
    // rate row is the admin's record of when the order took effect, so the screen
    // never has to be told "this is a revision week" by a server that might forget.
    //
    // Day arithmetic happens on LocalDate, not Instant: kotlinx-datetime's
    // `Instant.plus` only takes time-based units, and a revision week is a
    // calendar week in the pensioner's zone, not 168 fixed hours.
    val zone = TimeZone.currentSystemDefault()
    val todayDate = today.toLocalDateOrNull() ?: return false
    val effectiveDate = effectiveFrom.toLocalDateTime(zone).date
    return todayDate >= effectiveDate && todayDate < effectiveDate.plus(7, DateTimeUnit.DAY)
}

private fun daysBetween(earlier: Instant, later: Instant): Long {
    val zone = TimeZone.currentSystemDefault()
    return earlier.toLocalDateTime(zone).date.daysUntil(later.toLocalDateTime(zone).date)
}

private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

private fun String.toInstantOrNull(): Instant? =
    toLocalDateOrNull()?.atStartOfDayIn(TimeZone.currentSystemDefault())

/** A list is "ready and empty", not a failure. */
private fun <T> List<T>.loadable(source: FeedSource, cachedAt: Instant?): Loadable<List<T>> =
    Loadable.Ready(this, source, cachedAt)

private fun <T> T?.loadable(source: FeedSource, cachedAt: Instant?): Loadable<T?> =
    Loadable.Ready(this, source, cachedAt)
