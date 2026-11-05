package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AvailabilityWindow
import `in`.doosripari.core.model.GeoPoint
import `in`.doosripari.core.model.ListingMode
import `in`.doosripari.core.model.ListingStatus
import `in`.doosripari.core.model.ListingType
import `in`.doosripari.core.model.MatchReason
import `in`.doosripari.core.model.MatchResult
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.Tier
import `in`.doosripari.core.model.distanceKm
import kotlin.math.roundToInt

/**
 * The Anubhav board. Plan section 6.8.
 *
 * Two rules shape this file, both stated in the plan and both easy to lose in
 * a search screen.
 *
 * **Free, barter and paid are three modes, not three prices.**
 * [ListingMode.Free] is a first-class mode so that a volunteer offer is never
 * filtered out of a paid worker's search by sorting on an amount. A board that
 * sorted by price would bury every "I will sit with you for an hour" offer
 * under the paid work, and those offers are the ones this audience is most
 * likely to want. `core-model` says exactly this in its file header, and
 * [ListingOffer] is the type that keeps it true: a free listing has an amount
 * of zero *and says so*, which is not the same as a paid listing whose price
 * has not been filled in yet.
 *
 * **A filter is the user's decision; a score is ours.**
 * Somebody who has typed a radius of five kilometres has asked for five
 * kilometres, and the board honours it. But everything that comes back still
 * carries a [MatchResult], including
 * [MatchReason.OutsideRadius] on the ones that did not make the cut, so the
 * screen can offer "four more are further than that" instead of quietly
 * presenting a shorter list as if it were the whole board. Nothing on this
 * board is hidden by a score.
 */
data class ListingQuery(
    /** Free text over the title and the body. */
    val text: String = "",

    /** Empty means every type. Section 6.8 has five. */
    val types: Set<ListingType> = emptySet(),

    /** Empty means every mode, which is the point — see the file header. */
    val modes: Set<ListingMode> = emptySet(),

    val tags: Set<String> = emptySet(),

    val cityId: Int? = null,

    /**
     * Where the seeker is. Null means the device has not shared a location,
     * and then nothing is measured against distance.
     */
    val origin: GeoPoint? = null,

    /**
     * The radius the seeker typed. Null by default, and deliberately: a
     * distance filter that is on unless the user turns it off would be hiding
     * listings for a reason nobody asked for.
     */
    val maxDistanceKm: Double? = null,

    /**
     * "What I have time for" — a first-class filter, not a sort key.
     *
     * These are the seeker's *free hours*, and `Availability.kt` is the reason
     * that is not the same thing as a working day. A board filter that
     * defaulted to 09:00–17:00 would return an empty board to almost every
     * person using the app.
     */
    val freeHours: List<AvailabilityWindow> = emptyList(),

    /**
     * The seeker's own standing, for [SevaListing.freeForTiers].
     *
     * A paid listing that lists tiers is saying "free to members of these
     * tiers" — `core-model`'s `isFreeToMe`. Without this the board would show
     * a paid card to somebody the poster has explicitly waived the fee for,
     * which is a small way of being unfair to both of them. Null means we do
     * not know the seeker's tier yet, and then the filter stands aside.
     */
    val seekerTier: Tier? = null,

    /** Off by default: filled and withdrawn work is real, but it is not a search result. */
    val includeClosed: Boolean = false,
) {
    /** Whether this seeker has narrowed by time at all. */
    val hasAvailabilityFilter: Boolean get() = freeHours.isNotEmpty()
}

/**
 * Why a listing is not in the main list.
 *
 * Typed, and the UI is expected to render it. A list that silently drops rows
 * teaches a user the board is empty, which is the one thing a board for this
 * audience must never be.
 */
enum class ExclusionReason(val code: String) {
    /** The user asked for this type or mode and this is not it. */
    Type("type"),
    Mode("mode"),
    Tag("tag"),
    City("city"),

    /** Nothing in the title, the body or the tags contains what the seeker typed. */
    Text("text"),

    /** Paid, and the poster waived the fee only for other tiers. */
    Tier("tier"),

    /**
     * No overlap with the times the user has free. Shown with a "show anyway"
     * rather than hidden, because the availability on a listing is often
     * aspirational and the seeker is the only one who knows.
     */
    Availability("availability"),

    /**
     * Further than the radius the seeker typed.
     *
     * A filter, and an honest one — the user asked for five kilometres and
     * this is thirty — but it is a *typed* exclusion rather than a silent
     * one, and [ListingBoard.revealed] will hand it back. Note what is not on
     * this enum: there is no "low score". Nothing on this board is removed
     * because a scoring function said so.
     */
    OutsideRadius("outside_radius"),

    /** Section 6.8's "Never pay to get work", enforced before anything else. */
    AsksSeekerToPay("asks_seeker_to_pay"),

    /** Filled, withdrawn or removed. Kept for the audit, not for the board. */
    NotOpen("not_open"),
}

val ExclusionReason.message: String
    get() = when (this) {
        ExclusionReason.Type -> "Not the kind of work you chose."
        ExclusionReason.Mode -> "Not offered the way you asked for."
        ExclusionReason.Tag -> "Does not carry the tags you chose."
        ExclusionReason.City -> "In another city."
        ExclusionReason.Text -> "Does not mention what you searched for."
        ExclusionReason.Tier -> "Paid, and the free part is for other members."
        ExclusionReason.Availability -> "Outside the times you said you have."
        ExclusionReason.OutsideRadius -> "Further away than the distance you asked for."
        ExclusionReason.AsksSeekerToPay ->
            "This asks you to pay, so it is not shown. Never pay to get work."

        ExclusionReason.NotOpen -> "Already taken or no longer offered."
    }

/**
 * A listing with its score. The pairing is not optional: a board row without
 * the reasons is a black box, and [MatchResult] is the only place the reasons
 * live.
 */
data class ScoredListing(
    val listing: SevaListing,
    val match: MatchResult,
    /** Kilometres from [ListingQuery.origin], or null when either side has no point. */
    val distanceKm: Double? = null,
) {
    val offer: ListingOffer get() = listing.offer()
}

/** A listing that was filtered out, kept with the reason it was filtered out. */
data class ExcludedListing(
    val listing: SevaListing,
    val match: MatchResult,
    val reason: ExclusionReason,
    val distanceKm: Double? = null,
)

/**
 * What a listing is offering, in a form the screen can print.
 *
 * [mode] is never inferred from the amount. `SevaListing.isPaid` in
 * `core-model` is careful to be `mode == Paid && price > 0` for exactly this
 * reason, and this type carries the awkward case explicitly: a Paid listing
 * with nothing in the price field is [ListingOffer.Misconfigured], and must be
 * shown as "price not set" rather than as "Free". Printing Free there would
 * undo the entire reason [ListingMode] exists.
 */
sealed interface ListingOffer {

    /** Somebody offering their time for nothing. Not a zero price. */
    data object Free : ListingOffer

    /** Exchanging time rather than money, e.g. help in exchange for tuition. */
    data object Barter : ListingOffer

    data class Paid(val amount: Double) : ListingOffer

    /**
     * [ListingMode.Paid] with no amount. A posting mistake, and the only place
     * in the app where an anti-scam message and a data error meet.
     */
    data class Misconfigured(val title: String) : ListingOffer
}

/**
 * Plan section 6.8's hard-coded anti-scam line, shown on every paid listing.
 *
 * The plan puts it in a list of three rules that are not negotiable, so it is
 * a constant rather than a string in a layout file where a translator could
 * drop it.
 */
const val NEVER_PAY_NOTICE: String = "Never pay to get work."

/**
 * Turn a listing into what it is offering.
 */
fun SevaListing.offer(): ListingOffer = when {
    mode == ListingMode.Free -> ListingOffer.Free
    mode == ListingMode.Barter -> ListingOffer.Barter
    price > 0.0 -> ListingOffer.Paid(price)
    else -> ListingOffer.Misconfigured(title)
}

/**
 * The board.
 *
 * A listing is dropped from [ListingBoard.shown] only when the *user* asked for
 * it to be dropped — a type they did not tick, a mode they did not tick, a tag
 * they did not tick, a radius they typed — or when it is closed, or when
 * section 6.8's anti-scam rule stops it.
 *
 * **A low score never removes anything.** That is the whole of section 6.8's
 * "matching is a score, never a filter", and it is why there is no
 * "scored badly" member on [ExclusionReason]: a listing scoring 3 out of 100
 * is on the board, in its place, with its reasons, and the person reading it
 * can decide that 3 is fine for what they want.
 */
data class ListingBoard(
    val shown: List<ScoredListing>,
    val excluded: List<ExcludedListing>,
    /** How many listings were considered, before any filtering. */
    val considered: Int,
    val query: ListingQuery = ListingQuery(),
) {
    /**
     * Everything the user would see if they asked to see everything.
     *
     * The soft exclusions come back — availability and distance — and the hard
     * ones do not, because there is a difference between "you said five
     * kilometres and this is thirty, here it is anyway" and "you said you
     * wanted mentoring and this is somebody selling vegetables". A screen calls
     * this from behind a labelled control, never on its own.
     */
    fun revealed(): List<ScoredListing> = (shown + excluded
        .filter { it.reason in SOFT_EXCLUSIONS }
        .map { ScoredListing(it.listing, it.match, it.distanceKm) })
        .sortedWith(SHOWN_ORDER)

    /** How many were left out by distance alone, for "four more are further". */
    val beyondRadius: List<ScoredListing>
        get() = excluded.filter { it.reason == ExclusionReason.OutsideRadius }
            .map { ScoredListing(it.listing, it.match, it.distanceKm) }

    val isEmpty: Boolean get() = shown.isEmpty()

    /**
     * True when the board is empty *and* something was filtered out, which is
     * the case that needs a different message from "nobody has posted yet".
     */
    val wasFiltered: Boolean get() = shown.isEmpty() && excluded.isNotEmpty()

    companion object {
        /** Exclusions a "show me the rest" control is allowed to undo. */
        private val SOFT_EXCLUSIONS = setOf(ExclusionReason.Availability, ExclusionReason.OutsideRadius)

        /**
         * Highest score first, then newest, then by id.
         *
         * **Never by amount.** A board sorted by price buries every volunteer
         * offer under the paid work, which is the exact mistake
         * [ListingMode.Free] exists to prevent. The id tie-break is there so
         * that two platforms given the same listings show them in the same
         * order, which is a small thing until somebody tells their neighbour
         * "it was second on the list".
         */
        internal val SHOWN_ORDER: Comparator<ScoredListing> =
            compareByDescending<ScoredListing> { it.match.score }
                // `createdAt` is nullable in core-model — a listing imported
                // before we stamped it has none — so a missing timestamp sorts
                // as the oldest thing on the board rather than throwing on a
                // compare.
                .thenByDescending { it.listing.createdAt?.epochSeconds ?: Long.MIN_VALUE }
                .thenByDescending { it.listing.id }
    }
}

/**
 * Building the board.
 */
object ListingBoardBuilder {

    /**
     * Score, filter and order everything.
     *
     * [providers] maps a listing's `ownerId` to that person's
     * [MatchProfile]. It is a map rather than a field on the listing because
     * `core-model` has no `departmentId` on [SevaListing] — without the profile
     * the department and city reasons cannot be produced at all. Passing an
     * empty map is allowed and costs those two reasons, nothing else.
     */
    fun build(
        listings: List<SevaListing>,
        seeker: MatchProfile,
        providers: Map<Int, MatchProfile> = emptyMap(),
        query: ListingQuery = ListingQuery(),
    ): ListingBoard {
        val shown = mutableListOf<ScoredListing>()
        val excluded = mutableListOf<ExcludedListing>()

        for (listing in listings) {
            val provider = providers[listing.ownerId]
            val match = SevaMatcher.score(listing, seeker, provider, query.maxDistanceKm)
            val km = distanceBetween(query.origin, listing.location ?: provider?.location)

            val scored = ScoredListing(listing, match, km)
            val reason = exclusionFor(listing, provider, query, match, km)
            if (reason == null) shown += scored else excluded += ExcludedListing(listing, match, reason, km)
        }

        return ListingBoard(
            shown = shown.sortedWith(ListingBoard.SHOWN_ORDER),
            excluded = excluded.sortedByDescending { it.listing.id },
            considered = listings.size,
            query = query,
        )
    }

    /**
     * The one reason this listing is not shown, or null when it is.
     *
     * Ordered most-decisive first. The anti-scam rule is checked before
     * everything else including whether the listing is open, because a listing
     * that asks a pensioner for money is not a listing with a mild defect, it
     * is the thing section 6.8 tells us to block.
     *
     * [match] is passed in rather than recomputed so the board and the card
     * cannot disagree about why a listing is what it is.
     */
    private fun exclusionFor(
        listing: SevaListing,
        provider: MatchProfile?,
        query: ListingQuery,
        match: MatchResult,
        km: Double?,
    ): ExclusionReason? = when {
        ScamSignals.looksLikePaymentRequest(listing) -> ExclusionReason.AsksSeekerToPay

        !query.includeClosed && listing.status != ListingStatus.Open -> ExclusionReason.NotOpen

        query.text.isNotBlank() && !listing.mentions(query.text) -> ExclusionReason.Text

        query.types.isNotEmpty() && listing.type !in query.types -> ExclusionReason.Type

        query.modes.isNotEmpty() && listing.mode !in query.modes -> ExclusionReason.Mode

        !query.tags.isEmpty() && query.tags.none { listing.hasTag(it) } -> ExclusionReason.Tag

        // A listing carries no city of its own. It is the poster's city, which
        // means a city filter needs the profile we were already given, and a
        // poster we have not loaded cannot be placed. Those stay on the board
        // rather than being filtered out by a value we do not have.
        query.cityId != null && provider != null && query.cityId != provider.cityId ->
            ExclusionReason.City

        query.seekerTier != null && listing.isPaid && listing.freeForTiers.isNotEmpty() &&
            query.seekerTier !in listing.freeForTiers -> ExclusionReason.Tier

        // The radius the seeker typed. Honoured, because they asked for it —
        // and recorded as a typed reason rather than dropped, because
        // `MatchResult` is already carrying `MatchReason.OutsideRadius` for
        // exactly this listing and throwing that away would be the silent
        // hiding the plan forbids.
        query.maxDistanceKm != null && km != null && km > query.maxDistanceKm ->
            ExclusionReason.OutsideRadius

        // Any overlap with any of the free hours the seeker named, across all
        // of them at once — a seeker free Tuesday *and* Thursday is not made
        // unavailable by only the first being checked.
        query.hasAvailabilityFilter &&
            AvailabilityMatcher.coverage(listing.availability, query.freeHours) <= 0.0 ->
            ExclusionReason.Availability

        else -> null
    }

    private fun SevaListing.hasTag(tag: String): Boolean =
        tags.any { it.equals(tag.trim(), ignoreCase = true) }

    /**
     * Free-text match over the three fields a poster actually writes.
     *
     * Case- and spacing-insensitive, because somebody typing on a phone with
     * large type gets capitals wrong and should not be told the board is empty
     * for it. Substring rather than word matching, so "tuition" finds
     * "tuition classes" and a half-typed word still narrows the list as the
     * user types.
     */
    private fun SevaListing.mentions(phrase: String): Boolean {
        val needle = phrase.trim().lowercase()
        if (needle.isEmpty()) return true
        return title.lowercase().contains(needle) ||
            body.lowercase().contains(needle) ||
            tags.any { it.lowercase().contains(needle) }
    }

    private fun distanceBetween(a: GeoPoint?, b: GeoPoint?): Double? =
        if (a != null && b != null) distanceKm(a, b) else null
}

/**
 * Section 6.8: "any listing asking the pensioner to pay is blocked", and
 * section 15 repeats it under automatic checks.
 *
 * This is a **first-line screen, not a defence**. It reads the words a poster
 * wrote and it will miss anything clever. Its job is to stop the obvious case
 * reaching a pensioner in the first place and to give a moderator something to
 * look at; the real check is the one a person does. Saying so here is more
 * useful than a comment that implies the problem has been solved.
 */
object ScamSignals {

    /**
     * Phrases that mean somebody is asking the reader for money.
     *
     * Checked against the title and the body, lower-cased, in both Latin
     * script and Devanagari, because a post aimed at this audience may be
     * written in either and the second is not a translation of the first.
     */
    private val PAYMENT_PHRASES: List<String> = listOf(
        "pay", "payment", "advance", "deposit", "registration fee", "fee",
        "charge", "refund", "upfront", "bank account", "upi", "gpay",
        "पैसे", "पैसा", "भुगतान", "जमा", "पहले भेजें", "रुपये", "अग्रिम",
    )

    /**
     * The context that makes "pay" a payment *request* rather than a mention.
     *
     * "I did not have to pay anything" and "she paid me a visit" are ordinary
     * sentences that must not trip this, so a phrase only counts when it sits
     * near a first person asking or a second person offering.
     */
    private val REQUEST_CONTEXT: List<String> = listOf(
        "you must", "you have to", "you need to", "please pay", "send money",
        "send me", "pay me", "pay Rs", "pay ₹", "charge you", "before you",
        "you will have to pay", "aapko", "भेजें", "भुगतान कर",
    )

    /** Whether the listing reads as asking the reader for money. */
    fun looksLikePaymentRequest(listing: SevaListing): Boolean {
        val text = (listing.title + " " + listing.body).lowercase()
        val mentionsMoney = PAYMENT_PHRASES.any { text.contains(it) }
        val asksForIt = REQUEST_CONTEXT.any { text.contains(it) }
        return mentionsMoney && asksForIt
    }

    /**
     * The scam flag on a listing, for a moderator's queue.
     *
     * Deliberately returns the *phrases* rather than a boolean, so a moderator
     * can see why it fired and overrule it. A moderation queue that only shows
     * "suspicious" gets ignored.
     */
    fun matchedPhrases(listing: SevaListing): List<String> {
        val text = (listing.title + " " + listing.body).lowercase()
        return (PAYMENT_PHRASES + REQUEST_CONTEXT).filter { text.contains(it) }
    }
}

/**
 * Small display helpers the board rows need, kept here so two platforms cannot
 * disagree about what a rupee amount looks like.
 */
object ListingFormat {

    /**
     * The amount, or a word.
     *
     * Free and barter are words, not "₹0". A zero with a rupee sign in front
     * of it is a price, and a price of zero is a different statement from an
     * offer of help.
     */
    fun amountLabel(offer: ListingOffer): String = when (offer) {
        is ListingOffer.Free -> "Free"
        is ListingOffer.Barter -> "Barter"
        is ListingOffer.Paid -> "₹${offer.amount.toRupees()}"
        is ListingOffer.Misconfigured -> "Price not set"
    }

    /** Whether the anti-scam line has to be on this card. */
    fun needsScamNotice(offer: ListingOffer): Boolean = offer is ListingOffer.Paid

    /**
     * "3 km away", or nothing when there is no location to measure from.
     *
     * A blank rather than a dash: an empty cell reads as "we don't know" where
     * a dash reads as something that went wrong.
     */
    fun distanceLabel(km: Double?): String = when {
        km == null -> ""
        km < 1.0 -> "less than 1 km away"
        else -> "${km.roundToInt()} km away"
    }
}

/** Rounds to whole rupees, which is the smallest unit that is ever settled. */
private fun Double.toRupees(): Double = roundToInt().toDouble()
