// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1 — Yog Kendra, the exercise half of the Kendra.
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// Everything in THIS FILE exists to keep a pensioner with a bad knee from
// being handed a squat. The safety profile, the doctor's note, the mobility
// check and `PoseFilter` below are for EXERCISE ONLY. They apply to
// programmes, poses and sessions. They do not apply — and must never be
// extended — to anything in `AdhyatmModels.swift` or `SpiritualStateView`,
// `FestivalCalendarView` or `SpiritualAskView`.
//
// If you have just read this file and are now opening an Adhyatm file: the
// rules you just read do NOT carry over. Japa, aarti, scripture, festivals
// and spiritual questions are practice, not exercise. Nobody has ever been
// injured by a mantra in the way they can be injured by a forward fold with
// glaucoma, and a "safety gate" in front of a bhajan would be superstition
// dressed as medicine — the exact opposite of what plan 7.2 asks for
// ("traditionally…", never a promise). The boundary is held in code here:
// `PoseFilter` is declared in this file and used only by exercise screens,
// and `AdhyatmService` takes no safety profile in any method.
//
// ==========================================================================

import Foundation

// MARK: - The safety profile

/// The health check from plan 7.1's "Safety first", asked before the first
/// session. Each case is one question and one filter rule.
enum SafetyFlag: String, Codable, CaseIterable {
    case highBloodPressureOrHeart
    case kneeReplacement
    case hipReplacement
    case vertigo
    case osteoporosis
    case glaucoma

    /// The question, asked plainly. Never with the word "contraindication".
    var question: String {
        switch self {
        case .highBloodPressureOrHeart:
            return KendraText.t(
                "ken_flag_bp",
                "Do you have high blood pressure or a heart condition?"
            )
        case .kneeReplacement:
            return KendraText.t(
                "ken_flag_knee",
                "Have you had a knee replacement?"
            )
        case .hipReplacement:
            return KendraText.t(
                "ken_flag_hip",
                "Have you had a hip replacement?"
            )
        case .vertigo:
            return KendraText.t(
                "ken_flag_vertigo",
                "Do you get vertigo or dizziness?"
            )
        case .osteoporosis:
            return KendraText.t(
                "ken_flag_osteo",
                "Have you been told you have osteoporosis?"
            )
        case .glaucoma:
            return KendraText.t(
                "ken_flag_glaucoma",
                "Have you been told you have glaucoma?"
            )
        }
    }

    /// Plan 7.1's table, as the reason shown to the user for a withheld pose.
    /// Spoken as a fact about the programme, not about their body — the
    /// doctor is the one who talks about their body.
    var removalRule: String {
        switch self {
        case .highBloodPressureOrHeart:
            return KendraText.t(
                "ken_rule_bp",
                "No inversions or strong forward folds below the heart, no kapalbhati, no breath holding"
            )
        case .kneeReplacement:
            return KendraText.t(
                "ken_rule_knee",
                "No deep knee bends, kneeling or squats"
            )
        case .hipReplacement:
            return KendraText.t(
                "ken_rule_hip",
                "No crossing legs past the midline, no deep hip bends"
            )
        case .vertigo:
            return KendraText.t(
                "ken_rule_vertigo",
                "No quick head movements; standing only with a chair or wall"
            )
        case .osteoporosis:
            return KendraText.t(
                "ken_rule_osteo",
                "No loaded forward bends or strong twists"
            )
        case .glaucoma:
            return KendraText.t(
                "ken_rule_glaucoma",
                "No head-below-heart poses"
            )
        }
    }
}

enum BalanceStatus: String, Codable, CaseIterable {
    case steady
    case holdsSomething
    case unsteady

    var displayName: String {
        switch self {
        case .steady: return KendraText.t("ken_balance_steady", "Steady")
        case .holdsSomething: return KendraText.t("ken_balance_hold", "I hold a chair or wall")
        case .unsteady: return KendraText.t("ken_balance_unsteady", "Unsteady")
        }
    }
}

/// Recorded because the doctor said something worth repeating on the
/// programme screen (plan 7.1: "Doctor's note" field). It is shown verbatim,
/// attributed to nobody, interpreted by nobody.
struct SafetyProfile: Codable, Equatable {
    var flags: Set<SafetyFlag> = []

    /// Past injuries and sore joints, in the pensioner's own words. Free text
    /// list: "left shoulder, 2019" is more useful than a checkbox grid, and
    /// this audience types the way they talk.
    var injuries: [String] = []
    var joints: [String] = []
    var balance: BalanceStatus = .steady

    /// Their blood pressure situation as they understand it. Not a reading —
    /// readings live in Swasthya and are not read by anyone but the doctor.
    var bloodPressureMentioned: Bool = false

    /// Plan 7.1: "the user can type or say any advice their doctor gave; it
    /// shows on the programme screen". Shown, never edited, never analysed.
    var doctorNote: String?

    /// When the check was last answered. Plan 7.1 asks before the FIRST
    /// session; the date is kept so the screen can say how old the answers
    /// are without inventing an expiry policy the plan does not have.
    var answeredAt: Double?

    var isAnswered: Bool { answeredAt != nil }
}

// MARK: - Poses

/// Plan 7.1's pose library: about 25 poses, all drawn by the instructor
/// engine. `group` decides what is drawn around the instructor (chair, wall).
enum PoseGroup: String, Codable, CaseIterable {
    case seated
    case standingWithSupport
    case restAndBreath

    var displayName: String {
        switch self {
        case .seated: return KendraText.t("ken_group_seated", "Seated")
        case .standingWithSupport: return KendraText.t("ken_group_standing", "Standing with support")
        case .restAndBreath: return KendraText.t("ken_group_rest", "Rest and breath")
        }
    }
}

struct Pose: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var group: PoseGroup

    /// The safety flags that REMOVE this pose outright. A pose with an empty
    /// set is offered to everyone — which is itself a decision, so the list
    /// is written out in full rather than left as a default.
    var removedBy: Set<SafetyFlag>

    /// The safety flags that CHANGE this pose instead of removing it, where
    /// plan 7.1's table says "changed" rather than "removed" (vertigo:
    /// "stand only with a chair or wall"). The two sets are separate on
    /// purpose: a pose must never be "offered with a note" to somebody whose
    /// flag removes it outright.
    var changedFor: Set<SafetyFlag>

    /// The change itself, shown beside the pose when a `changedFor` flag is
    /// answered. Written as an instruction, not a warning.
    var changeNote: String?

    /// The spoken cue, word for word on screen (plan 7.1: "captions on
    /// screen, word for word").
    var voiceCue: String

    /// Plan 7.1: "Every movement at 1.5x Moholla's time, with longer holds".
    var holdSeconds: Int
}

/// A pose withheld from a programme, and why. See `ProgrammeListView` where
/// this is drawn — the plan's failure mode is silence.
struct WithheldPose: Identifiable, Equatable {
    let pose: Pose
    let flag: SafetyFlag

    var id: String { pose.id }

    var reason: String { flag.removalRule }
}

/// The filter, and the safety-critical half of the CENTRAL RULE above.
///
/// It returns BOTH lists. A function that returned only the offered poses
/// would be a function that lies by omission: the caller would render a
/// shorter programme and the pensioner would never know a pose existed. See
/// `ProgrammeListView` — the withheld list is drawn, always.
enum PoseFilter {

    /// - Returns: poses that are safe to offer, and poses withheld with the
    ///   flag that withheld them. A pose answered by a `changedFor` flag is
    ///   offered with its change note; a pose answered by a `removedBy` flag
    ///   is withheld, always, whatever else is set on it.
    static func partition(
        _ poses: [Pose],
        profile: SafetyProfile
    ) -> (offered: [OfferedPose], withheld: [WithheldPose]) {
        var offered: [OfferedPose] = []
        var withheld: [WithheldPose] = []

        for pose in poses {
            // A pose is only withheld for a flag the pensioner actually
            // answered yes to. Unanswered questions remove nothing — the
            // alternative is hiding half the app from somebody who skipped a
            // question, which plan 7.1 never asked for.
            let removals = pose.removedBy.intersection(profile.flags)

            if !removals.isEmpty {
                // Withheld. The reason is the flag's own words, shown to the
                // user. The first flag in a stable order, so the reason does
                // not shuffle between launches.
                let flag = removals.sorted { $0.rawValue < $1.rawValue }.first!
                withheld.append(WithheldPose(pose: pose, flag: flag))
                continue
            }

            let changes = pose.changedFor.intersection(profile.flags)
            if !changes.isEmpty, let note = pose.changeNote {
                // Changed, not removed (plan 7.1's vertigo row). The note is
                // the change, shown beside the pose.
                offered.append(OfferedPose(pose: pose, changeNote: note))
                continue
            }

            offered.append(OfferedPose(pose: pose, changeNote: nil))
        }

        return (offered, withheld)
    }
}

/// A pose offered to the user, possibly with a modification note.
struct OfferedPose: Identifiable, Equatable {
    let pose: Pose
    let changeNote: String?

    var id: String { pose.id }
}

// MARK: - Programmes and sessions

enum ProgrammeLevel: String, Codable {
    case gentle
    case steady

    var displayName: String {
        switch self {
        case .gentle: return KendraText.t("ken_level_gentle", "Gentle")
        case .steady: return KendraText.t("ken_level_steady", "Steady")
        }
    }
}

/// The six beats every programme shares (plan 7.1): settle and breathe →
/// warm-up → main → cool-down → breath → rest.
enum SessionPhase: String, Codable, CaseIterable {
    case settle
    case warmUp
    case main
    case coolDown
    case breath
    case rest

    var displayName: String {
        switch self {
        case .settle: return KendraText.t("ken_phase_settle", "Settle and breathe")
        case .warmUp: return KendraText.t("ken_phase_warm", "Warm-up")
        case .main: return KendraText.t("ken_phase_main", "Main practice")
        case .coolDown: return KendraText.t("ken_phase_cool", "Cool-down")
        case .breath: return KendraText.t("ken_phase_breath", "Breath")
        case .rest: return KendraText.t("ken_phase_rest", "Rest")
        }
    }
}

struct Programme: Codable, Identifiable, Equatable {
    let id: String
    var title: String
    var minutes: Int
    var level: ProgrammeLevel
    var summary: String

    /// The full pose list before filtering. The player runs the filtered one;
    /// the programme screen shows both.
    var poses: [Pose]
}

/// One step in the running session — the player's unit.
struct SessionStep: Identifiable, Equatable {
    let id: String
    let phase: SessionPhase
    let pose: Pose
}

// MARK: - Progress and mobility check

/// Plan 7.1: "shown kindly: 'You practised 4 days this week'".
struct PracticeWeek: Codable, Equatable {
    let weekStart: Double
    let daysPractised: Int
    let minutes: Int
}

/// Plan 7.1's monthly mobility check, from the Senior Fitness Test: the
/// 30-second chair stand and the single-leg stand with support.
struct MobilityResult: Codable, Identifiable, Equatable {
    let id: String

    /// Epoch seconds of when the check was done.
    let takenAt: Double

    /// How many times they stood up from a chair in 30 seconds.
    let chairStands: Int

    /// Seconds balanced on one leg holding support. Support is assumed: plan
    /// 7.1's test is explicitly "with support" and a fall during a self-test
    /// would be this app's fault.
    let singleLegStandSeconds: Double
}

// MARK: - Yoga saathi

/// Plan 7.1's "Together": pair with a friend; each sees whether the other
/// practised today and can send a "Shabash". No scores, no streaks, no
/// comparison — the plan is explicit that progress is "never compared with
/// others", and that extends to the pair.
struct YogaPartner: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var practicedToday: Bool

    /// Epoch seconds of the last "Shabash" sent TO this partner, so the
    /// button can say whether it has already been sent today.
    var lastShabashSentAt: Double?

    var canSendShabash: Bool {
        guard practicedToday else { return false }
        guard let last = lastShabashSentAt else { return true }
        return Date().timeIntervalSince1970 - last > 20 * 60 * 60
    }
}

/// The two instructors, plan 7.1: "so users see someone like themselves".
enum Instructor: String, Codable, CaseIterable {
    case dadi
    case dada

    var displayName: String {
        switch self {
        case .dadi: return KendraText.t("ken_dadi", "Dadi")
        case .dada: return KendraText.t("ken_dada", "Dada")
        }
    }
}