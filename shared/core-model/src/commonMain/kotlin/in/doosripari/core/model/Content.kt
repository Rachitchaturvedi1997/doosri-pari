package `in`.doosripari.core.model

import kotlinx.datetime.Instant

/**
 * Everything that does not belong to a feature of its own: voice routing,
 * Meri Kahani, the scam shield, news, subscriptions and the audit log.
 *
 * Grouped in one file rather than five because each of these is small and they
 * share one property — they are all *content or commerce* rather than state
 * machines, and none of them is load-bearing in the way check-in is.
 */

// ------------------------------------------------------------ Bolo (voice) --

/**
 * What "Bolo" heard. Plan section 5: voice is an ACTION, not a tab, so this
 * classifies an utterance and routes it. The module never converses.
 *
 * [Confidence] matters more here than anywhere else. A misheard word in a
 * pensioner's mouth must not open the SOS flow by accident, so [Emergency] is
 * handled by a separate path that requires either a very high confidence or an
 * explicit confirmation, and never by the router alone.
 */
enum class VoiceIntent(val code: String) {
    CheckIn("check_in"),
    Emergency("emergency"),
    AskSahayak("ask_sahayak"),
    OpenCalculator("open_calculator"),
    ReadNews("read_news"),
    StartYoga("start_yoga"),
    JoinRoom("join_room"),
    CallFamily("call_family"),
    Unknown("unknown");

    companion object {
        fun fromCode(code: String?): VoiceIntent? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The result of classifying one utterance.
 *
 * Below [CONFIRM_THRESHOLD] the app asks rather than acts. "Did you mean to
 * check in?" is a much better failure than an SOS that was not wanted, and
 * much better than a check-in that was not heard.
 */
data class VoiceRoute(
    val intent: VoiceIntent,
    val confidence: Float,
    /** What the classifier thought was said, for the confirmation prompt. */
    val heard: String = "",
    /** What to open, for intents that route to a screen. */
    val target: String? = null,
) {
    val needsConfirmation: Boolean get() = confidence < CONFIRM_THRESHOLD

    /**
     * Whether this may raise an emergency without asking.
     *
     * Only the highest confidence, and only the word itself. Everything else
     * about SOS is confirmation, the same 5-second countdown the button has.
     */
    val mayTriggerEmergencyDirectly: Boolean
        get() = intent == VoiceIntent.Emergency && confidence >= EMERGENCY_THRESHOLD

    companion object {
        const val CONFIRM_THRESHOLD = 0.7f
        const val EMERGENCY_THRESHOLD = 0.92f
    }
}

// ---------------------------------------------------------- Meri Kahani --

/**
 * A life story, told in the user's own voice. Plan section 6.9.
 *
 * [visibility] is the field that matters. These are memories of a 68-year-old
 * and a dead colleague, and the default is [StoryVisibility.OnlyMe]: a story
 * does not become a circle post because it was easy to share. The printed book
 * is a phase-3 feature and is a separate artefact the user approves, not a
 * side effect of recording.
 */
data class Story(
    val id: Int,
    val userId: Int,
    val promptId: Int? = null,
    val audioKey: String,
    /** Filled in asynchronously; null while transcribing. */
    val transcript: String? = null,
    val chapter: Int = 0,
    val visibility: StoryVisibility = StoryVisibility.OnlyMe,
    val isTranscribing: Boolean = false,
) {
    val isShareable: Boolean get() = visibility == StoryVisibility.Circle
}

enum class StoryVisibility(val code: String) {
    OnlyMe("only_me"),
    Circle("circle"),
    /** Has been approved for the printed book. */
    Book("book");

    companion object {
        fun fromCode(code: String?): StoryVisibility? =
            entries.firstOrNull { it.code == code }
    }
}

/** A prompt the app offers. Kept gentle, and never about death by default. */
data class StoryPrompt(
    val id: Int,
    val text: String,
    val language: AppLanguage = AppLanguage.Hindi,
)

/** The printed book, as a thing the user approves. Plan section 6.9. */
data class StoryBook(
    val id: Int,
    val userId: Int,
    val pdfKey: String,
    val chapters: Int,
    val generatedAt: Instant? = null,
)

// ------------------------------------------------------------ scam shield --

/**
 * The scam shield. Plan section 6.12.
 *
 * This exists because retired government employees are a named target: they
 * have a known employer, a known pension, and a known habit of following
 * official-looking instructions. [ScamAlert.isUrgent] marks the two kinds worth
 * interrupting for — a call that is happening now, and a notice that affects
 * everybody in a state.
 */
data class ScamAlert(
    val id: Int,
    val title: String,
    val body: String,
    val language: AppLanguage = AppLanguage.English,
    /** Where it was reported from, kept so it can be re-checked. */
    val source: String? = null,
    val publishedAt: Instant,
    val category: ScamCategory = ScamCategory.Other,
    val isUrgent: Boolean = false,
) {
    val isVerified: Boolean get() = source != null
}

enum class ScamCategory(val code: String) {
    FakeSparshApp("fake_sparsh_app"),
    FakeCustomerCare("fake_customer_care"),
    BankCall("bank_call"),
    Lottery("lottery"),
    Insurance("insurance"),
    Investment("investment"),
    Other("other");

    companion object {
        fun fromCode(code: String?): ScamCategory? =
            entries.firstOrNull { it.code == code }
    }
}

/** A question put to the scam checker. Plan section 12 — never stores PII. */
data class ScamCheckRequest(
    val description: String,
    val hasLink: Boolean = false,
    val hasPhoneNumber: Boolean = false,
    val callerId: String? = null,
)

// --------------------------------------------------------- news and radio --

/** A curated news item. Plan section 6.14. */
data class NewsItem(
    val id: Int,
    val headline: String,
    val summary: String,
    val source: String,
    val url: String? = null,
    val publishedAt: Instant,
    val languages: List<AppLanguage> = emptyList(),
    /** Shown as a large card on the Today screen when true. */
    val isFeatured: Boolean = false,
    val topics: List<String> = emptyList(),
) {
    /**
     * Whether this is worth the user's attention.
     *
     * The `news_curate` job only keeps pension-relevant items, so anything
     * reaching a client has already been through a filter; this is the second
     * gate, on the user's own service type and state.
     */
    fun isRelevantTo(user: UserProfile): Boolean =
        topics.isEmpty() || topics.any { it.equals(user.serviceType.code, ignoreCase = true) }
}

// ------------------------------------------------------------- billing --

/**
 * The family plan. Plan section 17.
 *
 * [coveredUserIds] is the point of the product: one payment, up to six
 * pensioners in one family. Billing goes through Play and the App Store and
 * the server only ever sees a receipt reference — it never handles a card.
 */
data class Subscription(
    val id: Int,
    val payerId: Int,
    val store: BillingStore,
    val productId: String,
    val coveredUserIds: Set<Int> = emptySet(),
    val status: SubscriptionStatus = SubscriptionStatus.None,
    val renewsAt: Instant? = null,
    /** A receipt handle from the store, never a card number. */
    val receiptRef: String? = null,
) {
    val isActive: Boolean get() = status.isActive
    val seatsRemaining: Int get() = (MAX_SEATS - coveredUserIds.size).coerceAtLeast(0)

    companion object {
        const val MAX_SEATS = 6
    }
}

enum class BillingStore(val code: String) {
    Play("play"),
    AppStore("app_store");

    companion object {
        fun fromCode(code: String?): BillingStore? = entries.firstOrNull { it.code == code }
    }
}

enum class SubscriptionStatus(val code: String) {
    None("none"),
    Active("active"),
    /** Lapsed but the family is still inside the free allowance. */
    GracePeriod("grace_period"),
    Cancelled("cancelled"),
    Expired("expired"),
    Refunded("refunded");

    val isActive: Boolean get() = this == Active || this == GracePeriod

    companion object {
        fun fromCode(code: String?): SubscriptionStatus? =
            entries.firstOrNull { it.code == code }
    }
}

// ------------------------------------------------------------ audit log --

/**
 * Who did what. Plan section 10.
 *
 * [ipHash] is a hash, never an address: enough to rate limit and to trace an
 * abuse, not enough to build a location history of a pensioner from their
 * login records.
 */
data class AuditEntry(
    val id: Int,
    val actorId: Int? = null,
    val action: String,
    val target: String? = null,
    val at: Instant,
    val ipHash: String? = null,
)
