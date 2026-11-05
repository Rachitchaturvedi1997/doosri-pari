// UNTESTED — never compiled. See ios/README.md.

import Foundation

/// Plan section 6.3, mirrored from shared/feature-checkin/CheckIn.kt.
///
/// This is a copy, and the plan's own reason for a shared Kotlin core is to
/// avoid exactly this. It is here because the shared core is Kotlin and Swift
/// cannot call it directly. The rule that keeps this honest: **no stage logic
/// in this file.** It holds names and the terminal set, nothing else. The
/// machine lives in the shared module and on the server, and both are tested.
///
/// The wire names are not free-hand. `shared/contract/escalation-contract.json`
/// states them, `python/tests/test_contract.py` pins that file against the
/// server, and `EscalationContractTests` here pins this enum against it. A
/// rename on any one side fails a test rather than silently showing a pensioner
/// a stale screen.
enum CheckInStage: String, Codable, CaseIterable {
    case waiting = "Waiting"
    case reminded = "Reminded"
    case called = "Called"
    case guardiansAlerted = "GuardiansAlerted"
    case helpersAlerted = "HelpersAlerted"
    case checkedIn = "CheckedIn"
    case resolved = "Resolved"

    /// Terminal stages end escalation. Nothing further is dispatched.
    var isTerminal: Bool {
        self == .checkedIn || self == .resolved
    }
}

/// How a check-in was recorded. Passive signals only ever produce "checked in".
///
/// The raw values are the strings the server stores in `checkins.method`. They
/// are lower-case with an underscore, unlike the stage names, because that is
/// what `python/models.py` has always written to the column.
enum CheckInMethod: String, Codable, CaseIterable {
    case tapped = "tapped"
    case passive = "passive"
    case ivr = "ivr"
    case inPerson = "in_person"
}

/// One day of check-ins, as the server sends it.
///
/// The keys are snake_case because `python/features/checkin.py` emits
/// snake_case. They are spelled out here rather than left to
/// `keyDecodingStrategy = .convertFromSnakeCase` on purpose: an explicit list
/// breaks loudly if the server renames a field, whereas a strategy change would
/// quietly leave everything decoding as nil.
struct CheckInSnapshot: Codable, Identifiable {
    let day: String
    let stage: CheckInStage

    /// Epoch seconds. The server's `time.time()`, so a float, not an integer.
    let checkedAt: Double?
    let method: CheckInMethod?
    let stageEnteredAt: Double?
    let resolvedAt: Double?

    var id: String { day }

    /// Whether the user themselves said they are fine.
    var isDone: Bool { stage == .checkedIn }

    /// Somebody has been told this person may be unwell.
    var isEscalated: Bool {
        stage == .guardiansAlerted || stage == .helpersAlerted
    }

    /// A day somebody else closed out by reaching the door.
    var wasResolved: Bool { stage == .resolved }

    /// Writing a custom `init(from:)` suppresses Swift's memberwise initialiser,
    /// so it is declared here. The app builds optimistic snapshots with it, and
    /// the tests build the fixtures.
    init(
        day: String,
        stage: CheckInStage,
        checkedAt: Double? = nil,
        method: CheckInMethod? = nil,
        stageEnteredAt: Double? = nil,
        resolvedAt: Double? = nil
    ) {
        self.day = day
        self.stage = stage
        self.checkedAt = checkedAt
        self.method = method
        self.stageEnteredAt = stageEnteredAt
        self.resolvedAt = resolvedAt
    }

    /// Decoding is lenient on purpose, and lenient in two different places.
    ///
    /// An unknown **stage** means the server is newer than the app, and
    /// crashing while somebody is trying to say they are fine is the worst
    /// possible outcome. Unknown stages read as `.waiting`, the least alarming
    /// one, which the server's own job will move forward on its own.
    ///
    /// An unknown **method** is only ever a label on a day that already
    /// happened, so it reads as nil. Dropping it would throw away a day the
    /// user can otherwise see, which is worse than losing a word.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        day = try c.decode(String.self, forKey: .day)
        let raw = try c.decode(String.self, forKey: .stage)
        stage = CheckInStage(rawValue: raw) ?? .waiting
        checkedAt = try c.decodeIfPresent(Double.self, forKey: .checkedAt)
        stageEnteredAt = try c.decodeIfPresent(Double.self, forKey: .stageEnteredAt)
        resolvedAt = try c.decodeIfPresent(Double.self, forKey: .resolvedAt)

        let rawMethod = try c.decodeIfPresent(String.self, forKey: .method)
        method = rawMethod.flatMap { CheckInMethod(rawValue: $0) }
    }

    enum CodingKeys: String, CodingKey {
        case day
        case stage
        case checkedAt = "checked_at"
        case method
        case stageEnteredAt = "stage_entered_at"
        case resolvedAt = "resolved_at"
    }
}

/// `checkin_settings` as the server sends it.
struct CheckInPreferences: Codable, Equatable {
    /// Local time, "HH:mm". A local-time window: 09:00 in Uttar Pradesh is
    /// 03:30 UTC, and getting that wrong pages people in the middle of the night.
    let windowStart: String
    let windowMins: Int
    let passive: Bool
    let shareLocation: Bool
    let pausedUntil: Double?

    var isPaused: Bool { pausedUntil != nil }

    /// The window as minutes past local midnight, for the countdown. Nil if the
    /// server sent something that is not "HH:mm", which the app treats as "we
    /// do not know" rather than guessing a start time.
    var windowStartMinutes: Int? {
        let parts = windowStart.split(separator: ":")
        guard parts.count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]),
              (0...23).contains(h), (0...59).contains(m)
        else { return nil }
        return h * 60 + m
    }

    enum CodingKeys: String, CodingKey {
        case windowStart = "window_start"
        case windowMins = "window_mins"
        case passive
        case shareLocation = "share_location"
        case pausedUntil = "paused_until"
    }
}
