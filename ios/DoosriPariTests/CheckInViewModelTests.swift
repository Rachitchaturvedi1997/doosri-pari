// UNTESTED — never compiled. See ios/README.md.

import XCTest
@testable import DoosriPari

/// The Kotlin `CheckInViewModel` has a test file; this is its counterpart.
/// The Android tests are the specification, and the two should be read
/// together: a behaviour that exists on one platform and not the other is a
/// bug, because the user learns the app on one and meets the other on the other.
@MainActor
final class CheckInViewModelTests: XCTestCase {

    private var service: FakeCheckInService!
    private var model: CheckInViewModel!

    override func setUp() {
        super.setUp()
        service = FakeCheckInService()
        model = CheckInViewModel(service: service)
    }

    override func tearDown() {
        model = nil
        service = nil
        super.tearDown()
    }

    // MARK: - Loading

    func testLoadingFillsInTodayTheHistoryAndTheSettings() async {
        service.todayResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .waiting)
        )
        service.historyResult = .success([
            CheckInSnapshot(day: "2026-10-13", stage: .checkedIn, checkedAt: 1),
            CheckInSnapshot(day: "2026-10-12", stage: .guardiansAlerted),
        ])
        service.preferencesResult = .success(
            CheckInPreferences(windowStart: "09:00", windowMins: 180, passive: false,
                               shareLocation: true, pausedUntil: nil)
        )

        await model.load()

        XCTAssertEqual(model.snapshot?.day, "2026-10-14")
        XCTAssertEqual(model.history.count, 2)
        XCTAssertEqual(model.preferences?.windowMins, 180)
        XCTAssertFalse(model.loading)
        XCTAssertNil(model.error)
    }

    /// The paused note on the screen is driven by the settings, so a load that
    /// skipped them left a branch of the view that could never be reached.
    func testThePausedNoteHasTheSettingsItNeedsToAppear() async {
        service.preferencesResult = .success(
            CheckInPreferences(windowStart: "09:00", windowMins: 180, passive: false,
                               shareLocation: false, pausedUntil: 1_792_000_000)
        )

        await model.load()

        XCTAssertEqual(model.preferences?.isPaused, true)
    }

    func testHistoryIsFourteenDays() async {
        await model.load()
        XCTAssertEqual(service.requestedHistoryDays, [14])
    }

    // MARK: - Failure while loading

    func testALostConnectionWhileLoadingIsReportedAsOffline() async {
        service.todayResult = .failure(.offline)

        await model.load()

        XCTAssertEqual(model.error, .offline)
        XCTAssertFalse(model.loading)
        XCTAssertNil(model.snapshot)
    }

    func testAServerErrorWhileLoadingIsReportedAsAFailure() async {
        service.todayResult = .failure(.server(status: 500))

        await model.load()

        XCTAssertEqual(model.error, .failed)
    }

    // MARK: - The big green button

    /// Waiting for a round trip to acknowledge a tap on a 96pt button feels
    /// broken even when it is only a second, so the tick appears immediately.
    ///
    /// The service's response is held open, because otherwise the awaited call
    /// returns with the real answer already in place and this test would pass
    /// whether or not the optimistic update existed.
    func testTheTickAppearsBeforeTheServerHasAnswered() async {
        await model.load()
        XCTAssertFalse(model.isDone)

        service.isCheckInHeld = true
        service.checkInResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .checkedIn, checkedAt: 1_792_000_120)
        )

        async let tap: Void = model.checkIn()
        // The call is now parked inside the service, on its way out to a
        // network that has not answered.
        while service.checkInCalls == 0 { await Task.yield() }

        XCTAssertTrue(model.isDone, "the tick must not wait for the round trip")
        XCTAssertTrue(model.sending)

        service.releaseCheckIn()
        await tap

        XCTAssertFalse(model.sending)
        XCTAssertEqual(model.snapshot?.checkedAt, 1_792_000_120, "the server's own time wins")
        XCTAssertNil(model.error)
    }

    /// The button is disabled while a check-in is in flight, so a second tap
    /// cannot send a second one.
    func testTheButtonCannotBePressedTwiceWhileTheFirstIsInFlight() async {
        await model.load()
        service.isCheckInHeld = true

        async let first: Void = model.checkIn()
        while service.checkInCalls == 0 { await Task.yield() }

        await model.checkIn()
        XCTAssertEqual(service.checkInCalls, 1)

        service.releaseCheckIn()
        await first
    }

    func testASuccessfulCheckInKeepsTheServersOwnAnswer() async {
        await model.load()
        service.checkInResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .checkedIn, checkedAt: 1_792_000_120)
        )

        await model.checkIn()

        XCTAssertEqual(model.snapshot?.checkedAt, 1_792_000_120)
    }

    /// Showing a tick that never reached the server is the one failure this
    /// screen must not have: the family is not told, and the user believes
    /// they are.
    func testAFailedCheckInRollsTheTickBack() async {
        await model.load()
        let before = model.snapshot
        service.checkInResult = .failure(.offline)

        await model.checkIn()

        XCTAssertFalse(model.isDone, "a tick that did not reach the server is a lie")
        XCTAssertEqual(model.snapshot?.day, before?.day)
        XCTAssertEqual(model.snapshot?.stage, .waiting)
        XCTAssertEqual(model.error, .offline)
    }

    func testAnAlreadyCheckedInDayCannotBeCheckedInTwice() async {
        service.todayResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .checkedIn, checkedAt: 1)
        )
        await model.load()

        await model.checkIn()

        XCTAssertEqual(service.checkInCalls, 0)
    }

    // MARK: - What "done" means

    /// A day somebody else closed out by reaching the door is not something
    /// the user did. They must still be able to say they are fine themselves,
    /// and the server must still be told.
    func testADayResolvedBySomebodyElseIsNotYetDone() async {
        service.todayResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .resolved, resolvedAt: 1)
        )

        await model.load()

        XCTAssertFalse(model.isDone)
        XCTAssertEqual(service.checkInCalls, 0)

        await model.checkIn()
        XCTAssertEqual(service.checkInCalls, 1)
    }

    func testAnAlertedDayReadsAsEscalated() async {
        service.todayResult = .success(
            CheckInSnapshot(day: "2026-10-14", stage: .helpersAlerted)
        )

        await model.load()

        XCTAssertTrue(model.isEscalated)
        XCTAssertFalse(model.isDone)
    }
}
