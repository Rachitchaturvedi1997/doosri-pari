package `in`.doosripari.network

import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where the JWT pair lives.
 *
 * ## The token is not a document
 *
 * This is an interface rather than a direct Keystore call because of where the
 * bytes must sit, and it is worth being blunt about the reasoning: an access
 * token for *this* user's account is a bearer credential. Anything that can
 * read it can check in as that user, read their circle, and — if the app is
 * open — see which documents their guardians may look at. Writing it to
 * `SharedPreferences` or `NSUserDefaults` would put it in a file that is
 * trivially readable from a backup, from an `adb backup`, or from a stolen
 * handset with no lockscreen.
 *
 * So the actual implementation MUST go through `core-crypto`'s platform
 * keystore — Android Keystore, iOS Keychain — and the implementation in each
 * `actual` is expected to call `platformSecureStore()`. Nothing in this
 * repository writes a token anywhere else, and `core-network` does not depend
 * on `core-crypto` (that would invert the module order in plan section 8.2);
 * the app module wires the two together through [platformTokenStore].
 *
 * The interface is here anyway, for three reasons:
 * 1. The tests need a fake, and a fake is the only way to test the
 *    single-flight refresh in [Auth.kt] without a keystore.
 * 2. "Where do the bytes go" is a decision worth writing down in one place
 *    rather than at each call site.
 * 3. A platform that cannot offer a keystore at all can supply an in-memory
 *    implementation that dies with the process, which is still better than a
 *    file — a user who is signed out when the process dies signs in again, and
 *    a user whose token is in a world-readable file is compromised.
 */

/**
 * A JWT access/refresh pair. Plan section 11: "JWT bearer tokens".
 *
 * [accessExpiresAt] is kept so a client can refresh *before* the first 401
 * rather than after it. That matters on a slow 2G connection in a village: an
 * access token that expires two seconds into a request produces a 401 the user
 * sees as "sign in again", and re-authenticating a 68-year-old through an OTP
 * is how they end up not using the app.
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    /** Server-declared expiry of [accessToken]. Null if the server sent none. */
    val accessExpiresAt: Instant? = null,
    /** Issued-at of [accessToken]; used to detect skew between phone clocks. */
    val issuedAt: Instant? = null,
) {
    /**
     * Whether this access token is close enough to expiry to refresh first.
     *
     * [skew] defaults to 60 s: long enough to cover a slow request, short
     * enough that a healthy session is not refreshed on every call.
     *
     * With no declared expiry this returns false. Guessing an expiry for a
     * server that did not state one would either refresh constantly or never.
     */
    fun isExpiring(now: Instant, skew: Duration = DEFAULT_SKEW): Boolean {
        val expiresAt = accessExpiresAt ?: return false
        return now >= expiresAt - skew
    }

    companion object {
        val DEFAULT_SKEW: Duration = 60.seconds
    }
}

/**
 * Storage for one user's token pair. Blocking or suspending is the platform's
 * choice: Android Keystore is suspending in practice (it touches a system
 * service) and the iOS Keychain is not.
 *
 * Implementations must be safe to call from any coroutine on any thread. The
 * refresh path in [Auth.kt] calls [read] from inside a mutex precisely so that
 * two threads cannot both decide the token is stale.
 */
interface TokenStore {
    /** The stored pair, or null when signed out. */
    suspend fun read(): TokenPair?

    /** Replace the stored pair. Writing null is the same as [clear]. */
    suspend fun write(pair: TokenPair?)

    /** Forget the pair. Called on sign-out and after a refresh the server refuses. */
    suspend fun clear()
}

/**
 * The platform's [TokenStore].
 *
 * **No `actual` exists yet.** Each target (`jvmMain`, `androidMain`, `iosX64`,
 * `iosArm64`, `iosSimulatorArm64`) needs one, and until it does this module
 * does not compile for that target — see the module report. The Android one
 * must be backed by `core-crypto`'s `platformSecureStore()`; the iOS one by
 * the Keychain, with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` so the
 * token is not restored onto a different handset from a backup.
 */
expect fun platformTokenStore(): TokenStore

/**
 * A store that keeps the pair in memory and forgets it when the process dies.
 *
 * Not a joke implementation, and not only for tests. It is the correct fallback
 * for a platform with no keystore, for the case where the keystore is
 * unavailable because the device is in a broken state, and for the seconds
 * between "the user typed their OTP" and "the keystore has been written".
 * Signing in again is an inconvenience; a bearer token in a world-readable
 * file is a compromise.
 */
class InMemoryTokenStore(initial: TokenPair? = null) : TokenStore {

    // Suspended writes and reads on one dispatcher, which is how the refresh
    // path uses it. A platform store must do better; see the interface KDoc.
    private var current: TokenPair? = initial

    override suspend fun read(): TokenPair? = current

    override suspend fun write(pair: TokenPair?) {
        current = pair
    }

    override suspend fun clear() {
        current = null
    }
}
