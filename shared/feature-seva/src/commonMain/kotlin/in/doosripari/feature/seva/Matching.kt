package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.AvailabilityWindow
import `in`.doosripari.core.model.GeoPoint
import `in`.doosripari.core.model.MatchReason
import `in`.doosripari.core.model.MatchResult
import `in`.doosripari.core.model.SevaListing
import `in`.doosripari.core.model.distanceKm
import kotlin.math.roundToInt

/**
 * Scoring a listing against a person. Plan section 6.8.
 *
 * The plan writes the formula out in full:
 *
 * ```
 * score = 0.35 × skill_tag_overlap + 0.20 × language_match
 *       + 0.20 × distance_score (1.0 within 3 km, 0 beyond 25 km, online = 1.0)
 *       + 0.15 × availability_overlap + 0.10 × rating (Bayesian, prior 4.0 from 5)
 * ```
 *
 * and the two words after it matter more than the formula: **matching is a
 * score, never a filter, and always explainable.** Those are why this file
 * returns a [MatchResult] rather than a boolean, why the reasons come back
 * alongside the number, and why the radius a user typed is honoured by the
 * *board* and not by this function.
 *
 * ## What is deliberately not scored
 *
 * `MatchReason.SameDepartment` and `MatchReason.SameCity` exist in `core-model`,
 * and neither appears in the formula. They are emitted as **reasons only** and
 * move nothing.
 *
 * That is a decision, and the reason for it is that the plan chose five
 * weights and these are not among them. Adding a sixth and a seventh because
 * the enum has room for them is how a score quietly becomes a black box: the
 * plan's number would no longer be the plan's number, and nobody reading
 * section 6.8 could say what the board is doing. A pensioner being shown
 * something because of a scoring function deserves the sentence that goes with
 * it, and the sentence is only honest if the weights are the ones that were
 * written down.
 *
 * ## The one guarantee
 *
 * [MatchResult.isStrong] implies at least one positive reason. It holds
 * structurally rather than by assertion, and the arithmetic that makes it hold
 * is worth having in writing: a listing can score without naming a positive
 * reason only when every field is *missing*, and the most that arrangement can
 * reach is
 *
 * ```
 * 0.35 × 0.5 (no tags) + 0.20 × 0.5 (no language) + 0.20 × 1.0 (online)
 *   + 0.15 × 0.5 (no availability) + 0.10 × 0.8 (the prior) = 0.63 → 63
 * ```
 *
 * which is below `MatchResult.isStrong`'s bar of 70. A strong match has
 * therefore always earned its score from something nameable. A test checks it,
 * and the arithmetic is the reason the weights above are `const`: change one
 * and the guarantee has to be re-derived.
 */
data class MatchProfile(
    val userId: Int,
    val departmentId: Int? = null,
    val cityId: Int? = null,
    val languages: List<AppLanguage> = emptyList(),
    val location: GeoPoint? = null,
    /**
     * When this person is actually free.
     *
     * Deliberately a list of [AvailabilityWindow]s and not working hours — see
     * `Availability.kt` for why that distinction is the whole feature.
     */
    val freeHours: List<AvailabilityWindow> = emptyList(),
    /** Tags this person is interested in. Matched against [SevaListing.tags]. */
    val tags: List<String> = emptyList(),
    /** The provider's standing. Null for a seeker, and for a provider we know nothing about. */
    val trust: TrustSummary? = null,
)

/**
 * How a seeker's tags met a listing's, and whether they actually met.
 *
 * Two answers rather than one, because "nobody wrote any tags down" and "we
 * found one tag in common" both score above zero and only one of them is worth
 * telling a user about.
 */
data class TagMatch(
    /** 0.0 to 1.0. [SevaMatcher.NEUTRAL] when either side has no tags. */
    val score: Double,
    /** The tags both sides named, in the seeker's spelling. */
    val shared: List<String>,
) {
    val hasOverlap: Boolean get() = shared.isNotEmpty()
}

/**
 * The scorer.
 *
 * Pure, and an object rather than a class because there is no configuration
 * worth having: the weights are the plan's and are `const`, so a board that
 * ranked by a different formula would be a different class and would have to
 * be written on purpose.
 */
object SevaMatcher {

    // The five weights of section 6.8. They sum to 1.0.
    const val WEIGHT_TAGS: Double = 0.35
    const val WEIGHT_LANGUAGE: Double = 0.20
    const val WEIGHT_DISTANCE: Double = 0.20
    const val WEIGHT_AVAILABILITY: Double = 0.15
    const val WEIGHT_RATING: Double = 0.10

    /** Section 6.8: "1.0 within 3 km". */
    const val NEAR_KM: Double = 3.0

    /** Section 6.8: "0 beyond 25 km". Between the two it falls off linearly. */
    const val FAR_KM: Double = 25.0

    /** Section 6.8: "online = 1.0". */
    const val ONLINE_DISTANCE_SCORE: Double = 1.0

    /**
     * Score one listing for one seeker.
     *
     * [provider] carries the things [SevaListing] does not: the poster's
     * department and city, and their [TrustSummary]. `core-model` has no
     * `departmentId` on a listing, so without it the department and city
     * reasons cannot be produced at all — see the note at the top of the file
     * on why that costs no points.
     *
     * [radiusKm] is the seeker's own filter. It does **not** remove anything
     * and does not change the score: it adds [MatchReason.OutsideRadius] so
     * the board can say "four more are further than you asked for" instead of
     * showing a shorter list with no explanation.
     */
    fun score(
        listing: SevaListing,
        seeker: MatchProfile,
        provider: MatchProfile? = null,
        radiusKm: Double? = null,
    ): MatchResult {
        val reasons = mutableListOf<MatchReason>()
        var total = 0.0

        // 1. Skill and tag overlap, the heaviest term at 0.35.
        val tags = matchTags(seeker.tags, listing.tags)
        total += WEIGHT_TAGS * tags.score
        if (tags.hasOverlap) reasons += MatchReason.FitsSkills

        // 2. Language. The seeker's first language counts for more than a
        //    second one: a person who reads Tamil and English will take a call
        //    in either but a session in English, and should not be shown
        //    English-only listings as though they were a language match.
        val languages = (listing.languages + (provider?.languages ?: emptyList())).distinct()
        val bothSided = seeker.languages.isNotEmpty() && languages.isNotEmpty()
        val languageScore = languageMatch(seeker.languages, languages)
        total += WEIGHT_LANGUAGE * languageScore
        // A reason is only claimed when both sides actually said something.
        // "Nobody wrote a language down" is not a language match, and printing
        // it as one would put a sentence under the score that is simply untrue.
        if (bothSided) {
            if (languageScore == 0.0) reasons += MatchReason.LanguageMismatch
            else reasons += MatchReason.SameLanguage
        }

        // 3. Distance. Both ends' locations, then the radius the user typed.
        val seekerAt = seeker.location
        val listedAt = listing.location ?: provider?.location
        val km = if (seekerAt != null && listedAt != null) distanceKm(seekerAt, listedAt) else null

        val distanceScore = distanceScore(km)
        total += WEIGHT_DISTANCE * distanceScore
        when {
            km == null -> Unit // Online on one side. Section 6.8 scores that 1.0.
            km <= NEAR_KM -> reasons += MatchReason.Nearby
            km >= FAR_KM -> reasons += MatchReason.TooFar
        }
        // Reported, never enforced. See the KDoc on [score].
        if (km != null && radiusKm != null && km > radiusKm) {
            reasons += MatchReason.OutsideRadius
        }

        // 4. Availability, against free hours rather than working hours.
        val availabilityScore = if (seeker.freeHours.isEmpty() || listing.availability.isEmpty()) {
            // Neither side has said. Half marks rather than zero, because
            // scoring somebody zero for a field nobody filled in is a way of
            // making "leave your availability blank" look better, not worse.
            NEUTRAL
        } else {
            AvailabilityMatcher.coverage(listing.availability, seeker.freeHours)
        }
        total += WEIGHT_AVAILABILITY * availabilityScore
        when {
            seeker.freeHours.isNotEmpty() && listing.availability.isNotEmpty() -> {
                if (availabilityScore > 0.0) reasons += MatchReason.MatchesAvailability
                else reasons += MatchReason.NoOverlap
            }
        }

        // 5. Rating, Bayesian. A provider we know nothing about gets the
        //    prior, so a new provider is not ranked below a bad one.
        val ratingScore = (provider?.trust?.bayesianAverage ?: Reputation.PRIOR_MEAN) / 5.0
        total += WEIGHT_RATING * ratingScore

        // Reported, not scored. The weights above are section 6.8's five.
        if (provider != null) {
            if (provider.departmentId != null && provider.departmentId == seeker.departmentId) {
                reasons += MatchReason.SameDepartment
            }
            if (provider.cityId != null && provider.cityId == seeker.cityId) {
                reasons += MatchReason.SameCity
            }
        }

        return MatchResult(
            score = (total * 100.0).roundToInt().coerceIn(0, 100),
            reasons = reasons.distinct(),
        )
    }

    /**
     * What a missing field is worth: half.
     *
     * Applied only to fields nobody has filled in. A field that *is* filled in
     * and does not match scores zero, because that is a real answer.
     */
    const val NEUTRAL: Double = 0.5

    /**
     * Section 6.8's distance curve.
     *
     * Linear between [NEAR_KM] and [FAR_KM] so that a 4 km listing and a 24 km
     * one are distinguishable — the section's "1.0 within 3, 0 beyond 25"
     * says where the ends are, and the shape in between is a choice, so a
     * linear one is the least surprising thing to put there.
     */
    fun distanceScore(km: Double?): Double = when {
        km == null -> ONLINE_DISTANCE_SCORE
        km <= NEAR_KM -> 1.0
        km >= FAR_KM -> 0.0
        else -> (FAR_KM - km) / (FAR_KM - NEAR_KM)
    }

    /**
     * Share of the seeker's tags the listing covers, and which ones.
     *
     * Measured against the seeker's tags, not the listing's: a listing with
     * forty tags should not out-score one with four just by being longer.
     *
     * The two answers are returned together because they are not the same
     * thing. A seeker with no tags and a listing with no tags scores
     * [NEUTRAL] — nobody has said anything — and the screen must not put
     * "matches your skills" under it, which is what a bare score would let it
     * do.
     */
    fun matchTags(wanted: List<String>, offered: List<String>): TagMatch {
        if (wanted.isEmpty() || offered.isEmpty()) return TagMatch(NEUTRAL, emptyList())
        val offeredLower = offered.map { it.trim().lowercase() }.toSet()
        val shared = wanted.filter { it.trim().lowercase() in offeredLower }
        return TagMatch(shared.size.toDouble() / wanted.size, shared)
    }

    /** Just the score, for callers that have no reason to name the tags. */
    fun tagOverlap(wanted: List<String>, offered: List<String>): Double =
        matchTags(wanted, offered).score

    /**
     * Language compatibility, 0.0 to 1.0.
     *
     * The seeker's primary language scores 1.0, any other shared language 0.6,
     * and two non-empty lists with nothing in common score 0.0. Two *empty*
     * lists score [NEUTRAL], because "nobody has said" is not "these people
     * have nothing in common" and treating it as the second quietly
     * penalises every listing whose poster skipped a field.
     */
    fun languageMatch(wanted: List<AppLanguage>, offered: List<AppLanguage>): Double = when {
        wanted.isEmpty() || offered.isEmpty() -> NEUTRAL
        wanted.first() in offered -> 1.0
        wanted.any { it in offered } -> 0.6
        else -> 0.0
    }
}

/**
 * Why a score looks the way it does, in a form a screen can render.
 *
 * The reason for the extra type: [MatchResult.reasons] is empty for a listing
 * that matched on nothing in particular, and an empty explanation under a
 * number is how a black box looks like a feature. [NoParticularReason] is the
 * honest version of that — the listing is here, the score is low, and nobody
 * should pretend otherwise.
 */
sealed interface MatchExplanation {
    data class Because(val reason: MatchReason) : MatchExplanation

    /** The listing is shown and scores low, and there is nothing to name. */
    data object NoParticularReason : MatchExplanation
}

/** Always at least one line. Never a bare number. */
val MatchResult.explanations: List<MatchExplanation>
    get() = if (reasons.isEmpty()) {
        listOf(MatchExplanation.NoParticularReason)
    } else {
        reasons.map(MatchExplanation::Because)
    }

/** The reasons that pushed the score up, for a "why this" line. */
val MatchResult.positives: List<MatchReason> get() = reasons.filter { it.positive }

/** The reasons that pushed it down, for "what is not quite right". */
val MatchResult.negatives: List<MatchReason> get() = reasons.filterNot { it.positive }
