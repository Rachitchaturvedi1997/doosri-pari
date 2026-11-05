package `in`.doosripari.feature.seva

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reputation, plan section 6.8's "Bayesian, prior 4.0 from 5".
 *
 * The requirement this file exists to satisfy: **a single 1-star from a
 * relative must not dominate.** There are three separate mechanisms for that
 * and each gets its own test, because they fail differently — a prior that is
 * too weak, a booking that can be rated many times, and a number printed
 * without its count.
 */
class ReputationTest {

    // --------------------------------------------------------------- prior --

    @Test
    fun `a provider nobody has rated starts at the prior, not at zero`() {
        val summary = Reputation.summarise(emptyList())

        assertEquals(0, summary.ratingCount)
        assertEquals(4.0, summary.bayesianAverage)
        // The plain mean has nothing to average, and saying 0.0 would put a
        // warning icon on somebody who has not been reviewed badly.
        assertNull(summary.rawAverage)
        assertEquals(TrustBand.Unrated, summary.band)
    }

    @Test
    fun `one one star review does not make a provider look terrible`() {
        // The requirement. A relative who had a bad evening taps one star, and
        // 4.0 becomes 3.5 — visible on a card, and not a verdict about somebody
        // the seeker has never met.
        val summary = Reputation.summarise(listOf(Fixtures.rating(1, 900, 1)))

        assertEquals(1, summary.ratingCount)
        assertEquals(3.5, summary.bayesianAverage)
        assertEquals(1.0, summary.rawAverage)
        assertTrue(summary.bayesianAverage > 3.0)
    }

    @Test
    fun `a single one star moves a provider by a visible but small amount`() {
        val fresh = Reputation.summarise(emptyList())
        val afterOneStar = Reputation.summarise(listOf(Fixtures.rating(1, 900, 1)))

        assertEquals(0.5, Reputation.effectOfOneStar(fresh))
        assertEquals(0.5, Reputation.ONE_STAR_EFFECT_NEW)
        // The raw average is the thing that would be a verdict — 1.0 out of 5
        // — and the Bayesian one is what the app is allowed to show.
        assertEquals(1.0, afterOneStar.rawAverage)
        assertTrue(afterOneStar.rawAverage!! < afterOneStar.bayesianAverage)
    }

    @Test
    fun `one glowing review does not make a provider look like a saint either`() {
        // The prior is symmetric on purpose. A relative who had a good evening
        // is the same threat to the average as one who had a bad one.
        // (20 + 5) / 6 = 4.17, not 5.0 and not 4.5.
        val summary = Reputation.summarise(listOf(Fixtures.rating(1, 900, 5)))

        assertEquals(4.17, summary.bayesianAverage)
        assertTrue(summary.bayesianAverage < 5.0)
    }

    @Test
    fun `enough bad reviews do move the number, because the prior is not a shield`() {
        // The other half of the requirement. If one bad review changed nothing
        // ever, the average would be theatre. Two of them is 3.14, five is
        // 2.5, and ten is 2.0 — the prior stops mattering as evidence
        // accumulates, which is what a prior is for.
        fun withOneStars(count: Int) = Reputation.summarise(
            (1..count).map { Fixtures.rating(it, 900, 1) },
        )

        assertEquals(3.14, withOneStars(2).bayesianAverage)
        assertEquals(2.5, withOneStars(5).bayesianAverage)
        assertEquals(2.0, withOneStars(10).bayesianAverage)
        assertEquals(TrustBand.Building, withOneStars(5).band)
    }

    @Test
    fun `a bad review from one relative cannot be multiplied`() {
        // The mechanism that the prior alone does not cover. Four taps on the
        // same evening are one opinion, and averaging four of them is how a
        // score becomes a way of being harassed.
        val repeated = listOf(
            Fixtures.rating(1, 900, 1),
            Fixtures.rating(1, 900, 1),
            Fixtures.rating(1, 900, 1),
            Fixtures.rating(1, 900, 1),
        )
        val once = listOf(Fixtures.rating(1, 900, 1))

        assertEquals(1, Reputation.summarise(repeated).ratingCount)
        assertEquals(
            Reputation.summarise(once).bayesianAverage,
            Reputation.summarise(repeated).bayesianAverage,
        )
    }

    @Test
    fun `a correction replaces the earlier rating rather than joining it`() {
        // A relative who taps one star and then fixes their mistake must not
        // end up with both on file. Newest wins, which is why the
        // deduplication is a map and not a distinct().
        val corrected = listOf(
            Fixtures.rating(1, 900, 1),
            Fixtures.rating(1, 900, 4),
        )

        val summary = Reputation.summarise(corrected)
        assertEquals(1, summary.ratingCount)
        assertEquals(4.0, summary.bayesianAverage)
    }

    @Test
    fun `two different people may rate the same booking`() {
        // A seeker and a provider, or two relatives who both sat in on it.
        // The key is the pair, not the booking, so this is not a duplicate.
        val both = listOf(Fixtures.rating(1, 900, 1), Fixtures.rating(1, 901, 5))

        assertEquals(2, Reputation.summarise(both).ratingCount)
    }

    // ---------------------------------------------------------- provisional --

    @Test
    fun `fewer than three ratings is provisional, however good they are`() {
        // Two five-star reviews are not evidence of anything, and a card that
        // printed "4.7" on the strength of them would be making a claim the
        // data cannot support.
        val summary = Reputation.summarise(
            listOf(Fixtures.rating(1, 900, 5), Fixtures.rating(2, 901, 5)),
        )

        assertTrue(summary.isProvisional)
        assertEquals(TrustBand.New, summary.band)
        assertEquals(2, Reputation.MIN_CORROBORATION - 1)
    }

    @Test
    fun `three good reviews earn the trusted band`() {
        val summary = Reputation.summarise(
            (1..3).map { Fixtures.rating(it, 900 + it, 5) },
        )

        assertTrue(!summary.isProvisional)
        assertEquals(TrustBand.Trusted, summary.band)
    }

    @Test
    fun `three poor reviews stay building rather than becoming unrated`() {
        val summary = Reputation.summarise(
            (1..3).map { Fixtures.rating(it, 900 + it, 1) },
        )

        assertEquals(TrustBand.Building, summary.band)
        assertFalse(summary.isProvisional)
    }

    @Test
    fun `confidence rises with the number of ratings and never reaches one`() {
        val two = Reputation.summarise((1..2).map { Fixtures.rating(it, 900, 4) })
        val ten = Reputation.summarise((1..10).map { Fixtures.rating(it, 900, 4) })

        assertEquals(0.29, two.confidence)
        assertEquals(0.67, ten.confidence)
        assertTrue(ten.confidence < 1.0)
    }

    @Test
    fun `a completed booking count of zero is kept rather than dropped`() {
        // Five reviews and no completed bookings is odd, and the screen
        // decides what to do with that. Losing the count here would make the
        // oddity invisible.
        val summary = Reputation.summarise(
            (1..5).map { Fixtures.rating(it, 900, 4) },
            completedBookings = 0,
        )

        assertEquals(0, summary.completedBookings)
        assertEquals(5, summary.ratingCount)
    }

    // --------------------------------------------------------- its effect --

    @Test
    fun `a single bad review moves a match score by about a point out of a hundred`() {
        // The reason the prior is worth having, in the units the user sees.
        // Reputation is 0.10 of section 6.8's formula, and 4.0 versus 3.5 out
        // of five is 0.8 versus 0.7 of that 0.10 — one point on a hundred.
        val listing = Fixtures.listing(tags = listOf("maths"), availability = listOf(Fixtures.window(3, 6, 12)))
        val seeker = Fixtures.profile(tags = listOf("maths"), freeHours = listOf(Fixtures.window(3, 7, 11)))

        val before = SevaMatcher.score(
            listing, seeker, Fixtures.profile(trust = Reputation.summarise(emptyList())),
        )
        val after = SevaMatcher.score(
            listing, seeker, Fixtures.profile(trust = Reputation.summarise(listOf(Fixtures.rating(1, 900, 1)))),
        )

        assertTrue(after.score <= before.score)
        assertTrue(before.score - after.score <= 1, "one bad review moved the score by ${before.score - after.score}")
    }

    // ------------------------------------------------------------- the band --

    @Test
    fun `an unknown band code falls back rather than throwing`() {
        // The house rule for every enum in the app: a code from a server that
        // is newer than this build is data, not a crash.
        assertEquals(TrustBand.Unrated, TrustBand.fromCode("a_band_from_the_future"))
        assertEquals(TrustBand.Unrated, TrustBand.fromCode(null))
        assertEquals(TrustBand.Trusted, TrustBand.fromCode("trusted"))
    }
}
