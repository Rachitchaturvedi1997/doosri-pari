package `in`.doosripari.network

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.FieldError
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one error shape. Plan section 11 fixes it as
 * `{"detail": "...", "errors": [{"field", "msg"}]}`, which exists so that a
 * client never has to guess which of three shapes it got, and so that a form
 * can say which field is wrong rather than "something went wrong".
 *
 * ## What "parse it strictly" means here
 *
 * The DTOs below are `internal` and exist only in this file. They are NOT the
 * types a feature sees. A feature gets an [ApiError] from `core-model`, which
 * is a plain `val`-only data class with no serialization on it — the same rule
 * `core-model`'s own KDoc states and the reason `HttpCheckInRepository` keeps
 * its DTOs separate from its snapshots.
 *
 * Strictness that matters:
 * - A body that is not the plan's shape is NOT a crash and NOT a lie. It
 *   becomes an [ApiError] with the HTTP status preserved and a message the
 *   user can be shown, because a reverse proxy's HTML 502 page must not reach a
 *   68-year-old's screen as raw markup.
 * - The HTTP status always wins over the body. A 500 that happens to carry
 *   `"detail": "ok"` is still a 500 and still retryable.
 * - [HTTP_TOO_MANY_REQUESTS] is retryable in the ordinary sense and is
 *   explicitly NOT retried immediately. See [RateLimitGate].
 */

/**
 * The shared decoder.
 *
 * Exactly the configuration `createHttpClient` installs — `ignoreUnknownKeys`
 * and nothing else. In particular there is **no naming strategy**: every field
 * below spells its own `@SerialName`, so a server rename surfaces here as a
 * missing field rather than as a field that silently becomes null.
 *
 * `explicitNulls` is left at its default so an absent `errors` key and an
 * explicit `"errors": null` are the same thing — plan section 11 shows `errors`
 * as always present but the arrays in the endpoint table make it optional in
 * practice, and a 4xx from a validation library may well omit it.
 */
internal val WIRE_JSON: Json = Json {
    ignoreUnknownKeys = true
}

// ------------------------------------------------------------------- the DTO --

/**
 * Plan section 11's error body, verbatim.
 *
 * `internal` on purpose. Nothing outside this file may name it, which is how
 * "wire DTOs are a separate concern" is enforced rather than merely intended.
 */
@Serializable
internal data class ApiErrorBody(
    @SerialName("detail") val detail: String,
    @SerialName("errors") val errors: List<FieldErrorBody>? = null,
)

/** One `{field, msg}` pair. `msg`, not `message` — plan section 11 says `msg`. */
@Serializable
internal data class FieldErrorBody(
    @SerialName("field") val field: String,
    @SerialName("msg") val msg: String,
)

/**
 * A parsed failure, and the three facts the policy layers need that a bare
 * [ApiError] cannot carry.
 *
 * [requiresAuthRefresh] is separated from [error].isRetryable because a 401 is
 * not retryable in the retry sense — re-sending the same dead token gets the
 * same 401 — it is *recoverable*, once, by refreshing. Conflating the two is
 * how a client ends up in a loop against `/auth/refresh` itself.
 */
data class ErrorMapping(
    val error: ApiError,
    /** True for a 401 (or a 403 on the token-only endpoints). */
    val requiresAuthRefresh: Boolean,
    /** True for a 429, which must be surfaced and not immediately retried. */
    val isRateLimited: Boolean,
    /** From the `Retry-After` header, when the server sent one. */
    val retryAfterSeconds: Long? = null,
    val cause: Throwable? = null,
) {
    /**
     * Whether the policy in [Retry.kt] may re-send this request now.
     *
     * Delegated rather than reimplemented: `core-model` already defines
     * retryable as `0 / 429 / 5xx`, and a second definition here is a second
     * thing to get wrong.
     */
    val isRetryable: Boolean get() = error.isRetryable

    /** The message to show a 60+ user. Falls back to the plan's wording. */
    val displayMessage: String get() = error.displayMessage

    /** The field the user can actually correct, if the error names one. */
    val firstFieldError: FieldError? get() = error.fieldErrors.firstOrNull()
}

/**
 * Thrown by [ApiClient] when a call cannot be completed.
 *
 * Exists so a feature layer can write `catch (e: ApiException) { LoadState.Failed(e.error) }`
 * and get the plan's error type back, rather than catching `Exception` and
 * losing the distinction between "the server said no" and "we never got there".
 */
class ApiException(
    val error: ApiError,
    cause: Throwable? = null,
) : Exception(error.displayMessage, cause)

// ---------------------------------------------------------------- the mapping --

/** Plan section 11's shape, and the statuses the policy layers care about. */
const val HTTP_UNAUTHORIZED: Int = 401
const val HTTP_FORBIDDEN: Int = 403
const val HTTP_TOO_MANY_REQUESTS: Int = 429
const val HTTP_BAD_REQUEST: Int = 400
const val HTTP_UNPROCESSABLE: Int = 422

/** Shown when the body is not the plan's shape and no better wording exists. */
internal const val FALLBACK_DETAIL: String =
    "Something went wrong. Please try again."

/** What a proxy's HTML error page becomes, rather than raw markup on a screen. */
internal const val UNREADABLE_DETAIL: String =
    "We could not read the server's reply. Please try again."

/**
 * Parse a response into an [ErrorMapping], or return null when it succeeded.
 *
 * Null rather than a sealed "Ok" type because the success path is the hot one
 * and a null check reads better at every call site in [ApiClient].
 */
fun errorFor(response: WireResponse): ErrorMapping? {
    if (response.isSuccess) return null
    return parseError(response.status, response.headers, response.body, cause = null)
}

/**
 * Parse a status and body into an [ApiError]. Split out from [errorFor] so the
 * status-only cases (a transport failure, a 401 the server gave no body for)
 * do not have to invent a JSON document to go with them.
 */
fun parseError(
    status: Int,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    cause: Throwable? = null,
): ErrorMapping {
    val parsed = body?.takeIf { it.isNotBlank() }?.let { decodeErrorBody(it) }

    // The status always wins. A body that claims success inside a 500 is a
    // server bug, and treating the bug as the truth is how "check-in saved"
    // appears for a check-in that was never stored.
    val detail = parsed?.detail?.takeIf { it.isNotBlank() } ?: fallbackDetailFor(status)
    val fieldErrors = parsed?.errors.orEmpty().map { FieldError(field = it.field, message = it.msg) }

    val error = ApiError(
        detail = detail,
        fieldErrors = fieldErrors,
        status = status,
    )
    return ErrorMapping(
        error = error,
        requiresAuthRefresh = status == HTTP_UNAUTHORIZED,
        isRateLimited = status == HTTP_TOO_MANY_REQUESTS,
        retryAfterSeconds = retryAfterSeconds(headers),
        cause = cause,
    )
}

/**
 * Decode plan section 11's shape, or null if the body is not it.
 *
 * Returns null rather than throwing: an unreadable error body is a *less*
 * informative error, not a worse failure, and turning it into an exception
 * here would discard the status code — the one thing the policy layers need.
 */
internal fun decodeErrorBody(body: String): ApiErrorBody? = try {
    WIRE_JSON.decodeFromString(ApiErrorBody.serializer(), body)
} catch (_: Exception) {
    // Includes SerializationException and IllegalArgumentException. Both mean
    // the same thing here: this is not the shape plan section 11 promised.
    null
}

/**
 * `Retry-After`, in seconds.
 *
 * Only the delta-seconds form of RFC 9110 is read. The HTTP-date form is
 * ignored rather than parsed: a server sending it is rare enough that the
 * back-off in [RateLimitGate] is a fine answer for it, and a date parser that
 * is wrong by a timezone is worse than no parser.
 */
internal fun retryAfterSeconds(headers: Map<String, String>): Long? =
    headers.entries
        .firstOrNull { it.key.equals(ApiClient.HEADER_RETRY_AFTER, ignoreCase = true) }
        ?.value
        ?.trim
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }

/**
 * Wording for a failure with no usable body.
 *
 * These are the strings a user is most likely to see — a dropped connection, a
 * rate limit, an expired session — so each one says what to do next rather than
 * naming the failure. A 60+ user who is told "HTTP 502" learns nothing and is
 * more likely to give up on the app than on the pension portal.
 */
internal fun fallbackDetailFor(status: Int): String = when (status) {
    HTTP_UNAUTHORIZED ->
        "Your session has ended. Please sign in again."
    HTTP_FORBIDDEN ->
        "This account is not allowed to do that."
    HTTP_TOO_MANY_REQUESTS ->
        "Too many requests. Please wait a moment and try again."
    HTTP_BAD_REQUEST, HTTP_UNPROCESSABLE ->
        "Some of the details were not right. Please check and try again."
    in 500..599 ->
        "Our side is having trouble. Please try again in a moment."
    WireResponse.HTTP_NO_STATUS ->
        "No connection. Please check your network and try again."
    else -> FALLBACK_DETAIL
}
