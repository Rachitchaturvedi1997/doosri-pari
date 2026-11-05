package `in`.doosripari.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan section 11's one error shape, and the three status facts the policy
 * layers branch on: 401 is refreshable, 429 is retryable-but-not-now, and
 * everything else is whatever the status says it is.
 */
class ErrorMappingTest {

    // ---------------------------------------------------------- the shape --

    @Test
    fun `the plan's shape parses into detail and field errors`() {
        val mapping = requireNotNull(
            errorFor(
                WireResponse(
                    status = 422,
                    body = errorBody(
                        "Those details are not right.",
                        listOf(
                            "phone_e164" to "That number is not valid.",
                            "service_type" to "Choose a service.",
                        ),
                    ),
                ),
            ),
        )

        assertEquals("Those details are not right.", mapping.error.detail)
        assertEquals(2, mapping.error.fieldErrors.size)
        assertEquals("phone_e164", mapping.firstFieldError?.field)
        assertEquals("Choose a service.", mapping.error.fieldErrors[1].message)
        assertEquals(422, mapping.error.status)
    }

    @Test
    fun `errors may be omitted entirely`() {
        // Plan section 11 shows the array, but a validation library in front of
        // the app need not send one, and a missing key must not be a failure to
        // parse.
        val mapping = requireNotNull(
            errorFor(WireResponse(status = 400, body = """{"detail":"Bad request."}""")),
        )

        assertEquals("Bad request.", mapping.error.detail)
        assertTrue(mapping.error.fieldErrors.isEmpty())
    }

    @Test
    fun `an errors array with no entries is still not a problem`() {
        val mapping = requireNotNull(
            errorFor(WireResponse(status = 400, body = """{"detail":"No.","errors":[]}""")),
        )
        assertTrue(mapping.error.fieldErrors.isEmpty())
    }

    @Test
    fun `a success is not an error`() {
        assertNull(errorFor(WireResponse(status = 200, body = "{}")))
        assertNull(errorFor(WireResponse(status = 204)))
    }

    // ------------------------------------------------------ the ugly cases --

    @Test
    fun `a reverse proxy's HTML 502 does not become markup on a screen`() {
        val mapping = requireNotNull(
            errorFor(
                WireResponse(
                    status = 502,
                    body = "<html><head><title>502 Bad Gateway</title></head></html>",
                ),
            ),
        )

        assertEquals(502, mapping.error.status)
        assertFalse(mapping.error.displayMessage.contains("<"))
        assertTrue(mapping.error.isRetryable)
    }

    @Test
    fun `an empty body still produces something showable`() {
        val mapping = requireNotNull(errorFor(WireResponse(status = 500, body = "")))

        assertEquals(500, mapping.error.status)
        assertTrue(mapping.error.displayMessage.isNotBlank())
    }

    @Test
    fun `the status wins over a body that claims otherwise`() {
        // A 500 carrying "everything is fine" is a server bug. Believing the
        // body would show "check-in saved" for a check-in that was never
        // stored, which is the worst possible thing for this app to do.
        val mapping = requireNotNull(
            errorFor(WireResponse(status = 500, body = """{"detail":"All good."}""")),
        )

        assertEquals(500, mapping.error.status)
        assertTrue(mapping.error.isRetryable)
    }

    @Test
    fun `a transport failure is status zero and is retryable`() {
        val mapping = parseError(status = WireResponse.HTTP_NO_STATUS)

        assertEquals(0, mapping.error.status)
        assertTrue(mapping.error.isRetryable)
        assertFalse(mapping.requiresAuthRefresh)
    }

    // ------------------------------------------------------------- statuses --

    @Test
    fun `a 401 is refreshable`() {
        val mapping = requireNotNull(errorFor(WireResponse(status = 401, body = errorBody("expired"))))

        assertTrue(mapping.requiresAuthRefresh)
        assertFalse(mapping.isRateLimited)
        // Refreshable is NOT the same as retryable — re-sending the same dead
        // token gets the same 401. This is the distinction that keeps the
        // client from looping.
        assertFalse(mapping.isRetryable)
    }

    @Test
    fun `a 429 is retryable and flagged as rate limited`() {
        val mapping = requireNotNull(
            errorFor(
                WireResponse(
                    status = 429,
                    body = errorBody("Too many requests."),
                    headers = mapOf("Retry-After" to "30"),
                ),
            ),
        )

        assertTrue(mapping.isRateLimited)
        assertTrue(mapping.isRetryable, "core-model says 429 is retryable, and it is")
        assertEquals(30L, mapping.retryAfterSeconds)
    }

    @Test
    fun `Retry-After is read case-insensitively and ignores junk`() {
        assertEquals(
            5L,
            requireNotNull(
                errorFor(WireResponse(status = 429, headers = mapOf("retry-after" to "5"))),
            ).retryAfterSeconds,
        )
        assertNull(
            requireNotNull(errorFor(WireResponse(status = 429, headers = mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")))).retryAfterSeconds,
            "the HTTP-date form is deliberately not parsed; see retryAfterSeconds",
        )
        assertNull(requireNotNull(errorFor(WireResponse(status = 429))).retryAfterSeconds)
    }

    @Test
    fun `a 403 is neither refreshable nor rate limited`() {
        val mapping = requireNotNull(errorFor(WireResponse(status = 403)))

        assertFalse(mapping.requiresAuthRefresh)
        assertFalse(mapping.isRateLimited)
        assertFalse(mapping.isRetryable)
    }

    @Test
    fun `fallback wording says what to do next, not what the status was`() {
        // These are the strings a 60+ user is most likely to read. Naming the
        // HTTP status teaches them nothing and reads as blame.
        val unauthorised = requireNotNull(errorFor(WireResponse(status = 401))).displayMessage
        val throttled = requireNotNull(errorFor(WireResponse(status = 429))).displayMessage
        val serverError = requireNotNull(errorFor(WireResponse(status = 500))).displayMessage
        val offline = parseError(WireResponse.HTTP_NO_STATUS).displayMessage

        assertFalse(unauthorised.contains("401"))
        assertFalse(throttled.contains("429"))
        assertFalse(serverError.contains("500"))
        assertTrue(offline.contains("connection", ignoreCase = true))
    }

    @Test
    fun `an unknown status still yields a usable message`() {
        val mapping = requireNotNull(errorFor(WireResponse(status = 418)))

        assertEquals(418, mapping.error.status)
        assertEquals(FALLBACK_DETAIL, mapping.error.displayMessage)
        assertFalse(mapping.error.isRetryable)
    }
}
