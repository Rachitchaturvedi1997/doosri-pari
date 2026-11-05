package `in`.doosripari.feature.checkin

import `in`.doosripari.data.CheckInPreferences
import `in`.doosripari.data.CheckInRepository
import `in`.doosripari.data.CheckInSnapshot
import `in`.doosripari.feature.checkin.CheckInMethod
import `in`.doosripari.feature.checkin.CheckInStage

/**
 * A repository the test drives by hand.
 *
 * The point of the interface is that the ViewModel's behaviour — what the
 * screen shows before the network answers, and what it shows when the network
 * fails — can be tested without a server. This is the other half of that.
 */
class FakeCheckInRepository(
    var todayRow: CheckInSnapshot = CheckInSnapshot(
        day = "2026-10-14",
        stage = CheckInStage.Waiting,
        enteredStageAt = null,
    ),
    var preferencesValue: CheckInPreferences? = CheckInPreferences(
        windowStart = "09:00",
        windowMinutes = 180,
        passive = false,
        shareLocation = false,
    ),
    var historyRows: List<CheckInSnapshot> = emptyList(),
) : CheckInRepository {

    /** Set to make the next call throw, which is how failures are simulated. */
    var failTodayWith: Exception? = null
    var failCheckInWith: Exception? = null

    var checkInCalls = 0
        private set

    /** The location the last check-in carried, so the test can assert it was dropped. */
    var lastLocation: Pair<Double, Double>? = null
        private set

    override suspend fun today(day: String): CheckInSnapshot {
        failTodayWith?.let { throw it }
        return todayRow
    }

    override suspend fun checkIn(
        method: CheckInMethod,
        location: Pair<Double, Double>?,
    ): CheckInSnapshot {
        checkInCalls++
        lastLocation = location
        failCheckInWith?.let { throw it }
        return todayRow.copy(stage = CheckInStage.CheckedIn, method = method)
    }

    override suspend fun preferences(): CheckInPreferences =
        preferencesValue ?: error("not configured")

    override suspend fun history(limit: Int): List<CheckInSnapshot> = historyRows
}
