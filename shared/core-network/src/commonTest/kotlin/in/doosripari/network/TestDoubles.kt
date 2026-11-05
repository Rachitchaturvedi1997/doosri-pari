package `in`.doosripari.network

/**
 * Test doubles shared by this module's tests.
 *
 * Everything here exists because `core-network` depends on `ktor-client-core`,
 * `ktor-client-content-negotiation` and kotlinx-serialization — and **not** on
 * `ktor-client-mock`, which the version catalog has but this module's build
 * file does not pull in. Rather than edit a build file I was given fixed, the
 * module's HTTP seam is [Transport], and a fake one is an object literal.
 *
 * That is not a workaround for the tests; it is why they are fast. The retry
 * loop, the single-flight refresh, the 429 back-off and the error mapping are
 * all worth testing and none of them needs a socket.
 */

/**
 * Answers a queued script of responses, then [fallback].
 *
 * Records every request so a test can assert on what actually went out — how
 * many times, and with which headers.
 */
class FakeTransport(vararg script: WireResponse) : Transport {

    private val script: List<WireResponse> = script.toList()

    /** Every request that was sent, in order. */
    val sent = mutableListOf<WireRequest>()

    /** The answer once the script runs out. */
    var fallback: WireResponse = WireResponse(status = 200, body = "{}")

    private var index = 0

    /** How many scripted answers are still unused. */
    val remaining: Int get() = script.size - index

    override suspend fun send(request: WireRequest): WireResponse {
        sent += request
        val response = if (index < script.size) script[index] else fallback
        index++
        return response
    }

    /** The bearer header of the nth request, or null if it carried none. */
    fun bearerOf(n: Int): String? = sent.getOrNull(n)
        ?.headers
        ?.entries
        ?.firstOrNull { it.key == ApiClient.HEADER_AUTHORIZATION }
        ?.value
}

/** A transport that always answers the same way. */
fun alwaysRespond(
    status: Int,
    body: String = "",
    headers: Map<String, String> = emptyMap(),
): Transport = FakeTransport().apply {
    fallback = WireResponse(status = status, body = body, headers = headers)
}

/** Plan section 11's error body, built exactly as the server would send it. */
internal fun errorBody(detail: String, fields: List<Pair<String, String>> = emptyList()): String {
    val errors = fields.joinToString(",") { """{"field":"${it.first}","msg":"${it.second}"}""" }
    return """{"detail":"$detail","errors":[$errors]}"""
}
