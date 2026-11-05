// UNTESTED — never compiled. See ios/README.md.

import Foundation

/// The shared escalation contract, read from the same file the Python side
/// generates and `python/tests/test_contract.py` pins.
///
/// This is what stops the iOS copy of the escalation names from being "kept in
/// step by hand", which is what the rest of this target used to rely on. There
/// is no Swift implementation of the state machine to test — deliberately,
/// because the machine lives in Kotlin and on the server, and both are tested.
/// What Swift *does* have to agree with is the vocabulary: the stage names on
/// the wire, which stages are terminal, and the shape of a missed morning.
struct EscalationContract: Decodable {
    struct Window: Decodable {
        let defaultStartLocal: String
        let defaultMinutes: Int
        let maxMinutes: Int
    }

    struct Timings: Decodable {
        let remindAfter: Int
        let ivrRetryGap: Int
        let ivrAttempts: Int
        let helperEscalationAfter: Int
    }

    struct Roster: Decodable {
        let maxGuardians: Int
        let maxHelpers: Int
        let pauseMinDays: Int
        let pauseMaxDays: Int
    }

    struct Step: Decodable {
        let atMinutes: Int
        let stage: String
        let action: String
        let note: String
    }

    let stages: [String]
    let terminalStages: [String]
    let actions: [String]
    let methods: [String]
    let window: Window
    let timingsSeconds: Timings
    let roster: Roster
    let missedMorning: [Step]
    let terminalNote: String

    /// The steps that place an automated call, in order.
    var callSteps: [Step] {
        missedMorning.filter { $0.action == "PlaceIvrCall" }
    }

    func step(_ action: String) -> Step? {
        missedMorning.first { $0.action == action }
    }
}

enum ContractError: Error {
    case notFound([String])
    case unreadable(Error)
}

/// Loads `shared/contract/escalation-contract.json`.
///
/// Two routes on purpose. The file is a resource of this test target, which is
/// how it is found under Xcode. Walking up from this source file is the
/// fallback, so the same tests also run from a plain
/// `swift test` or a command-line `xcodebuild` where resources may not have
/// been copied — and so the file is never silently missing.
enum ContractLoader {
    static func load() throws -> EscalationContract {
        let file = try locate()
        do {
            return try JSONDecoder().decode(EscalationContract.self, from: Data(contentsOf: file))
        } catch {
            throw ContractError.unreadable(error)
        }
    }

    static func locate() throws -> URL {
        if let bundled = Bundle(for: BundleToken.self)
            .url(forResource: "escalation-contract", withExtension: "json")
        {
            return bundled
        }

        var tried: [String] = []
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        // DoosriPariTests -> ios -> doosri-pari, plus a little slack.
        for _ in 0..<5 {
            let candidate = dir.appendingPathComponent("shared/contract/escalation-contract.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            tried.append(candidate.path)
            dir.deleteLastPathComponent()
        }
        throw ContractError.notFound(tried)
    }
}

/// `Bundle(for:)` needs a class, not a struct.
private final class BundleToken {}
