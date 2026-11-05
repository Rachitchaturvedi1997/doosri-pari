package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.ListingMode
import `in`.doosripari.core.model.ListingStatus
import `in`.doosripari.core.model.ListingType
import `in`.doosripari.core.model.MatchReason
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Anubhav board, plan section 6.8.
 *
 * Two claims are under test. The first is that **free, barter and paid are
 * three modes rather than three prices** — which is a claim about what the
 * screen is allowed to print. The second is that **a filter is the user's
 * decision and a score is ours** — a filter may remove a row, but it must
 * record why, and a low score must never remove anything at all.
 */
class ListingsTest {

    private val seeker = Fixtures.profile(
        tags = listOf("maths"),
        freeHours = listOf(Fixtures.window(Fixtures.WEDNESDAY, 7, 11)),
    )

    private fun board(
        vararg listings: SevaListing,
        seeker: MatchProfile = this.seeker,
        providers: Map<Int, MatchProfile> = emptyMap(),
        query: ListingQuery = ListingQuery(),
    ) = ListingBoardBuilder.build(listings.toList(), seeker, providers, query)

    // ------------------------------------------------- free is not ₹0 ------

    @Test
    fun `a free offer is free and is never printed as a price of zero`() {
        // The claim. A board that renders `price` would show "₹0" on the one
        // kind of listing that means "I am giving you my time", which is a
        // different statement and a colder one.
        val offer = Fixtures.listing(mode = ListingMode.Free, price = 0.0).offer()

        assertEquals(ListingOffer.Free, offer)
        assertEquals("Free", ListingFormat.amountLabel(offer))
        assertFalse(ListingFormat.needsScamNotice(offer))
    }

    @Test
    fun `a barter offer is barter, not a price either`() {
        val offer = Fixtures.barterListing().offer()

        assertEquals(ListingOffer.Barter, offer)
        assertEquals("Barter", ListingFormat.amountLabel(offer))
        assertFalse(ListingFormat.needsScamNotice(offer))
    }

    @Test
    fun `a paid offer is an amount`() {
        val offer = Fixtures.paidListing(price = 250.0).offer()

        assertEquals(ListingOffer.Paid(250.0), offer)
        assertEquals("₹250", ListingFormat.amountLabel(offer))
        assertTrue(ListingFormat.needsScamNotice(offer))
    }

    @Test
    fun `a paid offer with no amount is a mistake, and is not printed as free`() {
        // The awkward case [ListingMode] exists for. "Paid" with nothing in
        // the price field is a posting error, and rendering it as "Free"
        // would undo the entire reason the enum is a sealed set of three.
        val broken = Fixtures.paidListing(price = 0.0)
        val offer = broken.offer()

        assertIs<ListingOffer.Misconfigured>(offer)
        assertEquals("Price not set", ListingFormat.amountLabel(offer))
        assertEquals(broken.title, offer.title)
    }

    @Test
    fun `a free offer that somehow carries an amount is still free`() {
        // Mode wins over the amount. A poster who leaves a price on a listing
        // they marked free has made free, and the app should not charge
        // somebody on the strength of a field they did not mean.
        val offer = Fixtures.listing(mode = ListingMode.Free, price = 500.0).offer()

        assertEquals(ListingOffer.Free, offer)
        assertEquals("Free", ListingFormat.amountLabel(offer))
    }

    @Test
    fun `every paid card carries the anti-scam line the plan hard-codes`() {
        // Section 6.8 lists it as one of three rules that are not negotiable,
        // so it is a constant rather than a string somebody can lose in a
        // layout file.
        assertEquals("Never pay to get work.", NEVER_PAY_NOTICE)
        assertTrue(ListingFormat.needsScamNotice(ListingOffer.Paid(1.0)))
        assertFalse(ListingFormat.needsScamNotice(ListingOffer.Free))
    }

    // ------------------------------------------------------------- filters --

    @Test
    fun `a type filter keeps only what was asked for, and says what it dropped`() {
        val mentor = Fixtures.listing(id = 1, type = ListingType.Mentor)
        val help = Fixtures.listing(id = 2, type = ListingType.Help)

        val filtered = board(mentor, help, query = ListingQuery(types = setOf(ListingType.Mentor)))

        assertEquals(listOf(1), filtered.shown.map { it.listing.id })
        assertEquals(
            listOf(ExclusionReason.Type),
            filtered.excluded.map { it.reason },
        )
        // And the sentence is the one a person would need.
        assertTrue(ExclusionReason.Type.message.isNotBlank())
    }

    @Test
    fun `a mode filter keeps free work out of a paid search`() {
        // If modes were prices this could not be written, because a free offer
        // would sort as the cheapest and appear in every search.
        val free = Fixtures.listing(id = 1, mode = ListingMode.Free)
        val paid = Fixtures.paidListing(id = 2)
        val barter = Fixtures.barterListing(id = 3)

        val paidOnly = board(free, paid, barter, query = ListingQuery(modes = setOf(ListingMode.Paid)))
        assertEquals(listOf(2), paidOnly.shown.map { it.listing.id })

        val freeOnly = board(free, paid, barter, query = ListingQuery(modes = setOf(ListingMode.Free)))
        assertEquals(listOf(1), freeOnly.shown.map { it.listing.id })
    }

    @Test
    fun `an empty mode filter means every mode rather than no modes`() {
        // The default matters. A board that started on "free" would quietly
        // hide paid work from somebody looking for it.
        val everything = board(Fixtures.listing(id = 1), Fixtures.paidListing(id = 2), Fixtures.barterListing(id = 3))

        assertEquals(3, everything.shown.size)
        assertTrue(everything.excluded.isEmpty())
    }

    @Test
    fun `a tag filter matches without caring about case`() {
        val tagged = Fixtures.listing(id = 1, tags = listOf("Maths", "English"))
        val other = Fixtures.listing(id = 2, tags = listOf("Cooking"))

        val filtered = board(tagged, other, query = ListingQuery(tags = setOf(" maths ")))

        assertEquals(listOf(1), filtered.shown.map { it.listing.id })
    }

    @Test
    fun `a text search looks in the title, the body and the tags`() {
        val inTitle = Fixtures.listing(id = 1, title = "Help with pension forms")
        val inBody = Fixtures.listing(id = 2, body = "I will help you fill in your pension form")
        val inTags = Fixtures.listing(id = 3, tags = listOf("pension"))
        val unrelated = Fixtures.listing(id = 4, title = "Chai and company")

        val found = board(inTitle, inBody, inTags, unrelated, query = ListingQuery(text = "PENSION"))

        assertEquals(listOf(2, 1, 3), found.shown.map { it.listing.id })
        assertEquals(listOf(ExclusionReason.Text), found.excluded.map { it.reason })
    }

    @Test
    fun `a city filter uses the poster's city and leaves unplaced posters alone`() {
        // A listing carries no city of its own, so the filter needs the
        // profile. A poster whose profile has not loaded cannot be placed, and
        // removing their listing over a value we do not have would be wrong.
        val listings = arrayOf(Fixtures.listing(id = 1), Fixtures.listing(id = 2, ownerId = 200))
        val providers = mapOf(
            100 to Fixtures.profile(userId = 100, cityId = 7),
            200 to Fixtures.profile(userId = 200, cityId = 99),
        )

        val filtered = board(
            *listings,
            providers = providers,
            query = ListingQuery(cityId = 7),
        )

        assertEquals(listOf(1), filtered.shown.map { it.listing.id })
        assertEquals(listOf(ExclusionReason.City), filtered.excluded.map { it.reason })
    }

    @Test
    fun `a poster whose profile is missing is not filtered out of their own city`() {
        val filtered = board(
            Fixtures.listing(id = 1),
            query = ListingQuery(cityId = 7),
        )
        assertEquals(1, filtered.shown.size)
    }

    @Test
    fun `a paid listing waived for other tiers is hidden from the wrong tier`() {
        val waived = Fixtures.paidListing(id = 1).copy(freeForTiers = listOf(Tier.T2, Tier.T3))

        val wrongTier = board(waived, query = ListingQuery(seekerTier = Tier.T1))
        assertEquals(0, wrongTier.shown.size)
        assertEquals(listOf(ExclusionReason.Tier), wrongTier.excluded.map { it.reason })

        val rightTier = board(waived, query = ListingQuery(seekerTier = Tier.T2))
        assertEquals(1, rightTier.shown.size)
    }

    // ---------------------------------------------- availability as a filter --

    @Test
    fun `what a retired person has time for is a first class filter`() {
        // The requirement. Somebody free from seven to eleven cannot make a
        // two o'clock appointment, and the board says so in a sentence rather
        // than scoring it and leaving the person to work it out.
        val workingAfternoon = Fixtures.listing(
            id = 1,
            title = "Mentoring, weekday afternoons",
            availability = listOf(Fixtures.window(Fixtures.WEDNESDAY, 13, 18)),
        )
        val morning = Fixtures.listing(
            id = 2,
            title = "Mentoring, mornings",
            availability = RetiredHours.typical(Fixtures.WEDNESDAY),
        )

        val filtered = board(workingAfternoon, morning)

        assertEquals(listOf(2), filtered.shown.map { it.listing.id })
        assertEquals(listOf(ExclusionReason.Availability), filtered.excluded.map { it.reason })
        assertTrue(ExclusionReason.Availability.message.isNotBlank())
    }

    @Test
    fun `a listing the seeker could mostly make is kept rather than dropped`() {
        // An availability filter that required a perfect fit would empty the
        // board for anybody whose hours overlap by an hour. Half is worth
        // showing — the seeker is the only one who knows whether they could
        // come an hour early.
        val mostly = Fixtures.listing(
            id = 1,
            availability = listOf(Fixtures.window(Fixtures.WEDNESDAY, 6, 10)),
        )

        val filtered = board(mostly)

        assertEquals(1, filtered.shown.size)
        assertTrue(filtered.excluded.isEmpty())
    }

    @Test
    fun `an availability filter is off by default, because nobody asked for it`() {
        val officeOnly = Fixtures.listing(
            id = 1,
            availability = listOf(Fixtures.window(Fixtures.WEDNESDAY, 9, 17)),
        )

        val unfiltered = board(officeOnly)

        assertEquals(1, unfiltered.shown.size)
        assertTrue(unfiltered.excluded.isEmpty())
    }

    @Test
    fun `a seeker free on two days is not judged on the first one only`() {
        val tuesdayOnly = Fixtures.listing(
            id = 1,
            availability = listOf(Fixtures.window(2, 7, 11)),
        )
        val freeTuesdayAndThursday = listOf(
            Fixtures.window(2, 7, 11),
            Fixtures.window(4, 7, 11),
        )

        val seekerFreeBoth = seeker.copy(freeHours = freeTuesdayAndThursday)
        val filtered = board(tuesdayOnly, seeker = seekerFreeBoth)

        assertEquals(1, filtered.shown.size)
    }

    // ------------------------------------------------------- the radius ------

    @Test
    fun `a radius the user typed is respected, and what it dropped is counted`() {
        val near = Fixtures.listing(id = 1, location = Fixtures.NEARBY)
        val far = Fixtures.listing(id = 2, location = Fixtures.FAR_AWAY)

        val filtered = board(near, far, query = ListingQuery(origin = Fixtures.DELHI, maxDistanceKm = 5.0))

        assertEquals(listOf(1), filtered.shown.map { it.listing.id })
        assertEquals(listOf(ExclusionReason.OutsideRadius), filtered.excluded.map { it.reason })
        assertEquals(1, filtered.beyondRadius.size)
        // And the dropped one still carries its reason, so the screen can say
        // "one is further than that" rather than presenting a shorter list.
        assertTrue(MatchReason.OutsideRadius in filtered.excluded.single().match.reasons)
    }

    @Test
    fun `a listing the radius dropped comes back when the user asks for everything`() {
        // "Never pay, never filter" — the soft exclusions are undoable, and
        // the label on the control has to say what it does.
        val near = Fixtures.listing(id = 1, location = Fixtures.NEARBY)
        val far = Fixtures.listing(id = 2, location = Fixtures.FAR_AWAY)
        val query = ListingQuery(origin = Fixtures.DELHI, maxDistanceKm = 5.0)

        val filtered = board(near, far, query = query)
        val revealed = filtered.revealed()

        assertEquals(2, filtered.shown.size + filtered.beyondRadius.size)
        assertEquals(2, revealed.size)
        assertTrue(revealed.any { it.listing.id == 2 })
    }

    @Test
    fun `a type the user did not choose does not come back with the rest`() {
        // The difference between a preference and a statement. Somebody who
        // said "mentoring only" did not ask for the vegetable seller's listing
        // to reappear under a "show everything" button.
        val mentor = Fixtures.listing(id = 1, type = ListingType.Mentor, location = Fixtures.FAR_AWAY)
        val help = Fixtures.listing(id = 2, type = ListingType.Help, location = Fixtures.FAR_AWAY)
        val query = ListingQuery(
            types = setOf(ListingType.Mentor),
            origin = Fixtures.DELHI,
            maxDistanceKm = 5.0,
        )

        val revealed = board(mentor, help, query = query).revealed()

        assertEquals(listOf(1), revealed.map { it.listing.id })
    }

    @Test
    fun `a listing with no location is not excluded by a radius`() {
        // Work that is online is not far away. Removing it for being
        // unmeasured would be hiding a listing for a reason nobody chose.
        val online = Fixtures.listing(id = 1, location = null, languages = listOf(AppLanguage.English))

        val filtered = board(online, query = ListingQuery(origin = Fixtures.DELHI, maxDistanceKm = 5.0))

        assertEquals(1, filtered.shown.size)
        assertNull(filtered.shown.single().distanceKm)
    }

    // ------------------------------------------------- nothing is hidden ----

    @Test
    fun `a listing that scores terribly is still on the board`() {
        // Section 6.8: "matching is a score, never a filter". A listing three
        // hundred metres away that speaks Tamil, wants tamil and offers nothing
        // this seeker wants is still a listing, and somebody is entitled to see
        // it and decide for themselves.
        val hopeless = Fixtures.listing(
            id = 1,
            tags = listOf("tamil"),
            languages = listOf(AppLanguage.Tamil),
            location = Fixtures.FAR_AWAY,
            availability = listOf(Fixtures.window(Fixtures.WEDNESDAY, 2, 3)),
        )

        val result = board(hopeless)

        assertEquals(1, result.shown.size)
        assertTrue(result.shown.single().match.score < 20)
        assertTrue(result.shown.single().match.negatives.isNotEmpty())
        assertTrue(result.excluded.isEmpty())
    }

    @Test
    fun `an empty board says whether it was filtered or simply empty`() {
        // The difference between "nobody has posted yet" and "everything you
        // were looking for is in another city". Showing the same empty
        // illustration for both teaches a user the board is broken.
        val everythingFiltered = board(Fixtures.listing(id = 1), query = ListingQuery(types = setOf(ListingType.Space)))
        assertTrue(everythingFiltered.isEmpty)
        assertTrue(everythingFiltered.wasFiltered)

        val nothingPosted = board(query = ListingQuery())
        assertTrue(nothingPosted.isEmpty)
        assertFalse(nothingPosted.wasFiltered)
    }

    @Test
    fun `every exclusion reason has a sentence and a wire code`() {
        for (reason in ExclusionReason.entries) {
            assertTrue(reason.message.isNotBlank(), "$reason has no message")
            assertTrue(reason.code.isNotBlank())
        }
    }

    // --------------------------------------------------------- anti-scam ----

    @Test
    fun `a listing asking the pensioner to pay is blocked`() {
        // Section 6.8's hard-coded rule, and it is checked before everything
        // else so that a scam is never reported as "already taken".
        val scam = Fixtures.scamListing()
        val result = board(scam, query = ListingQuery(types = setOf(ListingType.Mentor)))

        assertTrue(result.shown.isEmpty())
        assertEquals(
            listOf(ExclusionReason.AsksSeekerToPay),
            result.excluded.map { it.reason },
        )
        assertTrue(ExclusionReason.AsksSeekerToPay.message.contains("Never pay"))
    }

    @Test
    fun `a listing that only mentions money is not blocked`() {
        // The false positive that matters most. "I did not have to pay
        // anything" and "she paid me a visit" are ordinary sentences, and a
        // filter that blocks them removes good listings from the board.
        val innocent = Fixtures.listing(
            id = 1,
            title = "I did not have to pay anything",
            body = "The doctor said there was no fee either.",
        )

        val result = board(innocent)

        assertEquals(1, result.shown.size)
        assertFalse(ScamSignals.looksLikePaymentRequest(innocent))
    }

    @Test
    fun `the scam screen shows a moderator which phrases fired`() {
        // A queue that only says "suspicious" gets ignored, and gets ignored
        // usefully as soon as it is wrong.
        val phrases = ScamSignals.matchedPhrases(Fixtures.scamListing())

        assertTrue(phrases.contains("registration fee"))
        assertTrue(phrases.contains("please pay"))
        assertTrue(phrases.isNotEmpty())
    }

    // --------------------------------------------------------- the order ----

    @Test
    fun `the board is ordered by match and never by amount`() {
        // The mistake the mode enum exists to prevent. A board sorted by price
        // puts every volunteer offer above every paid one, which means a
        // pensioner looking for paid mentoring never sees it.
        val paidAndGood = Fixtures.paidListing(id = 2).copy(tags = listOf("maths"))
        val freeAndWeaker = Fixtures.listing(id = 1, tags = emptyList())

        val result = board(freeAndWeaker, paidAndGood)

        assertEquals(listOf(2, 1), result.shown.map { it.listing.id })
        assertTrue(result.shown.first().match.score > result.shown.last().match.score)
    }

    @Test
    fun `an equally scored pair is ordered by newest and then by id`() {
        // Deterministic order across two platforms. Somebody telling their
        // neighbour "it was second on the list" is a real use of this screen.
        val older = Fixtures.listing(id = 1, createdAt = Fixtures.at(8))
        val newer = Fixtures.listing(id = 2, createdAt = Fixtures.at(9))
        val sameAgeOtherId = Fixtures.listing(id = 3, createdAt = Fixtures.at(9))

        val result = board(older, sameAgeOtherId, newer)

        assertEquals(listOf(3, 2, 1), result.shown.map { it.listing.id })
    }

    @Test
    fun `a listing with no timestamp sorts to the bottom rather than crashing`() {
        val undated = Fixtures.listing(id = 1, createdAt = null)
        val dated = Fixtures.listing(id = 2, createdAt = Fixtures.at(6))

        val result = board(undated, dated)

        assertEquals(listOf(2, 1), result.shown.map { it.listing.id })
    }

    // ------------------------------------------------------------- closed ---

    @Test
    fun `a filled listing is not a search result unless asked for`() {
        val filled = Fixtures.listing(id = 1, status = ListingStatus.Filled)

        val default = board(filled)
        assertTrue(default.shown.isEmpty())
        assertEquals(listOf(ExclusionReason.NotOpen), default.excluded.map { it.reason })

        val withClosed = board(filled, query = ListingQuery(includeClosed = true))
        assertEquals(1, withClosed.shown.size)
    }

    // -------------------------------------------------------------- misc ---

    @Test
    fun `a distance is measured from the seeker and shown as a sentence`() {
        val near = Fixtures.listing(id = 1, location = Fixtures.NEARBY)
        val nextDoor = Fixtures.listing(id = 2, location = Fixtures.NEXT_DOOR)

        val result = board(near, nextDoor, query = ListingQuery(origin = Fixtures.DELHI))

        assertEquals(
            "1 km away",
            ListingFormat.distanceLabel(result.shown.first { it.listing.id == 1 }.distanceKm),
        )
        // Under a kilometre is a phrase rather than a rounded "0 km", which
        // reads as though the listing were at the seeker's own address.
        assertEquals(
            "less than 1 km away",
            ListingFormat.distanceLabel(result.shown.first { it.listing.id == 2 }.distanceKm),
        )
        // And an unmeasured distance is a blank, not a dash: a dash reads as
        // something that went wrong.
        assertEquals("", ListingFormat.distanceLabel(null))
    }

    @Test
    fun `every listing carries its offer on the scored row`() {
        val result = board(Fixtures.listing(id = 1), Fixtures.paidListing(id = 2))

        assertEquals(ListingOffer.Free, result.shown.first { it.listing.id == 1 }.offer)
        assertEquals(ListingOffer.Paid(200.0), result.shown.first { it.listing.id == 2 }.offer)
    }

    @Test
    fun `the board remembers the query it was built from`() {
        // A screen that re-renders a "showing X near you" heading needs this,
        // and getting it from somewhere other than the board is how a heading
        // ends up describing a filter that was cleared.
        val query = ListingQuery(text = "tuition", maxDistanceKm = 5.0)
        val result = board(Fixtures.listing(id = 1), query = query)

        assertEquals(query, result.query)
    }
}
