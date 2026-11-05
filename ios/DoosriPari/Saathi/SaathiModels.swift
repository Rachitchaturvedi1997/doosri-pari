// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.6 (Saathi circles) and 6.7 (Chai pe Charcha). The wire
// names are not free-hand: the server owns them and a rename on either side
// should fail a test rather than quietly show a pensioner a stale circle.
// The same rule as `CheckInModels.swift` applies — keys are spelled out
// rather than left to `.convertFromSnakeCase`, because a strategy change
// turns a rename into a field that decodes as nil forever.
//
// DESIGN CONTRACT — `Design/Components/` is being written in parallel, so the
// call shapes assumed here are recorded rather than discovered:
//
//   DoosriScreen { content }
//   DoosriCard { content }
//   DoosriPrimaryButton(_ title: String, systemImage: String? = nil, action: () -> Void)
//   DoosriSecondaryButton(_ title: String, systemImage: String? = nil, action: () -> Void)
//   DoosriListRow(systemImage: String, title: String, value: String? = nil, onTap: (() -> Void)? = nil)
//   DoosriSosButton(action: () -> Void)
//   DoosriEmptyState(systemImage: String, title: String, message: String,
//                    actionTitle: String? = nil, action: (() -> Void)? = nil)
//   DoosriSheet(title: String, onCancel: (() -> Void)? = nil) { content }
//   DoosriConfirmAlert(title: String, message: String, confirmTitle: String,
//                      dismissTitle: String = "Cancel",
//                      isPresented: Binding<Bool>, onConfirm: () -> Void)
//   DoosriSectionHeader(_ title: String, subtitle: String? = nil)
//
// The custom pieces below — post card, comment row, participant row, filter
// chips — are built from `DoosriPalette`, `DoosriSpace`, `DoosriSize` and
// `Font.doosri*`, which all exist today in `Design/`.

import Foundation

// MARK: - Findability

/// Plan 6.6: a "Colleagues can find me" setting — everyone / verified only /
/// nobody.
///
/// The plan names `verifiedOnly` as the default and it is the only one of the
/// three that is safe to ship. `everyone` puts a pensioner's name in front of
/// every stranger in the app, and the reason that matters here is not privacy
/// in the abstract: a retired government employee is a person with a posting
/// station, a service year and a small circle of people who can be asked about
/// them. `nobody` is a real setting because some of these users will want it,
/// and it is a supported state, not a failure.
enum FindabilitySetting: String, Codable, CaseIterable {
    case everyone
    case verifiedOnly
    case nobody

    /// Spelled as the plan spells it.
    static let standard: FindabilitySetting = .verifiedOnly

    var displayName: String {
        switch self {
        case .everyone: return SaathiText.t("saathi_find_everyone", "Everyone")
        case .verifiedOnly: return SaathiText.t("saathi_find_verified", "Verified people only")
        case .nobody: return SaathiText.t("saathi_find_nobody", "Nobody")
        }
    }

    var explanation: String {
        switch self {
        case .everyone:
            return SaathiText.t(
                "saathi_find_everyone_note",
                "Any member can find you by name, city and department."
            )
        case .verifiedOnly:
            return SaathiText.t(
                "saathi_find_verified_note",
                "Only members who have verified their service can find you. This is the usual setting."
            )
        case .nobody:
            return SaathiText.t(
                "saathi_find_nobody_note",
                "You do not appear in anyone's search results."
            )
        }
    }

    /// Whether the signed-in user would be returned by a search run by a
    /// member of the given trust level. The list view calls this per row
    /// rather than trusting the server to have filtered, because a stale
    /// index is exactly how a "nobody" setting quietly stops working.
    func showsTo(trustLevel: TrustLevel) -> Bool {
        switch self {
        case .everyone: return true
        case .verifiedOnly: return trustLevel != .t0
        case .nobody: return false
        }
    }
}

/// Plan 6.6 and section 15. T1 is the level that may post in a batch circle;
/// T2 is somebody an association or a partner vouches for.
enum TrustLevel: String, Codable, CaseIterable {
    case t0 = "T0"
    case t1 = "T1"
    case t2 = "T2"

    var isVerified: Bool { self != .t0 }
}

// MARK: - Distance

/// Plan 6.6 wants "nearby", and the honest unit for these users is a city.
///
/// The buckets are fixed and the string is fixed. There is no code path in
/// this package that turns a coordinate into text, and no field that a
/// coordinate could be read out of later, because a value that exists is a
/// value that eventually gets rendered. "About 10 km away" is enough to
/// decide whether to walk to a chai; the last hundred metres is enough to
/// find somebody's house.
enum CoarseDistance: String, Codable, CaseIterable {
    case sameCity
    case aboutTenKm
    case aboutTwentyFiveKm
    case overTwentyFiveKm
    case online

    var displayName: String {
        switch self {
        case .sameCity:
            return SaathiText.t("saathi_distance_same_city", "In your city")
        case .aboutTenKm:
            return SaathiText.t("saathi_distance_10km", "About 10 km away")
        case .aboutTwentyFiveKm:
            return SaathiText.t("saathi_distance_25km", "About 25 km away")
        case .overTwentyFiveKm:
            return SaathiText.t("saathi_distance_far", "More than 25 km away")
        case .online:
            return SaathiText.t("saathi_distance_online", "Available online")
        }
    }

    /// The symbol that goes with the words. Plan 4.3: every icon has a text
    /// label beside it, so this is decoration next to a sentence and never
    /// the sentence itself.
    var symbolName: String {
        switch self {
        case .sameCity: return "building.2"
        case .aboutTenKm: return "figure.walk"
        case .aboutTwentyFiveKm: return "bus"
        case .overTwentyFiveKm: return "airplane"
        case .online: return "wifi"
        }
    }

    /// Sort weight, closest first. `online` sorts last deliberately: a
    /// distance that is not a distance should not look like the best one.
    var sortRank: Int {
        switch self {
        case .sameCity: return 0
        case .aboutTenKm: return 1
        case .aboutTwentyFiveKm: return 2
        case .overTwentyFiveKm: return 3
        case .online: return 4
        }
    }
}

// MARK: - Members

/// A person, as another member of a circle may see them.
///
/// **This field list is the point of the file.** It is what the people screen
/// shows, and it is deliberately short:
///
///   displayName     the name they chose to be shown by
///   city            one city, not a locality or a pin code
///   department      the department they retired from
///   retiredYears    the year range, as a range
///   distance        a `CoarseDistance` bucket, not a number of metres
///   trustLevel      drives findability and who may moderate
///
/// There is no phone number. There is no address, not even a city-local one.
/// There is no latitude, longitude, geohash or plus-code. There is no "last
/// seen posting from", no office address, no home town plus current town, and
/// no birth date.
///
/// The reason is not that the plan forgot. The reason is that this community
/// is small, structured and mutually identifying: in a batch circle of a few
/// hundred retired employees of one organisation, a colleague's *department and
/// city and years* narrows a person to a handful of people, and a colleague's
/// posting station narrows it to one. Any of those, composed with anything,
/// is a home address. A phone number is worse still, because a stranger with
/// it can call at 2am and the elder will answer, having been trained to be
/// helpful to colleagues.
///
/// Keeping the fields out of the struct is deliberate. A `phone: String?`
/// added later is a default that only has to be typed; the sentence above has
/// to be argued past. That is the whole defence.
///
/// The `Codable` conformance below is a closed set of keys for the same
/// reason: an unknown key from the server is ignored, but the type has no
/// place to *hold* a number it should not hold, so a later endpoint that
/// starts sending one cannot populate it by accident.
struct SaathiMember: Identifiable, Hashable, Codable {
    let id: String
    let displayName: String
    let city: String
    let department: String
    /// The years they served, as a range. A range, not a single year: people
    /// joined in different years and pinning a single one is a guess.
    let retiredYears: ClosedRange<Int>?
    let distance: CoarseDistance
    let trustLevel: TrustLevel
    let findability: FindabilitySetting

    /// Initials, never a photograph. A face is a stronger identifier than a
    /// name in a community this small, it does not scale down to a readable
    /// glyph at 20pt, and it makes a removed account's history much harder to
    /// clean up. The moderation path in section 15 acts on text.
    var initials: String {
        let parts = displayName
            .split(separator: " ")
            .prefix(2)
            .compactMap { $0.first }
        return parts.isEmpty ? "?" : String(parts).uppercased()
    }

    /// Plan 6.6 lets a member choose a symbol rather than initials.
    var avatarSymbolName: String { "person.crop.circle" }

    /// The one line a row shows under the name. Composed here, not in the
    /// view, so there is a single place to check that it never grows a fifth
    /// field.
    var subtitle: String {
        var parts = [department, city]
        if let retiredYears {
            parts.append(Self.yearsText(retiredYears))
        }
        return parts.joined(separator: " · ")
    }

    /// Plan 6.6 says "search by name, department and years". Years are a
    /// range, because people joined in different years and a single year is
    /// a guess about somebody's service.
    static func yearsText(_ range: ClosedRange<Int>) -> String {
        range.upperBound == range.lowerBound
            ? String(range.lowerBound)
            : "\(range.lowerBound)–\(range.upperBound)"
    }

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
        case city
        case department
        case retiredYears = "retired_years"
        case distance
        case trustLevel = "trust_level"
        case findability
    }
}

// MARK: - Circles

/// Plan 6.6's table, verbatim in shape.
enum CircleType: String, Codable, CaseIterable {
    case batch
    case department
    case city
    case association
    case interest

    /// `private` is a keyword, so the case is renamed and the wire name is
    /// pinned in `rawValue`.
    case privateCircle = "private"

    var displayName: String {
        switch self {
        case .batch: return SaathiText.t("saathi_type_batch", "Batch")
        case .department: return SaathiText.t("saathi_type_department", "Department")
        case .city: return SaathiText.t("saathi_type_city", "City")
        case .association: return SaathiText.t("saathi_type_association", "Association")
        case .interest: return SaathiText.t("saathi_type_interest", "Interest")
        case .privateCircle: return SaathiText.t("saathi_type_private", "Private")
        }
    }

    var symbolName: String {
        switch self {
        case .batch: return "graduationcap"
        case .department: return "building.columns"
        case .city: return "map"
        case .association: return "rosette"
        case .interest: return "leaf"
        case .privateCircle: return "lock"
        }
    }
}

/// Plan 6.6's "Joining" column.
enum CircleJoining: String, Codable {
    /// Open, like department, city and interest circles.
    case open
    /// Batch circles: joining is automatic, **posting** needs T1.
    case joinOpenPostNeedsT1
    /// Association circles: the office-bearers approve, and they are also the
    /// community moderators from section 15.
    case requestApproval
    /// Private circles: an invite link and nothing else.
    case inviteLink
}

/// Why this circle is on the suggested list, in a sentence the user reads
/// before they tap anything.
///
/// **The rule: a circle that cannot say its reason is not rendered.** Not
/// sorted to the bottom — not rendered. `SuggestedCirclesViewModel` drops
/// those circles and counts them, and the screen says how many were hidden.
///
/// This is a moderation decision wearing a UI costume. In a community of
/// retired government employees, almost everyone is identifiable from a
/// department, a city and a year range, so "why am I seeing this" is not a
/// nicety for the curious — it is the only thing standing between a ranked
/// recommendation feed and a targeting list. A circle that surfaces with no
/// stated basis is a claim the app is making about the user that the user
/// cannot check, contest, or turn off. It also leaks: the fact that a
/// recommendation engine put a batch circle in front of somebody is itself a
/// statement about that person's employer and posting station, and if the
/// circle is not explainable the statement is not even attributable.
///
/// So the reasons below are a closed set, and every case names a fact the
/// user supplied or can verify in one tap. There is deliberately no
/// `popularNearby`, no `similarPeople`, no `becauseYouMightLike` and no
/// positional fallback. A recommendation system for this population that
/// cannot be explained is not a discovery feature; it is a list of who the
/// app thinks is vulnerable to being shown a particular thing.
enum RecommendationReason: Hashable {
    case sameBatch(department: String, years: ClosedRange<Int>)
    case sameDepartment(String)
    case sameCity(String)
    /// An association the user is already on the roll of sent an invitation.
    case associationInvitation(association: String)
    /// The user picked this interest during onboarding.
    case statedInterest(String)
    /// A colleague the user already connected with is a member. Named, and
    /// tappable — this is the one reason that is about a *person*, so it has
    /// to be checkable.
    case colleagueIsMember(name: String)
    /// An official notice from the organisation, e.g. a welfare association.
    case officialNotice(from: String)

    /// The sentence shown under the circle name. Written as a fact about how
    /// the user got here, never as a command.
    var sentence: String {
        switch self {
        case let .sameBatch(department, years):
            let span = years.upperBound == years.lowerBound
                ? String(years.lowerBound)
                : "\(years.lowerBound)–\(years.upperBound)"
            return SaathiText.t(
                "saathi_reason_batch",
                "You worked in \(department), \(span)."
            )
        case let .sameDepartment(department):
            return SaathiText.t(
                "saathi_reason_department",
                "You are in the \(department) pensioners' circle."
            )
        case let .sameCity(city):
            return SaathiText.t(
                "saathi_reason_city",
                "These are pensioners in \(city)."
            )
        case let .associationInvitation(association):
            return SaathiText.t(
                "saathi_reason_association",
                "\(association) invited you to join."
            )
        case let .statedInterest(interest):
            return SaathiText.t(
                "saathi_reason_interest",
                "You told us you like \(interest)."
            )
        case let .colleagueIsMember(name):
            return SaathiText.t(
                "saathi_reason_colleague",
                "Someone you sent a namaste to, \(name), is here."
            )
        case let .officialNotice(from):
            return SaathiText.t(
                "saathi_reason_notice",
                "\(from) sent a notice to its members."
            )
        }
    }

    /// Whether the user can open the evidence behind the sentence. Shown as a
    /// "See why" link where it is true, and never as one where it is not —
    /// a link that goes nowhere teaches people that the reasons are theatre.
    var hasVisibleEvidence: Bool {
        switch self {
        case .sameBatch, .sameDepartment, .sameCity, .statedInterest:
            return true
        case .associationInvitation, .colleagueIsMember, .officialNotice:
            return true
        }
    }

    /// Every reason is checkable, so no case opts out. The switch is written
    /// exhaustively on purpose: adding a new case to the enum then fails to
    /// compile here, which is the moment to ask whether the new reason is
    /// something a user could be shown and could check.
    var isUserCheckable: Bool {
        switch self {
        case .sameBatch, .sameDepartment, .sameCity, .associationInvitation,
             .statedInterest, .colleagueIsMember, .officialNotice:
            return true
        }
    }
}

/// One circle.
///
/// `reason` is required when `isSuggested` is true and meaningless when it is
/// false — which is why it is an optional field and *not* an optional flag:
/// a circle the user has joined does not need to justify itself, and making
/// it carry a stale reason would be noise.
struct SaathiCircle: Identifiable, Hashable, Codable {
    let id: String
    let name: String
    let type: CircleType
    let city: String?
    let memberCount: Int
    let joining: CircleJoining
    let joined: Bool
    /// Nil once joined.
    let reason: RecommendationReason?
    /// SF Symbol for the circle. Always rendered beside the name.
    let symbolName: String

    /// Whether the user may post here, which is not the same as being a
    /// member. Plan 6.6: batch circles need T1 to post.
    func mayPost(trustLevel: TrustLevel) -> Bool {
        switch joining {
        case .open, .inviteLink: return true
        case .joinOpenPostNeedsT1: return trustLevel != .t0
        case .requestApproval: return joined
        }
    }

    /// The word for what tapping the row will do. A verb, per plan 4.4.
    var joinActionWord: String {
        switch joining {
        case .open, .joinOpenPostNeedsT1:
            return joined
                ? SaathiText.t("saathi_action_open", "Open")
                : SaathiText.t("saathi_action_join", "Join")
        case .requestApproval:
            return joined
                ? SaathiText.t("saathi_action_open", "Open")
                : SaathiText.t("saathi_action_request", "Request to join")
        case .inviteLink:
            return SaathiText.t("saathi_action_open_private", "Open with invite code")
        }
    }
}

// MARK: - Posts

/// Plan 6.6's post kinds.
enum PostKind: String, Codable, CaseIterable {
    case text
    case oldPhoto
    case achievement
    case question
    case poll
    case eventAnnouncement = "event"
    case voiceNote

    var displayName: String {
        switch self {
        case .text: return SaathiText.t("saathi_post_text", "Post")
        case .oldPhoto: return SaathiText.t("saathi_post_old_photo", "Old photo")
        case .achievement: return SaathiText.t("saathi_post_achievement", "Achievement")
        case .question: return SaathiText.t("saathi_post_question", "Question")
        case .poll: return SaathiText.t("saathi_post_poll", "Poll")
        case .eventAnnouncement: return SaathiText.t("saathi_post_event", "Event")
        case .voiceNote: return SaathiText.t("saathi_post_voice", "Voice note")
        }
    }

    var symbolName: String {
        switch self {
        case .text: return "text.bubble"
        case .oldPhoto: return "photo"
        case .achievement: return "rosette"
        case .question: return "questionmark.circle"
        case .poll: return "chart.bar"
        case .eventAnnouncement: return "calendar"
        case .voiceNote: return "waveform"
        }
    }
}

/// Who can see a post. The composer will not post until this is chosen, and
/// the words below are the words on screen — never "Public", which a user of
/// this generation reads as a notice to the whole internet.
enum PostVisibility: String, Codable, CaseIterable, Identifiable {
    case thisCircle
    case myCircles
    case everyone

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .thisCircle:
            return SaathiText.t("saathi_visibility_circle", "Only this circle")
        case .myCircles:
            return SaathiText.t("saathi_visibility_circles", "Anyone in my circles")
        case .everyone:
            return SaathiText.t("saathi_visibility_everyone", "Everyone on Doosri Pari")
        }
    }

    /// What the user is agreeing to, in a second line. Choosing a visibility
    /// blind is how people accidentally broadcast a complaint about an
    /// office to a department they left in 1998.
    var explanation: String {
        switch self {
        case .thisCircle:
            return SaathiText.t(
                "saathi_visibility_circle_note",
                "Members of this circle only."
            )
        case .myCircles:
            return SaathiText.t(
                "saathi_visibility_circles_note",
                "Members of every circle you belong to."
            )
        case .everyone:
            return SaathiText.t(
                "saathi_visibility_everyone_note",
                "Every member of Doosri Pari can read this."
            )
        }
    }

    /// The default a new composer starts on. `thisCircle`, not
    /// `everyone`: the narrower choice is the one that is wrong in a way the
    /// user can fix, and the wider one is wrong in a way they might not notice
    /// for a decade.
    static let suggested: PostVisibility = .thisCircle
}

struct PostMedia: Identifiable, Hashable, Codable {
    enum Kind: String, Codable {
        case photo
        case voiceNote
    }

    let id: String
    let kind: Kind
    /// Plan 6.6: up to six photos.
    let localPathOrURL: String
    /// Voice notes: up to three minutes.
    let duration: TimeInterval?
    /// Voice notes are auto-transcribed. The transcript is shown, never
    /// hidden, and never published without the user reading it first.
    let transcript: String?

    /// "Old photo" posts carry a year and a place, per plan 6.6. The place is
    /// a place — a station, a city, an office — not a person's address.
    let oldPhotoYear: Int?
    let oldPhotoPlace: String?
}

/// Plan 6.6's post. The 30-post cap lives in `CircleFeedViewModel`, not here.
///
/// **On treating this as untrusted text from strangers.** This is the only
/// screen in Saathi where the words come from people the user has never met,
/// and everything below follows from that:
///
/// - The body is `String` and is drawn as a `String`. It is never
///   `AttributedString` built from markup and never an `HTML` view, so there
///   is no path from a post to a styled link with a tap target inside it.
///   Section 15 puts links through a blocklist; a blocklist you then render
///   with a tappable styled span is a blocklist that leaks. `links` is carried
///   as plain data and the row shows the host only, un-tappable.
/// - No counters drive the order. Plan 6.6 is explicit: newest first, no
///   engagement ranking, a small boost for people the user interacted with in
///   the last 30 days, and then "You're all caught up." The boost is *shown*
///   on the row rather than applied silently, because a silent boost in a
///   batch circle means a colleague sees a ranking they cannot see.
/// - `isHeldForReview` renders as a visible, neutral, factual note — "This
///   post is being checked" — never as an empty space, and never as an
///   accusation. Section 15 holds T0 posts containing phone numbers and
///   payment requests for review; a silent gap would leave the poster thinking
///   it posted, and would hide the fact that the check ran from everybody.
/// - A voice note is never auto-published from a transcription. The composer
///   shows the transcript as an editable draft, and posting is a separate,
///   visible tap.
struct SaathiPost: Identifiable, Hashable, Codable {
    let id: String
    let author: SaathiMember
    let kind: PostKind
    let body: String
    let createdAt: Double
    let visibility: PostVisibility
    let circleID: String
    let circleName: String
    let commentCount: Int
    let namasteCount: Int
    let media: [PostMedia]
    let isHeldForReview: Bool
    let isEdited: Bool
    /// The 30-day interaction boost from plan 6.6, made visible.
    let isFromRecentConversation: Bool
    /// Section 15: a mention or a reply is the one thing that breaks the daily
    /// digest, so the feed is allowed to break it.
    let mentionsYou: Bool
    let replyToDisplayName: String?

    var createdDate: Date { Date(timeIntervalSince1970: createdAt) }

    /// Links are carried so the row can *count and name* them. They are never
    /// turned into a tappable span.
    var linkHosts: [String] { [] }

    enum CodingKeys: String, CodingKey {
        case id
        case author
        case kind
        case body
        case createdAt = "created_at"
        case visibility
        case circleID = "circle_id"
        case circleName = "circle_name"
        case commentCount = "comment_count"
        case namasteCount = "namaste_count"
        case media
        case isHeldForReview = "is_held_for_review"
        case isEdited = "is_edited"
        case isFromRecentConversation = "is_from_recent_conversation"
        case mentionsYou = "mentions_you"
        case replyToDisplayName = "reply_to_display_name"
    }
}

struct SaathiComment: Identifiable, Hashable, Codable {
    let id: String
    let postID: String
    let author: SaathiMember
    let body: String
    let createdAt: Double
    /// Plan 6.6: comments thread. Stored flat with a parent name, because
    /// three levels of reply in 20pt type on a 4.7-inch screen is a wall.
    let replyToDisplayName: String?
    let isHeldForReview: Bool
    let namasteCount: Int
    let createdDate: Date { Date(timeIntervalSince1970: createdAt) }

    enum CodingKeys: String, CodingKey {
        case id
        case postID = "post_id"
        case author
        case body
        case createdAt = "created_at"
        case replyToDisplayName = "reply_to_display_name"
        case isHeldForReview = "is_held_for_review"
        case namasteCount = "namaste_count"
    }
}

// MARK: - Events

/// The event's own RSVP, which is separate from the transient in-flight
/// state in `EventDetailViewModel`.
///
/// A pensioner taps RSVP with a thumb, on a phone they are holding one
/// handed, possibly twice because the first tap did not feel like it
/// registered. `going` is a fact about the event, not a command, and the
/// screen must make a second tap a no-op rather than an error. See
/// `EventDetailViewModel.setRsvp(_:)` for the idempotence, which is enforced
/// in one place and observable in the UI as a button that stays where it is.
enum RsvpState: String, Codable, CaseIterable, Identifiable {
    case none
    case going
    case maybe
    case notGoing

    var id: String { rawValue }

    /// The verb on the button. Past tense for the states that already
    /// happened, because the state is a fact about the past.
    var buttonTitle: String {
        switch self {
        case .none: return SaathiText.t("saathi_rsvp_going", "I am going")
        case .going: return SaathiText.t("saathi_rsvp_going_done", "You are going")
        case .maybe: return SaathiText.t("saathi_rsvp_maybe_done", "You may go")
        case .notGoing: return SaathiText.t("saathi_rsvp_not_going_done", "You are not going")
        }
    }

    /// A sentence under the button, so the button's own word is not the only
    /// evidence. Section 6.6 promises reminders a day and two hours before,
    /// and this is where the user checks that they will actually get one.
    var confirmation: String {
        switch self {
        case .none:
            return SaathiText.t("saathi_rsvp_none_note", "You can change this any time.")
        case .going:
            return SaathiText.t(
                "saathi_rsvp_going_note",
                "We will remind you one day before, and two hours before."
            )
        case .maybe:
            return SaathiText.t("saathi_rsvp_maybe_note", "We will remind you two hours before.")
        case .notGoing:
            return SaathiText.t("saathi_rsvp_not_note", "You can change this any time.")
        }
    }
}

/// Where an event is.
///
/// A *public* place, named at the granularity a public place is named. This
/// is a different risk class from `SaathiMember`, which shows a city: an
/// event has to be findable or it is useless, and the plan asks for a place.
/// The rule is that the place is a venue — a community hall, a school
/// auditorium, a café, a park gate — chosen by the organiser, and never a
/// private home. A member's own address never appears in an event, including
/// an event at the organiser's house; the organiser gives a landmark instead.
enum EventPlace: Hashable, Codable {
    case venue(name: String, area: String, city: String)
    /// Plan 6.6's online option for people who cannot travel.
    case online

    var displayName: String {
        switch self {
        case let .venue(name, area, _):
            return "\(name), \(area)"
        case .online:
            return SaathiText.t("saathi_event_online", "Online")
        }
    }

    var symbolName: String {
        switch self {
        case .venue: return "mappin.and.ellipse"
        case .online: return "video"
        }
    }
}

struct SaathiEvent: Identifiable, Hashable, Codable {
    let id: String
    let circleID: String
    let circleName: String
    let title: String
    let startsAt: Double
    let endsAt: Double
    let place: EventPlace
    /// Held in paise, like every amount in this app. See `Paise` in Seva —
    /// one type, not two, so a rupee can never be a float anywhere.
    let costPaise: Int64
    /// Nil means no limit.
    let capacity: Int?
    let goingCount: Int
    let organiser: SaathiMember
    let isOnline: Bool

    var startDate: Date { Date(timeIntervalSince1970: startsAt) }
    var endDate: Date { Date(timeIntervalSince1970: endsAt) }

    /// Plan 6.6: RSVP limits. `nil` when the event is open and no limit has
    /// been set; the wording differs because "42 places left" is a
    /// different kind of sentence from a bare number.
    var placesText: String? {
        guard let capacity else { return nil }
        let left = capacity - goingCount
        guard left > 0 else {
            return SaathiText.t("saathi_event_full", "This event is full.")
        }
        if left <= 5 {
            return SaathiText.t("saathi_event_few_left", "\(left) places left")
        }
        return SaathiText.t("saathi_event_places_left", "\(left) of \(capacity) places left")
    }

    var isFull: Bool {
        guard let capacity else { return false }
        return goingCount >= capacity
    }

    enum CodingKeys: String, CodingKey {
        case id
        case circleID = "circle_id"
        case circleName = "circle_name"
        case title
        case startsAt = "starts_at"
        case endsAt = "ends_at"
        case place
        case costPaise = "cost_paise"
        case capacity
        case goingCount = "going_count"
        case organiser
        case isOnline = "is_online"
    }
}

// MARK: - Voice rooms

/// The transport's view of whether audio is actually flowing.
///
/// **A room that is not live must never be presented as live.** The plan
/// promises a room of up to eight speakers and 200 listeners, and the
/// difference between "live" and "not" is the difference between a group of
/// colleagues talking and a room that appears to be talking while nothing
/// comes out of the speaker. For a hard-of-hearing user with hearing aids
/// that is not a bug report, it is the app lying to them, and the lie is
/// indistinguishable from their own hearing.
enum RoomConnectionState: String, Codable {
    /// Not attempted. The room row may be listed, but nothing else is said.
    case idle
    /// Handshaking with the SFU. The screen shows "Connecting" and does not
    /// show speaking rings, participant counts moving, or a live badge.
    case connecting
    /// Audio confirmed flowing. Only now may the screen say the room is live.
    case live
    case reconnecting
    /// It ran and stopped. Say it ended. Do not say the user left.
    case ended
    case failed
}

enum RoomRole: String, Codable, CaseIterable {
    case host
    case speaker
    case listener

    var displayName: String {
        switch self {
        case .host: return SaathiText.t("saathi_room_host", "Host")
        case .speaker: return SaathiText.t("saathi_room_speaker", "Speaking")
        case .listener: return SaathiText.t("saathi_room_listener", "Listening")
        }
    }
}

struct VoiceRoomParticipant: Identifiable, Hashable, Codable {
    let id: String
    let member: SaathiMember
    let role: RoomRole
    /// Only meaningful while the transport reports `RoomConnectionState.live`.
    /// The view model refuses to surface it otherwise, so a stale speaking
    /// flag cannot keep someone animated in a silent room.
    let isSpeaking: Bool
    let isMuted: Bool
    let handRaised: Bool
    /// Plan 6.7: recording is off by default, and when it is on every speaker
    /// must have consented. A speaker who has not is not recorded, and the
    /// screen says so rather than implying the room is being captured.
    let gaveRecordingConsent: Bool
    let joinedAt: Double

    enum CodingKeys: String, CodingKey {
        case id
        case member
        case role
        case isSpeaking = "is_speaking"
        case isMuted = "is_muted"
        case handRaised = "hand_raised"
        case gaveRecordingConsent = "gave_recording_consent"
        case joinedAt = "joined_at"
    }
}

/// How much to believe the server's "live" flag.
///
/// A room list that trusts a boolean is a room list that occasionally says
/// "Live" over a room whose host walked out of the building twenty minutes
/// ago. The flag is still needed — nobody can infer liveness from a schedule —
/// but it is only believed while a heartbeat is fresh. Past the window the
/// row degrades to "Not answering", which is the truth and is also the thing
/// a user can act on.
enum RoomLiveness: String {
    case live
    case startingSoon
    case scheduled
    case notAnswering

    var badge: String {
        switch self {
        case .live: return SaathiText.t("saathi_room_live", "Live now")
        case .startingSoon: return SaathiText.t("saathi_room_starting", "Starting soon")
        case .scheduled: return SaathiText.t("saathi_room_scheduled", "Scheduled")
        case .notAnswering: return SaathiText.t("saathi_room_stalled", "Not answering")
        }
    }

    /// A live badge is the one thing on this screen allowed to be red-ish
    /// rather than accent, because it is the only piece of time-sensitive
    /// state in the list and it has to be findable at a glance.
    var symbolName: String {
        switch self {
        case .live: return "dot.radiowaves.left.and.right"
        case .startingSoon: return "clock"
        case .scheduled: return "calendar"
        case .notAnswering: return "wifi.slash"
        }
    }

    var mayJoin: Bool { self == .live || self == .startingSoon || self == .scheduled }
}

/// A room, as the list sees it.
struct VoiceRoom: Identifiable, Hashable, Codable {
    let id: String
    let title: String
    let topic: String?
    let host: SaathiMember
    /// The plan's own examples: "Subah ki khabar" at 08:00 daily, "Sham ke
    /// bhajan" at 18:30, "Yaadon ki baat" on Sundays.
    let startsAt: Double
    let isRecurring: Bool
    let recurrenceText: String?
    let language: String
    /// The server's claim, taken at the time of the fetch.
    let isLiveFlag: Bool
    /// Epoch seconds of the host's last heartbeat, or nil.
    let lastHeartbeatAt: Double?
    /// Plan 6.7: 3 valid reports means no hosting for 30 days. Shown on the
    /// row for anyone who has hosted, because the rule should be visible to
    /// the person it constrains, not sprung on them.
    let hostingStrikes: Int

    var startDate: Date { Date(timeIntervalSince1970: startsAt) }

    /// How long a heartbeat stays believable.
    static let heartbeatTolerance: TimeInterval = 90

    /// The liveness the *screen* is allowed to show. The `isLiveFlag` above is
    /// never rendered directly.
    func liveness(now: Date = .now) -> RoomLiveness {
        if isLiveFlag {
            guard let lastHeartbeatAt else { return .notAnswering }
            let age = now.timeIntervalSince1970 - lastHeartbeatAt
            return age <= Self.heartbeatTolerance ? .live : .notAnswering
        }
        let untilStart = startsAt - now.timeIntervalSince1970
        if untilStart <= 0 { return .startingSoon }
        return .scheduled
    }

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case topic
        case host
        case startsAt = "starts_at"
        case isRecurring = "is_recurring"
        case recurrenceText = "recurrence_text"
        case language
        case isLiveFlag = "is_live"
        case lastHeartbeatAt = "last_heartbeat_at"
        case hostingStrikes = "hosting_strikes"
    }
}

/// A room's live state, for the in-room screen. Assembled by
/// `VoiceRoomViewModel` from the transport and the roster, never from the
/// list's `isLiveFlag`.
struct VoiceRoomSession: Identifiable, Hashable {
    let room: VoiceRoom
    var connection: RoomConnectionState
    var participants: [VoiceRoomParticipant]
    var isRecording: Bool
    /// The signed-in user's role, which the server assigns on join.
    var selfRole: RoomRole
    var isSelfMuted: Bool
    var selfHandRaised: Bool
    var endedReason: String?

    var id: String { room.id }

    /// The only gate on saying the word "live" anywhere in this package.
    var isLive: Bool { connection == .live }

    var speakers: [VoiceRoomParticipant] {
        participants.filter { $0.role == .speaker || $0.role == .host }
    }

    var listeners: [VoiceRoomParticipant] {
        participants.filter { $0.role == .listener }
    }

    /// Plan 6.7's eight speakers and 200 listeners, spelled out on screen
    /// rather than enforced silently, so somebody who cannot get in knows it
    /// is a full room and not a broken app.
    static let maxSpeakers = 8
    static let maxListeners = 200

    /// Plan 6.7: recording is off by default, and when it is on, every
    /// speaker has to have agreed. This is the check behind the on-screen
    /// sign, and it is a hard gate rather than a warning — a room where the
    /// host has switched recording on and a speaker has not consented is not
    /// in a state the transport should be allowed to be in, and this is where
    /// that gets noticed.
    var recordingIsUnilateral: Bool {
        isRecording && speakers.contains { !$0.gaveRecordingConsent }
    }
}

// MARK: - Reports

/// Section 15: "Reports on every post, comment, room, listing and profile."
enum ReportSubject: String, Codable {
    case post
    case comment
    case room
    case member
    case listing

    var displayName: String {
        switch self {
        case .post: return SaathiText.t("saathi_report_post", "post")
        case .comment: return SaathiText.t("saathi_report_comment", "comment")
        case .room: return SaathiText.t("saathi_report_room", "room")
        case .member: return SaathiText.t("saathi_report_member", "profile")
        case .listing: return SaathiText.t("saathi_report_listing", "listing")
        }
    }
}

/// Section 15's automatic checks, turned into the choices a user is offered.
///
/// The list is the section's own list, in the user's words, and it is a list
/// of *reasons* rather than a list of *people* — "report as racist" puts the
/// user in the position of judging a colleague, which is not a call this app
/// should be making them.
enum ReportReason: String, Codable, CaseIterable, Identifiable {
    case scamOrPayment
    case abuse
    case personalInformation
    case politicsInTheWrongCircle
    case impersonation
    case spam
    case other

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .scamOrPayment:
            return SaathiText.t("saathi_report_scam", "A scam or someone asking for money")
        case .abuse:
            return SaathiText.t("saathi_report_abuse", "Abusive or upsetting language")
        case .personalInformation:
            return SaathiText.t("saathi_report_personal", "Someone's private information")
        case .politicsInTheWrongCircle:
            return SaathiText.t("saathi_report_politics", "Not allowed in this circle")
        case .impersonation:
            return SaathiText.t("saathi_report_impersonation", "Pretending to be somebody else")
        case .spam:
            return SaathiText.t("saathi_report_spam", "Spam or repeated advertisements")
        case .other:
            return SaathiText.t("saathi_report_other", "Something else")
        }
    }

    /// Section 15's response times: scam within two hours between 07:00 and
    /// 22:00, everything else within 24 hours. Shown on the button, before
    /// the report is sent, because "we will look at it" is not a promise a
    /// user can hold anybody to and this is.
    var responseWindow: String {
        switch self {
        case .scamOrPayment:
            return SaathiText.t(
                "saathi_report_window_scam",
                "We look at scam reports within 2 hours, between 7am and 10pm."
            )
        case .abuse, .personalInformation, .impersonation, .politicsInTheWrongCircle:
            return SaathiText.t("saathi_report_window_day", "We look at this within 24 hours.")
        case .spam, .other:
            return SaathiText.t("saathi_report_window_day", "We look at this within 24 hours.")
        }
    }
}

struct ReportDraft: Hashable {
    let subject: ReportSubject
    let subjectID: String
    var reason: ReportReason?
    var note: String
}
