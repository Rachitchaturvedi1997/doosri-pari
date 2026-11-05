package `in`.doosripari.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The single-flight property, which is the whole reason [TokenAuthenticator]
 * exists. See the file KDoc in `Auth.kt` for the failure it prevents: twenty
 * overlapping 401s turning into twenty refreshes, nineteen of which present a
 * refresh token the server has already rotated away.
 */
class AuthTest {

    @Test
    fun `N concurrent refreshes produce exactly one refresh call`() = runTest {
        // Held open so every caller piles onto the in-flight refresh before any
        // of them can finish. Without the gate, `refresh()` would complete
        // synchronously and the callers would never actually overlap — which is
        // precisely why this bug survives casual testing.
        val gate = CompletableDeferred<Unit>()
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        var calls = 0

        val auth = TokenAuthenticator(store) {
            calls++
            gate.await()
            TokenPair("access-1", "refresh-1")
        }

        val waiters = List(20) { async { auth.refresh() } }
        advanceUntilIdle()
        gate.complete(Unit)

        assertEquals(20, waiters.awaitAll().count { it })
        assertEquals(1, calls, "20 concurrent 401s must produce exactly ONE refresh")
    }

    @Test
    fun `every waiter receives the same token`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val auth = TokenAuthenticator(store) { TokenPair("access-1", "refresh-1") }

        val results = List(8) { async { auth.refresh() } }.awaitAll()

        assertTrue(results.all { it }, "every waiter must see a successful refresh")
        assertEquals("access-1", store.read()?.accessToken)
    }

    @Test
    fun `a 401 refreshes once and replays once with the new token`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val transport = FakeTransport(
            WireResponse(status = 401, body = errorBody("expired")),
            WireResponse(status = 200, body = "{}"),
        )
        var refreshes = 0
        val auth = TokenAuthenticator(store) {
            refreshes++
            TokenPair("access-1", "refresh-1")
        }

        val response = auth.sendAuthenticated(
            transport,
            WireRequest("GET", "https://example.test/checkin/today"),
        )

        assertEquals(200, response.status)
        assertEquals(1, refreshes)
        assertEquals(2, transport.sent.size, "one original send plus exactly one replay")
        assertEquals("Bearer access-0", transport.bearerOf(0))
        assertEquals("Bearer access-1", transport.bearerOf(1))
    }

    @Test
    fun `a 401 whose token another coroutine already replaced does not refresh again`() = runTest {
        // The "second half of the bug": this request went out with access-0,
        // and while it was in flight somebody else refreshed to access-9. The
        // right answer is to replay with access-9 and leave /auth/refresh
        // alone — refreshing again would rotate a token a second time.
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val transport = FakeTransport(
            WireResponse(status = 401, body = errorBody("expired")),
            WireResponse(status = 200, body = "{}"),
        )
        var refreshes = 0
        val auth = TokenAuthenticator(store) {
            refreshes++
            TokenPair("access-1", "refresh-1")
        }

        // Capture the token this request will send, then swap it underneath
        // ourselves exactly as a concurrent refresh would.
        val staleRequest = WireRequest("GET", "https://example.test/me")
        store.write(TokenPair("access-9", "refresh-9"))

        val response = auth.sendAuthenticated(transport, staleRequest)

        assertEquals(200, response.status)
        assertEquals(0, refreshes, "another coroutine already refreshed; do not refresh again")
        assertEquals(2, transport.sent.size)
        assertEquals("Bearer access-9", transport.bearerOf(1))
    }

    @Test
    fun `two 401s in a row surface the failure rather than looping`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val transport = FakeTransport(
            WireResponse(status = 401, body = errorBody("expired")),
            WireResponse(status = 401, body = errorBody("expired")),
            WireResponse(status = 200, body = "{}"),
        )
        val auth = TokenAuthenticator(store) { TokenPair("access-1", "refresh-1") }

        val response = auth.sendAuthenticated(
            transport,
            WireRequest("GET", "https://example.test/me"),
        )

        assertEquals(401, response.status)
        assertEquals(2, transport.sent.size, "one original plus one replay, then stop")
        assertEquals(200, requireNotNull(parseError(transport.fallback.status)).error.status + 1)
    }

    @Test
    fun `a dead refresh token leaves the caller signed out, not stuck`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val transport = FakeTransport(WireResponse(status = 401, body = errorBody("expired")))
        val auth = TokenAuthenticator(store) { null }

        val response = auth.sendAuthenticated(
            transport,
            WireRequest("GET", "https://example.test/me"),
        )

        assertEquals(401, response.status)
        assertEquals(1, transport.sent.size, "nothing to replay with")
        assertNull(auth.currentAccessToken().takeIf { it != "access-0" })
    }

    @Test
    fun `a throwing refresher releases the slot for the next 401`() = runTest {
        // The failure mode this guards is worse than the bug: a deferred that
        // is never completed deadlocks every later 401 behind it, and the user
        // is signed out of their own app with no way back.
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        var attempt = 0
        val auth = TokenAuthenticator(store) {
            attempt++
            if (attempt == 1) throw IllegalStateException("keystore unavailable")
            TokenPair("access-1", "refresh-1")
        }

        val first = runCatching { auth.refresh() }
        assertTrue(first.isFailure)

        assertTrue(auth.refresh(), "the slot must be free again")
        assertEquals(2, attempt)
        assertNotNull(auth.currentAccessToken())
    }

    @Test
    fun `a refresh is not in flight once it has finished`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val auth = TokenAuthenticator(store) { TokenPair("access-1", "refresh-1") }

        assertEquals(false, auth.isRefreshing())
        auth.refresh()
        assertEquals(false, auth.isRefreshing())
    }

    @Test
    fun `no bearer header goes out when the user is signed out`() = runTest {
        val store = InMemoryTokenStore(null)
        val transport = FakeTransport(WireResponse(status = 200, body = "{}"))
        val auth = TokenAuthenticator(store) { TokenPair("access-1", "refresh-1") }

        auth.sendAuthenticated(transport, WireRequest("GET", "https://example.test/me"))

        assertNull(
            transport.sent.single().headers[ApiClient.HEADER_AUTHORIZATION],
            "an unauthenticated call must not claim a bearer token",
        )
    }

    @Test
    fun `sign out forgets the pair`() = runTest {
        val store = InMemoryTokenStore(TokenPair("access-0", "refresh-0"))
        val auth = TokenAuthenticator(store) { TokenPair("access-1", "refresh-1") }

        auth.signOut()

        assertNull(auth.currentAccessToken())
    }
}
