// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.2 — Adhyatm Kendra, the spiritual half of the Kendra.
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// If you have just come from `YogModels.swift`, NONE of it carries over.
// There is no safety profile here, no doctor's note, no pose filter, no
// safety gate, and no flag of any colour. Nothing in this file is medical.
//
// Why this comment is so loud: the exercise code directly above this file is
// built around withholding things for safety, and the next person to edit
// this package will read that code first and reasonably assume the pattern is
// house style. It is not. Plan 7.2's own rule is the opposite register:
// "Suggestions, not claims — words like 'traditionally…' and 'many people
// find…'. No promises of health, wealth or outcomes." A spiritual suggestion
// is offered, never vetted. The only vetting here is licensing and respect
// (plan 7.2's content policy), and the only hard line in this package is that
// festival dates come from a licensed source and are never computed — see
// `FestivalDay`.
//
// ==========================================================================

import Foundation

// MARK: - Paths

/// Plan 7.2: "choose your path(s)… or several. The whole centre follows the
/// choice." Ordered by the plan; no faith is ranked above another, in code as
/// everywhere else.
enum SpiritualPath: String, Codable, CaseIterable {
    case hindu
    case muslim
    case sikh
    case christian
    case buddhist
    case jain
    case jewish

    var displayName: String {
        switch self {
        case .hindu: return KendraText.t("ken_path_hindu", "Hindu")
        case .muslim: return KendraText.t("ken_path_muslim", "Muslim")
        case .sikh: return KendraText.t("ken_path_sikh", "Sikh")
        case .christian: return KendraText.t("ken_path_christian", "Christian")
        case .buddhist: return KendraText.t("ken_path_buddhist", "Buddhist")
        case .jain: return KendraText.t("ken_path_jain", "Jain")
        case .jewish: return KendraText.t("ken_path_jewish", "Jewish")
        }
    }
}

// MARK: - Today's spiritual start

/// Plan 7.2's "Today's spiritual start": a suggestion by weekday and chosen
/// practice, "described as tradition, never as a promise", a mantra or prayer
/// with its meaning, and a 2-minute practice.
struct SpiritualStart: Codable, Equatable {
    /// "Traditionally, Tuesday is kept for…" — the wording is tradition, not
    /// instruction. Nothing here says what will happen if they follow it,
    /// because plan 7.2 forbids exactly that.
    let suggestionText: String

    let mantraText: String

    /// The meaning, in the user's language. Public-domain or licensed
    /// translations only (plan 7.2), credited in `sourceCredit`.
    let mantraMeaning: String
    let sourceCredit: String

    let twoMinutePractice: String
}

// MARK: - Japa and sankalp

/// Plan 7.2: a digital mala — 108, or 33 for tasbih — with haptic taps and a
/// daily goal, and a long sankalp with a finish estimate.
struct JapaState: Codable, Equatable {
    /// 108 for a mala, 33 for tasbih. Set with the path, never assumed.
    let malaLength: Int

    /// Counted today. The count is the user's own doing; it is not a score
    /// and there is nothing to win.
    var countToday: Int
    var dailyGoal: Int

    /// A long sankalp, e.g. 1,25,000 chants. Nil when there is none.
    var sankalpTotal: Int?
    var sankalpCount: Int?

    /// Plan 7.2: "a finish estimate". At their own recent pace only — the
    /// estimate is arithmetic on what they have done, never a prediction in
    /// the plan's forbidden sense. If there is no pace yet, there is no
    /// estimate.
    var sankalpFinishEstimate: String?
}

// MARK: - Festival and vrat calendar

/// Plan 7.2: "tithi and panchang from a licensed panchang data source (check
/// the licence of any astronomy library before use)".
///
/// WHY THE DATE IS A STRING THE SERVER FILLS IN, AND NOT A `Date` THE APP
/// COMPUTES: an auspicious date that is wrong is real harm to somebody who
/// planned a trip around it. Ekadashi a day early is a fast kept on the wrong
/// day; a Gurpurab date invented by a lunar algorithm nobody audited is a
/// family travelling to a gurdwara for an empty hall. The plan says the data
/// is licensed and refreshed from that source; it does not say "compute it
/// locally", and this app must never grow that feature. If this struct ever
/// gains a `Calendar`-based date calculation, delete it and read plan 7.2
/// again. The `sourceName` and `sourceLastUpdated` fields exist so the screen
/// can say where the date came from — attribution is part of the licence and
/// part of the trust.
struct FestivalDay: Codable, Identifiable, Equatable {
    let id: String

    var name: String

    /// The date AS THE LICENSED SOURCE SPELLS IT, in the user's language. Not
    /// derived from any calendar in this app. See the comment above.
    var dateText: String

    /// Region matters: plan 7.2 says "by region", because the same vrata is
    /// not on the same day in Kerala and in Punjab.
    var region: String

    var paths: [SpiritualPath]

    /// "Ekadashi", "vrat", "Gurpurab"… shown as a plain label.
    var kindLabel: String

    /// Plan 7.2: "reminders the evening before". Opt-in per day.
    var remindEveningBefore: Bool

    /// Attribution, shown on the calendar (see above).
    let sourceName: String
    let sourceLastUpdated: String
}

// MARK: - Ask the Scriptures

/// Plan 7.2: "questions answered from the verses, with citations".
struct ScriptureAnswer: Codable, Identifiable, Equatable {
    let id: String
    let question: String

    /// The answer's own words. Suggestion register throughout: "The Gita
    /// says…", never "you should…".
    let answerText: String

    /// Book, chapter, verse — plus the translation's licence credit, per
    /// plan 7.2 ("public-domain or openly licensed texts… each credited").
    let citations: [String]
}

/// The one disclaimer this screen ends with.
///
/// Plan 7.2 asks for a plain, non-dismissive line — no tiny grey text, no
/// tap-to-dismiss. ONE constant, used in ONE place (`SpiritualAskView`, at
/// the foot of the answer). If any other screen needs this sentence, that
/// screen makes its own wording decision; a shared constant used in five
/// places becomes boilerplate nobody reads, which is the same as not saying
/// it.
enum AdhyatmDisclaimer {
    static let generalInformation = KendraText.t(
        "ken_ask_disclaimer",
        "This is general information from the scriptures, not medical or spiritual advice. For advice, please speak to your own teacher or doctor."
    )
}

// MARK: - Daily thought

/// Plan 7.2: "a short line from a public-domain source, in the user's
/// language", shareable as a card.
struct DailyThought: Codable, Equatable {
    let text: String
    let sourceCredit: String
}