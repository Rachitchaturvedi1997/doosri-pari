package `in`.doosripari.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When a request may be sent again.
 *
 * ## The one rule that is not negotiable
 *
 * **A POST is never retried.** Not on a 500, not on a timeout, not because the
 * body looks idempotent. Plan section 6.3 has a user pressing "I'm fine" once;
 * plan section 12 has the server turning a check-in into guardian alerts, IVR
 * calls and — after an hour of nobody acknowledging — a message to nearby
 * helpers. A retried `POST /checkin` is not a duplicate row, it is a second
 * round of all of that, and the person on the receiving end of those alerts is
 * somebody's son or daughter, about to find out from a phone that their mother
 * may have died.
 *
 * The same argument rules out a retry on any write whose idempotency the client
 * cannot prove. If the server honours `Idempotency-Key` (this module sends one
 * when the caller supplies it — see [ApiClient.post]), then a POST *with* a key
 * is safe to re-send. [RetryPolicy.shouldRetry] therefore takes an
 * [isIdempotent] flag rather than looking at the method alone, and the caller
 * passes true only when a key is actually going out. Absent that, the answer
 * is no.
 *
 * ## What IS retried
 *
 * - **5xx.** The server is unwell. GET, HEAD, PUT and DELETE are safe to
 *   repeat — PUT and DELETE by definition, and plan section 11's endpoint table
 *   is built on that (`GET/PUT /me`, `GET/PUT /checkin/settings`,
 *   `PATCH /guardian/{id}/perms`, `DELETE /devices/{id}`).
 * - **Transport failures** — status 0, a request that never completed. The
 *   server did not answer, so it very likely did not act.
 *
 * ## What is NOT retried, however tempting
 *
 * - **429.** It is retryable in the ordinary sense, and `core-model`'s
 *   `ApiError.isRetryable` says so. It still must not be re-sent *now*. Plan
 *   section 11 rate-limits every write per user and per IP; a client that
 *   retries immediately is the load the limit exists to shed. The server has
 *   usually said exactly how long to wait, in `Retry-After`, and [RateLimitGate]
 *   is holding that. Retrying here would race the cooldown and earn a longer
 *   ban.
 * - **Any other 4xx.** The request was wrong. Sending it again unchanged gets
 *   the same answer and, on a rate-limited endpoint, a worse one.
 * - **401.** Recoverable, but by refreshing — see [TokenAuthenticator], which
 *   handles that inside a single request. Looping on 401 here would hammer
 *   `/auth/refresh`, which is precisely the endpoint that most needs to be left
 *   alone.
 */
data class RetryPolicy(
    /** Total attempts including the first. 3 means one send plus two re-sends. */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** First back-off. Grows by [backoffFactor] per attempt. */
    val baseDelay: Duration = DEFAULT_BASE_DELAY,
    val maxDelay: Duration = DEFAULT_MAX_DELAY,
    val backoffFactor: Double = DEFAULT_BACKOFF_FACTOR,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(baseDelay > Duration.ZERO) { "baseDelay must be positive" }
        require(maxDelay >= baseDelay) { "maxDelay must be at least baseDelay" }
        require(backoffFactor >= 1.0) { "backoffFactor must not shrink the delay" }
    }

    /**
     * Whether the send numbered [attempt] (1-based, so the first send is 1) may
     * be followed by another send of the same request.
     *
     * [status] is `0` for a transport failure, matching [WireResponse].
     * [isIdempotent] is the caller's promise that re-sending cannot duplicate a
     * side effect — true for the safe and idempotent methods, and for a POST
     * that is carrying an `Idempotency-Key`.
     */
    fun shouldRetry(
        method: String,
        attempt: Int,
        status: Int,
        isTransportFailure: Boolean = status == WireResponse.HTTP_NO_STATUS,
        isIdempotent: Boolean = isIdempotentMethod(method),
    ): Boolean {
        if (attempt >= maxAttempts) return false
        if (!isIdempotent) return false
        if (status == HTTP_TOO_MANY_REQUESTS) return false
        if (isTransportFailure) return true
        return status in 500..599
    }

    /**
     * How long to wait before send [attempt] + 1.
     *
     * No jitter, deliberately: the delays are small, the variance of a mobile
     * connection is larger than the jitter would be, and a random delay makes
     * the retry tests non-deterministic for no measurable benefit. If this ever
     * runs a fleet of clients against one server, this is the line to revisit.
     */
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt is 1-based, was $attempt" }
        var scaled = baseDelay.toDouble()
        repeat(attempt - 1) { scaled *= backoffFactor }
        val millis = scaled.toLong().coerceAtMost(maxDelay.inWholeMilliseconds)
        return millis.milliseconds
    }

    /**
     * Methods that are safe to send twice by definition.
     *
     * HTTP calls GET, HEAD, OPTIONS and TRACE *safe*, and PUT and DELETE
     * *idempotent*. POST and PATCH are neither, whatever the payload looks
     * like, and this module has no way to tell otherwise.
     */
    fun isIdempotentMethod(method: String): Boolean = when (method.uppercase()) {
        "GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE" -> true
        else -> false
    }

    companion object {
        /** One send plus two re-sends: enough for a 500 that is really a blip. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /**
         * 400 ms. Longer than a good LTE round trip, shorter than a person
         * deciding the app is broken — with the 8 s request ceiling from
         * [ApiConfig] this puts the worst case near 24 s, all of it inside one
         * screen's budget.
         */
        val DEFAULT_BASE_DELAY: Duration = 400.milliseconds

        /** Four seconds. Past this the user should be told, not left waiting. */
        val DEFAULT_MAX_DELAY: Duration = 4.seconds

        val DEFAULT_BACKOFF_FACTOR: Double = 2.0

        val Default: RetryPolicy = RetryPolicy()

        /**
         * Never re-send anything, for an endpoint where even a GET is not worth
         * repeating — `POST /auth/verify`, for instance, where the OTP is
         * single use.
         */
        val Never: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}
