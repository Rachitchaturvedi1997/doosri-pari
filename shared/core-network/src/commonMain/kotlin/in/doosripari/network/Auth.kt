package `in`.doosripari.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlin.time.Duration

/**
 * JWT bearer auth with a **single-flight refresh**. Plan section 11.
 *
 * ## The bug this file exists to prevent
 *
 * The obvious way to write this is: on a 401, call `POST /auth/refresh`, then
 * re-send. That is correct for one request and catastrophic for twenty. Open
 * the Today screen on a phone that has been asleep: the access token expired
 * while the app was backgrounded, so twenty calls fire at once and twenty of
 * them come back 401. Twenty refreshes go out together. Each rotates the
 * refresh token, so nineteen of them present a token the server has already
 * invalidated, and — on a server that invalidates the family on first reuse —
 * all twenty now fail. The user is signed out. The screen they opened is the
 * one they open when they are worried about somebody.
 *
 * The failure is also invisible in testing, because it only appears when
 * requests genuinely overlap.
 *
 * So: **N concurrent 401s must produce exactly ONE refresh.** [TokenAuthenticator]
 * enforces it with a mutex plus a shared [CompletableDeferred]; every caller
 * that arrives while a refresh is in flight awaits that same deferred instead
 * of starting a second one. `AuthTest` holds the property down.
 *
 * ## The second half of the same bug
 *
 * Even with a single flight there is a window: request A sends with token T,
 * gets a 401, and refreshes to T2. Request B — sent earlier, also with T —
 * now gets its 401 and, seeing a *live* refresh it could have joined, would
 * otherwise have to start its own. [TokenAuthenticator.sendAuthenticated]
 * catches that case by remembering which token the failed request carried and
 * checking whether the store has already moved on. B re-sends with T2 without
 * touching `/auth/refresh` at all.
 *
 * ## Why this is not a Ktor auth plugin
 *
 * `ktor-client-auth` is not a dependency of this module, and adding one would
 * mean the single-flight logic — the part that has to be right — would live in
 * a plugin body with no seam to test it. Everything here is expressed over
 * [Transport], so the whole file is covered by a fake with no engine, no
 * keystore and no server.
 */

/** Performs the actual token exchange. Injected so tests can count calls. */
fun interface TokenRefresher {
    /**
     * Exchange the current refresh token for a new pair, or return null when
     * the refresh token is itself dead and the user has to sign in again.
     *
     * **Null is a decision, not a failure.** It means "the server refused this
     * refresh token", and [TokenAuthenticator.refresh] responds by clearing the
     * store, so the app routes to sign-in rather than looping on a credential
     * that will never work. A network blip during the refresh is a *throw*,
     * not a null: it leaves the session alone so the user is not signed out
     * because a village connection dropped.
     *
     * Implementations must be safe under concurrency: the caller guarantees at
     * most one is in flight at a time.
     */
    suspend fun refresh(): TokenPair?
}

/**
 * Attaches the bearer token, and refreshes it once when the server says 401.
 *
 * Thread-safe. All mutable state is behind [mutex].
 */
class TokenAuthenticator(
    private val store: TokenStore,
    private val refresher: TokenRefresher,
) {
    /**
     * The refresh currently running, if any. Null means "nobody is refreshing".
     *
     * Read and written only under [mutex]. Deliberately not an atomic: the
     * check-and-set of "is one already running, and if not start mine" has to be
     * a single step, and a mutex is that step.
     */
    private val mutex = Mutex()
    private var inFlight: InFlight? = null

    /** One refresh, and the shared result every waiter receives. */
    private class InFlight(val result: CompletableDeferred<TokenPair?>)

    /** The stored access token, or null when signed out. */
    suspend fun currentAccessToken(): String? = store.read()?.accessToken

    /**
     * Refresh, or join a refresh already running.
     *
     * **This is the single-flight.** If a refresh is in flight the caller
     * suspends on that one's [CompletableDeferred] and receives its result;
     * [refresher] is not called. Otherwise this caller owns the refresh, and
     * every later caller joins it.
     *
     * Returns true when a usable access token is in the store afterwards.
     */
    suspend fun refresh(): Boolean {
        val (slot, owner) = mutex.withLock {
            val existing = inFlight
            if (existing != null) {
                existing to false
            } else {
                InFlight(CompletableDeferred()).also { inFlight = it } to true
            }
        }

        if (!owner) return slot.result.await() != null

        val pair = try {
            refresher.refresh().also { fresh ->
                // Write BEFORE completing, so a waiter that wakes up and reads
                // the store cannot see the old token and decide it is still
                // stale. That ordering is the whole point of the shared
                // deferred.
                //
                // A null clears the store: per [TokenRefresher], that means the
                // refresh token is dead, and the correct response is to send
                // the user to sign in rather than to keep presenting it.
                if (fresh != null) store.write(fresh) else store.clear()
                slot.result.complete(fresh)
            }
        } catch (t: Throwable) {
            // Everyone waiting gets the same failure, and the slot is released
            // so the next 401 can try again rather than deadlocking behind a
            // deferred nobody will ever complete.
            slot.result.completeExceptionally(t)
            throw t
        } finally {
            mutex.withLock { if (inFlight === slot) inFlight = null }
        }
        return pair != null
    }

    /**
     * Whether a refresh is running right now. Diagnostics and tests only; the
     * policy does not branch on it.
     */
    suspend fun isRefreshing(): Boolean = mutex.withLock { inFlight != null }

    /**
     * Send [request] with the current bearer token, and on a 401 refresh once
     * and re-send once.
     *
     * Returns the replay's response, so the caller sees the server's real
     * answer rather than a synthesised success. Two 401s in a row come back as
     * the second 401 and [ApiClient] turns that into "sign in again" — which is
     * the honest thing, because a refresh that produced a token the server
     * still rejects means the session is gone.
     */
    suspend fun sendAuthenticated(
        transport: Transport,
        request: WireRequest,
    ): WireResponse {
        val token = currentAccessToken()
        val first = transport.send(request.withBearer(token))

        if (first.status != HTTP_UNAUTHORIZED) return first

        // The "second half of the bug" check. If the store already holds a
        // token different from the one that just failed, somebody else
        // refreshed while this request was in flight; re-send with theirs and
        // do not call /auth/refresh at all.
        val current = store.read()?.accessToken
        if (token != null && current != null && current != token) {
            return transport.send(request.withBearer(current))
        }

        if (!refresh()) return first

        val refreshed = store.read()?.accessToken
            ?: return first
        if (refreshed == token) {
            // The refresh produced the same access token. Re-sending would
            // produce the same 401, so surface the original instead of
            // spending another round trip to learn nothing.
            return first
        }
        return transport.send(request.withBearer(refreshed))
    }

    /**
     * Refresh ahead of expiry if the token is about to run out.
     *
     * Optional, and the reason [TokenPair.accessExpiresAt] exists. A village
     * 2G connection takes seconds; an access token that expires two seconds
     * into a request becomes a 401 the user experiences as being signed out,
     * and re-authenticating somebody through an OTP is how they stop using the
     * app. Does nothing when the server declared no expiry.
     */
    suspend fun refreshIfExpiring(
        now: Instant,
        skew: Duration = TokenPair.DEFAULT_SKEW,
    ): Boolean {
        val pair = store.read() ?: return false
        if (!pair.isExpiring(now, skew)) return false
        return refresh()
    }

    /** Sign out: forget the pair and refuse to refresh afterwards. */
    suspend fun signOut() {
        store.clear()
    }

    /** Copy [request] with a bearer header. A null token means "no header". */
    private fun WireRequest.withBearer(token: String?): WireRequest {
        if (token == null) return this
        val headers = buildMap {
            putAll(this@withBearer.headers)
            put(ApiClient.HEADER_AUTHORIZATION, "Bearer $token")
        }
        return copy(headers = headers)
    }
}
