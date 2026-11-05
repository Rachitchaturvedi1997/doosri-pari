package `in`.doosripari.network

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The retry rules. See `Retry.kt` for why each of these is the way it is — the
 * short version is that a retried `POST /checkin` is a second round of guardian
 * alerts and IVR calls to somebody's family.
 */
class RetryTest {

    private val policy = RetryPolicy.Default

    // ------------------------------------------------------------- the rule --

    @Test
    fun `a POST is never retried, whatever the failure`() {
        for (status in listOf(500, 502, 503, 504, WireResponse.HTTP_NO_STATUS)) {
            assertFalse(
                policy.shouldRetry("POST", attempt = 1, status = status),
                "POST must not be retried on $status",
            )
        }
    }

    @Test
    fun `a POST is retried only when an idempotency key is going out with it`() {
        // The outbox in core-db sends one of these on every replay, which is
        // what makes re-sending safe. Without a key, no.
        assertTrue(
            policy.shouldRetry(
                method = "POST",
                attempt = 1,
                status = 503,
                isIdempotent = true,
            ),
            "a POST carrying Idempotency-Key may be re-sent",
        )
    }

    @Test
    fun `a PATCH is never retried`() {
        assertFalse(policy.shouldRetry("PATCH", attempt = 1, status = 500))
    }

    @Test
    fun `a GET is retried on a 5xx`() {
        for (status in listOf(500, 502, 503, 504)) {
            assertTrue(
                policy.shouldRetry("GET", attempt = 1, status = status),
                "GET should be retried on $status",
            )
        }
    }

    @Test
    fun `a GET is retried on a transport failure`() {
        assertTrue(policy.shouldRetry("GET", attempt = 1, status = WireResponse.HTTP_NO_STATUS))
    }

    @Test
    fun `a GET is not retried on a 4xx`() {
        for (status in listOf(400, 401, 403, 404, 422)) {
            assertFalse(
                policy.shouldRetry("GET", attempt = 1, status = status),
                "GET should not be retried on $status",
            )
        }
    }

    @Test
    fun `a 429 is never retried immediately, even for a GET`() {
        // The plan's rate limit is per user and per IP. Retrying inside the
        // back-off window is the load the limit exists to shed, and it earns a
        // longer ban than the original one.
        for (method in listOf("GET", "PUT", "DELETE")) {
            assertFalse(
                policy.shouldRetry(method, attempt = 1, status = 429),
                "$method on 429 must be handled by RateLimitGate, not retried here",
            )
        }
    }

    @Test
    fun `retries stop at maxAttempts`() {
        val capped = RetryPolicy(maxAttempts = 3)
        assertTrue(capped.shouldRetry("GET", attempt = 1, status = 503))
        assertTrue(capped.shouldRetry("GET", attempt = 2, status = 503))
        assertFalse(capped.shouldRetry("GET", attempt = 3, status = 503))
        assertFalse(capped.shouldRetry("GET", attempt = 4, status = 503))
    }

    @Test
    fun `Never retries nothing`() {
        assertFalse(RetryPolicy.Never.shouldRetry("GET", attempt = 1, status = 503))
    }

    @Test
    fun `safe and idempotent methods are recognised, POST and PATCH are not`() {
        for (method in listOf("GET", "HEAD", "OPTIONS", "TRACE", "PUT", "DELETE")) {
            assertTrue(policy.isIdempotentMethod(method), "$method should be retryable")
            assertTrue(policy.isIdempotentMethod(method.lowercase()), "$method lowercase too")
        }
        for (method in listOf("POST", "PATCH")) {
            assertFalse(policy.isIdempotentMethod(method), "$method must never be retryable")
        }
    }

    // ------------------------------------------------------------ the delay --

    @Test
    fun `back-off grows and is clamped`() {
        assertEquals(400.milliseconds, policy.delayFor(1))
        assertEquals(800.milliseconds, policy.delayFor(2))
        assertEquals(1_600.milliseconds, policy.delayFor(3))
        // Clamped, not growing without bound.
        assertEquals(4.seconds, policy.delayFor(9))
    }

    // ------------------------------------------------- through the real client --

    @Test
    fun `a GET on 500 is retried by the client and then succeeds`() = runTest {
        val transport = FakeTransport(
            WireResponse(status = 500, body = errorBody("boom")),
            WireResponse(status = 200, body = """{"ok":true}"""),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val body: Map<String, Boolean> = client.get("/ok")

        assertEquals(true, body["ok"])
        assertEquals(2, transport.sent.size, "one send plus exactly one retry")
    }

    @Test
    fun `a POST on 500 is NOT retried by the client`() = runTest {
        // The end-to-end form of the rule above. This is the assertion that
        // would fail if somebody ever swapped the retry loop for a Ktor plugin
        // with `retryOnServerErrors` switched on.
        val transport = FakeTransport(
            WireResponse(status = 500, body = errorBody("boom")),
            WireResponse(status = 200, body = """{"ok":true}"""),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val failure = runCatching { client.post<Any>("/checkin") }

        assertTrue(failure.isFailure)
        val error = (failure.exceptionOrNull() as ApiException).error
        assertEquals(500, error.status)
        assertEquals(1, transport.sent.size, "the POST must have gone out exactly once")
    }

    @Test
    fun `a POST with an idempotency key IS retried`() = runTest {
        val transport = FakeTransport(
            WireResponse(status = 503, body = errorBody("busy")),
            WireResponse(status = 200, body = """{"ok":true}"""),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        client.post<Map<String, Boolean>>("/checkin", idempotencyKey = "key-1")

        assertEquals(2, transport.sent.size)
        assertEquals("key-1", transport.sent[0].headers[ApiClient.HEADER_IDEMPOTENCY_KEY])
        assertEquals("key-1", transport.sent[1].headers[ApiClient.HEADER_IDEMPOTENCY_KEY])
    }

    @Test
    fun `a 429 is surfaced immediately and not re-sent`() = runTest {
        val transport = FakeTransport(
            WireResponse(status = 429, body = errorBody("slow down"), headers = mapOf("Retry-After" to "30")),
            WireResponse(status = 200, body = "{}"),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val failure = runCatching { client.get<Any>("/checkin/today") }

        assertTrue(failure.isFailure)
        assertEquals(429, (failure.exceptionOrNull() as ApiException).error.status)
        assertEquals(1, transport.sent.size, "a 429 must not be re-sent straight away")
    }

    @Test
    fun `the client retries up to maxAttempts and then gives up`() = runTest {
        val transport = FakeTransport().apply {
            fallback = WireResponse(status = 503, body = errorBody("busy"))
        }
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            retry = RetryPolicy(maxAttempts = 3),
            sleeper = { },
        )

        val failure = runCatching { client.get<Any>("/checkin/today") }

        assertTrue(failure.isFailure)
        assertEquals(3, transport.sent.size, "one send plus two retries, then stop")
    }

    @Test
    fun `a transport throw becomes a retryable status zero`() = runTest {
        val transport = object : Transport {
            var calls = 0

            override suspend fun send(request: WireRequest): WireResponse {
                calls++
                if (calls == 1) throw RuntimeException("connection reset")
                return WireResponse(status = 200, body = """{"ok":true}""")
            }
        }
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val body: Map<String, Boolean> = client.get("/ok")

        assertEquals(true, body["ok"])
        assertEquals(2, transport.calls)
    }

    @Test
    fun `a decoded failure keeps the plan's detail and field errors`() = runTest {
        val transport = FakeTransport(
            WireResponse(
                status = 422,
                body = errorBody(
                    "Those details are not right.",
                    listOf("phone_e164" to "That number is not valid."),
                ),
            ),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val error = (runCatching { client.get<Any>("/me") }.exceptionOrNull() as ApiException).error

        assertEquals(422, error.status)
        assertEquals("Those details are not right.", error.displayMessage)
        assertEquals("phone_e164", error.fieldErrors.single().field)
        assertEquals("That number is not valid.", error.fieldErrors.single().message)
    }
}
