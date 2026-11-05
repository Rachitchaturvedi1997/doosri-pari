package `in`.doosripari.data

import `in`.doosripari.BuildConfig
import `in`.doosripari.feature.checkin.CheckInMethod
import `in`.doosripari.feature.checkin.CheckInStage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talks to the FastAPI backend.
 *
 * Timeouts are short on purpose. This is the one screen a user opens when they
 * are worried about somebody, and a request that spins for thirty seconds
 * before failing teaches them the app does not work. Failing fast lets the
 * screen say so and offer another go.
 *
 * NOTE: not exercised by a test. The tests cover [CheckInRepository]'s contract
 * through a fake; this class needs a running server and a device, so it is
 * unverified until then. See the README.
 */
class HttpCheckInRepository(
    private val client: HttpClient,
    private val userId: String,
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) : CheckInRepository {

    override suspend fun today(day: String): CheckInSnapshot =
        get<CheckInDto>("/checkin/today").toSnapshot()

    override suspend fun checkIn(
        method: CheckInMethod,
        location: Pair<Double, Double>?,
    ): CheckInSnapshot = post<CheckInDto>("/checkin") {
        setBody(
            CheckInBody(
                method = method.name,
                lat = location?.first,
                lng = location?.second,
            )
        )
    }.toSnapshot()

    override suspend fun preferences(): CheckInPreferences =
        get<PreferencesDto>("/checkin/settings").let { dto ->
            CheckInPreferences(
                windowStart = dto.windowStart,
                windowMinutes = dto.windowMins,
                passive = dto.passive,
                shareLocation = dto.shareLocation,
                pausedUntil = dto.pausedUntil?.let { Instant.fromEpochSeconds(it.toLong()) },
            )
        }

    override suspend fun history(limit: Int): List<CheckInSnapshot> =
        get<List<CheckInDto>>("/checkin/history?days=$limit").map { it.toSnapshot() }

    // ----------------------------------------------------------------- plumbing --

    private suspend inline fun <reified T> get(path: String): T =
        client.get("$baseUrl$path") { auth() }.body()

    private suspend inline fun <reified T> post(
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): T = client.post("$baseUrl$path") {
        auth()
        contentType(ContentType.Application.Json)
        block()
    }.body()

    /**
     * Sends the user id the backend's development auth shim expects.
     *
     * This is a placeholder for the OTP auth the plan puts in phase 1. Until
     * that exists the app cannot prove who it is, and a release built this way
     * could read and close other people's alerts by guessing an id. Do not ship
     * a release with this in it.
     */
    private fun HttpRequestBuilder.auth() {
        header("X-User-Id", userId)
    }

    companion object {
        fun create(userId: String): CheckInRepository = HttpCheckInRepository(
            userId = userId,
            client = HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 8_000
                    connectTimeoutMillis = 5_000
                }
            },
        )
    }
}

/**
 * An unrecognised stage means the server is newer than this build. Throwing
 * would strand the user on a spinner while they are trying to say they are
 * fine, so the row is read as Waiting — the least alarming state, and one the
 * escalation job will move forward on its own within the hour.
 */
private fun CheckInDto.toSnapshot(): CheckInSnapshot = CheckInSnapshot(
    day = day,
    stage = CheckInStage.fromWire(stage) ?: CheckInStage.Waiting,
    enteredStageAt = stageEnteredAt?.let { Instant.fromEpochSeconds(it.toLong()) },
    checkedAt = checkedAt?.let { Instant.fromEpochSeconds(it.toLong()) },
    method = method?.let { CheckInMethod.fromWire(it) },
    lat = lat,
    lng = lng,
)

@Serializable
internal data class PreferencesDto(
    @SerialName("window_start") val windowStart: String,
    @SerialName("window_mins") val windowMins: Int,
    val passive: Boolean,
    @SerialName("share_location") val shareLocation: Boolean,
    @SerialName("paused_until") val pausedUntil: Double? = null,
)
