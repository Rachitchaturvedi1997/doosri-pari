// UNTESTED — never compiled. See ios/README.md.
//
// The seam for Saathi's network calls.
//
// A `DoosriService` protocol is being written in `Networking/` by another
// agent and its exact shape is not known from here, so this file declares a
// narrow protocol for *this package's* calls rather than depending on a shape
// that does not exist yet. When `Networking/` lands, the adapter that
// implements `SaathiService` is the only file that changes; the view models
// keep talking to this protocol. The alternative — guessing at
// `DoosriService`'s methods now — would make a compile error land in eight
// screens instead of one adapter.
//
// Every method is `async throws` and every failure arrives as
// `SaathiServiceError`, so each view model has exactly one error case to
// handle and the wording for "no connection" is written once.

import Foundation

protocol SaathiService {
    // Circles
    func suggestedCircles() async throws -> [SaathiCircle]
    func myCircles() async throws -> [SaathiCircle]
    @discardableResult
    func joinCircle(id: String) async throws -> SaathiCircle
    @discardableResult
    func requestToJoinCircle(id: String) async throws -> SaathiCircle
    func leaveCircle(id: String) async throws

    // Feed and posts
    func feed(circleID: String, before: Double?) async throws -> SaathiFeedPage
    @discardableResult
    func post(_ draft: PostDraft) async throws -> SaathiPost
    @discardableResult
    func addNamaste(toPost postID: String) async throws -> SaathiPost

    // Comments
    func comments(postID: String) async throws -> [SaathiComment]
    @discardableResult
    func addComment(postID: String, body: String) async throws -> SaathiComment

    // Events
    func events(circleID: String?) async throws -> [SaathiEvent]
    func event(id: String) async throws -> SaathiEvent
    /// The server treats a repeated identical RSVP as a no-op and returns the
    /// same event. The client also short-circuits before calling, in
    /// `EventDetailViewModel`, because a second tap that waits on the network
    /// still *looks* like an error on a slow connection.
    @discardableResult
    func setRsvp(eventID: String, to state: RsvpState) async throws -> SaathiEvent

    // Voice rooms
    func voiceRooms() async throws -> [VoiceRoom]
    func participants(roomID: String) async throws -> [VoiceRoomParticipant]
    /// The signed-in user's role. The server assigns it on join, and a
    /// listener who turns out to have been made a speaker should find out
    /// from the transport rather than guess.
    func roleInRoom(roomID: String) async throws -> RoomRole

    // People
    func people(
        inCircle circleID: String?,
        query: String,
        viewerTrustLevel: TrustLevel
    ) async throws -> [SaathiMember]
    func findabilitySetting() async throws -> FindabilitySetting
    func setFindabilitySetting(_ setting: FindabilitySetting) async throws

    // Reports — section 15
    @discardableResult
    func sendReport(_ draft: ReportDraft) async throws -> ReportReceipt
}

/// One page of a circle's feed.
///
/// Plan 6.6: newest first, no engagement ranking, and after thirty posts,
/// "You're all caught up. See you tomorrow." `caughtUp` is a field on the
/// page rather than something the client counts, because the cutoff is the
/// server's business — a client-side count would either stop early or never
/// stop, and a feed that never stops is the engagement loop the plan is
/// written against.
struct SaathiFeedPage: Hashable {
    let posts: [SaathiPost]
    /// The timestamp to send as `before` for the next page. Nil when there is
    /// nothing older.
    let nextBefore: Double?
    /// Plan 6.6's thirty posts.
    let caughtUp: Bool

    static let empty = SaathiFeedPage(posts: [], nextBefore: nil, caughtUp: true)
}

/// What the composer hands the service.
///
/// `visibility` is not optional and the model will not build without one, so
/// there is no code path that posts a body of text with an implicit audience.
/// See `PostComposerViewModel` for the UI half of that.
struct PostDraft: Hashable {
    let circleID: String
    let body: String
    let visibility: PostVisibility
    let kind: PostKind
    let media: [PostMedia]
    /// Set when the text came from the Bolo voice layer. Plan 5 is explicit
    /// that the voice layer never posts without a visible confirmation, so
    /// this flag does not change what happens — it changes what the user is
    /// told they are looking at when they open the composer.
    let cameFromVoice: Bool
}

/// Section 15's acknowledgement.
///
/// `responseWindow` comes back from the server rather than being chosen here,
/// because it depends on the reason and on the hour the report landed, and a
/// client that guessed "2 hours" for a report that arrived at 23:00 would be
/// promising something the moderation rota cannot deliver.
struct ReportReceipt: Hashable {
    let reportID: String
    let responseWindow: String
    /// True when the report was accepted as a scam report and therefore goes
    /// to the two-hour queue.
    let treatedAsScam: Bool
}

enum SaathiServiceError: LocalizedError, Equatable {
    case offline
    case server(status: Int)
    case decoding
    /// The user is not allowed to do this — a T0 posting in a batch circle, a
    /// listing that asks for a payment, an association circle without
    /// approval. The message is the server's, already in the user's language,
    /// because the server is the only side that knows which rule fired.
    case notAllowed(String)
    /// Plan 6.6: RSVP limits. A full event is a normal outcome, not a fault,
    /// and the screen says so in a sentence rather than in an error.
    case eventFull

    var errorDescription: String? {
        switch self {
        case .offline:
            return SaathiText.t(
                "saathi_error_offline",
                "No connection. We will try again when you are back online."
            )
        case .server, .decoding:
            return SaathiText.t(
                "saathi_error_failed",
                "Could not load. Please try again."
            )
        case .notAllowed(let message):
            return message
        case .eventFull:
            return SaathiText.t(
                "saathi_error_event_full",
                "This event is full."
            )
        }
    }

    /// The only place in the package that decides between "try again" and
    /// "there is nothing to do". Being offline and being told no are very
    /// different sentences and a user who is offline deserves to be told so.
    var isRetryable: Bool {
        switch self {
        case .offline, .server, .decoding: return true
        case .notAllowed, .eventFull: return false
        }
    }
}
