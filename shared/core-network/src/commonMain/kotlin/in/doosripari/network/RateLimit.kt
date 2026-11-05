package `in`.doosripari.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Back-off after a 429. Plan section 11: "Every write is rate-limited per user
 * and IP."
 *
 * ## What the client can and cannot do about that
 *
 * The per-IP half is the server's job, and it has to be. A phone cannot see its
 * own public address without asking somebody — and putting a third-party
 * "what is my IP" call on the critical path of a check-in, before the check-in,
 * would mean a check-in that cannot be sent whenever that lookup is down. So
 * the client does not try, and this file makes no pretence otherwise: it keys
 * on [ApiClient.BUCKET_USER] and says plainly that the server holds the rest.
 *
 * The per-user half is the one the client can honour honestly, because the
 * server tells it: `Retry-After`, in seconds. When a 429 arrives this gate
 * records a cooldown for that bucket, and no further request goes out until it
 * ends. Without that, a user tapping "I'm fine" twice because the first tap
 * felt slow — which is exactly what a 68-year-old does, and exactly what
 * plan section 12's `checkin_escalate` job would then escalate on — earns a
 * self-inflicted ban on the account whose alerts matter most.
 *
 * ## Design: no sleeping inside the gate
 *
 * [observe] and [cooldownRemainingMs] are pure functions of a clock this class
 * does not own, and [awaitPermit] takes the sleeper as a parameter. That is
 * what makes the behaviour testable with virtual time — no `Thread.sleep`, no
 * real minutes — and it is why [ApiClient] is the only place that blocks.
 *
 * ## Buckets
 *
 * Keyed by string, not by user id. The phone has one user; a bucket keyed by
 * id would outlive a sign-out and hold the *next* user's first request hostage
 * to the previous one's 429. See [ApiClient.BUCKET_USER].
 */
class RateLimitGate(
    /** Ceiling on the back-off this gate will impose on itself. */
    private val maxCooldown: Duration = MAX_COOLDOWN,
    /** Back-off when the server sent no `Retry-After`. */
    private val fallbackCooldown: Duration = FALLBACK_COOLDOWN,
    private val backoffFactor: Double = BACKOFF_FACTOR,
    /**
     * How many consecutive 429s to accept before ignoring the server's
     * `Retry-After`. A server answering 1 s for ever is telling the client it
     * does not want the write; the app should stop trying rather than become a
     * background load generator. See [observe].
     */
    private val maxObserved429: Int = MAX_OBSERVED_429,
) {

    /** Bucket name to the instant its cooldown ends, in [nowMs] units. */
    private val cooldowns = mutableMapOf<String, Long>()

    /** Bucket name to how many 429s in a row that bucket has produced. */
    private val strikes = mutableMapOf<String, Int>()

    /** When this bucket may next send, or null if it is free now. */
    fun nextAttemptAt(bucket: String, nowMs: Long): Long? =
        cooldowns[bucket]?.takeIf { it > nowMs }

    /** Milliseconds left on this bucket's cooldown; 0 when it is free. */
    fun cooldownRemainingMs(bucket: String, nowMs: Long): Long =
        (nextAttemptAt(bucket, nowMs) ?: nowMs) - nowMs

    /** Whether this bucket is being held back right now. */
    fun isCoolingDown(bucket: String, nowMs: Long): Boolean =
        cooldownRemainingMs(bucket, nowMs) > 0

    /**
     * Wait out the cooldown, then return.
     *
     * Returns at once for a free bucket; returns after the cooldown for a
     * bucket under one. The loop re-reads the clock each pass, because waiting
     * is exactly how time moves, and because a second 429 may have arrived from
     * somewhere else in the app while this coroutine slept.
     */
    suspend fun awaitPermit(
        bucket: String,
        nowMs: () -> Long,
        sleeper: suspend (Duration) -> Unit,
    ) {
        while (true) {
            val remaining = cooldownRemainingMs(bucket, nowMs())
            if (remaining <= 0) return
            sleeper(remaining.milliseconds)
        }
    }

    /**
     * Record what the server just said. Call on every response.
     *
     * A 429 extends the cooldown for [bucket]; anything else clears it, because
     * a success proves the limit is no longer in force — and a stale cooldown
     * left behind after one working call would make a healthy app feel broken
     * for no reason at all.
     *
     * Back-off grows by [backoffFactor] per consecutive 429 so a pathological
     * client settles rather than oscillating, and is clamped to [maxCooldown]
     * so a broken `Retry-After: 86400` cannot park the write path until
     * tomorrow. Two minutes is long enough to be a real pause and short enough
     * that a check-in is still worth sending inside its window.
     */
    fun observe(bucket: String, response: WireResponse, nowMs: Long) {
        if (response.status != HTTP_TOO_MANY_REQUESTS) {
            cooldowns.remove(bucket)
            strikes.remove(bucket)
            return
        }

        val count = (strikes[bucket] ?: 0) + 1
        strikes[bucket] = count

        val requested = retryAfterSeconds(response.headers)
        val backoff = scale(fallbackCooldown, count)
        val wait = when {
            // Past the ceiling on 429s, use our own growing back-off rather than
            // the server's number. A server is entitled to ask for a pause; it
            // is not entitled to define one long enough that a pensioner cannot
            // say they are fine until tomorrow morning.
            count > maxObserved429 -> backoff
            requested != null -> minOf(requested.seconds, maxCooldown)
            else -> backoff
        }
        cooldowns[bucket] = nowMs + wait.inWholeMilliseconds
    }

    /**
     * Forget everything, or one bucket.
     *
     * Used on sign-out and on an explicit "try again now" action, where honouring
     * a cooldown recorded in a previous session would simply look broken.
     */
    fun reset(bucket: String? = null) {
        if (bucket == null) {
            cooldowns.clear()
            strikes.clear()
        } else {
            cooldowns.remove(bucket)
            strikes.remove(bucket)
        }
    }

    private fun scale(base: Duration, count: Int): Duration {
        var millis = base.toDouble()
        repeat((count - 1).coerceAtLeast(0)) { millis *= backoffFactor }
        return millis.toLong()
            .coerceIn(0L, maxCooldown.inWholeMilliseconds)
            .milliseconds
    }

    companion object {
        /**
         * Two minutes. Long enough to be a real pause on a plan that rate
         * limits writes; short enough that a check-in queued now is still worth
         * sending before the user's window closes.
         */
        val MAX_COOLDOWN: Duration = 2.minutes

        /** What we impose when the server sent no `Retry-After`. */
        val FALLBACK_COOLDOWN: Duration = 5.seconds

        val BACKOFF_FACTOR: Double = 2.0

        /** After this many in a row, stop believing the server's number. */
        const val MAX_OBSERVED_429: Int = 5
    }
}
