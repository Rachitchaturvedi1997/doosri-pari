// UNTESTED — never compiled. See ios/README.md.
//
// The seam for the Me package's calls.
//
// A `DoosriService` protocol is being written in `Networking/` by another
// agent and its exact shape is not known from here, so this file declares a
// narrow protocol for this package rather than guessing. When `Networking/`
// lands, the adapter that implements `MeService` is the only file that
// changes.
//
// The document safe is NOT in this protocol. Its store is local-only by
// design (plan 14: pension slips and documents stay on the phone) and lives
// in `DocumentCrypto.swift` — see the boundary comment there.

import Foundation

protocol MeService {

    // Meri Kahani (plan 6.9)
    func storyPrompts() async throws -> [StoryPrompt]
    func storyEntries() async throws -> [StoryEntry]
    @discardableResult
    func saveStoryEntry(_ entry: StoryEntry) async throws -> StoryEntry
    func deleteStoryEntry(id: String) async throws
    func storyChapters() async throws -> [StoryChapter]
    @discardableResult
    func saveStoryChapter(_ chapter: StoryChapter) async throws -> StoryChapter

    // Scam shield (plan 6.12)
    func scamAlerts() async throws -> [ScamAlert]

    /// The user pasted a message or described a call. Hard rules run first
    /// (in `ScamShieldViewModel`, on the phone, before this call) and the
    /// explanation comes back from here.
    @discardableResult
    func explainScamCheck(
        inputText: String,
        signals: [ScamSignal]
    ) async throws -> ScamCheck

    // Family connect (plan 6.13)
    func guardians() async throws -> [Guardian]
    @discardableResult
    func saveGuardian(_ guardian: Guardian) async throws -> Guardian
    func removeGuardian(id: String) async throws
    func familyPhotos() async throws -> [FamilyPhoto]

    // Seekho (plan 6.11)
    func lessons() async throws -> [LearnLesson]
    @discardableResult
    func earnBadge(lessonID: String) async throws -> LearnLesson

    // Notifications (plan 6.15)
    func notificationHistory() async throws -> [NotificationRecord]
    func channelSettings() async throws -> [NotificationChannel: Bool]
    func setChannel(_ channel: NotificationChannel, on: Bool) async throws

    // The three consents (plan 14)
    func consents() async throws -> [ConsentStatus]

    /// Granting is a stored preference and nothing more.
    @discardableResult
    func grantConsent(_ kind: ConsentKind) async throws -> ConsentStatus

    /// WITHDRAWAL IS NOT A FLAG FLIP. The implementation of this method MUST
    /// stop the underlying thing, not merely record the decision:
    ///
    ///   - `.location` — stop all location updates and revoke the tokens the
    ///     backend uses to attach a location to an SOS or a missed check-in.
    ///   - `.healthData` — stop scheduling medicine and vitals reminders and
    ///     stop accepting readings on the server.
    ///   - `.guardianSharing` — revoke the guardians' view of check-ins, SOS
    ///     and shared medicines on the server, so their app stops showing it,
    ///     not just ours.
    ///
    /// If this method only wrote a boolean anywhere, the screen in
    /// `SettingsView` is lying to a pensioner about the one decision plan 14
    /// made a first-class right. Do not weaken this signature.
    @discardableResult
    func withdrawConsent(_ kind: ConsentKind) async throws -> ConsentStatus

    // Plan 14: "a named contact and a grievance officer".
    func grievanceContact() async throws -> String
}

enum MeServiceError: Error, Equatable {
    case offline
    case failed

    /// A consent-dependent call after withdrawal. Not a failure: it is the
    /// user's decision being obeyed, and the view models show the consent-off
    /// state rather than an error.
    case consentWithdrawn

    var isOffline: Bool { self == .offline }
}

// MARK: - Temporary wiring

/// A small in-memory `MeService`, in the same spirit as the "dev service"
/// `DoosriPariApp` wires for check-ins (see ios/README.md): the screens and
/// view models compile and run against a real seam while `Networking/` is
/// being written beside this package.
///
/// This is NOT the product's data. Seed rows are English placeholders so the
/// screens have something honest to draw; the real content arrives through
/// the adapter that replaces this class. The one thing this class takes
/// seriously is `withdrawConsent`, whose job does not change when the
/// transport does.
final class MeSampleService: MeService {

    private var consents: [ConsentKind: ConsentStatus] = [
        .location: ConsentStatus(kind: .location, isGranted: false, changedAt: nil),
        .healthData: ConsentStatus(kind: .healthData, isGranted: false, changedAt: nil),
        .guardianSharing: ConsentStatus(kind: .guardianSharing, isGranted: true, changedAt: nil)
    ]

    private var channels: [NotificationChannel: Bool] = [
        .safety: true,
        .medicines: true,
        .pension: true,
        .circles: true,
        .seva: false,
        .practice: true
    ]

    func storyPrompts() async throws -> [StoryPrompt] {
        [
            StoryPrompt(
                id: "p1",
                weekNumber: 1,
                text: MeText.t("me_prompt_1", "Tell us about your first day at work.")
            ),
            StoryPrompt(
                id: "p2",
                weekNumber: 2,
                text: MeText.t("me_prompt_2", "What advice would you give your grandchildren?")
            )
        ]
    }

    func storyEntries() async throws -> [StoryEntry] { [] }

    @discardableResult
    func saveStoryEntry(_ entry: StoryEntry) async throws -> StoryEntry { entry }

    func deleteStoryEntry(id: String) async throws {}

    func storyChapters() async throws -> [StoryChapter] {
        [
            StoryChapter(
                id: "c1",
                title: MeText.t("me_chapter_childhood", "Childhood"),
                entryIDs: [],
                photoPaths: [],
                share: .privateOnly,
                bookPageReference: nil
            ),
            StoryChapter(
                id: "c2",
                title: MeText.t("me_chapter_career", "Career"),
                entryIDs: [],
                photoPaths: [],
                share: .privateOnly,
                bookPageReference: nil
            )
        ]
    }

    @discardableResult
    func saveStoryChapter(_ chapter: StoryChapter) async throws -> StoryChapter { chapter }

    func scamAlerts() async throws -> [ScamAlert] {
        [
            ScamAlert(
                id: "a1",
                title: MeText.t(
                    "me_alert_fake_office",
                    "Fake pension office calls"
                ),
                body: MeText.t(
                    "me_alert_fake_office_body",
                    "Nobody from the pension office will call to ask for money or an OTP. Hang up and call your office on a number you already have."
                ),
                dateText: "12 September 2026",
                reviewedBy: MeText.t("me_alert_moderator", "Moderator, Pension Helpline Circle")
            ),
            ScamAlert(
                id: "a2",
                title: MeText.t(
                    "me_alert_life_cert",
                    "Fake life-certificate links"
                ),
                body: MeText.t(
                    "me_alert_life_cert_body",
                    "Links saying your life certificate has expired are usually fake. Jeevan Pramaan is the real app, and it never asks for a PIN."
                ),
                dateText: "28 September 2026",
                reviewedBy: MeText.t("me_alert_moderator", "Moderator, Pension Helpline Circle")
            )
        ]
    }

    @discardableResult
    func explainScamCheck(
        inputText: String,
        signals: [ScamSignal]
    ) async throws -> ScamCheck {
        let verdict: ScamVerdict
        if signals.contains(.otpRequest)
            || signals.contains(.pinOrCvvRequest)
            || signals.contains(.paymentRequest) {
            verdict = .looksLikeAScam
        } else if signals.count >= 2 {
            verdict = .beCareful
        } else {
            verdict = .noCommonScamSignsFound
        }

        let explanation = MeText.t(
            "me_check_explain_default",
            "The checks looked for OTP or PIN requests, urgency, links, money requests and threats. Take anything suspicious to your bank in person."
        )

        return ScamCheck(
            id: UUID().uuidString,
            inputText: inputText,
            signals: signals,
            verdict: verdict,
            explainText: explanation,
            checkedAt: Date().timeIntervalSince1970,
            containsPersonalDetails: Self.containsPersonalDetails(inputText)
        )
    }

    /// Plan 6.12's posting warning: phone numbers and bank details in
    /// something about to be shared. Digits of 10 or more, and common Indian
    /// banking words.
    static func containsPersonalDetails(_ text: String) -> Bool {
        let words = text.lowercased()
        if words.contains("account") || words.contains("ifsc")
            || words.contains("cvv") || words.contains("aadhaar") {
            return true
        }
        // Ten or more consecutive digits reads as a phone or account number.
        var run = 0
        for character in text {
            if character.isNumber {
                run += 1
                if run >= 10 { return true }
            } else {
                run = 0
            }
        }
        return false
    }

    func guardians() async throws -> [Guardian] {
        [
            Guardian(
                id: "g1",
                name: "Meera",
                relationship: MeText.t("me_rel_daughter", "Daughter"),
                photoPath: nil,
                phone: "+91 90000 00000",
                permissions: GuardianPermissions(),
                weeklyDigest: true,
                linkedAt: Date().timeIntervalSince1970
            )
        ]
    }

    @discardableResult
    func saveGuardian(_ guardian: Guardian) async throws -> Guardian { guardian }

    func removeGuardian(id: String) async throws {}

    func familyPhotos() async throws -> [FamilyPhoto] { [] }

    func lessons() async throws -> [LearnLesson] {
        [
            LearnLesson(
                id: "l1",
                title: MeText.t("me_lesson_video", "Making a WhatsApp video call"),
                category: .smartphone,
                minutes: 4,
                hasCheck: true,
                badgeEarned: false,
                liveCallAvailable: true
            ),
            LearnLesson(
                id: "l2",
                title: MeText.t("me_lesson_upi", "UPI: what a real payment app never asks"),
                category: .moneySafety,
                minutes: 5,
                hasCheck: true,
                badgeEarned: false,
                liveCallAvailable: true
            ),
            LearnLesson(
                id: "l3",
                title: MeText.t("me_lesson_jeevan", "Jeevan Pramaan, step by step"),
                category: .governmentApps,
                minutes: 5,
                hasCheck: true,
                badgeEarned: false,
                liveCallAvailable: false
            )
        ]
    }

    @discardableResult
    func earnBadge(lessonID: String) async throws -> LearnLesson {
        LearnLesson(
            id: lessonID,
            title: "",
            category: .smartphone,
            minutes: 0,
            hasCheck: true,
            badgeEarned: true,
            liveCallAvailable: false
        )
    }

    func notificationHistory() async throws -> [NotificationRecord] {
        [
            NotificationRecord(
                id: "n1",
                channel: .medicines,
                title: MeText.t("me_notif_medicine", "Time for your tablet"),
                body: MeText.t("me_notif_medicine_body", "Metformin 500, with food."),
                sentAt: Date().addingTimeInterval(-3600).timeIntervalSince1970,
                wasUrgent: true
            ),
            NotificationRecord(
                id: "n2",
                channel: .practice,
                title: MeText.t("me_notif_practice", "Your practice is waiting"),
                body: MeText.t("me_notif_practice_body", "Chair yoga, 10 minutes."),
                sentAt: Date().addingTimeInterval(-86400).timeIntervalSince1970,
                wasUrgent: false
            )
        ]
    }

    func channelSettings() async throws -> [NotificationChannel: Bool] { channels }

    func setChannel(_ channel: NotificationChannel, on: Bool) async throws {
        channels[channel] = on
    }

    func consents() async throws -> [ConsentStatus] {
        ConsentKind.allCases.compactMap { consents[$0] }
    }

    @discardableResult
    func grantConsent(_ kind: ConsentKind) async throws -> ConsentStatus {
        let status = ConsentStatus(
            kind: kind,
            isGranted: true,
            changedAt: Date().timeIntervalSince1970
        )
        consents[kind] = status
        return status
    }

    @discardableResult
    func withdrawConsent(_ kind: ConsentKind) async throws -> ConsentStatus {
        // Where the real adapter stops the machinery: location updates,
        // reminder scheduling, the guardians' server-side view. This sample
        // records the decision and nothing else, which is exactly what the
        // protocol's comment forbids in production — it is honest here only
        // because there is no machinery running yet.
        let status = ConsentStatus(
            kind: kind,
            isGranted: false,
            changedAt: Date().timeIntervalSince1970
        )
        consents[kind] = status
        return status
    }

    func grievanceContact() async throws -> String {
        MeText.t(
            "me_grievance_contact",
            "Grievance officer: grievance@doosripari.example · Named contact: support@doosripari.example"
        )
    }
}