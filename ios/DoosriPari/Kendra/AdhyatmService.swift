// UNTESTED — never compiled. See ios/README.md.
//
// The seam for Adhyatm Kendra's calls — PRACTICE ONLY.
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// Nothing here takes a `SafetyProfile`, and nothing here ever should. There
// is no health gate in front of a festival date or a verse. If a change to
// this package wants to pass safety flags into any method below, the change
// is wrong — read `AdhyatmModels.swift`'s header first. The gate that DOES
// exist here is licensing: texts and recordings arrive already checked as
// public-domain or openly licensed, credited, or they arrive as a link to
// their source (plan 7.2's content policy).
//
// ==========================================================================
//
// A `DoosriService` protocol is being written in `Networking/` by another
// agent and its exact shape is not known from here, so this file declares a
// narrow protocol for this package rather than guessing.

import Foundation

protocol AdhyatmService {

    // Paths
    func chosenPaths() async throws -> [SpiritualPath]
    func setChosenPaths(_ paths: [SpiritualPath]) async throws

    // Today's spiritual start
    func spiritualStart(paths: [SpiritualPath], weekday: Int) async throws -> SpiritualStart

    // Japa and sankalp
    func japaState(paths: [SpiritualPath]) async throws -> JapaState
    @discardableResult
    func recordJapaCount(_ count: Int) async throws -> JapaState

    // Festival and vrat calendar
    ///
    /// The server is the licensed source's client; this app displays what
    /// comes back and never derives a date. See `FestivalDay`.
    func festivals(region: String, paths: [SpiritualPath]) async throws -> [FestivalDay]
    @discardableResult
    func setFestivalReminder(festivalID: String, on: Bool) async throws -> FestivalDay

    // Ask the Scriptures
    @discardableResult
    func askScriptures(_ question: String, paths: [SpiritualPath]) async throws -> ScriptureAnswer
    func recentScriptureAnswers() async throws -> [ScriptureAnswer]

    // Daily thought
    func dailyThought(paths: [SpiritualPath]) async throws -> DailyThought
}

enum AdhyatmServiceError: Error, Equatable {
    case offline
    case failed

    /// Returned when the licensed panchang feed cannot be reached. The screen
    /// shows "the calendar is unavailable" and NOTHING else — it must never
    /// fall back to computing a date locally. See `FestivalDay`.
    case calendarSourceUnavailable

    var isOffline: Bool { self == .offline }
}