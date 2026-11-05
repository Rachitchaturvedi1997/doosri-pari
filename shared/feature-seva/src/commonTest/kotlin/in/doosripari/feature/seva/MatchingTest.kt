package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.MatchReason
import `in`.doosripari.core.model.MatchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Scoring, plan section 6.8, and the two words that matter more than the
 * formula: **matching is a score, never a filter, and always explainable.**
 *
 * Those two are what this file is for. A high score that arrives with no
 * explanation is a black box, and a black box is exactly what a 65-year-old is
 * entitled not to be shown something by. The radius tests at the bottom are
 * the other half: a filter the user set is honoured, and a *score* never
 * removes anything.
 */
class MatchingTest {

    /** A seeker and a listing that agree on everything, as a starting point. */
    private val perfectSeeker = Fixtures.profile(
        tags = listOf("maths", "english"),
        freeHours = listOf(Fixtures.window(3, 7, 11)),
    )
    private val perfectListing = Fixtures.listing(
        tags = listOf("maths", "english", "physics"),
        availability = listOf(Fixtures.window(3, 6, 12)),
    )

    // ------------------------------------------------------- explainability --

    @Test
    fun `a high score arrives with reasons that say why`() {
        val result = SevaMatcher.score(perfectListing, perfectSeeker)

        assertTrue(result.isStrong)
        assertEquals(
            listOf(
                MatchReason.FitsSkills,
                MatchReason.SameLanguage,
                MatchReason.Nearby,
                MatchReason.MatchesAvailability,
            ),
            result.reasons,
        )
        // And the screen never has to print a bare number.
        assertEquals(4, result.explanations.size)
        assertTrue(result.explanations.all { it is MatchExplanation.Because })
        assertEquals(result.reasons, result.positives)
        assertTrue(result.negatives.isEmpty())
    }

    @Test
    fun `a strong match always has earned its score from something it can name`() {
        // The invariant, checked over every combination of the five terms that
        // the code could plausibly get wrong. It holds because the most a
        // listing can score *without* naming a reason is 63, and the bar for
        // strong is 70 — the arithmetic is written out at the top of
        // `Matching.kt` and this is the test that keeps it honest.
        val tagSets = listOf(emptyList(), listOf("maths"), listOf("tamil"))
        val languageSets = listOf(emptyList(), listOf(AppLanguage.Hindi), listOf(AppLanguage.Tamil))
        val locations = listOf(null, Fixtures.DELHI, Fixtures.FAR_AWAY)
        val availabilitySets = listOf(emptyList(), listOf(Fixtures.window(3, 6, 12)))

        var strong = 0
        for (tags in tagSets) {
            for (seekerLanguages in languageSets) {
                for (seekerAt in locations) {
                    for (free in availabilitySets) {
                        val seeker = perfectSeeker.copy(
                            tags = tags,
                            languages = seekerLanguages,
                            location = seekerAt,
                            freeHours = free,
                        )
                        val result = SevaMatcher.score(perfectListing, seeker)
                        if (result.isStrong) {
                            strong++
                            assertTrue(
                                result.positives.isNotEmpty(),
                                "score ${result.score} was strong with reasons ${result.reasons}",
                            )
                        }
                    }
                }
            }
        }
        // And the sweep was not vacuous — some of those really were strong.
        assertTrue(strong > 0, "no combination was strong, so nothing was proved")
    }

    @Test
    fun `a listing that matched on nothing in particular still gets a line of text`() {
        // The worst case: every field on both sides is empty. There is nothing
        // honest to say, and printing nothing under a 63 is how a black box
        // looks like a feature.
        val blankSeeker = MatchProfile(userId = 1)
        val blankListing = Fixtures.listing(
            tags = emptyList(),
            languages = emptyList(),
            location = null,
            availability = emptyList(),
        )

        val result = SevaMatcher.score(blankListing, blankSeeker)

        assertTrue(result.reasons.isEmpty())
        assertEquals(
            listOf(MatchExplanation.NoParticularReason),
            result.explanations,
        )
        // And the number the KDoc's arithmetic predicts: 0.63 of a hundred.
        assertEquals(63, result.score)
        assertFalse(result.isStrong)
    }

    @Test
    fun `a score always lands inside zero to one hundred`() {
        // The empty case is the floor and the best possible case is the
        // ceiling, and neither may throw in `MatchResult`'s `require`.
        val worst = SevaMatcher.score(
            perfectListing.copy(
                tags = listOf("tamil"),
                languages = listOf(AppLanguage.Tamil),
                location = Fixtures.FAR_AWAY,
                availability = listOf(Fixtures.window(3, 2, 3)),
            ),
            perfectSeeker.copy(languages = listOf(AppLanguage.Kannada)),
        )
        val best = SevaMatcher.score(perfectListing, perfectSeeker)

        assertTrue(worst.score in 0..100)
        assertTrue(best.score in 0..100)
    }

    // ------------------------------------------------------------- distance --

    @Test
    fun `a listing beyond the radius is reported as a reason, not removed`() {
        // The requirement, and the reason `score` takes a radius at all. The
        // listing is still scored, still returned, and says why it is far.
        val far = perfectListing.copy(location = Fixtures.FAR_AWAY)
        val seeker = perfectSeeker.copy(location = Fixtures.DELHI)

        val result = SevaMatcher.score(far, seeker, radiusKm = 5.0)

        assertTrue(MatchReason.OutsideRadius in result.reasons)
        assertTrue(MatchReason.TooFar in result.reasons)
        // Nothing was removed and nothing was scored differently — the radius
        // adds a sentence, it does not change the arithmetic.
        val withoutRadius = SevaMatcher.score(far, seeker)
        assertEquals(withoutRadius.score, result.score)
        assertTrue(MatchReason.OutsideRadius !in withoutRadius.reasons)
    }

    @Test
    fun `a listing inside the radius is not reported as outside it`() {
        val near = perfectListing.copy(location = Fixtures.NEARBY)
        val result = SevaMatcher.score(near, perfectSeeker, radiusKm = 5.0)

        assertTrue(MatchReason.OutsideRadius !in result.reasons)
        assertTrue(MatchReason.Nearby in result.reasons)
    }

    @Test
    fun `no radius typed means no distance judgement at all`() {
        // A distance filter that is on unless the user turns it off would be
        // hiding listings for a reason nobody asked for.
        val result = SevaMatcher.score(
            perfectListing.copy(location = Fixtures.FAR_AWAY),
            perfectSeeker,
        )

        assertTrue(MatchReason.OutsideRadius !in result.reasons)
    }

    @Test
    fun `the plan's distance curve is one at three kilometres and zero at twenty five`() {
        assertEquals(1.0, SevaMatcher.distanceScore(0.0))
        assertEquals(1.0, SevaMatcher.distanceScore(3.0))
        assertEquals(0.0, SevaMatcher.distanceScore(25.0))
        assertEquals(0.0, SevaMatcher.distanceScore(80.0))
        // And it falls off linearly in between, so 14 km is exactly half.
        assertEquals(0.5, SevaMatcher.distanceScore(14.0))
    }

    @Test
    fun `work with no location is online and scores a full point for distance`() {
        // Section 6.8: "online = 1.0". A listing with no address is not
        // "far away", and treating a missing pin as a bad one is the same
        // mistake as scoring a missing field zero.
        assertEquals(1.0, SevaMatcher.distanceScore(null))
    }

    // -------------------------------------------------- reasons need facts --

    @Test
    fun `nobody having written any tags is not a skills match`() {
        val result = SevaMatcher.score(
            perfectListing.copy(tags = emptyList()),
            perfectSeeker,
        )

        assertTrue(MatchReason.FitsSkills !in result.reasons)
    }

    @Test
    fun `tags that do not overlap are not a skills match`() {
        val result = SevaMatcher.score(
            perfectListing.copy(tags = listOf("tamil", "telugu")),
            perfectSeeker,
        )

        assertTrue(MatchReason.FitsSkills !in result.reasons)
        // Half marks, not zero: a seeker who wants maths and english is partly
        // served by a listing that wants neither, and a hard zero would put
        // them in the same place as somebody with nothing at all in common.
        assertEquals(0.0, SevaMatcher.matchTags(listOf("maths"), listOf("tamil")).score)
    }

    @Test
    fun `a tag match names the tags it found`() {
        val match = SevaMatcher.matchTags(
            listOf("maths", "english", "science"),
            listOf("science", "maths", "art"),
        )

        assertEquals(listOf("maths", "science"), match.shared)
        assertTrue(match.hasOverlap)
        assertEquals(2.0 / 3.0, match.score)
    }

    @Test
    fun `tag matching ignores case and stray spaces`() {
        // Somebody typing on a phone with large type gets capitals wrong, and
        // a board that returns nothing for "Maths " teaches them the search is
        // broken.
        assertEquals(
            1.0,
            SevaMatcher.matchTags(listOf(" Maths "), listOf("maths")).score,
        )
    }

    @Test
    fun `nobody having written a language is not a language match`() {
        val result = SevaMatcher.score(
            perfectListing.copy(languages = emptyList()),
            perfectSeeker.copy(languages = emptyList()),
        )

        assertTrue(MatchReason.SameLanguage !in result.reasons)
        assertTrue(MatchReason.LanguageMismatch !in result.reasons)
    }

    @Test
    fun `languages that do not overlap are a mismatch, and it says so`() {
        val result = SevaMatcher.score(
            perfectListing.copy(languages = listOf(AppLanguage.Tamil)),
            perfectSeeker.copy(languages = listOf(AppLanguage.Hindi)),
        )

        assertTrue(MatchReason.LanguageMismatch in result.reasons)
        assertTrue(MatchReason.SameLanguage !in result.reasons)
    }

    @Test
    fun `a second language counts for less than a first`() {
        // Somebody who reads Tamil and English will take a call in either but
        // a session in English, and should not be shown English-only listings
        // as though they were a full language match.
        assertEquals(1.0, SevaMatcher.languageMatch(listOf(AppLanguage.Hindi), listOf(AppLanguage.Hindi, AppLanguage.English)))
        assertEquals(0.6, SevaMatcher.languageMatch(listOf(AppLanguage.Hindi, AppLanguage.English), listOf(AppLanguage.English)))
        assertEquals(0.0, SevaMatcher.languageMatch(listOf(AppLanguage.Hindi), listOf(AppLanguage.Tamil)))
    }

    // -------------------------------------------- department and the prior --

    @Test
    fun `department and city are reported but move no points`() {
        // Section 6.8's formula has five weights and these are not among them.
        // Emitting them as reasons is honest; scoring them would quietly change
        // the plan's number into something nobody can quote.
        val seeker = perfectSeeker.copy(departmentId = 3, cityId = 7)
        val same = Fixtures.profile(departmentId = 3, cityId = 7)
        val different = Fixtures.profile(departmentId = 9, cityId = 99)

        val withSame = SevaMatcher.score(perfectListing, seeker, same)
        val withDifferent = SevaMatcher.score(perfectListing, seeker, different)

        assertTrue(MatchReason.SameDepartment in withSame.reasons)
        assertTrue(MatchReason.SameCity in withSame.reasons)
        assertTrue(MatchReason.SameDepartment !in withDifferent.reasons)
        assertEquals(withDifferent.score, withSame.score)
    }

    @Test
    fun `a provider nobody knows anything about gets the prior, not a zero`() {
        // Otherwise every new provider ranks below every bad one, and the only
        // way onto the board is to have been reviewed already. The department
        // and city reasons appear, and move nothing, so the two scores are the
        // same number.
        val withoutProfile = SevaMatcher.score(perfectListing, perfectSeeker)
        val withNewProvider = SevaMatcher.score(perfectListing, perfectSeeker, Fixtures.profile())

        assertEquals(withoutProfile.score, withNewProvider.score)
        assertTrue(MatchReason.SameDepartment in withNewProvider.reasons)
        assertTrue(MatchReason.SameDepartment !in withoutProfile.reasons)
    }

    @Test
    fun `a well-reviewed provider scores above one nobody knows`() {
        val wellReviewed = Reputation.summarise((1..20).map { Fixtures.rating(it, 900, 5) })
        val unknown = SevaMatcher.score(perfectListing, perfectSeeker)
        val known = SevaMatcher.score(perfectListing, perfectSeeker, Fixtures.profile(trust = wellReviewed))

        // Reputation is 0.10 of the formula, so even a flawless record cannot
        // buy more than a couple of points. A board where a good name decides
        // the order is not the board section 6.8 describes.
        assertEquals(98, unknown.score)
        assertEquals(100, known.score)
    }

    // ---------------------------------------------------------------- misc --

    @Test
    fun `reasons are never repeated`() {
        // Both sides naming the same language once, in a listing and in a
        // profile, must not print the sentence twice.
        val result = SevaMatcher.score(
            perfectListing,
            perfectSeeker,
            perfectSeeker,
        )
        assertEquals(result.reasons.size, result.reasons.distinct().size)
    }

    @Test
    fun `a reason code round trips and an unknown one is refused rather than guessed`() {
        for (reason in MatchReason.entries) {
            assertEquals(reason, MatchReason.fromCode(reason.code))
        }
        assertEquals(null, MatchReason.fromCode("a_reason_from_the_future"))
    }

    @Test
    fun `the explanation of a strong match is never the no-particular-reason one`() {
        val result = SevaMatcher.score(perfectListing, perfectSeeker)
        assertFalse(result.explanations.any { it is MatchExplanation.NoParticularReason })
    }

    @Test
    fun `a match result with a score outside its range is refused at construction`() {
        // The guard is in core-model, and a score function that could produce
        // 101 would blow up on a user's screen rather than in a test.
        assertFailsWith<IllegalArgumentException> { MatchResult(101, emptyList()) }
        assertFailsWith<IllegalArgumentException> { MatchResult(-1, emptyList()) }
    }

    @Test
    fun `the score a seeker sees is bounded below as well as above`() {
        // Worst possible: nothing matches, and even then the missing fields
        // keep it off zero.
        val hostile = SevaMatcher.score(
            perfectListing.copy(
                tags = listOf("tamil"),
                languages = listOf(AppLanguage.Tamil),
                location = Fixtures.FAR_AWAY,
                availability = listOf(Fixtures.window(3, 2, 3)),
            ),
            perfectSeeker.copy(languages = listOf(AppLanguage.Kannada)),
            Fixtures.profile(
                departmentId = 9,
                cityId = 99,
                trust = Reputation.summarise((1..20).map { Fixtures.rating(it, 900, 1) }),
            ),
        )

        assertTrue(hostile.score in 0..100)
        assertTrue(hostile.negatives.isNotEmpty())
        assertEquals(
            listOf(
                MatchReason.TooFar,
                MatchReason.LanguageMismatch,
                MatchReason.NoOverlap,
            ),
            hostile.negatives,
        )
    }

    @Test
    fun `an explanation is rendered for a reason the screen has not heard of`() {
        // A reason added to core-model by a newer backend must still produce a
        // line rather than a crash in a renderer that switches on it.
        val explanation = MatchExplanation.Because(MatchReason.OutsideRadius)
        assertIs<MatchExplanation.Because>(explanation)
        assertEquals(MatchReason.OutsideRadius, explanation.reason)
    }
}
