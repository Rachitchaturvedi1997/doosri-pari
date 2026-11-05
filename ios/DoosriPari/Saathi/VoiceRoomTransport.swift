// UNTESTED — never compiled. See ios/README.md.
//
// ─────────────────────────────────────────────────────────────────────────────
// THE MEDIA TRANSPORT SEAM. Nothing in this package knows how audio moves.
// ─────────────────────────────────────────────────────────────────────────────
//
// Plan 6.7 says the rooms move to an SFU for scale, with loudness
// normalisation, noise suppression, the iOS audio background mode and CallKit
// for the in-room experience, and a host who can mute and remove. None of that
// is written here and none of it should be: it is the part of this feature
// that is a vendor decision rather than a product decision, and the vendor
// (LiveKit, Agora, Daily, an SFU we run) owns the audio path, the reconnect
// logic, the TURN fallback and the call statistics.
//
// What production supplies behind this protocol:
//
//   1. An SFU connection that publishes the local track and subscribes to up
//      to eight speaker tracks and a mixed listener track.
//   2. A roster stream: join, leave, role change, mute change, hand raise.
//   3. Active-speaker events. These are what `isSpeaking` is built from.
//   4. Connection state, including the *actual* transport state and not a
//      timer. `RoomConnectionState.live` must mean bytes are moving.
//   5. The raise-hand queue the host pops when granting a speaker slot.
//   6. Recording control, refused locally if any speaker has not consented —
//      the policy is `VoiceRoomSession.recordingIsUnilateral`; only the
//      transport can enforce it against the server.
//   7. `AVAudioSession` configuration, the background audio mode, and CallKit
//      reporting so the room shows as a call in Control Centre.
//
// The one rule this package enforces regardless of vendor, because a vendor
// SDK cannot be trusted to say it: **`RoomConnectionState.live` is the only
// value that may put the word "live" on screen.** `VoiceRoomViewModel` derives
// every visible indicator — the badge, the speaking rings, the counts, the
// enabled mic — from that single flag. A transport that reports `.live`
// before audio is flowing will produce a screen that says a room is live, and
// for a user with a hearing aid that is indistinguishable from their own
// hearing failing. It is the worst kind of bug in this app: silent, plausible,
// and aimed at exactly the people least able to report it.
//
// The transport is not `@MainActor`, because an SDK is not. The view model is.

import Foundation

protocol VoiceRoomTransport: AnyObject {

    /// Called once before any other method. Production opens the SFU session
    /// here and returns when the room metadata is known — **not** when audio
    /// is confirmed, which is what `connectionUpdates` is for.
    func connect(roomID: String, as role: RoomRole) async throws

    /// Idempotent, and it must be: the leave button is a 64pt target that
    /// users will press twice, and the app will also call it from
    /// `scenePhase`. A transport that throws on a second leave gets a
    /// stranded participant in a room they have left.
    func disconnect() async

    /// The local microphone. `true` mutes. A mute is a thing a user does
    /// mid-sentence, so it takes effect locally and is reported to the room.
    func setMicrophoneMuted(_ muted: Bool) async throws

    /// Plan 6.7's "raise hand". A request, not a join: the host grants the
    /// speaker slot. The screen shows the queue position so the user knows
    /// the hand went up.
    func setHandRaised(_ raised: Bool) async throws

    /// Plan 6.7: off by default. Production must refuse this call when any
    /// speaker has not consented, and the transport's own state is what the
    /// recording banner reads.
    func setRecordingEnabled(_ enabled: Bool) async throws

    /// Host-only. Plan 6.7: "the host can mute or remove". Mute is immediate;
    /// removal is a person being shown the door, so the view model puts a
    /// `DoosriConfirmAlert` in front of it.
    func muteMember(id: String) async throws
    func removeMember(id: String) async throws

    /// Whether the transport believes the room is live. Read once on connect
    /// and then driven by `connectionUpdates`.
    func currentConnectionState() async -> RoomConnectionState

    /// The roster and every change to it, as a stream.
    ///
    /// One stream rather than separate ones because they are not independent:
    /// a join and a role change and a mute can all be the same event, and
    /// splitting them would let the view apply half of one.
    func events() -> AsyncStream<VoiceRoomTransportEvent>
}

enum VoiceRoomTransportEvent {
    case connection(RoomConnectionState)
    /// The whole roster, replaced. Sent on connect and after any change that
    /// invalidates a local patch.
    case roster([VoiceRoomParticipant])
    /// One participant changed. Carries the whole participant rather than a
    /// field patch, so a missed patch cannot leave a row showing a stranger
    /// as muted.
    case participant(VoiceRoomParticipant)
    case participantLeft(id: String)
    /// The room stopped. `reason` is a sentence for the user, not a code.
    case ended(reason: String)
    case failed(message: String)
}

enum VoiceRoomTransportError: LocalizedError, Equatable {
    /// The room is at plan 6.7's eight speakers or two hundred listeners.
    case roomFull
    /// The user is not allowed to host. Section 6.7: three valid reports
    /// means no hosting for thirty days, and the app asks for the token at
    /// join time rather than failing at the moment somebody presses the button.
    case notAllowedToHost
    case microphoneDenied
    case connectionLost

    var errorDescription: String? {
        switch self {
        case .roomFull:
            return SaathiText.t(
                "saathi_room_full",
                "This room is full. Try another one."
            )
        case .notAllowedToHost:
            return SaathiText.t(
                "saathi_room_no_host",
                "You cannot start a room right now."
            )
        case .microphoneDenied:
            return SaathiText.t(
                "saathi_room_mic_denied",
                "Doosri Pari needs permission to use the microphone. You can still listen."
            )
        case .connectionLost:
            return SaathiText.t(
                "saathi_room_lost",
                "The connection dropped. We are trying to reconnect."
            )
        }
    }
}

// MARK: - The stand-in

/// A transport that carries no audio and pretends to nothing.
///
/// It exists so this package is exercisable — a demo, a SwiftUI preview, a
/// test — and, more importantly, so that shipping is not possible by accident.
/// It reports `RoomConnectionState.ended` immediately and says why, in a
/// sentence, which is what the screen is required to do when a room is not
/// live. If a build ever reaches a device with this in it, the user sees
/// "This room has ended" and never a room that looks live and is not.
///
/// **Do not add a fake `.live`.** A stub that reports a live room is the exact
/// failure this file exists to prevent, and a preview that fakes one will be
/// copied into a demo that ships.
final class UnavailableVoiceRoomTransport: VoiceRoomTransport {
    private let reason: String

    init(reason: String = "Voice rooms are not available in this build.") {
        self.reason = reason
    }

    func connect(roomID: String, as role: RoomRole) async throws {
        throw VoiceRoomTransportError.connectionLost
    }

    func disconnect() async { }

    func setMicrophoneMuted(_ muted: Bool) async throws { }

    func setHandRaised(_ raised: Bool) async throws { }

    func setRecordingEnabled(_ enabled: Bool) async throws {
        throw VoiceRoomTransportError.notAllowedToHost
    }

    func muteMember(id: String) async throws {
        throw VoiceRoomTransportError.notAllowedToHost
    }

    func removeMember(id: String) async throws {
        throw VoiceRoomTransportError.notAllowedToHost
    }

    func currentConnectionState() async -> RoomConnectionState { .ended }

    func events() -> AsyncStream<VoiceRoomTransportEvent> {
        AsyncStream { continuation in
            continuation.yield(.ended(reason: reason))
            continuation.finish()
        }
    }
}
