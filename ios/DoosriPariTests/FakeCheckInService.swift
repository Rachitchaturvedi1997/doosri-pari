// UNTESTED — never compiled. See ios/README.md.

import Foundation
@testable import DoosriPari

/// A `CheckInService` that answers from memory and records what it was asked.
///
/// The ViewModel's whole job is deciding what the user sees between a tap and
/// the server's answer, so the interesting cases are all about failure and
/// about ordering. A fake is the only way to drive those; there is no server in
/// a unit test.
@MainActor
final class FakeCheckInService: CheckInService {

    // Set these to make a call succeed or fail.
    var todayResult: Result<CheckInSnapshot, CheckInServiceError> = .success(
        CheckInSnapshot(day: "2026-10-14", stage: .waiting)
    )
    var checkInResult: Result<CheckInSnapshot, CheckInServiceError> = .success(
        CheckInSnapshot(day: "2026-10-14", stage: .checkedIn, checkedAt: 1792000120)
    )
    var preferencesResult: Result<CheckInPreferences, CheckInServiceError> = .success(
        CheckInPreferences(windowStart: "09:00", windowMins: 180, passive: false,
                           shareLocation: false, pausedUntil: nil)
    )
    var historyResult: Result<[CheckInSnapshot], CheckInServiceError> = .success([])

    // Recorded calls, so a test can assert the app asked for the right things.
    private(set) var todayCalls = 0
    private(set) var checkInCalls = 0
    private(set) var preferencesCalls = 0
    private(set) var requestedHistoryDays: [Int] = []

    /// When true, `checkIn()` parks until `releaseCheckIn()`.
    ///
    /// Without this the "the tick appears immediately" behaviour cannot be
    /// tested at all: `await model.checkIn()` returns only once the service has
    /// answered, by which point the optimistic state and the real state look
    /// identical. Holding the response open is the only way to look at the
    /// screen in between.
    var isCheckInHeld = false
    private var heldCheckIn: CheckedContinuation<Void, Never>?

    func releaseCheckIn() {
        isCheckInHeld = false
        let continuation = heldCheckIn
        heldCheckIn = nil
        continuation?.resume()
    }

    func today() async throws -> CheckInSnapshot {
        todayCalls += 1
        return try todayResult.get()
    }

    func checkIn() async throws -> CheckInSnapshot {
        checkInCalls += 1
        if isCheckInHeld {
            await withCheckedContinuation { continuation in
                heldCheckIn = continuation
            }
        }
        return try checkInResult.get()
    }

    func preferences() async throws -> CheckInPreferences {
        preferencesCalls += 1
        return try preferencesResult.get()
    }

    func history(days: Int) async throws -> [CheckInSnapshot] {
        requestedHistoryDays.append(days)
        return try historyResult.get()
    }
}
