// UNTESTED — never compiled. See ios/README.md.
//
// The Kotlin CheckInViewModel is covered by tests; this one is not. The two
// should be kept behaviourally identical, and the tests there are the
// specification for this.

import Foundation

@MainActor
final class CheckInViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var snapshot: CheckInSnapshot?
    @Published private(set) var history: [CheckInSnapshot] = []
    @Published private(set) var preferences: CheckInPreferences?
    @Published private(set) var loading = true
    @Published private(set) var sending = false
    @Published private(set) var error: LoadError?

    private let service: CheckInService

    init(service: CheckInService) {
        self.service = service
    }

    /// Deliberately not the same as "the stage is terminal". A day closed by a
    /// helper reaching the user in person is `.resolved`, which is not
    /// something the user did — they should still be able to say they are fine
    /// themselves.
    var isDone: Bool { snapshot?.isDone == true }
    var isEscalated: Bool { snapshot?.isEscalated == true }

    func load() async {
        loading = true
        error = nil
        do {
            async let today = service.today()
            async let past = service.history(days: 14)
            // Preferences come along because the paused note on the screen is
            // driven by them. Without this the note could never appear.
            async let settings = service.preferences()
            snapshot = try await today
            history = try await past
            preferences = try await settings
            loading = false
        } catch let e as CheckInServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// The user pressed the big green button.
    ///
    /// The button flips to a tick immediately and rolls back if the send fails.
    /// Waiting for a round trip to acknowledge a tap on a 96pt button feels
    /// broken even when it is only a second.
    func checkIn() async {
        guard !sending, !isDone else { return }

        let before = snapshot
        sending = true
        error = nil
        snapshot = before?.copyAsCheckedIn()

        do {
            snapshot = try await service.checkIn()
            sending = false
        } catch let e as CheckInServiceError {
            sending = false
            snapshot = before
            error = e.isOffline ? .offline : .failed
        } catch {
            sending = false
            snapshot = before
            error = .failed
        }
    }
}

extension CheckInSnapshot {
    /// The optimistic state shown between the tap and the server's answer.
    func copyAsCheckedIn() -> CheckInSnapshot {
        CheckInSnapshot(
            day: day,
            stage: .checkedIn,
            checkedAt: Date().timeIntervalSince1970,
            method: .tapped
        )
    }
}

extension CheckInServiceError {
    /// Whether this is a lost connection rather than a server problem. The two
    /// get different wording but the same remedy: try again.
    var isOffline: Bool { self == .offline }
}
