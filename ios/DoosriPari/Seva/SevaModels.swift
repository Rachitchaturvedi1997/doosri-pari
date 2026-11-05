// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.8, the Anubhav board: mentoring, skills, help, paid work and
// volunteering. This file is the model half — see `SevaService.swift` for the
// network seam and the screen files for the views.
//
// Wire keys are spelled out per `SaathiModels.swift`'s rule: a strategy change
// must fail a test rather than quietly show a pensioner a stale listing.
//
// Trust levels and the report types come from `SaathiModels.swift`. They are
// app-wide vocabulary (section 15 applies to listings and profiles exactly as
// it applies to posts), and until somebody gives them their own file there is
// exactly one definition of "T1" in this app rather than two.

import Foundation

// MARK: - Categories

/// Plan 6.8's table, verbatim in shape.
///
/// The money direction is part of the type, because it is the difference
/// between a legitimate session and a scam: in `paidWork` the *partner* pays
/// the pensioner, and any listing of that kind asking the pensioner for money
/// is blocked, not warned about. See `SevaListingDraft.validate()`.
enum SevaCategory: String, Codable, CaseIterable, Identifiable {
    case mentoring
    case skill
    case help
    case paidWork = "paid_work"
    case volunteer

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .mentoring: return SevaText.t("seva_cat_mentoring", "Mentoring")
        case .skill: return SevaText.t("seva_cat_skill", "Skill")
        case .help: return SevaText.t("seva_cat_help", "Help needed")
        case .paidWork: return SevaText.t("seva_cat_paid_work", "Paid work")
        case .volunteer: return SevaText.t("seva_cat_volunteer", "Volunteering")
        }
    }

    /// Plan 4.3: an SF Symbol, always beside a text label. The label is the
    /// name; this is decoration pointing at the same thing.
    var symbolName: String {
        switch self {
        case .mentoring: return "person.2"
        case .skill: return "wrench.and.screwdriver"
        case .help: return "hands.and.arrow.down"
        case .paidWork: return "indianrupeesign.circle"
        case .volunteer: return "hands.sparkles"
        }
    }

    /// Who may post it. Shown on the create screen before the user types, so
    /// nobody writes out a paid-work listing only to be told it is closed.
    var whoMayPost: String {
        switch self {
        case .mentoring, .skill:
            return SevaText.t("seva_cat_t1", "Members who have verified their service (T1 and above).")
        case .help:
            return SevaText.t("seva_cat_anyone", "Anyone.")
        case .paidWork:
            return SevaText.t("seva_cat_partners", "Verified partner organisations only.")
        case .volunteer:
            return SevaText.t("seva_cat_ngo", "Verified NGOs and schools only.")
        }
    }

    /// Whether the person reading the listing can be the one who pays. Only
    /// mentoring and skill sessions are paid by the seeker, and only through
    /// the payment step — never by handing money to anybody.
    var seekerMayBeCharged: Bool {
        self == .mentoring || self == .skill
    }
}

// MARK: - Ratings

/// Plan 6.8's matching formula uses "rating (Bayesian, prior 4.0 from 5)".
/// The raw sums are kept, not an average: an average of three 5-star ratings
/// is indistinguishable from an average of three hundred, and the shrinkage
/// below is the whole point of the prior.
struct SevaRating: Hashable, Codable {
    /// Sum of the star values, 1...5 each.
    let points: Int
    let count: Int

    /// The prior: as if everybody starts at 4.0 after five opinions.
    private static let priorMean: Double = 4.0
    private static let priorCount: Double = 5.0

    /// The number used for ranking. A rating is not money — this is the one
    /// place in Seva where a `Double` is correct, and it never touches
    /// `Paise`.
    var bayesian: Double {
        (Double(points) + Self.priorMean * Self.priorCount)
            / (Double(count) + Self.priorCount)
    }

    /// "New here" until somebody has actually rated, rather than a confident
    /// 4.0 invented out of the prior.
    var displayText: String {
        count == 0
            ? SevaText.t("seva_rating_none", "New here")
            : SevaText.t(
                "seva_rating_text",
                "\(String(format: "%.1f", bayesian)) from \(count) \(count == 1 ? "person" : "people")"
            )
    }
}

// MARK: - Providers

/// Who stands behind a listing.
///
/// **This field list is the point of the section.** The screen shows:
///
///   displayName     the name they chose to be shown by
///   city            ONE city — not a locality, not a pin code, not a landmark
///   trustLevel      T0/T1/T2, drives what they may post
///   isVerifiedPartner   for paid work and volunteering, plan 6.8
///   rating          the Bayesian rating above
///
/// There is no home address. There is no office address. There is no phone
/// number, no WhatsApp handle, no email, no latitude/longitude, and no
/// "meet me at" that is narrower than the city. The reason is the same as in
/// `SaathiMember`: a retired government employee's city plus department plus
/// service years already narrows them to a handful of people, and the last
/// hundred metres is the difference between a mentor and a target. A booking
/// screen that leaks an address turns the Anubhav board into a directory of
/// pensioners who are at home on Tuesday afternoons, and they are exactly the
/// population section 15 exists to protect.
///
/// Contact happens through the app's own booking and chat, or in person at a
/// public place the two people choose when the booking is confirmed. The
/// model has nowhere to hold anything narrower than a city, so no endpoint can
/// populate one by accident.
struct SevaProvider: Identifiable, Hashable, Codable {
    let id: String
    let displayName: String
    /// City granularity, always. A listing that needs a meeting place gives a
    /// public venue at booking time, not a home.
    let city: String
    let department: String?
    let trustLevel: TrustLevel
    let isVerifiedPartner: Bool
    let rating: SevaRating?

    var initials: String {
        let parts = displayName
            .split(separator: " ")
            .prefix(2)
            .compactMap { $0.first }
        return parts.isEmpty ? "?" : String(parts).uppercased()
    }

    /// The one line under the name. Composed here so the screen cannot grow a
    /// fifth field by accident.
    var subtitle: String {
        var parts = [city]
        if let department { parts.append(department) }
        return parts.joined(separator: " · ")
    }

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
        case city
        case department
        case trustLevel = "trust_level"
        case isVerifiedPartner = "is_verified_partner"
        case rating
    }
}

// MARK: - Listings

/// One row on the Anubhav board. Plan 6.8.
struct SevaListing: Identifiable, Hashable, Codable {
    let id: String
    let title: String
    let summary: String
    let category: SevaCategory
    let provider: SevaProvider
    /// Held in paise. Zero is a real price — "Free" — and not a missing value.
    let costPaise: Int64
    /// Plan 6.8's matching formula treats online as distance 1.0, so remote
    /// work is a first-class case and not an afterthought.
    let isRemote: Bool
    /// Where an in-person session happens — the **city**, never a venue and
    /// never an address. See `SevaProvider` for why.
    let city: String?
    /// A bucket, never metres. See `CoarseDistance` in `SaathiModels.swift`.
    let distance: CoarseDistance
    let languages: [String]
    let skillTags: [String]
    /// Free-text windows like "weekday mornings"; matching treats them as tags.
    let availabilityTags: [String]
    let sessionMinutes: Int?
    let createdAt: Double

    var createdDate: Date { Date(timeIntervalSince1970: createdAt) }
    var isFree: Bool { Paise.isFree(costPaise) }

    /// The money line, in words the plan uses. Plan 6.8: paid work is "paid by
    /// the partner", so the seeker reading it should see no price at all
    /// rather than a price of ₹0 that reads like a trick.
    var moneyText: String {
        switch category {
        case .paidWork:
            return SevaText.t("seva_money_by_partner", "Paid by the partner")
        case .help, .volunteer:
            return SevaText.t("seva_money_free", "Free")
        case .mentoring, .skill:
            return Paise.perSession(costPaise)
        }
    }

    /// Plan 6.8's hard-coded anti-scam rule: "every paid listing shows 'Never
    /// pay to get work'". It is shown on the categories where somebody could
    /// plausibly be tricked into paying to be employed, not as a small print
    /// under a price.
    var showsNeverPayWarning: Bool {
        category == .paidWork || category == .volunteer
    }

    var neverPayWarning: String {
        SevaText.t("seva_never_pay", "Never pay to get work.")
    }

    /// "Online" or "In Lucknow" — city granularity, never narrower.
    var placeText: String {
        if isRemote {
            return SevaText.t("seva_place_online", "Online")
        }
        guard let city else {
            return SevaText.t("seva_place_unstated", "Place agreed when the booking is confirmed")
        }
        return SevaText.t("seva_place_city", "In \(city)")
    }

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case summary
        case category
        case provider
        case costPaise = "cost_paise"
        case isRemote = "is_remote"
        case city
        case distance
        case languages
        case skillTags = "skill_tags"
        case availabilityTags = "availability_tags"
        case sessionMinutes = "session_minutes"
        case createdAt = "created_at"
    }
}

// MARK: - Bookings

/// A booking's state.
///
/// **The state is never rendered as an enum on screen.** There is no code path
/// that prints "requested" or a raw value; every surface shows one of the
/// sentences in `statusSentence(providerName:)`, because "requested" is a
/// developer's word and "Requested — waiting for Anil ji to say yes" is a
/// sentence a seventy-one-year-old can act on. The strings below are the whole
/// vocabulary the user ever sees for a booking, so the day the flow gains a
/// state, the sentence is written here or the state does not exist.
enum BookingState: String, Codable, CaseIterable {
    case requested
    case confirmed
    case completed
    case cancelled

    /// The one word on the chip. A word, never the case name.
    var shortWord: String {
        switch self {
        case .requested: return SevaText.t("seva_state_requested", "Requested")
        case .confirmed: return SevaText.t("seva_state_confirmed", "Confirmed")
        case .completed: return SevaText.t("seva_state_completed", "Completed")
        case .cancelled: return SevaText.t("seva_state_cancelled", "Cancelled")
        }
    }

    /// The sentence under the chip: what has happened and what happens next.
    func statusSentence(providerName: String) -> String {
        switch self {
        case .requested:
            return SevaText.t(
                "seva_state_requested_note",
                "You asked \(providerName). They usually answer within a day."
            )
        case .confirmed:
            return SevaText.t(
                "seva_state_confirmed_note",
                "\(providerName) said yes. The session is on."
            )
        case .completed:
            return SevaText.t(
                "seva_state_completed_note",
                "Both of you confirmed that the session happened."
            )
        case .cancelled:
            return SevaText.t(
                "seva_state_cancelled_note",
                "This booking was cancelled. Nobody is charged."
            )
        }
    }

    var symbolName: String {
        switch self {
        case .requested: return "hourglass"
        case .confirmed: return "checkmark.circle"
        case .completed: return "checkmark.seal"
        case .cancelled: return "xmark.circle"
        }
    }

    /// The legal moves. Terminal states stay terminal: a completed session
    /// cannot be un-completed on a phone in a pocket.
    func canMove(to next: BookingState) -> Bool {
        switch self {
        case .requested: return next == .confirmed || next == .cancelled
        case .confirmed: return next == .completed || next == .cancelled
        case .completed, .cancelled: return false
        }
    }
}

/// One booking, as "My bookings" shows it.
struct SevaBooking: Identifiable, Hashable, Codable {
    let id: String
    let listingID: String
    let listingTitle: String
    /// Name only. The booking confirms a *session*; the provider's address is
    /// never part of it. See the field list on `SevaProvider`.
    let providerName: String
    let costPaise: Int64
    let state: BookingState
    let requestedAt: Double
    /// What the two people agreed: "Tuesday 15 October, 11 am", or "Online,
    /// this week". Words, not a calendar object, because the plan's booking is
    /// a conversation before it is a slot.
    let slotText: String
    let seekerNote: String?
    /// Whether the money has moved. Free bookings are trivially "paid".
    let isPaid: Bool

    var requestedDate: Date { Date(timeIntervalSince1970: requestedAt) }
    var isFree: Bool { Paise.isFree(costPaise) }

    /// The words on the row. Built from `BookingState`'s sentences so there is
    /// one place where a state turns into language.
    var statusText: String { state.statusSentence(providerName: providerName) }
    var statusWord: String { state.shortWord }

    /// Plan 6.8 step 5: "disputes go to moderators". A paid session that has
    /// happened is the only thing worth disputing, so that is the only place
    /// the door is shown.
    var mayRaiseDispute: Bool {
        state == .completed && !isFree
    }

    enum CodingKeys: String, CodingKey {
        case id
        case listingID = "listing_id"
        case listingTitle = "listing_title"
        case providerName = "provider_name"
        case costPaise = "cost_paise"
        case state
        case requestedAt = "requested_at"
        case slotText = "slot_text"
        case seekerNote = "seeker_note"
        case isPaid = "is_paid"
    }
}

// MARK: - Seeking profile (plan 6.8's matching formula)

/// The signed-in user's side of the matching formula. Everything in it is
/// something the user typed or picked; nothing is inferred from their
/// department or their posts.
struct SevaSeekerProfile: Hashable, Codable {
    var skillTags: [String]
    var languages: [String]
    var availabilityTags: [String]
    var city: String?

    static let empty = SevaSeekerProfile(
        skillTags: [],
        languages: [],
        availabilityTags: [],
        city: nil
    )

    enum CodingKeys: String, CodingKey {
        case skillTags = "skill_tags"
        case languages
        case availabilityTags = "availability_tags"
        case city
    }
}

// MARK: - Filters and sorting

enum SevaSort: String, Codable, CaseIterable, Identifiable {
    case bestMatch = "best_match"
    case newest
    case nearest

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .bestMatch: return SevaText.t("seva_sort_match", "Best match")
        case .newest: return SevaText.t("seva_sort_newest", "Newest first")
        case .nearest: return SevaText.t("seva_sort_nearest", "Nearest first")
        }
    }
}

struct SevaFilter: Hashable {
    var category: SevaCategory?
    var freeOnly = false
    var remoteOnly = false
    var sort: SevaSort = .bestMatch

    func matches(_ listing: SevaListing) -> Bool {
        if let category, listing.category != category { return false }
        if freeOnly, !listing.isFree { return false }
        if remoteOnly, !listing.isRemote { return false }
        return true
    }
}

// MARK: - Drafts

/// What the create screen hands the service. Money is paise here too — the
/// form's rupee field is parsed by `Paise.paise(fromRupees:)` and never held
/// as a decimal the user typed.
struct SevaListingDraft: Hashable {
    var title: String
    var summary: String
    var category: SevaCategory
    var costPaise: Int64
    var isRemote: Bool
    /// City, one word pair, or nothing. Never a street.
    var city: String?
    var languages: [String]
    var skillTags: [String]
    var availabilityTags: [String]
    var sessionMinutes: Int?

    /// Plan 6.8's anti-scam rules, checked before anything is sent:
    ///
    /// > partners must be verified businesses
    /// > any listing asking the pensioner to pay is blocked
    /// > every paid listing shows "Never pay to get work"
    ///
    /// The first is a server rule — only the server knows who is verified — so
    /// the client checks the two it can see, and the one it can check most
    /// importantly: a category whose money flows *to* the pensioner must never
    /// carry a price. A "paid work" listing with a fee is the exact shape of
    /// the "job scam" this audience is already hunted with, and it is blocked
    /// at the button rather than flagged after posting.
    func validate() -> [SevaValidationError] {
        var problems: [SevaValidationError] = []

        if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            problems.append(.titleNeeded)
        }
        if summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            problems.append(.summaryNeeded)
        }

        switch category {
        case .help, .volunteer, .paidWork:
            if costPaise > 0 {
                problems.append(.seekerMustNotPay(category))
            }
        case .mentoring, .skill:
            if costPaise < 0 {
                problems.append(.negativeAmount)
            }
        }

        if !isRemote && (city?.trimmingCharacters(in: .whitespaces).isEmpty ?? true) {
            problems.append(.cityNeeded)
        }
        return problems
    }
}

enum SevaValidationError: Hashable {
    case titleNeeded
    case summaryNeeded
    case cityNeeded
    case negativeAmount
    case seekerMustNotPay(SevaCategory)

    /// The sentence on the form, written so the user knows what to change
    /// rather than that something is wrong.
    var message: String {
        switch self {
        case .titleNeeded:
            return SevaText.t("seva_err_title", "Give the listing a title.")
        case .summaryNeeded:
            return SevaText.t("seva_err_summary", "Say a little about what you are offering or asking for.")
        case .cityNeeded:
            return SevaText.t("seva_err_city", "Say which city. One city is enough — never a home address.")
        case .negativeAmount:
            return SevaText.t("seva_err_negative", "An amount cannot be negative.")
        case .seekerMustNotPay:
            return SevaText.t(
                "seva_err_pay_to_work",
                "In this kind of listing nobody pays you, and you never pay to get work. A listing that asks a member for money is not allowed."
            )
        }
    }
}

struct SevaBookingDraft: Hashable {
    let listingID: String
    /// The words the seeker proposes: "Tuesday morning", "Online, Thursday".
    var slotText: String
    var seekerNote: String
}

// MARK: - Payment (plan 6.8)

/// What the pay step shows before the confirm step, so the confirm step can be
/// one button and nothing else. Minted by the server — the client never
/// assembles its own price quote, because a quote the client computed is a
/// quote the client can get wrong about somebody's money.
struct SevaPaymentQuote: Hashable {
    let bookingID: String
    let listingTitle: String
    /// Who receives the money, by name and city.
    let payeeName: String
    let payeeCity: String
    /// The full amount the seeker pays, in paise.
    let amountPaise: Int64
    /// Plan 6.8: the mentor is paid 48 hours after both sides confirm, minus
    /// 12%. Shown on the pay step as words and amounts, computed from paise.
    let payoutDelayText: String

    var feePaise: Int64 { Paise.platformFee(amountPaise) }
    var payeePayoutPaise: Int64 { Paise.payoutAfterFee(amountPaise) }
}

/// The receipt, after the gateway (Razorpay, per plan 6.8 — a payment gateway,
/// not in-app purchase, because mentoring is a real-world service between two
/// people) has taken the money.
struct SevaPaymentReceipt: Hashable {
    let id: String
    let bookingID: String
    let amountPaise: Int64
    let paidAt: Double

    var paidDate: Date { Date(timeIntervalSince1970: paidAt) }
}