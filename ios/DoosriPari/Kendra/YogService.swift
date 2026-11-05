// UNTESTED — never compiled. See ios/README.md.
//
// The seam for Yog Kendra's calls — EXERCISE ONLY.
//
// A `DoosriService` protocol is being written in `Networking/` by another
// agent and its exact shape is not known from here, so this file declares a
// narrow protocol for this package rather than depending on a shape that does
// not exist yet.
//
// THE CENTRAL RULE again, because a service file is where it is easiest to
// lose: EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT. Every method here is
// about exercise, and the ones that hand out poses take a `SafetyProfile`
// because a filtered programme is the entire point of plan 7.1's "Safety
// first". `AdhyatmService.swift` has no `SafetyProfile` in any signature and
// must never grow one.

import Foundation

protocol YogService {

    // The safety profile (plan 7.1: asked before the first session)
    func safetyProfile() async throws -> SafetyProfile
    @discardableResult
    func saveSafetyProfile(_ profile: SafetyProfile) async throws -> SafetyProfile

    // Programmes and poses
    /// The server also filters, and the client filters again in
    /// `ProgrammeListViewModel`. Belt and braces on purpose: a stale cache
    /// must never hand a squat to somebody with a knee replacement, so the
    /// check closest to the screen is the one that cannot be skipped.
    func programmes(profile: SafetyProfile) async throws -> [Programme]
    func programme(id: String, profile: SafetyProfile) async throws -> Programme

    // Progress (plan 7.1: shown kindly, never compared with others)
    func practiceWeeks() async throws -> [PracticeWeek]
    func mobilityResults() async throws -> [MobilityResult]
    @discardableResult
    func saveMobilityResult(_ result: MobilityResult) async throws -> MobilityResult

    // Yoga saathi
    func partners() async throws -> [YogaPartner]
    @discardableResult
    func sendShabash(to partnerID: String) async throws -> YogaPartner

    // Preference kept on the server so a new phone shows the same instructor
    func preferredInstructor() async throws -> Instructor
    func setPreferredInstructor(_ instructor: Instructor) async throws
}

enum YogServiceError: Error, Equatable {
    case offline
    case failed

    /// The first session of a programme cannot start until the safety check
    /// has been answered (plan 7.1: "Health check before the first session").
    /// This is a gate, not an error; the view models surface it as the check
    /// screen rather than as a red banner.
    case safetyCheckRequired

    var isOffline: Bool { self == .offline }
}