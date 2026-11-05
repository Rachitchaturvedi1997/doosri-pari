package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.AvailabilityWindow
import `in`.doosripari.core.model.BookingStatus
import `in`.doosripari.core.model.GeoPoint
import `in`.doosripari.core.model.ListingMode
import `in`.doosripari.core.model.ListingStatus
import `in`.doosripari.core.model.ListingType
import `in`.doosripari.core.model.Rating
import `in`.doosripari.core.model.SevaBooking
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.SevaPartner
import `in`.doosripari.core.model.Tier
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime

/**
 * Shared test values for the Anubhav board, plan section 6.8.
 *
 * The fixed date matters. 2026-10-14 is a **Wednesday**, ISO weekday 3, and
 * every availability test below depends on knowing that — a fixture that landed
 * on a Sunday would make half the weekday arithmetic pass for the wrong reason.
 */
object Fixtures {

    val NOW: Instant = Instant.parse("2026-10-14T09:00:00Z")

    /** The same day, a different hour. `at(21, 0)` is 21:00 UTC on 2026-10-14. */
    fun at(hour: Int, minute: Int = 0): Instant =
        Instant.parse("2026-10-14T%02d:%02d:00Z".format(hour, minute))

    /** Wednesday, the day [NOW] falls on. */
    const val WEDNESDAY: Int = 3

    fun time(hour: Int, minute: Int = 0): LocalTime = LocalTime(hour, minute)

    /** An availability window, given as hours so the tests read as sentences. */
    fun window(weekday: Int, fromHour: Int, toHour: Int): AvailabilityWindow =
        AvailabilityWindow(weekday, LocalTime(fromHour, 0), LocalTime(toHour, 0))

    /**
     * The three kinds of offer the plan distinguishes, with the defaults that
     * keep each test about one thing.
     */
    fun listing(
        id: Int = 1,
        ownerId: Int = 100,
        type: ListingType = ListingType.Mentor,
        title: String = "Free help with forms",
        body: String = "I will sit with you and fill in the form.",
        tags: List<String> = emptyList(),
        languages: List<AppLanguage> = listOf(AppLanguage.Hindi),
        mode: ListingMode = ListingMode.Free,
        location: GeoPoint? = DELHI,
        price: Double = 0.0,
        freeForTiers: List<Tier> = emptyList(),
        status: ListingStatus = ListingStatus.Open,
        availability: List<AvailabilityWindow> = emptyList(),
        createdAt: Instant? = NOW,
    ): SevaListing = SevaListing(
        id = id,
        ownerId = ownerId,
        ownerName = "Sharma ji",
        type = type,
        title = title,
        body = body,
        tags = tags,
        languages = languages,
        mode = mode,
        location = location,
        price = price,
        freeForTiers = freeForTiers,
        status = status,
        availability = availability,
        createdAt = createdAt,
    )

    /**
     * A paid listing, which in this plan always runs through an organisation.
     * The default ₹200 and the default partner are what the happy-path tests
     * use; the refusal tests change one or the other.
     */
    fun paidListing(
        id: Int = 2,
        price: Double = 200.0,
        availability: List<AvailabilityWindow> = listOf(window(WEDNESDAY, 6, 12)),
        createdAt: Instant? = NOW,
    ): SevaListing = listing(
        id = id,
        type = ListingType.Mentor,
        title = "Mentoring on pension rules",
        body = "One hour, in person, near the temple.",
        mode = ListingMode.Paid,
        price = price,
        availability = availability,
        createdAt = createdAt,
    )

    fun barterListing(id: Int = 3): SevaListing = listing(
        id = id,
        type = ListingType.Skill,
        title = "Will teach in exchange for company",
        body = "I will help you with English twice a week, if you will sit with me.",
        mode = ListingMode.Barter,
    )

    /**
     * The listing section 6.8 tells us to block, in the plainest words a
     * scammer would use.
     */
    fun scamListing(id: Int = 99): SevaListing = listing(
        id = id,
        type = ListingType.Work,
        title = "Work from home, guaranteed",
        body = "You must pay a registration fee of ₹500 before you start. Please pay me first.",
        mode = ListingMode.Paid,
        price = 500.0,
    )

    val DELHI = GeoPoint(lat = 28.6139, lng = 77.2090)

    /**
     * Roughly 30 km north of [DELHI] — comfortably past the plan's 25 km mark,
     * so a test that wants "too far" does not have to compute anything.
     */
    val FAR_AWAY = GeoPoint(lat = 28.9139, lng = 77.2090)

    /** About 1 km away, comfortably inside the plan's 3 km "nearby". */
    val NEARBY = GeoPoint(lat = 28.6239, lng = 77.2090)

    /** About 110 m away — the same street, for the "less than 1 km" case. */
    val NEXT_DOOR = GeoPoint(lat = 28.6149, lng = 77.2090)

    fun profile(
        userId: Int = 200,
        departmentId: Int? = 3,
        cityId: Int? = 7,
        languages: List<AppLanguage> = listOf(AppLanguage.Hindi),
        location: GeoPoint? = DELHI,
        freeHours: List<AvailabilityWindow> = emptyList(),
        tags: List<String> = emptyList(),
        trust: TrustSummary? = null,
    ): MatchProfile = MatchProfile(
        userId = userId,
        departmentId = departmentId,
        cityId = cityId,
        languages = languages,
        location = location,
        freeHours = freeHours,
        tags = tags,
        trust = trust,
    )

    /**
     * A partner that can be paid.
     *
     * Both halves of [SevaPartner.canTakePayment] are true, which is the case
     * that has to be reached by the tests that care about everything else.
     */
    fun partner(
        id: Int = 500,
        name: String = "Kalyan Trust",
        gstinOrReg: String? = "DEL2019KAL1234",
        verifiedAt: Instant? = NOW,
    ): SevaPartner = SevaPartner(id, name, gstinOrReg, verifiedAt, contact = null)

    fun unverifiedPartner(id: Int = 501): SevaPartner =
        partner(id = id, name = "Quick Jobs", gstinOrReg = null, verifiedAt = null)

    /** Verified as a business but with no registration number, so still unpaid. */
    fun unregisteredPartner(id: Int = 502): SevaPartner =
        partner(id = id, name = "Community Trust", gstinOrReg = "  ", verifiedAt = NOW)

    fun rating(bookingId: Int, byUserId: Int, stars: Int): Rating = Rating(bookingId, byUserId, stars)

    fun booking(
        id: Int = 1,
        listingId: Int = 2,
        seekerId: Int = 200,
        providerId: Int = 100,
        slotAt: Instant = NOW,
        status: BookingStatus = BookingStatus.Requested,
        amount: Double = 200.0,
        fee: Double = 24.0,
        paymentRef: String? = null,
        payoutRef: String? = null,
        payoutHeldUntil: Instant? = null,
    ): SevaBooking = SevaBooking(
        id = id,
        listingId = listingId,
        seekerId = seekerId,
        providerId = providerId,
        slotAt = slotAt,
        status = status,
        amount = amount,
        fee = fee,
        paymentRef = paymentRef,
        payoutRef = payoutRef,
        payoutHeldUntil = payoutHeldUntil,
    )

    /**
     * A booking that has been through both sides confirming and both sides
     * marking it finished, with the hold running from the later of the two
     * instants — [NOW], since the slot is [NOW].
     */
    fun completedBooking(
        id: Int = 1,
        amount: Double = 200.0,
        paid: Boolean = true,
    ): Booking {
        val record = booking(
            id = id,
            amount = amount,
            fee = if (paid) BookingEngine.feeFor(amount) else 0.0,
            status = BookingStatus.Completed,
            paymentRef = if (paid) "pay_$id" else null,
            payoutHeldUntil = BookingEngine.holdExpiresAt(NOW, NOW),
        )
        return Booking(
            record = record,
            confirmations = Confirmations(seekerConfirmedAt = NOW, providerConfirmedAt = NOW),
            completedBy = setOf(record.seekerId, record.providerId),
        )
    }

    /** The context the payouts job runs with, for a completed paid booking. */
    fun settleContext(
        booking: Booking = completedBooking(),
        partner: SevaPartner? = Fixtures.partner(),
        now: Instant = BookingEngine.holdExpiresAt(NOW, NOW),
    ) = BookingContext(
        booking = booking,
        listing = paidListing(),
        partner = partner,
        now = now,
    )
}
