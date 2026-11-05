// UNTESTED — never compiled. See ios/README.md.

import XCTest
@testable import DoosriPari

/// The iOS half of the parity check.
///
/// `python/tests/test_parity.py` pins the Kotlin source against the server.
/// Neither of those can reach Swift, so this pins the Swift copy against
/// `shared/contract/escalation-contract.json` — the same file, written by
/// `python/tools/gen_contract.py` and checked by `test_contract.py`.
///
/// A stage renamed in Kotlin and Python but not here fails on the first test.
/// A stage renamed everywhere but with the contract file left stale fails on
/// the Python side. There is no path where the three disagree silently, which
/// is the whole point: these names are what every installed build of the app
/// has already seen, and a rename is a wire break, not a refactor.
final class EscalationContractTests: XCTestCase {

    private var contract: EscalationContract!

    override func setUpWithError() throws {
        try super.setUpWithError()
        contract = try ContractLoader.load()
    }

    // MARK: - Vocabulary

    func testTheStageNamesAreExactlyTheOnesTheContractDeclares() {
        XCTAssertEqual(
            CheckInStage.allCases.map(\.rawValue),
            contract.stages,
            "The server sends these strings. Renaming one here breaks every installed build."
        )
    }

    func testTheTerminalSetIsTheOnesTheContractDeclares() {
        let terminal = Set(CheckInStage.allCases.filter(\.isTerminal).map(\.rawValue))
        XCTAssertEqual(terminal, Set(contract.terminalStages))
    }

    func testOnlyCheckedInAndResolvedAreTerminal() {
        XCTAssertEqual(
            CheckInStage.allCases.filter(\.isTerminal).map(\.rawValue).sorted(),
            ["CheckedIn", "Resolved"],
            "A third terminal stage would stop the server escalating, silently."
        )
    }

    func testTheCheckInMethodWireValuesMatchTheContract() {
        // CheckInMethod is a strict raw-value decoder, so unlike the stage enum
        // a mismatch here throws on every response rather than degrading.
        XCTAssertEqual(
            Set(CheckInMethod.allCases.map(\.rawValue)),
            Set(contract.methods)
        )
    }

    func testEveryStageNamedInTheContractHasASwiftCase() {
        for name in contract.stages {
            XCTAssertNotNil(CheckInStage(rawValue: name), "no Swift case for \(name)")
        }
    }

    // MARK: - The window

    func testTheDefaultWindowIsThreeHoursAndOpensInTheMorning() {
        XCTAssertEqual(contract.window.defaultMinutes, 180)
        XCTAssertEqual(contract.window.defaultStartLocal, "09:00")
    }

    // MARK: - The missed morning

    /// The bug the Python parity test caught during development, asserted from
    /// the third side. Guardians must not be told before the second automated
    /// call has had the gap the plan gives it.
    func testGuardiansAreNotToldBeforeTheSecondCallHasHadItsGap() {
        let calls = contract.callSteps
        XCTAssertEqual(
            calls.count,
            contract.timingsSeconds.ivrAttempts,
            "The plan places two calls before anybody is told."
        )

        let guardians = contract.step("AlertGuardians")
        XCTAssertNotNil(guardians)
        guard let last = calls.last, let guardians else { return }

        let gapMinutes = guardians.atMinutes - last.atMinutes
        XCTAssertEqual(gapMinutes * 60, contract.timingsSeconds.ivrRetryGap)
        XCTAssertGreaterThan(gapMinutes, 0)
    }

    func testTheFirstCallLandsWhenTheWindowCloses() {
        XCTAssertEqual(contract.callSteps.first?.atMinutes, contract.window.defaultMinutes)
    }

    func testTheReminderIsAnHourAfterTheWindowOpens() {
        XCTAssertEqual(contract.step("RemindUser")?.atMinutes, 60)
    }

    func testHelpersWaitTheFullHourAfterGuardiansWereTold() {
        guard let helpers = contract.step("AlertHelpers"),
              let guardians = contract.step("AlertGuardians")
        else { return XCTFail("the timeline is missing a step") }

        XCTAssertEqual(
            (helpers.atMinutes - guardians.atMinutes) * 60,
            contract.timingsSeconds.helperEscalationAfter
        )
    }

    func testTheTimelineOnlyMovesForward() {
        let offsets = contract.missedMorning.map(\.atMinutes)
        XCTAssertEqual(offsets, offsets.sorted())
        XCTAssertEqual(offsets.count, Set(offsets).count, "two steps at the same minute is ambiguous")
    }

    func testEveryStageInTheTimelineIsAStageTheAppKnows() {
        for step in contract.missedMorning {
            XCTAssertNotNil(CheckInStage(rawValue: step.stage), "unknown stage \(step.stage)")
        }
    }

    func testNoTerminalStageAppearsInAMissedMorning() {
        for step in contract.missedMorning {
            let stage = CheckInStage(rawValue: step.stage)
            XCTAssertEqual(stage?.isTerminal, false, "\(step.stage) is terminal and cannot be in a timeline")
        }
    }

    // MARK: - Why the app shows what it shows

    /// A day the user closed themselves is the only thing the green tick means.
    func testOnlyACheckInByTheUserReadsAsDone() {
        XCTAssertTrue(CheckInStage.checkedIn.isTerminal)
        XCTAssertFalse(CheckInStage.resolved.isTerminal)
    }

    /// A day somebody came looking has to look alarming, or the user never
    /// learns that the system worked.
    func testTheTwoAlertedStagesAreTheOnlyOnesThatReadAsEscalated() {
        for stage in CheckInStage.allCases {
            let snapshot = CheckInSnapshot(day: "2026-10-14", stage: stage)
            let expected = stage == .guardiansAlerted || stage == .helpersAlerted
            XCTAssertEqual(snapshot.isEscalated, expected, "\(stage.rawValue)")
        }
    }
}
