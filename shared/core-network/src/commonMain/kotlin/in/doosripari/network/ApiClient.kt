package `in`.doosripari.network

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.Page
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The HTTP layer. Plan section 11.
 *
 * ## Why the client is built this way
 *
 * Three decisions here are load-bearing, and each one is a plan reference:
 *
 * - **Short timeouts.** 8 s request, 5 s connect, carried over from the Android
 *   slice's `HttpCheckInRepository`. This is the screen a user opens when they
 *   are worried about somebody. A request that spins for thirty seconds before
 *   failing teaches a 65-year-old that the app does not work, and they stop
 *   opening it — which is a worse outcome than a bug. Failing fast lets the
 *   screen say so in words and offer another go.
 * - **No naming strategy on the [Json].** The existing wire layer decodes with
 *   `Json { ignoreUnknownKeys = true }` and nothing else, and every DTO carries
 *   an explicit `@SerialName("snake_case")`. That combination is deliberate: a
 *   server that renames `share_location` to `shareLocation` is then a decode
 *   failure in one small file, not a field that quietly becomes null on a
 *   user's phone and leaves guardians not being told.
 * - **`expectSuccess = false`.** A 4xx has to arrive as a response so
 *   [errorFor] can read the plan's error shape out of the body. Letting Ktor
 *   throw first would discard the only thing worth showing the user.
 *
 * ## Wire types versus domain types
 *
 * [WireRequest] and [WireResponse] are not domain types and are not
 * `@Serializable` — they are the seam that keeps Ktor out of the tests. Every
 * behaviour in this module (retry, single-flight refresh, rate-limit back-off,
 * error mapping, paging) is expressed over [Transport], and the tests supply a
 * fake. That is why no test in this module needs a running server, a device, or
 * `ktor-client-mock`, which the module does not depend on.
 *
 * The `@Serializable` DTOs — there are four, all of them `internal` — live in
 * [ErrorMapping.kt] and [Pagination.kt]. Nothing in `core-model` is
 * serializable and nothing in this module makes it so.
 */

// ----------------------------------------------------------------- wire seam --

/** One outgoing call, as the rest of this module needs to describe it. */
data class WireRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * One incoming call.
 *
 * [status] is `0` for a transport failure — a request that never reached the
 * server and therefore has no HTTP status at all. That is the convention
 * `core-model`'s `ApiError.isRetryable` already uses, and it is the only thing
 * that distinguishes "the server said no" from "we never reached the server".
 * The second is the retryable one.
 */
data class WireResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    val isSuccess: Boolean get() = status in 200..299

    /** A transport failure rather than an HTTP answer. */
    val isTransportFailure: Boolean get() = status == HTTP_NO_STATUS

    companion object {
        /** "No HTTP status": the request did not complete. */
        const val HTTP_NO_STATUS: Int = 0
    }
}

/**
 * Sends a request. One method, and no Ktor type in its signature.
 *
 * The production implementation is [KtorTransport]; the tests supply a lambda.
 */
fun interface Transport {
    suspend fun send(request: WireRequest): WireResponse
}

/** The only place Ktor's request and response types appear. */
class KtorTransport(private val client: HttpClient) : Transport {

    override suspend fun send(request: WireRequest): WireResponse {
        val response: HttpResponse = client.request(request.url) {
            method = HttpMethod(request.method)
            request.headers.forEach { (name, value) -> header(name, value) }
            request.body?.let {
                // Set as an explicit TextContent rather than setBody(String),
                // which guesses text/plain — and an API that receives a
                // check-in body as text/plain answers 422 with a field error
                // the user then cannot see, because the error names a field
                // their screen does not have.
                body = TextContent(it, ContentType.Application.Json)
            }
        }
        return WireResponse(
            status = response.status.value,
            headers = response.headers.entries()
                .associate { (name, values) -> name to values.joinToString(",") },
            body = response.bodyAsText(),
        )
    }
}

// -------------------------------------------------------------------- config --

/**
 * Where the API lives and how long it is allowed to take.
 *
 * The engine is NOT part of this. `core-network` depends on neither
 * `ktor-client-okhttp` nor `ktor-client-darwin`, so the platform chooses one —
 * OkHttp under Android, Darwin under iOS — and hands it in. A module that
 * pinned an engine could not be used from the other platform.
 */
data class ApiConfig(
    val baseUrl: String,
    val userAgent: String? = null,
    val requestTimeout: Duration = REQUEST_TIMEOUT,
    val connectTimeout: Duration = CONNECT_TIMEOUT,
    val socketTimeout: Duration = SOCKET_TIMEOUT,
) {
    /** Absolute URL for a path, so no call site ever builds one by hand. */
    fun url(path: String): String =
        if (path.startsWith("http")) path
        else baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        /**
         * See the module KDoc. The Android slice writes the same numbers as
         * `requestTimeoutMillis = 8_000`; the `Duration` spelling is used here
         * because it is the one Ktor has kept across versions.
         */
        val REQUEST_TIMEOUT: Duration = 8.seconds
        val CONNECT_TIMEOUT: Duration = 5.seconds
        val SOCKET_TIMEOUT: Duration = 8.seconds

        /** Plan section 6.6: enough posts to fill a feed without a huge payload. */
        const val DEFAULT_PAGE_SIZE: Int = 20
    }
}

/**
 * Build the one [HttpClient] every feature shares.
 *
 * Deliberately small: timeouts and JSON, nothing more. Auth ([Auth.kt]),
 * retries ([Retry.kt]) and back-off ([RateLimit.kt]) are applied by [ApiClient]
 * on top of this rather than installed as engine-level plugins, because a
 * plugin's retry loop hides whether a request was re-sent — and "was this POST
 * re-sent" is a question with consequences here. See [RetryPolicy].
 */
fun createHttpClient(engine: HttpClientEngine, config: ApiConfig): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeout = config.requestTimeout
            connectTimeout = config.connectTimeout
            socketTimeout = config.socketTimeout
        }
        install(ContentNegotiation) {
            // No naming strategy. See the module KDoc.
            json(Json { ignoreUnknownKeys = true })
        }
    }

/**
 * The facade every feature module talks to instead of a raw [HttpClient].
 *
 * It owns, in this order: the rate-limit cooldown, the bearer token and its
 * single-flight refresh, the retry policy, and finally the error mapping. That
 * order matters — a 429 has to reach [RateLimitGate] *before* anything decides
 * it is retryable, because plan section 11 rate-limits every write per user and
 * per IP, and an immediate retry is the thing the limit exists to stop.
 */
class ApiClient(
    private val config: ApiConfig,
    private val transport: Transport,
    /** Null for the endpoints that need no token: `/auth/otp`, health checks. */
    private val authenticator: TokenAuthenticator? = null,
    private val retry: RetryPolicy = RetryPolicy.Default,
    private val rateLimiter: RateLimitGate = RateLimitGate(),
    /** Injected so the rate-limit tests can move time without sleeping. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Duration) -> Unit = { delay(it) },
    /**
     * The same configuration [createHttpClient] installs. Held here because
     * [ApiClient] decodes bodies itself rather than letting ContentNegotiation
     * do it: a failing response has to be read as an [ErrorMapping] *before*
     * anybody tries to turn it into a `T`.
     */
    @PublishedApi internal val json: Json = WIRE_JSON,
) {

    // ---------------------------------------------------------------- reads --

    /**
     * GET and decode. [query] is appended verbatim; [Pagination.kt] owns how a
     * cursor becomes a query string so that only one file knows the shape.
     */
    suspend inline fun <reified T> get(path: String, query: String? = null): T =
        request("GET", path + (query?.let { if (it.startsWith("?")) it else "?$it" } ?: ""))

    /**
     * GET one cursor page and map it to the domain [Page].
     *
     * The wire shape is `{"items": [...], "next_cursor": "..."}` and the mapping
     * lives in [Pagination.kt]. `core-model`'s [Page] is not `@Serializable`
     * and must not become so.
     */
    suspend inline fun <reified T> getPage(path: String, cursor: String? = null): Page<T> {
        val dto: PageDto<T> = get(path, cursor?.let { "cursor=${it.urlEncode()}" })
        return dto.toPage()
    }

    // --------------------------------------------------------------- writes --

    /**
     * POST and decode.
     *
     * [idempotencyKey] goes out as an `Idempotency-Key` header when supplied.
     * That header is the contract `core-db`'s outbox relies on: a check-in
     * queued while offline and replayed after a flaky connection must not
     * become two check-ins. The client only carries it — the key itself is
     * generated once, in the outbox, and reused across every flush attempt.
     */
    suspend inline fun <reified T> post(
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): T = request("POST", path, body, idempotencyKey)

    /** PUT and decode. Idempotent by method, so [RetryPolicy] may re-send it. */
    suspend inline fun <reified T> put(
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): T = request("PUT", path, body, idempotencyKey)

    /** DELETE and decode. Idempotent by method. */
    suspend inline fun <reified T> delete(path: String): T = request("DELETE", path)

    // --------------------------------------------------------------- engine --

    /**
     * The one path every verb goes through, with the caller supplying how a
     * successful body becomes a `T`.
     *
     * Public so a feature with an unusual shape — a `204` with no body, say —
     * can reuse the whole policy chain rather than reimplementing it.
     */
    suspend inline fun <T> request(
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
        decode: (WireResponse) -> T,
    ): T {
        val response = execute(method, path, body, idempotencyKey)
        return try {
            decode(response)
        } catch (e: SerializationException) {
            // A 2xx the client cannot read is a disagreement between server and
            // client, and the "no naming strategy" rule in the module KDoc
            // exists to make exactly this loud. Turning it into a null is the
            // silent failure the rule was written to prevent.
            throw ApiException(unreadableResponse(response.status), cause = e)
        }
    }

    /** [request] for the ordinary case: decode the whole body as `T`. */
    suspend inline fun <reified T> request(
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): T = request(method, path, body, idempotencyKey) { response ->
        json.decodeFromString<T>(response.body)
    }

    /**
     * Send until it succeeds, or until the policy says to stop.
     *
     * Returns the successful response, or throws [ApiException]. It never
     * returns an error for the caller to forget to check: every caller in this
     * codebase is a coroutine feeding a `LoadState`, and one of them will
     * forget.
     */
    @PublishedApi
    internal suspend fun execute(
        method: String,
        path: String,
        body: String?,
        idempotencyKey: String?,
    ): WireResponse {
        var attempt = 1
        while (true) {
            val wire = buildWire(method, path, body, idempotencyKey)

            // Plan section 11 rate-limits every write per user and per IP. The
            // server enforces the IP half — a client cannot see its own public
            // address, and asking one to find it would put a third-party call
            // on the critical path of a check-in. The client honours the part
            // it can see: do not send before the cooldown the server asked for
            // has ended.
            rateLimiter.awaitPermit(BUCKET_USER, nowMs, sleeper)

            val response = sendOnce(wire)
            rateLimiter.observe(BUCKET_USER, response, nowMs())

            val mapping = errorFor(response)
            if (mapping == null) return response

            if (!retry.shouldRetry(
                    method = method,
                    attempt = attempt,
                    status = mapping.status,
                    isTransportFailure = response.isTransportFailure,
                    // A POST is retryable only when an idempotency key is
                    // actually going out with it — see Retry.kt.
                    isIdempotent = retry.isIdempotentMethod(method) || idempotencyKey != null,
                )
            ) {
                throw ApiException(mapping.error, cause = mapping.cause)
            }

            sleeper(retry.delayFor(attempt))
            attempt++
        }
    }

    /**
     * One send, with the 401 dance folded in when there is an authenticator.
     *
     * A transport-level throw becomes status `0` rather than propagating, so
     * [RetryPolicy] sees one vocabulary. Cancellation is rethrown first and
     * always: absorbing it would make a cancelled scope look like a slow
     * network, which only shows up in production, under load.
     */
    @PublishedApi
    internal suspend fun sendOnce(wire: WireRequest): WireResponse = try {
        authenticator?.sendAuthenticated(transport, wire) ?: transport.send(wire)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        WireResponse(
            status = WireResponse.HTTP_NO_STATUS,
            body = e.message ?: e::class.simpleName.orEmpty(),
        )
    }

    @PublishedApi
    internal fun buildWire(
        method: String,
        path: String,
        body: String?,
        idempotencyKey: String?,
    ): WireRequest {
        val headers = buildMap {
            config.userAgent?.let { put(HEADER_USER_AGENT, it) }
            put(HEADER_ACCEPT, CONTENT_JSON)
            idempotencyKey?.let { put(HEADER_IDEMPOTENCY_KEY, it) }
            body?.let { put(HEADER_CONTENT_TYPE, CONTENT_JSON) }
        }
        return WireRequest(
            method = method.uppercase(),
            url = config.url(path),
            headers = headers,
            body = body,
        )
    }

    companion object {
        /**
         * The client-side bucket for plan section 11's per-user write limit.
         * A constant rather than the user id on purpose: the phone has one
         * user, and a bucket keyed by id would outlive a sign-out.
         */
        const val BUCKET_USER: String = "user"

        const val HEADER_ACCEPT: String = "Accept"
        const val HEADER_CONTENT_TYPE: String = "Content-Type"
        const val HEADER_USER_AGENT: String = "User-Agent"
        const val HEADER_IDEMPOTENCY_KEY: String = "Idempotency-Key"
        const val HEADER_RETRY_AFTER: String = "Retry-After"
        const val HEADER_AUTHORIZATION: String = "Authorization"

        const val CONTENT_JSON: String = "application/json"
    }
}

/**
 * Build a whole [ApiClient] from an engine.
 *
 * The engine comes from the platform module, never from here — see [ApiConfig].
 */
fun createApiClient(
    engine: HttpClientEngine,
    config: ApiConfig,
    authenticator: TokenAuthenticator? = null,
): ApiClient = ApiClient(
    config = config,
    transport = KtorTransport(createHttpClient(engine, config)),
    authenticator = authenticator,
)

/** Minimal percent-encoding, for a cursor that is opaque server-issued text. */
@PublishedApi
internal fun String.urlEncode(): String = buildString {
    for (char in this@urlEncode) {
        if (char.isLetterOrDigit() || char in "-_.~") {
            append(char)
        } else {
            append('%')
            append(char.code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * A 2xx this build cannot read.
 *
 * The status code is preserved, because the UI distinguishes "try again" from
 * "no" on it — and a 200 that will not decode is emphatically not something to
 * offer a retry button for.
 */
@PublishedApi
internal fun unreadableResponse(status: Int): ApiError = ApiError(
    detail = "The server sent something this version of the app cannot read. " +
        "Please update the app.",
    status = status,
)
