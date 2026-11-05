// UNTESTED — never compiled. See ios/README.md.

import XCTest
@testable import DoosriPari

/// Decoding the server's actual payloads.
///
/// Every JSON literal below is copied from what `python/features/checkin.py`
/// emits. That is the point: this file is where the iOS app's idea of the wire
/// format is checked, and the failure it was written for already happened once.
/// The models used camelCase property names with no key mapping, so
/// `checked_at`, `window_start`, `window_mins`, `share_location` and
/// `paused_until` all failed to decode and every single response threw
/// `.decoding`. Nothing else in the app could work, and no amount of reading
/// the Swift would have made it obvious.
final class CheckInModelsTests: XCTestCase {

    // MARK: - The keys the server actually sends

    /// Verbatim shape of `view()` in python/features/checkin.py.
    private let dayJSON = """
    {
      "day": "2026-10-14",
      "stage": "GuardiansAlerted",
      "checked_at": null,
      "method": null,
      "resolved_at": null,
      "events": [{"at": 1792000000.0, "stage": "GuardiansAlerted", "action": "AlertGuardians"}]
    }
    """

    /// Verbatim shape of `settings_view()`.
    private let settingsJSON = """
    {
      "window_start": "09:00",
      "window_mins": 180,
      "passive": false,
      "share_location": true,
      "paused_until": null
    }
    """

    private func decoder() -> JSONDecoder { JSONDecoder() }

    // MARK: - CheckInSnapshot

    func testASnakeCasedDayDecodesIntoItsCamelCasedProperties() {
        let snapshot = try decoder().decode(CheckInSnapshot.self, from: Data(dayJSON.utf8))

        XCTAssertEqual(snapshot.day, "2026-10-14")
        XCTAssertEqual(snapshot.stage, .guardiansAlerted)
        XCTAssertNil(snapshot.checkedAt)
        XCTAssertNil(snapshot.method)
        XCTAssertTrue(snapshot.isEscalated)
        XCTAssertFalse(snapshot.isDone)
    }

    func testCheckedAtIsReadFromCheckedAt_notCheckedAt() {
        // The single most important key on the screen: without it the button
        // reads "Checked in" with no time on it.
        let json = """
        {"day": "2026-10-14", "stage": "CheckedIn", "checked_at": 1792000120.0, "method": "tapped"}
        """
        let snapshot = try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8))

        XCTAssertEqual(snapshot.checkedAt, 1792000120.0)
        XCTAssertEqual(snapshot.method, .tapped)
        XCTAssertTrue(snapshot.isDone)
        XCTAssertFalse(snapshot.isEscalated)
    }

    func testTheMethodFieldUsesTheLowerCaseUnderscoredWireValues() {
        for (wire, expected) in [
            ("tapped", CheckInMethod.tapped),
            ("passive", CheckInMethod.passive),
            ("ivr", CheckInMethod.ivr),
            ("in_person", CheckInMethod.inPerson),
        ] {
            let json = """
            {"day": "2026-10-14", "stage": "CheckedIn", "checked_at": 1.0, "method": "\(wire)"}
            """
            let snapshot = try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8))
            XCTAssertEqual(snapshot.method, expected, wire)
        }
    }

    // MARK: - Leniency, which is the point of the custom decoder

    func testAnUnknownStageReadsAsWaitingRatherThanThrowing() {
        // The server is newer than this build. Crashing here means a pensioner
        // cannot press the button to say they are fine.
        let json = """
        {"day": "2026-10-14", "stage": "SomeStageFromTheFuture", "checked_at": null}
        """
        let snapshot = try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snapshot.stage, .waiting)
        XCTAssertFalse(snapshot.isEscalated, "an unknown stage must never look alarming")
    }

    func testAnUnknownMethodDropsTheMethodButKeepsTheDay() {
        let json = """
        {"day": "2026-10-14", "stage": "CheckedIn", "checked_at": 1.0, "method": "carrier_pigeon"}
        """
        let snapshot = try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8))
        XCTAssertNil(snapshot.method)
        XCTAssertTrue(snapshot.isDone, "a lost label is not worth losing the day over")
    }

    func testAMissingDayIsStillAnError() {
        // Leniency on unknown *values*, never on a missing identity.
        let json = """
        {"stage": "Waiting", "checked_at": null}
        """
        XCTAssertThrowsError(try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8)))
    }

    func testAnUnreadableStageTypeThrowsRatherThanGuessing() {
        let json = """
        {"day": "2026-10-14", "stage": 7, "checked_at": null}
        """
        XCTAssertThrowsError(try decoder().decode(CheckInSnapshot.self, from: Data(json.utf8)))
    }

    // MARK: - CheckInPreferences

    func testSettingsDecodeFromTheirSnakeCasedKeys() {
        let prefs = try decoder().decode(CheckInPreferences.self, from: Data(settingsJSON.utf8))

        XCTAssertEqual(prefs.windowStart, "09:00")
        XCTAssertEqual(prefs.windowMins, 180)
        XCTAssertFalse(prefs.passive)
        XCTAssertTrue(prefs.shareLocation, "share_location did not map")
        XCTAssertFalse(prefs.isPaused)
    }

    func testAPausedUserIsRecognised() {
        let json = """
        {"window_start": "09:00", "window_mins": 180, "passive": false,
         "share_location": false, "paused_until": 1792000000.0}
        """
        let prefs = try decoder().decode(CheckInPreferences.self, from: Data(json.utf8))
        XCTAssertTrue(prefs.isPaused)
    }

    /// The window is local time, so the app needs it as minutes past midnight
    /// to show a countdown. A malformed value must read as "unknown", not as
    /// midnight, which would put a 03:30 UTC window on a screen and page a
    /// family in the middle of the night.
    func testTheWindowStartConvertsToMinutesPastLocalMidnight() {
        XCTAssertEqual(
            CheckInPreferences(windowStart: "09:00", windowMins: 180, passive: false,
                               shareLocation: false, pausedUntil: nil).windowStartMinutes,
            540
        )
    }

    func testAMalformedWindowStartReadsAsUnknownRatherThanMidnight() {
        for bad in ["", "9", "25:00", "09:60", "morning", "09:00:00"] {
            let prefs = CheckInPreferences(windowStart: bad, windowMins: 180, passive: false,
                                           shareLocation: false, pausedUntil: nil)
            XCTAssertNil(prefs.windowStartMinutes, "\(bad) should not become a time")
        }
    }
}
