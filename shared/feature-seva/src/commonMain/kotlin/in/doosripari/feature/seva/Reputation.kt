package `in`.doosripari.feature.seva

import `in`.doosripari.core.model.Rating
import kotlin.math.round

/**
 * Ratings and the trust summary that goes with them. Plan section 6.8.
 *
 * Plan section 6.8 puts a Bayesian average in the matching formula — "0.10 ×
 * rating (Bayesian, prior 4.0 from 5)" — and does not say anything else about
 * reputation. This file is that average, and it exists as its own file because
 * the arithmetic has one job that is not obvious: **stopping one angry relative
 * from deciding whether somebody is a good provider.**
 *
 * Four things do that work together, and each is commented where it is used.
 *
 *  1. **A prior, so zero ratings is not zero trust and one rating is not a
 *     verdict.** The prior is 4.0 out of 5, straight from the plan, weighted
 *     as though the provider already had five 4-star reviews. A single 1-star
 *     moves the number from 4.0 to 3.5 — visible, and not decisive.
 *  2. **One rating per booking.** A relative can rate a session once. This is
 *     the rule that stops a determined person from rating the same evening
 *     nine times, which no amount of averaging will fix.
 *  3. **A provisional flag, so the UI can say so.** Below three ratings the
 *     number is not yet evidence, and the screen says "based on 1 review"
 *     rather than printing a bare 3.5 that a reader will take as a verdict.
 *  4. **Recency is not modelled, on purpose.** A provider's five-year-old
 *     record should not be averaged together with last month's, and doing it
 *     properly needs a decay the plan did not choose. Inventing one here would
 *     make the number mean something different from what the matching formula
 *     assumes, which is worse than a slightly stale average.
 */
data class TrustSummary(
    /** How many distinct bookings have been rated. */
    val ratingCount: Int,

    /**
     * The Bayesian average, 0.0 to 5.0.
     *
     * This is the number the matching formula uses. It is never zero for a
     * provider with no ratings — it is the prior — so a new provider is not
     * scored as though they were a bad one.
     */
    val bayesianAverage: Double,

    /**
     * The plain mean of the ratings actually given, or null when there are
     * none.
     *
     * Null rather than 0.0 because a zero-star provider and a provider nobody
     * has rated yet are different things, and only one of them deserves a
     * warning icon.
     */
    val rawAverage: Double?,

    /**
     * How far along the number is, 0.0 to 1.0.
     *
     * `n / (n + priorWeight)`. The UI uses it to say how much to trust the
     * figure rather than printing the figure alone.
     */
    val confidence: Double,

    /** Bookings that actually happened. A high rating count on none of them is odd. */
    val completedBookings: Int = 0,

    /** True below [Reputation.MIN_CORROBORATION] ratings. */
    val isProvisional: Boolean = true,
) {
    /**
     * A band, for a badge.
     *
     * Bands are cut at the *provisional* line first: somebody with two reviews
     * is never "trusted", however good they are, because two reviews is not
     * evidence of anything yet.
     */
    val band: TrustBand
        get() = when {
            ratingCount == 0 -> TrustBand.Unrated
            isProvisional -> TrustBand.New
            bayesianAverage >= TrustBand.THREE_STARS -> TrustBand.Trusted
            else -> TrustBand.Building
        }
}

/** The badge a provider earns. Ordered worst to best. */
enum class TrustBand(val code: String) {
    Unrated("unrated"),
    /** Under [Reputation.MIN_CORROBORATION] ratings. */
    New("new"),
    Building("building"),
    Trusted("trusted");

    companion object {
        /** The bar for [Trusted]. Below this, a provider is still building. */
        const val THREE_STARS: Double = 3.0

        fun fromCode(code: String?): TrustBand =
            entries.firstOrNull { it.code == code } ?: Unrated
    }
}

/**
 * Turning a pile of [Rating]s into a [TrustSummary].
 */
object Reputation {

    /**
     * Plan section 6.8: "Bayesian, prior 4.0 from 5".
     *
     * The mean is the plan's. The *weight* is not given, and it is worth
     * saying why it is five: five 4-star reviews is a provider with a modest
     * but real history, which is what "prior 4.0" should mean. A weight of one
     * would let a single 1-star drag the average to 2.5 and a weight of twenty
     * would make the first ten reviews invisible. Five gives a single 1-star a
     * visible but non-decisive effect — 3.5 out of 5 — which is the behaviour
     * section 6.8's use of a Bayesian prior is there to produce.
     */
    const val PRIOR_MEAN: Double = 4.0
    const val PRIOR_WEIGHT: Int = 5

    /**
     * Below this many ratings the number is shown with its count and not as a
     * score.
     */
    const val MIN_CORROBORATION: Int = 3

    /**
     * The summary for a provider.
     *
     * [ratings] may be in any order, and duplicates are resolved by
     * `(bookingId, byUserId)` with the **last** one winning, on the assumption
     * the API returns newest first. That is the rule that stops a relative
     * rating the same evening repeatedly: the second rating for a booking is
     * an edit, not another opinion.
     */
    fun summarise(
        ratings: List<Rating>,
        completedBookings: Int = 0,
    ): TrustSummary {
        val counted = deduplicate(ratings)
        val n = counted.size
        val total = counted.sumOf { it.stars }

        val bayesian = (PRIOR_MEAN * PRIOR_WEIGHT + total) / (PRIOR_WEIGHT + n)
        val raw = if (n == 0) null else total.toDouble() / n

        return TrustSummary(
            ratingCount = n,
            bayesianAverage = round2(bayesian),
            rawAverage = raw?.let(::round2),
            confidence = round2(n.toDouble() / (n + PRIOR_WEIGHT)),
            completedBookings = completedBookings,
            isProvisional = n < MIN_CORROBORATION,
        )
    }

    /**
     * One rating per booking per person.
     *
     * Two people can each rate a booking — a seeker and a provider, or two
     * relatives who both sat in on it — so the key is the pair and not the
     * booking alone.
     */
    fun deduplicate(ratings: List<Rating>): List<Rating> {
        val byKey = LinkedHashMap<Pair<Int, Int>, Rating>()
        for (rating in ratings) {
            // A later entry overwrites an earlier one, which is what makes a
            // correction work and a spam campaign not.
            byKey[rating.bookingId to rating.byUserId] = rating
        }
        return byKey.values.toList()
    }

    /**
     * How far one star would move a provider's number.
     *
     * Exposed so a screen can say "one more review and this goes up" instead
     * of asking a person to take a number on faith. It also makes the
     * single-review problem checkable: with [PRIOR_WEIGHT] five, a 1-star
     * review moves a fresh provider by [ONE_STAR_EFFECT_NEW].
     */
    fun effectOfOneStar(summary: TrustSummary): Double {
        val n = summary.ratingCount
        val current = summary.bayesianAverage
        val after = (PRIOR_MEAN * PRIOR_WEIGHT + (n * current) + 1) / (PRIOR_WEIGHT + n + 1)
        return round2(kotlin.math.abs(after - current))
    }

    /** The move a single 1-star makes on a provider with no ratings at all. */
    val ONE_STAR_EFFECT_NEW: Double
        get() = round2(kotlin.math.abs((PRIOR_MEAN * PRIOR_WEIGHT + 1) / (PRIOR_WEIGHT + 1) - PRIOR_MEAN))

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
