package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.BookingStatus
import `in`.doosripari.core.model.ListingStatus
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.SevaPartner
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Booking and the money that follows, plan section 6.8's "Paid sessions".
 *
 * The five numbered lines in the plan are the structure of this file. Two of
 * the tests are the ones named in the brief — **money cannot be released
 * before the hold expires**, and **money cannot go to an unverified partner** —
 * and everything else exists because those two would be easy to pass with a
 * booking system that had not considered the obvious neighbours of each.
 */
class BookingTest {

    private val seekerId = 200
    private val providerId = 100

    private fun context(
        booking: Booking,
        listing: SevaListing = Fixtures.paidListing(),
        partner: SevaPartner? = Fixtures.partner(),
        now: Instant = Fixtures.NOW,
    ) = BookingContext(booking = booking, listing = listing, partner = partner, now = now)

    /** A booking that both sides have agreed and both sides have finished. */
    private fun contextFor(
        booking: Booking = Fixtures.completedBooking(),
        partner: SevaPartner? = Fixtures.partner(),
        now: Instant = BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW),
    ) = context(booking, partner = partner, now = now)

    // ---------------------------------------------------------- arithmetic --

    @Test
    fun `the fee is the plan's twelve per cent, taken out of the amount`() {
        // Section 6.8 line 4 and section 17's revenue table both say 12%. A fee
        // *added* to the agreed amount is not that number.
        assertEquals(12.0, BookingEngine.PLATFORM_FEE_RATE * 100)
        assertEquals(24.0, BookingEngine.feeFor(200.0))
        assertEquals(12.0, BookingEngine.feeFor(100.0))
        assertEquals(0.0, BookingEngine.feeFor(0.0))
        assertEquals(176.0, BookingEngine.payoutFor(200.0, 24.0))
    }

    @Test
    fun `the hold is forty eight hours from the later of the session and the completion`() {
        val slot = Fixtures.NOW
        val completion = Instant.parse("2026-10-14T20:00:00Z")

        assertEquals(Instant.parse("2026-10-16T09:00:00Z"), BookingEngine.holdExpiresAt(slot, slot))
        // Marked complete in the evening: the hold runs from the evening.
        assertEquals(Instant.parse("2026-10-16T20:00:00Z"), BookingEngine.holdExpiresAt(slot, completion))
        // Marked complete in the morning for a *later* session: the hold runs
        // from the session, or the seeker loses their only window before the
        // session could have happened.
        val laterSession = Instant.parse("2026-10-15T09:00:00Z")
        val earlyMark = Instant.parse("2026-10-15T07:00:00Z")
        assertEquals(Instant.parse("2026-10-17T09:00:00Z"), BookingEngine.holdExpiresAt(laterSession, earlyMark))
    }

    @Test
    fun `a countdown stops at zero rather than going negative`() {
        val booking = Fixtures.completedBooking()
        val until = BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW)

        assertEquals(48.hours, BookingEngine.holdRemaining(booking, Fixtures.NOW))
        // A countdown below zero is a countdown that has broken.
        assertEquals(Duration.ZERO, BookingEngine.holdRemaining(booking, until + 1.hours))
        // And a booking with no hold on it is not counting down from somewhere
        // it never started.
        assertEquals(Duration.ZERO, BookingEngine.holdRemaining(requested(), Fixtures.NOW))
    }

    // --------------------------------------------------------- creating ---

    @Test
    fun `paid work cannot be booked without a verified partner`() {
        // Section 6.8's table: "Paid work | Verified partners only | Paid by
        // the partner", and the first hard-coded anti-scam rule. Refused at
        // booking rather than at payout, because a seeker who has already
        // agreed to pay is the worst moment to tell them there is nowhere to
        // pay.
        assertEquals(
            BookingBlock.PaidWorkHasNoPartner,
            assertIs<CreateResult.Refused>(
                BookingEngine.create(
                    listing = Fixtures.paidListing(),
                    seekerId = seekerId,
                    slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                    at = Fixtures.NOW,
                    partner = null,
                ),
            ).block,
        )
        assertEquals(
            BookingBlock.PaidWorkHasNoPartner,
            assertIs<CreateResult.Refused>(
                BookingEngine.create(
                    listing = Fixtures.paidListing(),
                    seekerId = seekerId,
                    slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                    at = Fixtures.NOW,
                    partner = Fixtures.unverifiedPartner(),
                ),
            ).block,
        )
    }

    @Test
    fun `free work needs no partner at all`() {
        // The whole point of free being a mode. Requiring a verified business
        // to receive ₹0 is how a community of retirees ends up unable to offer
        // each other a lift.
        val created = assertIs<CreateResult.Created>(
            BookingEngine.create(
                listing = Fixtures.listing(),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                at = Fixtures.NOW,
                partner = null,
            ),
        )

        assertEquals(0.0, created.booking.record.amount)
        assertEquals(0.0, created.booking.record.fee)
        assertEquals(BookingStatus.Requested, created.booking.status)
    }

    @Test
    fun `a paid booking carries the amount and the fee`() {
        val created = assertIs<CreateResult.Created>(
            BookingEngine.create(
                listing = Fixtures.paidListing(price = 300.0),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                at = Fixtures.NOW,
                partner = Fixtures.partner(),
                bookingId = 42,
            ),
        )

        assertEquals(300.0, created.booking.record.amount)
        assertEquals(36.0, created.booking.record.fee)
        assertEquals(42, created.booking.id)
    }

    @Test
    fun `a listing asking the pensioner to pay is refused before anything else`() {
        // Checked first, so that a scam is never reported as "already taken".
        assertEquals(
            BookingBlock.ScamListing,
            assertIs<CreateResult.Refused>(
                BookingEngine.create(
                    listing = Fixtures.scamListing(),
                    seekerId = seekerId,
                    slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                    at = Fixtures.NOW,
                    partner = Fixtures.partner(),
                ),
            ).block,
        )
        // Even a closed scam listing reports the scam.
        assertEquals(
            BookingBlock.ScamListing,
            assertIs<CreateResult.Refused>(
                BookingEngine.create(
                    listing = Fixtures.scamListing().copy(status = ListingStatus.Withdrawn),
                    seekerId = seekerId,
                    slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                    at = Fixtures.NOW,
                ),
            ).block,
        )
    }

    @Test
    fun `the obvious refusals all have a reason`() {
        val slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9))

        assertEquals(
            BookingBlock.BookingYourself,
            refusedBy(BookingEngine.create(Fixtures.listing(ownerId = seekerId), seekerId, slot, Fixtures.NOW)),
        )
        assertEquals(
            BookingBlock.ListingWithdrawn,
            refusedBy(BookingEngine.create(Fixtures.listing(status = ListingStatus.Withdrawn), seekerId, slot, Fixtures.NOW)),
        )
        assertEquals(
            BookingBlock.ListingClosed,
            refusedBy(BookingEngine.create(Fixtures.listing(status = ListingStatus.Filled), seekerId, slot, Fixtures.NOW)),
        )
    }

    @Test
    fun `a slot the provider is not free for the whole of is refused`() {
        // The listing is free 06:00 to 12:00. The whole of a 13:00 to 15:00
        // request is outside it, and this is a booking rule rather than a
        // scoring signal — see `Availability.kt`.
        val refused = refusedBy(
            BookingEngine.create(
                listing = Fixtures.paidListing(),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(13), Fixtures.time(15)),
                at = Fixtures.NOW,
                partner = Fixtures.partner(),
            ),
        )
        assertEquals(BookingBlock.OutsideAvailability, refused)
    }

    @Test
    fun `a provider who has not filled in their hours can still be booked`() {
        // Nobody has said they are unavailable; they have said nothing.
        // Refusing every offer whose poster skipped a field would quietly
        // remove the listings of everybody who found the form confusing.
        val created = assertIs<CreateResult.Created>(
            BookingEngine.create(
                listing = Fixtures.listing(availability = emptyList()),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                at = Fixtures.NOW,
            ),
        )
        assertEquals(Fixtures.listing().id, created.booking.record.listingId)
    }

    @Test
    fun `somebody with three sessions already arranged is told to finish one first`() {
        val open = (1..BookingEngine.MAX_OPEN_BOOKINGS).map {
            Fixtures.booking(id = it, seekerId = seekerId, status = BookingStatus.Confirmed)
        }
        val refused = refusedBy(
            BookingEngine.create(
                listing = Fixtures.listing(),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                at = Fixtures.NOW,
                otherOpenBookings = open,
            ),
        )
        assertEquals(BookingBlock.TooManyOpenBookings, refused)
    }

    @Test
    fun `a past booking does not count towards the limit`() {
        // A ceiling on what is happening now, not a quota on what somebody has
        // ever done.
        val finished = (1..10).map { Fixtures.booking(id = it, status = BookingStatus.Completed) }
        val created = assertIs<CreateResult.Created>(
            BookingEngine.create(
                listing = Fixtures.listing(),
                seekerId = seekerId,
                slot = TimeSlot(Fixtures.WEDNESDAY, Fixtures.time(7), Fixtures.time(9)),
                at = Fixtures.NOW,
                otherOpenBookings = finished,
            ),
        )
        assertEquals(seekerId, created.booking.record.seekerId)
    }

    // ----------------------------------------------------- both sides say --

    @Test
    fun `one side agreeing is not a confirmed booking`() {
        // Line 3: "Both sides confirm." A single tap is not agreement, and a
        // status that said Confirmed on one tap would be a lie in a field the
        // whole app trusts.
        val booking = requested()

        val afterSeeker = applied(BookingEngine.confirm(context(booking), seekerId))
        assertEquals(BookingStatus.Requested, afterSeeker.status)
        assertEquals(1, afterSeeker.confirmations.count)
        assertFalse(afterSeeker.confirmations.bothConfirmed)

        val afterProvider = applied(BookingEngine.confirm(context(afterSeeker), providerId))
        assertEquals(BookingStatus.Confirmed, afterProvider.status)
        assertTrue(afterProvider.confirmations.bothConfirmed)
    }

    @Test
    fun `agreeing twice is not an error`() {
        // A dropped connection and a second tap both arrive as a repeated
        // confirm, and neither is worth showing somebody who has just agreed
        // to be somewhere.
        val booking = requested()
        val once = applied(BookingEngine.confirm(context(booking), seekerId))
        val twice = applied(BookingEngine.confirm(context(once), seekerId))

        assertEquals(once.confirmations, twice.confirmations)
        assertTrue(twice.note.isNotBlank())
    }

    @Test
    fun `somebody who is neither party cannot confirm`() {
        assertEquals(
            BookingBlock.NotAParty,
            assertIs<BookingResult.Refused>(BookingEngine.confirm(context(requested()), 999)).block,
        )
    }

    @Test
    fun `a confirmed booking cannot be confirmed again`() {
        val both = applied(
            BookingEngine.confirm(
                context(applied(BookingEngine.confirm(context(requested()), seekerId))),
                providerId,
            ),
        )
        assertEquals(
            BookingBlock.NotARequest,
            assertIs<BookingResult.Refused>(BookingEngine.confirm(context(both), seekerId)).block,
        )
    }

    @Test
    fun `a session is finished when both sides say it was`() {
        val confirmed = confirmed()
        val afterSeeker = applied(BookingEngine.complete(context(confirmed), seekerId))

        // The hold does not start on the first tap. If it did, one person
        // pressing a button could start the seeker's 48 hours early.
        assertEquals(BookingStatus.Confirmed, afterSeeker.status)
        assertEquals(null, afterSeeker.record.payoutHeldUntil)

        val afterProvider = applied(BookingEngine.complete(context(afterSeeker), providerId))
        assertEquals(BookingStatus.Completed, afterProvider.status)
        assertEquals(BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW), afterProvider.record.payoutHeldUntil)
    }

    @Test
    fun `a booking that was never agreed cannot be marked finished`() {
        assertEquals(
            BookingBlock.NotConfirmed,
            assertIs<BookingResult.Refused>(BookingEngine.complete(context(requested()), seekerId)).block,
        )
    }

    // ---------------------------------------------------------- cancelling --

    @Test
    fun `cancelling after a payment refunds the seeker`() {
        // The alternative is a booking that reads as cancelled while the money
        // is still sitting in a gateway, and a pensioner noticing that a week
        // later.
        val paid = confirmed().let { it.copy(record = it.record.copy(paymentRef = "pay_1")) }
        val cancelled = assertIs<BookingResult.Applied>(
            BookingEngine.cancel(context(paid), seekerId, "The session was cancelled."),
        )

        assertEquals(BookingStatus.Cancelled, cancelled.booking.status)
        assertEquals(MoneyMove.RefundSeeker, cancelled.money)
        assertEquals(seekerId, cancelled.booking.cancelledBy)
        assertEquals("The session was cancelled.", cancelled.booking.cancelledReason)
    }

    @Test
    fun `cancelling work that was free moves no money`() {
        val cancelled = assertIs<BookingResult.Applied>(
            BookingEngine.cancel(context(confirmed()), providerId),
        )
        assertEquals(MoneyMove.None, cancelled.money)
        assertTrue(BookingEngine.isFinal(cancelled.booking))
    }

    @Test
    fun `a cancelled booking cannot be cancelled again`() {
        val cancelled = applied(BookingEngine.cancel(context(confirmed()), seekerId))
        assertEquals(
            BookingBlock.NotOpenAnyMore,
            assertIs<BookingResult.Refused>(BookingEngine.cancel(context(cancelled), seekerId)).block,
        )
    }

    // ---------------------------------------------------------- disputing --

    @Test
    fun `a session that did not happen can be disputed inside the hold`() {
        // Line 5, and the whole reason the 48 hours exist. A seeker who has
        // paid and been let down has to have somewhere to say so while it can
        // still be acted on.
        val disputed = assertIs<BookingResult.Applied>(
            BookingEngine.dispute(contextFor(now = Fixtures.NOW), seekerId, "He did not come."),
        )

        assertEquals(BookingStatus.Disputed, disputed.booking.status)
        assertEquals("He did not come.", disputed.booking.disputeReason)
        assertTrue(BookingEngine.disputeWindowClosed(disputed.booking, Fixtures.NOW).not())
    }

    @Test
    fun `a dispute after the hold is refused, and the sentence says the door is still open`() {
        val afterTheHold = contextFor(now = BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW))
        val refused = assertIs<BookingResult.Refused>(
            BookingEngine.dispute(afterTheHold, seekerId, "He did not come."),
        )

        assertEquals(BookingBlock.DisputeWindowClosed, refused.block)
        // The message has to point somewhere. A refusal that reads as a dead
        // end is worse than no button at all, because now the person knows
        // there is a problem and has nowhere to put it.
        assertTrue(refused.block.message.contains("contact us"))
    }

    @Test
    fun `a cancelled booking cannot be disputed`() {
        val cancelled = applied(BookingEngine.cancel(context(confirmed()), seekerId))
        assertEquals(
            BookingBlock.NotOpenAnyMore,
            assertIs<BookingResult.Refused>(BookingEngine.dispute(context(cancelled), seekerId, "x")).block,
        )
    }

    @Test
    fun `a moderator can refund, release or split`() {
        val disputed = applied(BookingEngine.dispute(contextFor(now = Fixtures.NOW), seekerId, "x"))

        val refund = assertIs<BookingResult.Applied>(
            BookingEngine.resolveDispute(context(disputed.booking), 1, DisputeOutcome.RefundSeeker),
        )
        assertEquals(MoneyMove.RefundSeeker, refund.money)
        assertEquals(DisputeOutcome.RefundSeeker, refund.booking.disputeOutcome)
        // The row stays Disputed, so the history shows there was one.
        assertEquals(BookingStatus.Disputed, refund.booking.status)
        assertTrue(refund.booking.isSettled)

        val release = applied(BookingEngine.resolveDispute(context(disputed.booking), 1, DisputeOutcome.ReleaseToMentor))
        assertEquals(MoneyMove.ReleaseToPartner, release.money)

        val split = applied(BookingEngine.resolveDispute(context(disputed.booking), 1, DisputeOutcome.Split))
        assertEquals(MoneyMove.Split, split.money)
    }

    @Test
    fun `a moderator cannot release money to an unverified partner either`() {
        // The anti-scam rule does not switch off for disputes. A moderation
        // decision is a reason to pay a specific person, and the same person
        // who is not allowed to be paid normally is not allowed here.
        val disputed = applied(BookingEngine.dispute(contextFor(now = Fixtures.NOW), seekerId, "x"))
        val release = assertIs<BookingResult.Applied>(
            BookingEngine.resolveDispute(
                context(disputed.booking, partner = Fixtures.unverifiedPartner()),
                1,
                DisputeOutcome.ReleaseToMentor,
            ),
        )

        assertEquals(MoneyMove.Hold, release.money)
        assertFalse(release.booking.isSettled)
    }

    @Test
    fun `a dispute is answered once`() {
        val disputed = applied(BookingEngine.dispute(contextFor(now = Fixtures.NOW), seekerId, "x"))
        val answered = applied(BookingEngine.resolveDispute(context(disputed.booking), 1, DisputeOutcome.RefundSeeker))

        assertEquals(
            BookingBlock.DisputeAlreadyResolved,
            assertIs<BookingResult.Refused>(
                BookingEngine.resolveDispute(context(answered.booking), 2, DisputeOutcome.ReleaseToMentor),
            ).block,
        )
    }

    // ------------------------------------------------ the money, the hold --

    @Test
    fun `money cannot be released before the hold expires`() {
        // The requirement, and the reason the hold exists: a pensioner who has
        // paid for a session that did not happen has to have a window in which
        // to say so, and the money has to still be there when they do.
        val refused = assertIs<BookingResult.Refused>(
            BookingEngine.settle(contextFor(now = Fixtures.NOW), "payout_1"),
        )

        assertEquals(BookingBlock.HoldNotExpired, refused.block)
        assertEquals(null, refused.booking.record.payoutRef)
        // And the sentence explains the wait rather than just refusing.
        assertTrue(refused.block.message.contains("48 hours"))
    }

    @Test
    fun `one second early is still early`() {
        val justEarly = BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW) - 1.seconds
        assertEquals(
            BookingBlock.HoldNotExpired,
            assertIs<BookingResult.Refused>(BookingEngine.settle(contextFor(now = justEarly), "p")).block,
        )
    }

    @Test
    fun `money is released the moment the hold expires`() {
        val atTheMoment = BookingEngine.holdExpiresAt(Fixtures.NOW, Fixtures.NOW)
        val settled = assertIs<BookingResult.Applied>(
            BookingEngine.settle(contextFor(now = atTheMoment), "payout_1"),
        )

        assertEquals("payout_1", settled.booking.record.payoutRef)
        assertEquals(MoneyMove.ReleaseToPartner, settled.money)
        // 200 less the 12% fee.
        assertEquals(176.0, settled.booking.payoutAmount)
    }

    @Test
    fun `money cannot go to an unverified partner`() {
        // The requirement, and the first of section 6.8's hard-coded anti-scam
        // rules. The check is on the partner's state *now*, not when the
        // booking was made, so a verification that is later withdrawn stops
        // payouts rather than merely failing to start one.
        assertEquals(
            BookingBlock.PartnerNotVerified,
            assertIs<BookingResult.Refused>(
                BookingEngine.settle(contextFor(partner = Fixtures.unverifiedPartner()), "payout_1"),
            ).block,
        )
        // Verified, but with no registration number, is still not payable.
        assertEquals(
            BookingBlock.PartnerNotVerified,
            assertIs<BookingResult.Refused>(
                BookingEngine.settle(contextFor(partner = Fixtures.unregisteredPartner()), "payout_1"),
            ).block,
        )
        // And there is no route to a person: with no organisation at all, the
        // booking has nowhere to send money.
        assertEquals(
            BookingBlock.NoPartner,
            assertIs<BookingResult.Refused>(
                BookingEngine.settle(contextFor(partner = null), "payout_1"),
            ).block,
        )
    }

    @Test
    fun `the refusal to pay an unverified partner tells the seeker not to pay them`() {
        // The important part of the sentence. Somebody who has just been told
        // "we cannot pay this" may well pay it themselves out of pocket, and
        // this app is trying to stop exactly that.
        val block = BookingBlock.PartnerNotVerified
        assertTrue(block.message.contains("Do not pay"))
    }

    @Test
    fun `a booking that was not completed is not settled`() {
        assertEquals(
            BookingBlock.NotCompleted,
            assertIs<BookingResult.Refused>(
                BookingEngine.settle(context(confirmed(), now = Fixtures.at(20)), "payout_1"),
            ).block,
        )
    }

    @Test
    fun `a booking with no hold on it is not settled`() {
        // A row that reached Completed some other way, with no hold set. The
        // job skips it rather than guessing when the window was.
        val unheld = Fixtures.booking(status = BookingStatus.Completed, payoutHeldUntil = null)
        assertEquals(
            BookingBlock.HoldNotExpired,
            assertIs<BookingResult.Refused>(
                BookingEngine.settle(context(Booking(record = unheld)), "payout_1"),
            ).block,
        )
    }

    @Test
    fun `a settled booking is not settled twice`() {
        val settled = applied(BookingEngine.settle(contextFor(), "payout_1"))
        assertEquals(
            BookingBlock.AlreadySettled,
            assertIs<BookingResult.Refused>(BookingEngine.settle(context(settled.booking), "payout_2")).block,
        )
        assertEquals("payout_1", settled.booking.record.payoutRef)
    }

    @Test
    fun `free work settles with nothing to pay rather than staying open forever`() {
        // The payouts job walks every completed booking, and a record that
        // never resolves is one it keeps picking up.
        val freeBooking = Fixtures.completedBooking(amount = 0.0, paid = false)
        val settled = assertIs<BookingResult.Applied>(
            BookingEngine.settle(contextFor(booking = freeBooking), "payout_1"),
        )

        assertEquals(MoneyMove.None, settled.money)
        assertTrue(settled.booking.isSettled)
        assertTrue(settled.note.contains("free"))
    }

    // -------------------------------------------------------------- misc ---

    @Test
    fun `every block has a sentence somebody could read to a user`() {
        for (block in BookingBlock.entries) {
            assertTrue(block.message.isNotBlank(), "$block has no message")
            assertTrue(block.code.isNotBlank())
        }
    }

    @Test
    fun `a dispute outcome code from a newer backend is refused rather than guessed`() {
        assertEquals(DisputeOutcome.RefundSeeker, DisputeOutcome.fromCode("refund_seeker"))
        assertEquals(null, DisputeOutcome.fromCode("something_new"))
        assertEquals(null, DisputeOutcome.fromCode(null))
    }

    @Test
    fun `a refusal leaves the booking exactly as it was`() {
        // The point of a pure reducer. A screen that gets a refusal and then
        // re-renders from the returned booking must not have lost the state
        // the user had.
        val before = Fixtures.completedBooking()
        val result = BookingEngine.settle(context(booking = before, now = Fixtures.NOW), "payout_1")

        assertEquals(before, assertIs<BookingResult.Refused>(result).booking)
    }

    @Test
    fun `a confirmed booking needs both sides even when the seeker is the one paying`() {
        // The plan does not make the payer special, and a booking the payer
        // can complete alone is a booking the other side never has to attend.
        val booking = applied(BookingEngine.confirm(context(requested()), seekerId))
        assertEquals(1, booking.confirmations.count)
        assertEquals(null, booking.record.paymentRef)
    }

    @Test
    fun `a rating outside one to five is refused at construction`() {
        // Core-model's guard, restated so a future change to it is noticed.
        assertFailsWith<IllegalArgumentException> { Fixtures.rating(1, 2, 0) }
        assertFailsWith<IllegalArgumentException> { Fixtures.rating(1, 2, 6) }
    }

    // ----------------------------------------------------------- helpers ---

    private fun requested(): Booking = Booking(
        record = Fixtures.booking(listingId = 2, seekerId = seekerId, providerId = providerId),
    )

    private fun confirmed(): Booking {
        val booking = requested()
        return applied(BookingEngine.confirm(context(booking), seekerId))
            .let { applied(BookingEngine.confirm(context(it.booking), providerId)).booking }
    }

    private fun applied(result: BookingResult): BookingResult.Applied =
        assertIs<BookingResult.Applied>(result, "expected the action to be applied")

    private fun refusedBy(result: CreateResult): BookingBlock =
        assertIs<CreateResult.Refused>(result, "expected the booking to be refused").block
}
