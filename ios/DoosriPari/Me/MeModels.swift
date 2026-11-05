// UNTESTED — never compiled. See ios/README.md.
//
// Plan sections 5 (the Main tab), 6.9 (Meri Kahani), 6.11 (Seekho), 6.12
// (Scam shield), 6.13 (Family connect), 6.15 (Notifications) and 14 (the
// three consents and the document safe). Keys are spelled out rather than
// left to `.convertFromSnakeCase`, matching `CheckInModels.swift` and
// `SaathiModels.swift`.
//
// The models that carry personal text are commented where that text lives.
// This package holds stories, family links and safety decisions; it is the
// most personal surface in the app and the fields say so.

import Foundation

// MARK: - Meri Kahani (plan 6.9)

/// One of 52 weekly prompts — "your first day at work" through "advice for
/// your grandchildren".
struct StoryPrompt: Codable, Identifiable, Equatable {
    let id: String
    let weekNumber: Int
    var text: String
}

/// One answer to one prompt.
struct StoryEntry: Codable, Identifiable, Equatable {
    let id: String
    let promptID: String

    /// THE STORY TEXT.
    ///
    /// A story is deeply personal and often about loss, illness or money.
    /// This field is the most private string in the app. It is private by
    /// default, it is never published anywhere — not to a circle, not to
    /// family, not to the book — without the user choosing to share it
    /// themselves, per chapter, on the chapter's own screen. Nothing in this
    /// codebase may read this field to "suggest", summarise, advertise or
    /// train on. If a future feature needs it, the answer is no; if the user
    /// needs it, they will press Share.
    var text: String

    /// The original voice, kept alongside the words (plan 6.9: edit "by
    /// re-recording a paragraph"). Local file path — on the phone, like
    /// everything else personal here.
    var audioPath: String?

    /// Plan 6.9: up to 10 minutes per answer.
    var durationSeconds: Int?

    /// Speech-to-text in the user's language (plan 6.9); the text above is
    /// the transcript they then edited.
    var wasTranscribed: Bool

    let recordedAt: Double
}

/// Plan 6.9: "Chapters: auto-grouped (childhood, career, family, wisdom),
/// with photos" and "private by default; shared per chapter".
enum ChapterShare: String, Codable, CaseIterable {
    /// The default, and the state every chapter starts in.
    case privateOnly
    case sharedWithFamily
    case sharedToCircles

    var displayName: String {
        switch self {
        case .privateOnly: return MeText.t("me_share_private", "Only me")
        case .sharedWithFamily: return MeText.t("me_share_family", "My family")
        case .sharedToCircles: return MeText.t("me_share_circles", "My circles")
        }
    }
}

struct StoryChapter: Codable, Identifiable, Equatable {
    let id: String
    var title: String
    var entryIDs: [String]
    var photoPaths: [String]
    var share: ChapterShare

    /// Plan 6.9: "a QR per chapter that plays the original voice", for the
    /// printed book in phase 3.
    var bookPageReference: String?
}

// MARK: - Seekho (plan 6.11)

struct LearnLesson: Codable, Identifiable, Equatable {
    enum Category: String, Codable {
        case smartphone
        case moneySafety
        case governmentApps
        case paperwork
        case hobby
    }

    let id: String
    var title: String
    var category: Category

    /// Plan 6.11: "3–5 minutes each".
    var minutes: Int

    /// Plan 6.11: "a 3-question check and a badge at the end of each lesson".
    var hasCheck: Bool
    var badgeEarned: Bool

    /// "Teach me live": 20-minute volunteer calls (plan 6.11).
    var liveCallAvailable: Bool

    var categoryName: String {
        switch category {
        case .smartphone: return MeText.t("me_learn_phone", "Phone basics")
        case .moneySafety: return MeText.t("me_learn_money", "Money safety")
        case .governmentApps: return MeText.t("me_learn_gov", "Government apps")
        case .paperwork: return MeText.t("me_learn_papers", "Paperwork")
        case .hobby: return MeText.t("me_learn_hobby", "Hobbies")
        }
    }
}

// MARK: - Scam shield (plan 6.12)

/// A moderated alert from the feed — "fake pension office calls", "fake
/// life-certificate links", "KYC scams", "'digital arrest' calls", "job
/// scams" (plan 6.12).
struct ScamAlert: Codable, Identifiable, Equatable {
    let id: String
    var title: String
    var body: String
    var dateText: String

    /// Who checked this before it was published. Plan 6.12 says the feed is
    /// moderated; the moderator's name is part of why the alert is believed.
    var reviewedBy: String
}

/// The hard rules from plan 6.12, checked before any explanation: OTP, PIN
/// or CVV requests, urgency, links, payment requests and threats.
enum ScamSignal: String, Codable, CaseIterable {
    case otpRequest
    case pinOrCvvRequest
    case urgency
    case link
    case paymentRequest
    case threat

    var displayName: String {
        switch self {
        case .otpRequest: return MeText.t("me_signal_otp", "Asks for an OTP")
        case .pinOrCvvRequest: return MeText.t("me_signal_pin", "Asks for a PIN or CVV")
        case .urgency: return MeText.t("me_signal_urgent", "Says it is urgent")
        case .link: return MeText.t("me_signal_link", "Sends a link")
        case .paymentRequest: return MeText.t("me_signal_pay", "Asks for money")
        case .threat: return MeText.t("me_signal_threat", "Threatens you")
        }
    }
}

/// Plan 6.12's three verdicts, verbatim. Never a percentage, never a score:
/// a scam is a sentence a person can act on.
enum ScamVerdict: String, Codable {
    case looksLikeAScam
    case beCareful
    case noCommonScamSignsFound

    var displayName: String {
        switch self {
        case .looksLikeAScam: return MeText.t("me_verdict_scam", "Looks like a scam")
        case .beCareful: return MeText.t("me_verdict_careful", "Be careful")
        case .noCommonScamSignsFound:
            return MeText.t("me_verdict_clean", "No common scam signs found")
        }
    }
}

struct ScamCheck: Codable, Identifiable, Equatable {
    let id: String

    /// What the user pasted or said. Held only long enough to answer; plan
    /// 14 keeps Sahayak-style logs against a hashed ID and short-lived.
    let inputText: String
    let signals: [ScamSignal]
    let verdict: ScamVerdict

    /// "Claude explains what it found" (plan 6.12), in plain language.
    let explainText: String
    let checkedAt: Double

    /// Plan 6.12: "This will be visible to others" — set when the pasted
    /// text holds phone numbers or bank details and the user is about to
    /// warn their circles about it.
    let containsPersonalDetails: Bool
}

// MARK: - Family connect (plan 6.13)

/// Plan 6.13: "on by default: check-in and SOS. Off by default: medicines,
/// location during SOS or a missed check-in, activity summary."
struct GuardianPermissions: Codable, Equatable {
    var checkInAndSOS = true
    var medicines = false
    var locationOnSOSOrMissedCheckIn = false
    var activitySummary = false
}

struct Guardian: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var relationship: String

    /// For the big photo call buttons (plan 6.13). Local path or nil; a
    /// missing photo shows a large initial, never a tiny grey silhouette.
    var photoPath: String?
    var phone: String

    var permissions: GuardianPermissions

    /// Plan 6.13: "weekly guardian digest".
    var weeklyDigest: Bool

    /// Plan 6.13: "linked by QR or code, with the parent's approval".
    var linkedAt: Double?
}

struct FamilyPhoto: Codable, Identifiable, Equatable {
    let id: String
    var caption: String
    var path: String
    var addedBy: String
    var addedAt: Double
}

// MARK: - Notifications (plan 6.15)

enum NotificationChannel: String, Codable, CaseIterable {
    case safety
    case medicines
    case pension
    case circles
    case seva
    case practice

    var displayName: String {
        switch self {
        case .safety: return MeText.t("me_channel_safety", "Safety")
        case .medicines: return MeText.t("me_channel_medicines", "Medicines")
        case .pension: return MeText.t("me_channel_pension", "Pension")
        case .circles: return MeText.t("me_channel_circles", "Circles")
        case .seva: return MeText.t("me_channel_seva", "Seva")
        case .practice: return MeText.t("me_channel_practice", "Practice")
        }
    }

    /// Plan 6.15: "Only Safety cannot be turned off." This is that rule as
    /// code, and the switch is drawn from this property — not from a second
    /// copy of the rule in the view.
    var canBeTurnedOff: Bool { self != .safety }

    /// Plan 6.15's iOS interruption levels: SOS and missed check-in are
    /// critical (with the entitlement) or time-sensitive; medicines are
    /// time-sensitive; everything else is active or passive.
    var interruptionLevel: String {
        switch self {
        case .safety: return "critical"
        case .medicines: return "timeSensitive"
        case .pension, .circles, .seva, .practice: return "active"
        }
    }
}

struct NotificationRecord: Codable, Identifiable, Equatable {
    let id: String
    let channel: NotificationChannel
    var title: String
    var body: String
    let sentAt: Double

    /// SOS and missed check-in ride over quiet hours (plan 6.15).
    let wasUrgent: Bool
}

// MARK: - The three consents (plan 14)

/// Plan 14: "Separate consents for location, health data and guardian
/// sharing; withdrawal in Settings." Three, not one — each independently
/// withdrawable, each with its own consequences.
enum ConsentKind: String, Codable, CaseIterable {
    case location
    case healthData
    case guardianSharing

    var displayName: String {
        switch self {
        case .location: return MeText.t("me_consent_location", "Location")
        case .healthData: return MeText.t("me_consent_health", "Health data")
        case .guardianSharing: return MeText.t("me_consent_guardian", "Guardian sharing")
        }
    }

    /// What turning it ON means, stated on the screen (plan 14: the notice in
    /// every language).
    var onMeaning: String {
        switch self {
        case .location:
            return MeText.t(
                "me_consent_location_on",
                "Your location is sent to your guardians only when you press SOS or when a check-in is missed."
            )
        case .healthData:
            return MeText.t(
                "me_consent_health_on",
                "Your medicine times and readings are stored so reminders work and so you can show your doctor."
            )
        case .guardianSharing:
            return MeText.t(
                "me_consent_guardian_on",
                "Your guardians can see your check-ins and SOS, and any medicine you have chosen to share, per medicine."
            )
        }
    }

    /// And what STOPPING it means. Withdrawal must actually stop the thing —
    /// see `SettingsViewModel.withdraw`, which calls the stop methods rather
    /// than flipping this flag alone.
    var offMeaning: String {
        switch self {
        case .location:
            return MeText.t(
                "me_consent_location_off",
                "Location stops entirely. Even during an SOS, nothing is sent. Your guardians will only know that you pressed the button."
            )
        case .healthData:
            return MeText.t(
                "me_consent_health_off",
                "Medicine reminders stop and no readings are stored. What you already recorded stays on your phone until you delete it."
            )
        case .guardianSharing:
            return MeText.t(
                "me_consent_guardian_off",
                "Your guardians stop seeing your check-ins and SOS. The people stay linked, but they are told nothing until you turn this on again."
            )
        }
    }
}

struct ConsentStatus: Codable, Equatable {
    let kind: ConsentKind
    var isGranted: Bool

    /// Epoch seconds of the last change, so the screen can say "you turned
    /// this off on 3 March" instead of leaving the state unexplained.
    var changedAt: Double?
}

// MARK: - Document safe (plan 14, plan 6.5)

/// A document in the on-phone safe.
///
/// The safe encrypts ON THE PHONE (CryptoKit + Keychain); the server never
/// holds these, ever. See `DocumentCrypto.swift` for where that boundary is
/// enforced.
struct SafeDocument: Codable, Identifiable, Equatable {
    let id: String
    var title: String

    /// "Pension slip", "PPO", "Aadhaar" — free text with suggestions.
    var kindLabel: String
    let createdAt: Double
    let byteCount: Int

    /// Always true once written. A document that reaches this struct has been
    /// through `DocumentCrypto.encrypt`; there is no unencrypted path.
    var isEncrypted: Bool
}

// MARK: - The hub (plan 5)

/// The Main tab's rows, in the plan's own order.
struct MeHubItem: Identifiable, Equatable {
    enum Destination: Equatable {
        case pensionSahayak
        case toolkit
        case documentSafe
        case health
        case kendra
        case meriKahani
        case learn
        case family
        case settings
    }

    let destination: Destination
    let systemImage: String
    let title: String
    let subtitle: String

    var id: String { String(describing: destination) }
}