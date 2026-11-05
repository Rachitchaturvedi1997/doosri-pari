package `in`.doosripari.feature.today

import kotlinx.datetime.Instant

/**
 * Aaj (Today) — plan sections 4.5, 6.2 and 13.
 *
 * ## The one boundary in this file
 *
 * Plan section 14 and the section 10 note ("These stay on the phone, not on the
 * server: pension amounts and slips") are enforced here by *splitting the
 * repository in two* rather than by remembering not to serialise a field:
 *
 *  * [TodayFeedRepository] — the server. It may return a DR **rate**, because a
 *    rate is a published table the admin maintains for everybody on that service
 *    (plan 6.2: "from the admin table"), and it may return the credit day the
 *    user entered, because that is a profile field and not money. It has no
 *    method that returns a rupee amount belonging to this user, and adding one
 *    would be a change to this interface rather than an omission from a DTO —
 *    which is the only way the rule survives a refactor.
 *  * [OnDevicePensionSource] — the phone. [PensionSnapshot] holds the figures
 *    from the on-device document safe. It is not `@Serializable`, has no DTO, and
 *    is not returned by anything that talks HTTP.
 *
 * Read that as a comment before "tidying" [PensionSnapshot] into the feed DTO
 * "for consistency". A later edit that merges these two interfaces does not
 * produce a lint warning; it puts a pensioner's net pension in the server's
 * Postgres and their `sahayak_questions` hash next to it. The observable
 * consequence is that the app can no longer be launched with the family plan's
 * promise of keeping financial data local, and nobody notices until a breach.
 *
 * ## Strings
 *
 * Every string below is a Kotlin literal rather than a `R.string` resource.
 * `res/values/strings.xml` is owned by another slice and was not writable for
 * this one, and plan section 6.16 needs ten human-reviewed languages — so these
 * literals are a known debt, flagged in each file rather than quietly shipped.
 * Extract before the translation pass or a Hindi reviewer inherits a Kotlin file
 * and a wrong answer about a rupee amount.
 */

// -------------------------------------------------------------- load state --

/**
 * Where a section's data came from. Plan section 13 puts Today in the offline
 * column, so [Cache] is a normal state here and not a failure: a pensioner on a
 * train must still see yesterday's DR rate and this morning's weather.
 */
enum class FeedSource { Live, Cache }

/**
 * One card's state, separately from every other card's.
 *
 * A section that failed and a section with nothing in it must not look alike. A
 * pensioner who sees an empty "From your circles" card thinks they have no
 * circles; one who sees a "could not load" line on the pension card thinks their
 * pension is in trouble. So [Failed] is a distinct case rather than a null value,
 * and [Ready] carrying an empty list is what "nothing to show" looks like — the
 * screen renders [in.doosripari.ui.design.DoosriEmptyState] for that.
 */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(
        val value: T,
        val source: FeedSource = FeedSource.Live,
        val cachedAt: Instant? = null,
    ) : Loadable<T>
}

@Suppress("UNCHECKED_CAST")
fun <T> Loadable<T>.valueOrNull(): T? = (this as? Loadable.Ready<T>)?.value

fun <T> Loadable<T>.isStale(): Boolean =
    this is Loadable.Ready && source == FeedSource.Cache

// ----------------------------------------------------------------- sections --

enum class ReminderKind {
    LifeCertificate, Tax, CghsRenewal, Medical,
    CommutationRestoration, AdditionalPension, Other,
}

data class ReminderItem(
    val id: String,
    val title: String,
    /** Already a display string: "7:00 pm" and "1 November" in the user's locale. */
    val whenLabel: String,
    val kind: ReminderKind,
)

data class PracticeItem(
    val id: String,
    val title: String,
    val minutes: Int,
    val subtitle: String,
)

enum class CircleHighlightKind { Post, Birthday, Retirement }

data class CircleHighlight(
    val id: String,
    val circle: String,
    val line: String,
    val kind: CircleHighlightKind,
)

data class NewsItem(
    val id: String,
    val headline: String,
    val source: String,
    val publishedAt: Instant,
)

data class WeatherSnapshot(
    val city: String,
    val summary: String,
    val minC: Int,
    val maxC: Int,
    val airQuality: String?,
    val observedAt: Instant,
)

data class ScamAlert(val id: String, val title: String, val body: String)
data class SevaMatch(val id: String, val title: String, val why: String)
data class LiveRoom(val id: String, val title: String, val host: String, val listeners: Int)

/**
 * The current dearness rate, from the admin table (plan 6.2, 6.5).
 *
 * A *rate*, not an amount: 58% is the same fact for a hundred thousand central
 * pensioners, and publishing it is what plan section 6.4's corpus does with the
 * DoPPW order it comes from. [sourceNote] names that order, because a rate
 * without its order is the thing this app is supposed to stop people repeating
 * from memory.
 */
data class DrRateRow(
    val ratePercent: Double,
    val effectiveFrom: Instant,
    val sourceNote: String,
)

data class PensionHeadline(
    val id: String,
    val headline: String,
    val source: String,
    val publishedAt: Instant,
)

// ------------------------------------------------------------- the boundary --

/**
 * This user's own pension figures. **PHONE ONLY.**
 *
 * Read from the on-device document safe (plan 6.5) or typed in by the user and
 * kept locally. Not serialisable, not on any repository that has a base URL, and
 * never written to the server — see the note at the top of this file.
 *
 * Every field here is nullable-or-present rather than defaulted, because a
 * pensioner who has never opened the pension-slip reader must get an honest
 * "we do not know yet" from the pension card, not a ₹0 that reads like a
 * garnishment.
 */
data class PensionSnapshot(
    val basicPensionRupees: Double?,
    val netPensionRupees: Double?,
    val lastCreditedOn: Instant?,
    /** When these numbers were last read from the phone, not when the server saw them. */
    val readOn: Instant?,
) {
    val hasAnyFigure: Boolean
        get() = basicPensionRupees != null || netPensionRupees != null
}

// ------------------------------------------------------------------ inputs --

/**
 * What the pension card shows, assembled by the ViewModel from [TodayFeedRepository]
 * (rate, credit day, headline) and [OnDevicePensionSource] ([local]).
 *
 * The split is visible in the type on purpose: a reader can see at a glance
 * which half of the card came off the phone.
 */
data class PensionCard(
    /** The credit day the user entered — a profile field, so it is server-side. */
    val creditDayOfMonth: Int,
    val nextCreditDate: Instant?,
    val isCreditDayToday: Boolean,
    val dr: DrRateRow,
    val headline: PensionHeadline?,
    /** The phone's contribution. Null until the user has read a slip. */
    val local: PensionSnapshot?,
)

/**
 * Plan 6.2's credit-day rule, and the state machine around it.
 *
 * "On credit day, a 'Did your pension credit?' prompt; five days of 'No' links to
 * the grievance helper." The five days are the whole reason this is a type rather
 * than a boolean: a single "No" is noise, and a persistent "No" is a pensioner
 * who needs a grievance drafted for them, which is a different screen.
 */
sealed interface CreditPrompt {
    /** Not the credit day; nothing to ask. */
    data object NotToday : CreditPrompt

    /** Credit day, and the user has not been asked yet. */
    data object Due : CreditPrompt

    /** The user answered today. */
    data class Answered(val saidNo: Boolean) : CreditPrompt

    /**
     * Five "No" answers with no "Yes" between them. The card now offers the
     * grievance helper rather than asking a sixth time.
     */
    data class OfferGrievance(val noStreak: Int) : CreditPrompt
}

/** Plan 6.2's order logic, reduced to the inputs it actually uses. */
data class TodayFeed(
    val addressAs: String,
    val dateLabel: String,
    /** Tithi or vrat, only when the user has turned it on (plan 6.2 card 1). */
    val vratLabel: String?,
    val creditDayOfMonth: Int,
    val dr: DrRateRow,
    val headline: PensionHeadline?,
    val reminders: List<ReminderItem>,
    val practice: PracticeItem?,
    val circles: List<CircleHighlight>,
    val news: List<NewsItem>,
    val weather: WeatherSnapshot?,
    val scamAlert: ScamAlert?,
    val sevaMatch: SevaMatch?,
    val liveRoom: LiveRoom?,
    /** Section id → times opened in the last 14 days. Plan 6.2's ranking input. */
    val opensIn14Days: Map<TodaySection, Int>,
    /** Section id → last time the user tapped it, for the 30-day demotion rule. */
    val lastTapped: Map<TodaySection, Instant>,
    val cachedAt: Instant?,
)

/** Every card on the screen, in one enum so the ranker and "Edit Today" agree. */
enum class TodaySection {
    CheckIn, Pension, Reminders, Practice, Circles, LiveRoom, Seva, Scam, Weather, News;

    /** What "Edit Today" calls it, and what the section header says. */
    val label: String
        get() = when (this) {
            CheckIn -> "Daily check-in"
            Pension -> "Pension"
            Reminders -> "Reminders"
            Practice -> "Today's practice"
            Circles -> "From your circles"
            LiveRoom -> "Live voice room"
            Seva -> "Seva match"
            Scam -> "Scam alert"
            Weather -> "Weather and air"
            News -> "News"
        }
}

// ------------------------------------------------------------- repositories --

/**
 * The server half of Today.
 *
 * An interface, as in the check-in slice, so [TodayViewModel] is constructible
 * from a test with a fake and so a cache-backed implementation can be dropped in
 * without the screen noticing (plan 13: Today works offline).
 *
 * There is deliberately no method here that returns this user's own money. See
 * the boundary note at the top of this file — that absence is the feature.
 */
interface TodayFeedRepository {
    /** Cached first, then refreshed. A cache hit should be cheap enough to return before the network call. */
    suspend fun load(includeHidden: Set<TodaySection> = emptySet()): TodayFeed

    /** "Try again" after an offline banner. */
    suspend fun refresh(): TodayFeed

    /** Plan 6.2's "Edit Today": hide a card. */
    suspend fun setHidden(hidden: Boolean, section: TodaySection)

    /** Feedback for the three buttons on the credit-day prompt. */
    suspend fun recordCreditAnswer(credited: Boolean, on: String)
}

/**
 * The phone half of Today.
 *
 * Reads the on-device safe. The implementation will be encrypted storage
 * (AES-256-GCM behind the Android Keystore, plan 6.5); what matters here is that
 * the port itself has no network sibling.
 */
interface OnDevicePensionSource {
    /** Cached figures, or empty if the user has never read a slip. Never throws. */
    suspend fun read(): PensionSnapshot
}
