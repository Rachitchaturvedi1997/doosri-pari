// UNTESTED — never compiled. See ios/README.md.
//
// Plan sections 4.5, 6.2 and 13 — Aaj (Today). This is the Swift twin of
// `android/.../feature/today/TodayModels.kt`, and the Android file is the
// specification for it: where the two differ, the Android one is the one that
// has been thought about longest, and a deliberate difference is commented
// here rather than left to be discovered.
//
// The style follows `CheckIn/CheckInModels.swift`: plain structs, explicit
// wire keys, no stage logic in the models.
//
// ## The one boundary in this file
//
// Plan section 14 and the section 10 note ("These stay on the phone, not on
// the server: pension amounts and slips") are enforced here by *splitting the
// services in two* rather than by remembering not to encode a field:
//
//  * `TodayFeedService` — the server. It may return a DR **rate**, because a
//    rate is a published table the admin maintains for everybody on that
//    service (plan 6.2: "from the admin table"), and it may return the credit
//    day the user entered, because that is a profile field and not money. It
//    has no method that returns a rupee amount belonging to this user, and
//    adding one would be a change to this protocol rather than an omission
//    from a DTO — which is the only way the rule survives a refactor.
//  * `OnDevicePensionSource` — the phone. `PensionSnapshot` holds the figures
//    from the on-device document safe. It is deliberately **not Codable**, has
//    no wire form, and is not returned by anything that has a base URL.
//
// Read that before "tidying" `PensionSnapshot` into `TodayFeed` "for
// consistency". A later edit that merges these two interfaces does not produce
// a warning; it puts a pensioner's net pension in the server's Postgres, next
// to the `sahayak_questions` hash, and the app can no longer be opened with
// the promise plan section 10 makes. The screen says this out loud too — see
// the pension card in `TodayView.swift`.
//
// ## Wire keys
//
// There is no `/today/feed` endpoint on the server yet (`python/features/`
// has no today module), so the keys below pin the intended contract. They are
// spelled out rather than left to `.convertFromSnakeCase` for the reason
// `CheckInModels.swift` gives: an explicit list breaks loudly on a rename.

import Foundation

// MARK: - Where a card's data came from

/// Plan section 13 puts Today in the offline column, so `cache` is a normal
/// state and not a failure: a pensioner on a train still sees yesterday's DR
/// rate and this morning's weather, and is told which they are looking at.
enum FeedSource: Equatable {
    case live
    case cache
}

/// One card's state, separately from every other card's.
///
/// A section that failed and a section with nothing in it must not look alike.
/// A pensioner who sees an empty "From your circles" card thinks they have no
/// circles; one who sees a "could not reach the server" line on the pension
/// card thinks their pension is in trouble. So `.failed` is a distinct case
/// rather than a nil value, and `.ready` carrying an empty array is what
/// "nothing today" looks like — the screen renders `DoosriEmptyState` for that.
enum Loadable<Value> {
    case loading
    case failed(message: String)
    case ready(value: Value, source: FeedSource, cachedAt: Date?)

    var valueOrNull: Value? {
        if case let .ready(value, _, _) = self { return value }
        return nil
    }

    /// True when what is on screen came off the phone's cache. The screen
    /// shows the saved-at line on the card, because a DR rate from yesterday
    /// and a DR rate from this morning are not the same claim.
    var isStale: Bool {
        if case let .ready(_, source, _) = self { return source == .cache }
        return false
    }

    var cachedAt: Date? {
        if case let .ready(_, _, cachedAt) = self { return cachedAt }
        return nil
    }
}

// MARK: - Sections

enum ReminderKind: String, Codable {
    case lifeCertificate = "life_certificate"
    case tax
    case cghsRenewal = "cghs_renewal"
    case medical
    case commutationRestoration = "commutation_restoration"
    case additionalPension = "additional_pension"
    case other
}

struct ReminderItem: Codable, Identifiable {
    let id: String
    let title: String
    /// Already a display string: "7:00 pm" and "1 November" in the user's
    /// locale. The server formats it, so a Hindi user reads "1 नवंबर" and this
    /// app does not have to know the word for November in ten languages.
    let whenLabel: String
    let kind: ReminderKind

    enum CodingKeys: String, CodingKey {
        case id, title, kind
        case whenLabel = "when_label"
    }
}

struct PracticeItem: Codable, Identifiable {
    let id: String
    let title: String
    let minutes: Int
    let subtitle: String
}

enum CircleHighlightKind: String, Codable {
    case post
    case birthday
    case retirement
}

struct CircleHighlight: Codable, Identifiable {
    let id: String
    let circle: String
    let line: String
    let kind: CircleHighlightKind
}

struct NewsItem: Codable, Identifiable {
    let id: String
    let headline: String
    let source: String
    /// Epoch seconds, as the server sends times everywhere else.
    let publishedAt: Double

    enum CodingKeys: String, CodingKey {
        case id, headline, source
        case publishedAt = "published_at"
    }
}

struct WeatherSnapshot: Codable {
    let city: String
    let summary: String
    let minC: Int
    let maxC: Int
    let airQuality: String?
    let observedAt: Double

    enum CodingKeys: String, CodingKey {
        case city, summary
        case minC = "min_c"
        case maxC = "max_c"
        case airQuality = "air_quality"
        case observedAt = "observed_at"
    }
}

struct ScamAlert: Codable, Identifiable {
    let id: String
    let title: String
    let body: String
}

struct SevaMatch: Codable, Identifiable {
    let id: String
    let title: String
    let why: String
}

struct LiveRoom: Codable, Identifiable {
    let id: String
    let title: String
    let host: String
    let listeners: Int
}

/// The current dearness rate, from the admin table (plan 6.2, 6.5).
///
/// A *rate*, not an amount: 58% is the same fact for a hundred thousand central
/// pensioners, and `sourceNote` names the order it comes from, because a rate
/// without its order is the thing this app exists to stop people repeating
/// from memory.
struct DrRateRow: Codable {
    let ratePercent: Double
    let effectiveFrom: Double
    let sourceNote: String

    enum CodingKeys: String, CodingKey {
        case ratePercent = "rate_percent"
        case effectiveFrom = "effective_from"
        case sourceNote = "source_note"
    }
}

struct PensionHeadline: Codable, Identifiable {
    let id: String
    let headline: String
    let source: String
    let publishedAt: Double

    enum CodingKeys: String, CodingKey {
        case id, headline, source
        case publishedAt = "published_at"
    }
}

// MARK: - The boundary

/// This user's own pension figures. **PHONE ONLY.**
///
/// Read from the on-device document safe (plan 6.5) or typed in by the user and
/// kept locally. Not Codable, not on any protocol that has a base URL, never
/// written to the server — see the note at the top of this file. The absence of
/// `Codable` here is the compiler's half of that promise; the comment is for
/// the person who adds it.
///
/// Every figure is optional rather than defaulted, because a pensioner who has
/// never opened the pension-slip reader must get an honest "we do not know
/// yet" from the pension card, not a ₹0 that reads like a garnishment.
struct PensionSnapshot {
    var basicPensionRupees: Double?
    var netPensionRupees: Double?
    var lastCreditedOn: Date?
    /// When these numbers were last read from the phone — not when the server
    /// saw anything, because the server never does.
    var readOn: Date?

    var hasAnyFigure: Bool {
        basicPensionRupees != nil || netPensionRupees != nil
    }

    static let empty = PensionSnapshot(
        basicPensionRupees: nil,
        netPensionRupees: nil,
        lastCreditedOn: nil,
        readOn: nil
    )
}

/// What the pension card shows, assembled by the view model from
/// `TodayFeedService` (rate, credit day, headline) and `OnDevicePensionSource`
/// (`local`). The split is visible in the type on purpose: a reader sees at a
/// glance which half of the card came off the phone.
struct PensionCard {
    /// The credit day the user entered — a profile field, so it is server-side.
    let creditDayOfMonth: Int
    let nextCreditDate: Date?
    let isCreditDayToday: Bool
    let dr: DrRateRow
    let headline: PensionHeadline?
    /// The phone's contribution. Nil until the user has read a slip.
    let local: PensionSnapshot?
}

/// Plan 6.2's credit-day rule, as a state machine.
///
/// "On credit day, a 'Did your pension credit?' prompt; five days of 'No' links
/// to the grievance helper." The five days are the whole reason this is a type
/// rather than a boolean: a single "No" is noise, and a persistent "No" is a
/// pensioner who needs a grievance drafted for them, which is a different
/// screen.
enum CreditPrompt: Equatable {
    /// Not the credit day; nothing to ask.
    case notToday
    /// Credit day, and the user has not been asked yet.
    case due
    /// The user answered today.
    case answered(saidNo: Bool)
    /// Five "No" answers with no "Yes" between them. The card now offers the
    /// grievance helper rather than asking a sixth time.
    case offerGrievance(noStreak: Int)
}

/// Plan 6.2's order logic is applied to these fields; see `TodayRanker`.
struct TodayFeed: Codable {
    let addressAs: String
    let dateLabel: String
    /// Tithi or vrat, only when the user has turned it on (plan 6.2 card 1).
    let vratLabel: String?
    let creditDayOfMonth: Int
    let dr: DrRateRow
    let headline: PensionHeadline?
    let reminders: [ReminderItem]
    let practice: PracticeItem?
    let circles: [CircleHighlight]
    let news: [NewsItem]
    let weather: WeatherSnapshot?
    let scamAlert: ScamAlert?
    let sevaMatch: SevaMatch?
    let liveRoom: LiveRoom?
    /// Section raw value → times opened in the last 14 days. Plan 6.2's
    /// ranking input. Kept as a plain dictionary on the wire and converted by
    /// the view model, so an unknown section id from a newer server is ignored
    /// rather than failing the whole feed.
    let opensIn14Days: [String: Int]
    /// Section raw value → epoch seconds of the last tap, for the 30-day
    /// demotion rule. Same treatment as above.
    let lastTapped: [String: Double]
    let cachedAt: Double?

    enum CodingKeys: String, CodingKey {
        case dr, headline, reminders, practice, circles, news, weather
        case addressAs = "address_as"
        case dateLabel = "date_label"
        case vratLabel = "vrat_label"
        case creditDayOfMonth = "credit_day_of_month"
        case scamAlert = "scam_alert"
        case sevaMatch = "seva_match"
        case liveRoom = "live_room"
        case opensIn14Days = "opens_in_14_days"
        case lastTapped = "last_tapped"
        case cachedAt = "cached_at"
    }
}

/// A feed plus where it came from. The view model needs both: `TodayFeed`
/// carries `cachedAt` for the words on screen, and `source` decides whether the
/// card says "saved on this phone".
struct TodayFeedResult {
    let feed: TodayFeed
    let source: FeedSource
}

/// Every card on the screen, in one enum so the ranker and "Edit Today" agree.
enum TodaySection: String, Codable, CaseIterable {
    case checkIn = "check_in"
    case pension
    case reminders
    case practice
    case circles
    case liveRoom = "live_room"
    case seva
    case scam
    case weather
    case news

    /// What "Edit Today" calls it, and what the section header says.
    var label: String {
        switch self {
        case .checkIn: return TodayText.t("today_section_check_in", "Daily check-in")
        case .pension: return TodayText.t("today_section_pension", "Pension")
        case .reminders: return TodayText.t("today_section_reminders", "Reminders")
        case .practice: return TodayText.t("today_section_practice", "Today's practice")
        case .circles: return TodayText.t("today_section_circles", "From your circles")
        case .liveRoom: return TodayText.t("today_section_live_room", "Live voice room")
        case .seva: return TodayText.t("today_section_seva", "Seva match")
        case .scam: return TodayText.t("today_section_scam", "Scam alert")
        case .weather: return TodayText.t("today_section_weather", "Weather and air")
        case .news: return TodayText.t("today_section_news", "News")
        }
    }
}

/// The check-in button's state, as Today needs it.
///
/// Today does **not** own check-in. The state machine, the escalation and the
/// server's truth live in `CheckIn/`, and the only thing this package does is
/// narrow that much larger state to the five things a button on this screen
/// needs. Re-implementing the transition here is how two screens end up
/// disagreeing about whether somebody is safe.
struct TodayCheckIn {
    /// Non-null once today's row is done; the design-system button swaps to a
    /// tick and this is the time it prints.
    var checkedAtLabel: String? = nil
    var sending: Bool = false
    var failed: Bool = false
    var paused: Bool = false
    /// Plan 6.2 card 2: the button is hidden entirely if check-in is off.
    var enabled: Bool = true
}

/// Whether the phone can reach the server, and what to say about it.
enum Connection: Equatable {
    case checking
    /// Live, or live-enough: the feed came back from the network.
    case online
    /// Plan section 13 lists Today in the offline column, so this is a state
    /// the screen renders in full rather than an error it renders over.
    /// `savedAt` is when the cache was written and is shown verbatim:
    /// "showing what this phone saved at 6:40 this morning" is something the
    /// user can act on; a spinner that never stops is not.
    case offline(savedAt: Date?)

    var savedAt: Date? {
        if case let .offline(savedAt) = self { return savedAt }
        return nil
    }
}

// MARK: - Services

/// The server half of Today.
///
/// A protocol, as in the check-in slice, so the view model is constructible
/// from a test with a fake and so the caching implementation can wrap an HTTP
/// one without the screen noticing (plan 13: Today works offline).
///
/// There is deliberately no method here that returns this user's own money.
/// See the boundary note at the top of this file — that absence is the feature.
protocol TodayFeedService {
    /// Cache first where the implementation has one, then the network.
    func load() async throws -> TodayFeedResult

    /// "Try again" after an offline banner. Bypasses any warm cache.
    func refresh() async throws -> TodayFeedResult

    /// Plan 6.2's "Edit Today": hide a card.
    func setHidden(_ hidden: Bool, section: TodaySection) async throws

    /// Feedback for the credit-day prompt's three buttons.
    func recordCreditAnswer(credited: Bool, on day: String) async throws
}

/// The phone half of Today.
///
/// Reads the on-device safe. The implementation is encrypted local storage
/// (plan 6.5: on iOS, files with Data Protection "complete"); what matters here
/// is that the protocol has no network sibling. **Never throws** — a pensioner
/// whose document safe cannot be read right now should see "we do not know
/// yet", not a broken Today screen.
protocol OnDevicePensionSource {
    func read() async -> PensionSnapshot
}

/// Check-in, as Today needs it. `CheckInBackedTodayCheckIn` in
/// `TodayService.swift` adapts `CheckIn/CheckInService`; this protocol exists
/// so Today compiles, tests and reasons without importing the check-in state
/// machine.
protocol TodayCheckInService {
    func todayCheckIn() async throws -> TodayCheckIn

    @discardableResult
    func recordCheckIn() async throws -> TodayCheckIn
}