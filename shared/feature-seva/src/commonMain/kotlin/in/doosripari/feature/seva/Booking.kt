package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.BookingStatus
import `in`.doosripari.core.model.ListingStatus
import `in`.doosripari.core.model.SevaBooking
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.SevaPartner
import kotlinx.datetime.Instant
import kotlin.math.round
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Booking somebody's time, and the money that follows. Plan section 6.8,
 * "Paid sessions".
 *
 * The plan writes the sequence out in five numbered lines and this file is
 * those five lines, in order:
 *
 * ```
 * 1. the seeker pays through Razorpay
 * 2. the session happens by in-app call or in person
 * 3. both sides confirm
 * 4. the mentor is paid 48 hours later, minus a 12% fee
 * 5. disputes go to moderators
 * ```
 *
 * Three of those are state machine rules and one is arithmetic, and the two
 * that matter most for this audience are the two the plan also lists under
 * hard-coded anti-scam rules: **partners must be verified businesses** and
 * **any listing asking the pensioner to pay is blocked**. Both are enforced
 * here as refusals with a sentence attached, not as warnings, because a
 * warning on a screen is something a person can scroll past and a pensioner
 * should not have to.
 *
 * ## Why the 48 hours
 *
 * The hold is not a payment float. It is a **window in which the seeker can
 * still say the session did not happen**, and it is the reason this app is
 * different from a classifieds site: somebody who has already handed over
 * money has very little left to defend themselves with, and 48 hours is the
 * only thing standing between a bad evening and no recourse at all. It is
 * counted from the *later* of the session and the completion, so a booking
 * marked complete in the morning of a session that was booked for the evening
 * does not hand the money over before the session could have happened.
 *
 * ## Why confirmation is per side
 *
 * Line 3 says *both sides confirm*, and `core-model`'s [SevaBooking] has no
 * column for it — a status can only be Requested or Confirmed, so "confirmed"
 * would otherwise mean "somebody pressed a button". [Confirmations] is that
 * missing half, held alongside the row. The backend will want two timestamps
 * on the table; until it has them the feature module is the only place the
 * information exists, and losing it here would lose it entirely.
 */
data class Booking(
    /** The row as the API knows it. */
    val record: SevaBooking,

    /** Line 3: who has said yes, and when. */
    val confirmations: Confirmations = Confirmations(),

    /** Who has marked the session as having happened. Empty until it has. */
    val completedBy: Set<Int> = emptySet(),

    val cancelledBy: Int? = null,
    val cancelledReason: String? = null,

    val disputedBy: Int? = null,
    val disputeReason: String? = null,

    /**
     * What a moderator decided about an open dispute, or null while it is
     * still with them.
     *
     * A resolved dispute does not move [Booking.record]'s status: the row
     * stays [BookingStatus.Disputed] forever so that the history shows there
     * was one, and this says how it ended.
     */
    val disputeOutcome: DisputeOutcome? = null,
) {
    val id: Int get() = record.id
    val status: BookingStatus get() = record.status

    val isFree: Boolean get() = !record.isPaid
    val isSettled: Boolean get() = record.isSettled

    /** How much the provider is actually handed, after the platform's cut. */
    val payoutAmount: Double get() = Booking.payoutFor(record.amount, record.fee)
}

/** Line 3, kept as its own type so it cannot be collapsed into a status. */
data class Confirmations(
    val seekerConfirmedAt: Instant? = null,
    val providerConfirmedAt: Instant? = null,
) {
    val bothConfirmed: Boolean
        get() = seekerConfirmedAt != null && providerConfirmedAt != null

    /** How many of the two have said yes. */
    val count: Int get() = listOfNotNull(seekerConfirmedAt, providerConfirmedAt).size

    fun has(userId: Int, seekerId: Int, providerId: Int): Boolean = when (userId) {
        seekerId -> seekerConfirmedAt != null
        providerId -> providerConfirmedAt != null
        else -> false
    }

    fun with(userId: Int, seekerId: Int, providerId: Int, at: Instant): Confirmations = when (userId) {
        seekerId -> copy(seekerConfirmedAt = seekerConfirmedAt ?: at)
        providerId -> copy(providerConfirmedAt = providerConfirmedAt ?: at)
        else -> this
    }
}

/** How a moderator settled a dispute. */
enum class DisputeOutcome(val code: String) {
    /** The seeker was right. The mentor is not paid. */
    RefundSeeker("refund_seeker"),
    /** The mentor did the work. The payout goes ahead. */
    ReleaseToMentor("release_to_mentor"),
    /** Split. Only reachable if the split is recorded elsewhere. */
    Split("split");

    companion object {
        fun fromCode(code: String?): DisputeOutcome? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the ledger has to do as a result of an action.
 *
 * Carried on the result rather than inferred, because "the booking cancelled"
 * does not tell a payment service whether to refund, and getting that wrong
 * takes money out of a pensioner's account.
 */
enum class MoneyMove {
    /** Free or barter work. Nothing moves. */
    None,
    /** Take the amount from the seeker through the gateway. */
    TakeFromSeeker,
    /** Hold it. This is what a completion does, for 48 hours. */
    Hold,
    /** Pay the verified partner, less the fee. */
    ReleaseToPartner,
    /** Give the seeker's money back. */
    RefundSeeker,
    /** Part of it each way. The split itself is recorded by the ledger. */
    Split,
}

/**
 * Why an action is refused.
 *
 * Every one of these has a sentence, and the sentence is the thing the user
 * reads. A booking screen that says "Error" to somebody who has just been
 * told a session is unavailable teaches them that the app does not work.
 */
enum class BookingBlock(val code: String) {
    ListingClosed("listing_closed"),
    ListingWithdrawn("listing_withdrawn"),
    ListingRemoved("listing_removed"),
    BookingYourself("booking_yourself"),
    SlotNotFree("slot_not_free"),
    OutsideAvailability("outside_availability"),
    TooManyOpenBookings("too_many_open_bookings"),

    NotAParty("not_a_party"),

    NotARequest("not_a_request"),
    NotConfirmed("not_confirmed"),
    NotCompleted("not_completed"),
    NotOpenAnyMore("not_open_any_more"),
    AlreadySettled("already_settled"),

    /** Line 4. The 48 hours have not gone by. */
    HoldNotExpired("hold_not_expired"),

    /** Line 5. The window in which a dispute could be raised has closed. */
    DisputeWindowClosed("dispute_window_closed"),

    /** A second moderator answered a dispute that was already answered. */
    DisputeAlreadyResolved("dispute_already_resolved"),

    /** Anti-scam rule one: paid work runs through a verified business. */
    PartnerNotVerified("partner_not_verified"),
    NoPartner("no_partner"),

    /** Anti-scam rule two: a listing asking the pensioner to pay. */
    ScamListing("scam_listing"),

    /** An amount on work that was offered for nothing. */
    MoneyOnFreeWork("money_on_free_work"),

    /** Paid work with nobody to pay. */
    PaidWorkHasNoPartner("paid_work_has_no_partner"),
}

val BookingBlock.message: String
    get() = when (this) {
        BookingBlock.ListingClosed -> "Somebody else took this one first."
        BookingBlock.ListingWithdrawn -> "This offer has been withdrawn."
        BookingBlock.ListingRemoved -> "This offer is no longer available."
        BookingBlock.BookingYourself -> "You cannot book your own offer."
        BookingBlock.SlotNotFree -> "That time is already spoken for."
        BookingBlock.OutsideAvailability ->
            "The person offering this is not free for all of that time."

        BookingBlock.TooManyOpenBookings ->
            "You have ${Booking.MAX_OPEN_BOOKINGS} sessions already arranged. Finish or cancel one first."

        BookingBlock.NotAParty -> "This is not your booking."
        BookingBlock.NotARequest -> "This has already been agreed."
        BookingBlock.NotConfirmed -> "This has not been agreed by both sides yet."
        BookingBlock.NotCompleted -> "The session has not been marked as finished."
        BookingBlock.NotOpenAnyMore -> "This booking is closed."
        BookingBlock.AlreadySettled -> "This has already been paid out."
        BookingBlock.HoldNotExpired ->
            "Money is released ${Booking.PAYOUT_HOLD_HOURS} hours after the session, so that a session that did not happen can still be reported."

        BookingBlock.DisputeWindowClosed ->
            "The 48 hours to report a problem have passed. If something is wrong, contact us and we will look at it anyway."

        BookingBlock.DisputeAlreadyResolved ->
            "A moderator has already answered this one."

        BookingBlock.PartnerNotVerified ->
            "This organisation has not been verified, so it cannot be paid. Do not pay them yourself."

        BookingBlock.NoPartner -> "This booking has no organisation to pay."
        BookingBlock.ScamListing ->
            "This asks you to pay, so it cannot be booked. Never pay to get work."

        BookingBlock.MoneyOnFreeWork -> "This work was offered for free, so no payment is taken."
        BookingBlock.PaidWorkHasNoPartner ->
            "Paid work runs through a verified organisation. This one has not set that up, so it cannot be booked."
    }

/** What happened when somebody acted on a booking. */
sealed interface BookingResult {

    /** The row as it stands now, whatever was asked for. */
    val booking: Booking

    data class Applied(
        override val booking: Booking,
        /** One line for the person who acted. */
        val note: String,
        val money: MoneyMove = MoneyMove.None,
    ) : BookingResult

    /** Nothing changed. [BookingBlock] carries the sentence to show. */
    data class Refused(
        override val booking: Booking,
        val block: BookingBlock,
    ) : BookingResult
}

/** The result of trying to book a listing in the first place. */
sealed interface CreateResult {

    data class Created(val booking: Booking) : CreateResult

    data class Refused(val block: BookingBlock) : CreateResult
}

/**
 * Everything the rules need that is not on the booking itself.
 *
 * The listing is here because most of the refusals are about the *offer* —
 * it is closed, it is not free at that time, it is one of the blocked ones —
 * rather than about the row.
 */
data class BookingContext(
    val booking: Booking,

    /** The offer this was booked against, as it stands now. */
    val listing: SevaListing,

    /**
     * The organisation the money goes to, or null.
     *
     * Null is normal for free and barter work. For a paid listing it is a
     * refusal, because the plan's table says "Paid work | Verified partners
     * only | Paid by the partner" and there is no version of paying a person
     * directly that this app offers.
     */
    val partner: SevaPartner? = null,

    /** The seeker's other live bookings, for the "you already have three" check. */
    val otherOpenBookings: List<SevaBooking> = emptyList(),

    val now: Instant,
)

/**
 * The booking rules. Pure, so the tests can walk a booking through a week.
 */
object BookingEngine {

    /**
     * Plan line 4: "The mentor is paid 48 hours later".
     *
     * A hold on money this app has taken, for the sake of the person who paid
     * it and did not get what they paid for.
     */
    const val PAYOUT_HOLD_HOURS: Long = 48

    /**
     * Plan line 4: "minus a 12% fee", and section 17's revenue table gives the
     * same 12% per session.
     *
     * Applied to the amount the seeker pays, never added on top of it. A
     * seeker who agrees to ₹100 has agreed to ₹100, and discovering the fee
     * afterwards is the sort of thing that makes an audience stop trusting a
     * screen.
     */
    const val PLATFORM_FEE_RATE: Double = 0.12

    /**
     * How many sessions one person may have arranged at once.
     *
     * The plan does not give a number and this one is ours, not its: it exists
     * because a seeker who has booked six afternoons of somebody's time has
     * almost certainly misunderstood the board, and telling them so at the
     * sixth booking is kinder than at the sixth no-show. The figure is three
     * because a pensioner is not going to be juggling more than a few
     * commitments in a week, and the check is a soft ceiling rather than a
     * quota — nothing is refused for having had a booking in the past.
     */
    const val MAX_OPEN_BOOKINGS: Int = 3

    /** The fee on [amount], to two places. */
    fun feeFor(amount: Double): Double = round2(amount * PLATFORM_FEE_RATE)

    /** What the partner is handed: the amount less the fee. Never negative. */
    fun payoutFor(amount: Double, fee: Double): Double = (amount - fee).coerceAtLeast(0.0)

    /**
     * When the money may be released.
     *
     * Counted from the later of the session and the moment both sides marked
     * it finished. A booking marked complete at nine in the morning for a
     * session booked that evening would otherwise start its 48 hours before
     * the session could have happened, which is a way of losing the only
     * protection the seeker has.
     */
    fun holdExpiresAt(slotAt: Instant, completedAt: Instant): Instant =
        (if (completedAt > slotAt) completedAt else slotAt) + PAYOUT_HOLD_HOURS.hours

    /**
     * How long is left on the hold, for a screen that says "paid on Thursday".
     *
     * Zero once it has passed rather than negative: a countdown that goes
     * below zero is a countdown that has broken.
     */
    fun holdRemaining(booking: Booking, now: Instant): Duration {
        val until = booking.record.payoutHeldUntil ?: return Duration.ZERO
        val left = until - now
        return if (left.isNegative()) Duration.ZERO else left
    }

    // ------------------------------------------------------------- creating --

    /**
     * Book a listing, producing a Requested booking.
     *
     * Every check the plan implies happens here rather than at confirmation,
     * because a refusal after the seeker has already agreed to pay is a much
     * worse experience than one before, and a payment taken for a booking that
     * cannot be confirmed is money somebody has to chase back.
     */
    fun create(
        listing: SevaListing,
        seekerId: Int,
        slot: TimeSlot,
        at: Instant,
        otherOpenBookings: List<SevaBooking> = emptyList(),
        partner: SevaPartner? = null,
        bookingId: Int = 0,
    ): CreateResult {
        val block = creationBlock(listing, seekerId, slot, otherOpenBookings, partner)
        if (block != null) return CreateResult.Refused(block)

        val paid = listing.isPaid
        return CreateResult.Created(
            Booking(
                record = SevaBooking(
                    id = bookingId,
                    listingId = listing.id,
                    seekerId = seekerId,
                    providerId = listing.ownerId,
                    slotAt = at,
                    status = BookingStatus.Requested,
                    amount = if (paid) listing.price else 0.0,
                    fee = if (paid) feeFor(listing.price) else 0.0,
                ),
            ),
        )
    }

    private fun creationBlock(
        listing: SevaListing,
        seekerId: Int,
        slot: TimeSlot,
        otherOpenBookings: List<SevaBooking>,
        partner: SevaPartner?,
    ): BookingBlock? = when {
        // Anti-scam, before anything else. A listing that asks the reader
        // for money is not a listing with a defect; it is what the plan says
        // to block, and no other reason should be reported instead of this one.
        ScamSignals.looksLikePaymentRequest(listing) -> BookingBlock.ScamListing

        listing.status == ListingStatus.Withdrawn -> BookingBlock.ListingWithdrawn
        listing.status == ListingStatus.Removed -> BookingBlock.ListingRemoved
        listing.status == ListingStatus.Filled -> BookingBlock.ListingClosed

        listing.ownerId == seekerId -> BookingBlock.BookingYourself

        // Two people arriving for the same afternoon. Checked on the listing
        // and the instant together, because a slot is a time and a place, and
        // a provider is one person.
        otherOpenBookings.any {
            it.listingId == listing.id && it.slotAt == at && it.status.isOpen
        } -> BookingBlock.SlotNotFree

        otherOpenBookings.count { it.status.isOpen } >= MAX_OPEN_BOOKINGS ->
            BookingBlock.TooManyOpenBookings

        // Availability is a hard rule at booking time even though it is only
        // 0.15 of the match score. The score says "these two suit each other";
        // this says "this person is actually there then", and a 15% difference
        // does not put anybody in a room.
        //
        // A listing with no availability at all is *not* refused, for the same
        // reason `SevaMatcher` gives a missing field half marks rather than
        // zero: nobody has said they are unavailable, they have said nothing,
        // and refusing every booking of an offer whose poster never filled in
        // the field would quietly remove every listing posted by somebody who
        // found the form confusing.
        listing.availability.isNotEmpty() &&
            !AvailabilityMatcher.covers(listing.availability, slot) -> BookingBlock.OutsideAvailability

        // Anti-scam: paid work runs through a verified business.
        listing.isPaid && (partner == null || !partner.canTakePayment) ->
            BookingBlock.PaidWorkHasNoPartner

        else -> null
    }

    // -------------------------------------------------------------- actions --

    /**
     * Line 3: one side says yes.
     *
     * Idempotent, and deliberately so — a dropped connection and a second tap
     * both arrive as a repeated confirm, and neither is an error worth
     * showing somebody who has just agreed to be somewhere.
     */
    fun confirm(context: BookingContext, byUserId: Int): BookingResult {
        val b = context.booking
        if (b.isSettled) return refused(context, BookingBlock.AlreadySettled)
        if (!isParty(b, byUserId)) return refused(context, BookingBlock.NotAParty)
        if (b.status != BookingStatus.Requested) return refused(context, BookingBlock.NotARequest)

        val already = b.confirmations.has(byUserId, b.record.seekerId, b.record.providerId)
        val confirmations = b.confirmations.with(byUserId, b.record.seekerId, b.record.providerId, context.now)
        if (already) {
            return applied(context, b.copy(confirmations = confirmations), "You have already agreed to this.")
        }

        val nowBoth = confirmations.bothConfirmed
        val next = b.copy(
            record = b.record.copy(status = if (nowBoth) BookingStatus.Confirmed else BookingStatus.Requested),
            confirmations = confirmations,
        )
        return applied(
            context,
            next,
            if (nowBoth) "Both sides have agreed." else "Waiting for the other side.",
        )
    }

    /**
     * Line 2 happened. Both sides mark it, for the same reason line 3 has two
     * sides: one person pressing a button is not a session.
     */
    fun complete(context: BookingContext, byUserId: Int): BookingResult {
        val b = context.booking
        if (!isParty(b, byUserId)) return refused(context, BookingBlock.NotAParty)
        if (b.status != BookingStatus.Confirmed) return refused(context, BookingBlock.NotConfirmed)

        val completedBy = b.completedBy + byUserId
        if (byUserId in b.completedBy) {
            return applied(context, b.copy(completedBy = completedBy), "You have already marked this finished.")
        }

        val both = completedBy.containsAll(setOf(b.record.seekerId, b.record.providerId))
        val next = b.copy(
            record = b.record.copy(
                status = if (both) BookingStatus.Completed else BookingStatus.Confirmed,
                // The hold starts here, and only on the second mark, so the
                // first side pressing the button cannot start the clock early.
                payoutHeldUntil = if (both) holdExpiresAt(b.record.slotAt, context.now) else null,
            ),
            completedBy = completedBy,
        )
        return applied(
            context,
            next,
            when {
                !b.record.isPaid -> if (both) "Thank you. This work was free." else "Waiting for the other side."
                both -> "Money is released after ${PAYOUT_HOLD_HOURS} hours."
                else -> "Waiting for the other side."
            },
            money = if (both && b.record.isPaid) MoneyMove.Hold else MoneyMove.None,
        )
    }

    /**
     * Either side, at any point before the money has moved.
     *
     * A cancellation after a payment is a refund, and the result says so
     * explicitly — the alternative is a booking that reads as cancelled while
     * the money is still sitting in a gateway.
     */
    fun cancel(context: BookingContext, byUserId: Int, reason: String? = null): BookingResult {
        val b = context.booking
        if (!isParty(b, byUserId)) return refused(context, BookingBlock.NotAParty)
        if (b.isSettled) return refused(context, BookingBlock.AlreadySettled)
        if (!b.status.isOpen) return refused(context, BookingBlock.NotOpenAnyMore)

        val wasPaid = b.record.paymentRef != null
        val next = b.copy(
            record = b.record.copy(status = BookingStatus.Cancelled),
            cancelledBy = byUserId,
            cancelledReason = reason,
        )
        return applied(
            context,
            next,
            if (wasPaid) "Cancelled. Your money will come back to you." else "Cancelled.",
            money = if (wasPaid) MoneyMove.RefundSeeker else MoneyMove.None,
        )
    }

    /**
     * Line 5: "Disputes go to moderators".
     *
     * Only inside the hold. After it the money has gone and the honest thing
     * to say is that the window has closed — the [BookingBlock.DisputeWindowClosed]
     * sentence says the app will still look at it, because it will, and a
     * support route that is not mentioned does not exist as far as the person
     * reading is concerned.
     */
    fun dispute(context: BookingContext, byUserId: Int, reason: String): BookingResult {
        val b = context.booking
        if (!isParty(b, byUserId)) return refused(context, BookingBlock.NotAParty)
        if (b.status != BookingStatus.Confirmed && b.status != BookingStatus.Completed) {
            return refused(context, BookingBlock.NotOpenAnyMore)
        }
        if (b.status == BookingStatus.Completed && disputeWindowClosed(b, context.now)) {
            return refused(context, BookingBlock.DisputeWindowClosed)
        }

        val next = b.copy(
            record = b.record.copy(status = BookingStatus.Disputed),
            disputedBy = byUserId,
            disputeReason = reason,
        )
        return applied(context, next, "A moderator will look at this.")
    }

    /**
     * A moderator's answer to an open dispute.
     *
     * The only place [MoneyMove.RefundSeeker] is produced without a
     * cancellation, and the only place money is released while the row is
     * still [BookingStatus.Disputed] — a moderated release is a deliberate
     * override of the hold, so the actor is recorded and the outcome is on
     * the booking rather than buried in a log.
     */
    fun resolveDispute(
        context: BookingContext,
        byModeratorId: Int,
        outcome: DisputeOutcome,
    ): BookingResult {
        val b = context.booking
        if (b.status != BookingStatus.Disputed) return refused(context, BookingBlock.NotOpenAnyMore)
        if (b.disputeOutcome != null) return refused(context, BookingBlock.DisputeAlreadyResolved)

        val money = when (outcome) {
            DisputeOutcome.RefundSeeker -> MoneyMove.RefundSeeker
            DisputeOutcome.ReleaseToMentor ->
                if (context.partner?.canTakePayment == true) MoneyMove.ReleaseToPartner
                else MoneyMove.Hold

            DisputeOutcome.Split -> MoneyMove.Split
        }
        val settled = money == MoneyMove.ReleaseToPartner || money == MoneyMove.RefundSeeker
        val next = b.copy(
            record = if (settled) b.record.copy(payoutRef = "dispute-$byModeratorId") else b.record,
            disputeOutcome = outcome,
        )
        return applied(context, next, "A moderator has looked at this.", money)
    }

    /**
     * Line 4, run by the payouts job.
     *
     * The three refusals that matter, in the order they are checked:
     *
     *  - **before [PAYOUT_HOLD_HOURS] hours have passed** — the seeker has not
     *    yet had their chance to report a session that did not happen;
     *  - **without a verified partner** — anti-scam rule one, and the check is
     *    on the partner's state *now* rather than when the booking was made, so
     *    a verification that is later withdrawn stops payouts;
     *  - **for work that was never paid for** — there is nothing to release.
     *    The booking is still marked settled, with a reference saying so,
     *    because the payouts job runs over every completed booking and a
     *    record that never resolves is a record it keeps picking up.
     */
    fun settle(context: BookingContext, payoutRef: String): BookingResult {
        val b = context.booking
        if (b.isSettled) return refused(context, BookingBlock.AlreadySettled)
        if (b.status != BookingStatus.Completed) return refused(context, BookingBlock.NotCompleted)

        val until = b.record.payoutHeldUntil
            ?: return refused(context, BookingBlock.HoldNotExpired)
        if (context.now < until) return refused(context, BookingBlock.HoldNotExpired)

        if (!b.record.isPaid) {
            return applied(
                context,
                b.copy(record = b.record.copy(payoutRef = "no-payment")),
                "Nothing to pay. This work was free.",
            )
        }

        // An amount on work that was offered for nothing. `create` cannot
        // produce this, so it means the row was built somewhere else or the
        // listing was changed from paid to free after the booking was made —
        // and the second is a real event, not a hypothetical. Refused rather
        // than paid, because paying it would be charging a pensioner for
        // somebody's volunteering.
        if (!context.listing.isPaid) {
            return refused(context, BookingBlock.MoneyOnFreeWork)
        }

        // Money for paid work goes to a verified organisation and nowhere
        // else, and never to a person.
        val partner = context.partner
            ?: return refused(context, BookingBlock.NoPartner)
        if (!partner.canTakePayment) return refused(context, BookingBlock.PartnerNotVerified)

        val next = b.copy(record = b.record.copy(payoutRef = payoutRef))
        return applied(
            context,
            next,
            "₹${round2(b.payoutAmount)} to ${partner.name}, after the ${(PLATFORM_FEE_RATE * 100).toInt()}% fee.",
            money = MoneyMove.ReleaseToPartner,
        )
    }

    // ------------------------------------------------------------- helpers --

    /**
     * Is the hold window still open?
     *
     * Exposed because the booking screen asks it to decide whether to *offer*
     * the report button, which is a different question from whether the
     * report would be accepted — a person who cannot report has not been told
     * they cannot, they have just been left with no way to.
     */
    fun disputeWindowClosed(booking: Booking, now: Instant): Boolean {
        val until = booking.record.payoutHeldUntil ?: return false
        return now >= until
    }

    /**
     * Has a booking passed the point where cancelling and disputing are both
     * too late?
     */
    fun isFinal(booking: Booking): Boolean =
        booking.isSettled || !booking.status.isOpen

    private fun isParty(booking: Booking, userId: Int): Boolean =
        userId == booking.record.seekerId || userId == booking.record.providerId

    private fun applied(context: BookingContext, booking: Booking, note: String, money: MoneyMove = MoneyMove.None) =
        BookingResult.Applied(booking, note, money)

    private fun refused(context: BookingContext, block: BookingBlock) =
        BookingResult.Refused(context.booking, block)

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
