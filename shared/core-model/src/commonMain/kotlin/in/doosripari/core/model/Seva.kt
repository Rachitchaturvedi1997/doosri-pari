package `in`.doosripari.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlin.math.abs

/**
 * The Anubhav board — volunteering, mentoring, and paid work. Plan section 6.8.
 *
 * This is the feature that turns the app from a safety net into a community,
 * and it is the one with the most commercial and legal exposure. Three rules
 * run through the whole file:
 *
 *  1. **Money moves through a partner, not between users.** [SevaBooking.paid]
 *    is settled by [Partner] against [SevaPartner.gstinOrReg], never person to
 *    person, and the payouts job only releases money older than 48 hours.
 *  2. **A listing can be free, and "free" is a first-class mode, not a zero
 *    price.** [ListingMode.Free] exists so that a volunteer offer is not
 *    silently filtered out of the paid worker's search by sorting on amount.
 *  3. **Matching is a score, never a filter.** [matchScore] is advisory and the
 *    user can always see everything.
 */

enum class ListingType(val code: String) {
    /** Help wanted — sitting with somebody, shopping, paperwork. */
    Help("help"),
    /** Skills offered: tuition, computer classes, legal advice. */
    Skill("skill"),
    /** Mentoring by somebody senior. */
    Mentor("mentor"),
    /** Room or space offered. */
    Space("space"),
    /** Paid work. */
    Work("work");

    val isPaidCategory: Boolean get() = this == Work

    companion object {
        fun fromCode(code: String?): ListingType =
            entries.firstOrNull { it.code == code } ?: Help
    }
}

/**
 * How a listing is offered. [Free] is separate from a zero price so that
 * "I will help, for nothing" and "₹0 because it is not set yet" cannot be the
 * same row.
 */
enum class ListingMode(val code: String) {
    Free("free"),
    Barter("barter"),
    Paid("paid");

    companion object {
        fun fromCode(code: String?): ListingMode =
            entries.firstOrNull { it.code == code } ?: Free
    }
}

enum class ListingStatus(val code: String) {
    Open("open"),
    Filled("filled"),
    Withdrawn("withdrawn"),
    /** Removed by a moderator; kept for the audit. */
    Removed("removed");

    companion object {
        fun fromCode(code: String?): ListingStatus =
            entries.firstOrNull { it.code == code } ?: Open
    }
}

data class SevaListing(
    val id: Int,
    val ownerId: Int,
    val ownerName: String,
    val type: ListingType,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val languages: List<AppLanguage> = emptyList(),
    val mode: ListingMode = ListingMode.Free,
    val location: GeoPoint? = null,
    /** Search radius. Null means "wherever you are". */
    val radiusKm: Double? = null,
    /** Only paid listings have one. */
    val price: Double = 0.0,
    /** Tiers this is free to, e.g. members only. Empty means everyone. */
    val freeForTiers: List<Tier> = emptyList(),
    val status: ListingStatus = ListingStatus.Open,
    val availability: List<AvailabilityWindow> = emptyList(),
    /** How well this listing matches the current user. Advisory only. */
    val matchScore: Int = 0,
    val createdAt: Instant? = null,
) {
    val isOpen: Boolean get() = status == ListingStatus.Open
    val isPaid: Boolean get() = mode == ListingMode.Paid && price > 0.0
    val isFreeToMe: Boolean get() = !isPaid || freeForTiers.isNotEmpty()
}

/**
 * When somebody is actually available. A retirement is not a working day, and
 * an availability window of 09:00–17:00 would exclude almost the entire
 * audience of this app.
 */
data class AvailabilityWindow(
    /** 1 = Monday, matching `java.time.DayOfWeek` and the Kotlin one. */
    val weekday: Int,
    val from: LocalTime,
    val to: LocalTime,
) {
    init {
        require(weekday in 1..7) { "weekday must be 1..7, was $weekday" }
    }
}

enum class BookingStatus(val code: String) {
    Requested("requested"),
    Confirmed("confirmed"),
    Completed("completed"),
    Cancelled("cancelled"),
    /** Disputed; the payout is held. */
    Disputed("disputed");

    val isOpen: Boolean get() = this == Requested || this == Confirmed

    companion object {
        fun fromCode(code: String?): BookingStatus =
            entries.firstOrNull { it.code == code } ?: Requested
    }
}

/**
 * A booking of somebody's time.
 *
 * [payoutHeldUntil] is the plan's 48-hour hold (section 12, `payouts`). Money
 * is not released on completion; it is released two days later, so a pensioner
 * who pays a helper who does not turn up has a window in which to say so.
 */
data class SevaBooking(
    val id: Int,
    val listingId: Int,
    val seekerId: Int,
    val providerId: Int,
    val slotAt: Instant,
    val status: BookingStatus = BookingStatus.Requested,
    val amount: Double = 0.0,
    /** The platform's cut, if any. Zero for free and barter work. */
    val fee: Double = 0.0,
    val paymentRef: String? = null,
    val payoutRef: String? = null,
    /** The payouts job skips anything before this instant. */
    val payoutHeldUntil: Instant? = null,
) {
    val isPaid: Boolean get() = amount > 0.0
    val isSettled: Boolean get() = payoutRef != null
}

data class Rating(
    val bookingId: Int,
    val byUserId: Int,
    val stars: Int,
    val text: String? = null,
) {
    init {
        require(stars in 1..5) { "stars must be 1..5, was $stars" }
    }
}

/**
 * A verified organisation. Plan section 10, `partners`.
 *
 * [gstinOrReg] is the registration number a paying job runs through. It is
 * nullable because a partner can exist for a non-commercial purpose — a
 * pensioners' association running a free medical camp — and requiring a GSTIN
 * for that would keep the most useful partners out.
 */
data class SevaPartner(
    val id: Int,
    val name: String,
    val gstinOrReg: String? = null,
    val verifiedAt: Instant? = null,
    val contact: String? = null,
) {
    val isVerified: Boolean get() = verifiedAt != null
    /** Only a verified partner can be paid for work. */
    val canTakePayment: Boolean get() = isVerified && !gstinOrReg.isNullOrBlank()
}

/**
 * How well a listing suits this particular user, 0–100.
 *
 * Deliberately a score and not a filter, and deliberately explainable. The
 * reasons come back with it so the UI can say WHY, which is the difference
 * between a recommendation and a black box. A 65-year-old who is being shown
 * something because of a scoring function deserves the sentence that goes
 * with it.
 */
data class MatchResult(
    val score: Int,
    val reasons: List<MatchReason>,
) {
    init {
        require(score in 0..100) { "score must be 0..100, was $score" }
    }

    val isStrong: Boolean get() = score >= 70
}

enum class MatchReason(val code: String, val positive: Boolean) {
    SameDepartment("same_department", true),
    SameCity("same_city", true),
    SameLanguage("same_language", true),
    Nearby("nearby", true),
    MatchesAvailability("matches_availability", true),
    FitsSkills("fits_skills", true),

    TooFar("too_far", false),
    OutsideRadius("outside_radius", false),
    LanguageMismatch("language_mismatch", false),
    NoOverlap("no_overlap", false);

    companion object {
        fun fromCode(code: String): MatchReason? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Haversine distance in kilometres, used for [MatchReason.Nearby].
 *
 * Kept in the model rather than the network layer because both the "nearby
 * helpers" rule in check-in and the seva board need it, and they must agree:
 * a helper 12 km away is nearby for one and not for the other otherwise.
 */
fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val h = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2) * kotlin.math.cos(lat1) * kotlin.math.cos(lat2)
    return abs(2 * earthRadiusKm * kotlin.math.asin(kotlin.math.sqrt(h)))
}
