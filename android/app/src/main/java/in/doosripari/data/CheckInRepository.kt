package `in`.doosripari.data

import `in`.doosripari.feature.checkin.CheckInMethod
import `in`.doosripari.feature.checkin.CheckInStage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One day of a user's check-in, as the app sees it.
 *
 * The stage is the shared enum, not a string, so no screen in the app can
 * invent a stage the state machine does not have.
 */
data class CheckInSnapshot(
    val day: String,
    val stage: CheckInStage,
    val enteredStageAt: Instant?,
    val checkedAt: Instant? = null,
    val method: CheckInMethod? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    val isDone: Boolean get() = stage == CheckInStage.CheckedIn

    /** True once somebody has been told this person may be unwell. */
    val isEscalated: Boolean
        get() = stage == CheckInStage.GuardiansAlerted ||
            stage == CheckInStage.HelpersAlerted
}

/** The check-in window and privacy choices, as stored server-side. */
data class CheckInPreferences(
    val windowStart: String,
    val windowMinutes: Int,
    val passive: Boolean,
    val shareLocation: Boolean,
    val pausedUntil: Instant? = null,
) {
    val isPaused: Boolean get() = pausedUntil != null
}

// ------------------------------------------------------------------ wire DTOs --
// These mirror the JSON the FastAPI backend returns. They are kept apart from
// the types above so a change in the server's field names is a compile error in
// one small file rather than a silent behaviour change across the UI.

@Serializable
internal data class CheckInDto(
    val day: String,
    val stage: String,
    @SerialName("stage_entered_at") val stageEnteredAt: Double? = null,
    @SerialName("checked_at") val checkedAt: Double? = null,
    val method: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
internal data class CheckInBody(
    val method: String,
    val lat: Double? = null,
    val lng: Double? = null,
)

/**
 * What the app needs from the check-in backend. An interface rather than a
 * concrete class so the ViewModel can be tested against a fake, and so the
 * offline queue in a later phase can sit behind it without the screen knowing.
 */
interface CheckInRepository {
    /**
     * Today's row, created in Waiting if the server has not seen today yet.
     *
     * [LocalDate.now] is passed in rather than read here so tests can pin the
     * day; a check-in window is a *local* day and a UTC default would open the
     * window at the wrong time for anyone east or west of Greenwich.
     */
    suspend fun today(day: String): CheckInSnapshot

    /** Record that the user is fine. */
    suspend fun checkIn(
        method: CheckInMethod,
        location: Pair<Double, Double>? = null,
    ): CheckInSnapshot

    suspend fun preferences(): CheckInPreferences

    /** Newest first. */
    suspend fun history(limit: Int = 14): List<CheckInSnapshot>
}
