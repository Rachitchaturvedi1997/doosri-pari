// UNTESTED — never compiled. See ios/README.md.
//
// The seam for Swasthya's calls.
//
// A `DoosriService` protocol is being written in `Networking/` by another
// agent and its exact shape is not known from here, so this file declares a
// narrow protocol for *this package's* calls rather than depending on a shape
// that does not exist yet. When `Networking/` lands, the adapter that
// implements `SwasthyaService` is the only file that changes; the view models
// keep talking to this protocol.
//
// Every method is `async throws` and every failure arrives as
// `SwasthyaServiceError`, so each view model has exactly one error case to
// handle and the wording for "no connection" is written once.
//
// NOTE ON WHAT IS NOT HERE: no method interprets anything. There is no
// `assessBloodPressure`, no `suggestDoseChange`. The server side of this
// package stores values and delivers reminders; that is all it is for.

import Foundation

protocol SwasthyaService {

    // Consent (plan 14: health data is a separate named consent)
    func healthConsent() async throws -> HealthConsentState
    @discardableResult
    func setHealthConsent(_ state: HealthConsentState) async throws -> HealthConsentState

    // Medicines
    func medicines() async throws -> [Medicine]
    @discardableResult
    func saveMedicine(_ medicine: Medicine) async throws -> Medicine
    func deleteMedicine(id: String) async throws

    // The dose log
    func doseLog(from: Date, to: Date) async throws -> [DoseLogEntry]
    @discardableResult
    func acknowledgeDose(
        entryID: String,
        as acknowledgement: DoseAcknowledgement
    ) async throws -> DoseLogEntry

    // Vitals
    func vitals(from: Date, to: Date) async throws -> [VitalReading]
    @discardableResult
    func saveVital(_ reading: VitalReading) async throws -> VitalReading

    /// Plan 6.10: PDF export of the vitals log, for a doctor's visit. The PDF
    /// is generated on the phone from the readings already here; it is not a
    /// document the server holds.
    func vitalsPDF(from: Date, to: Date) async throws -> Data

    // Reminders
    func reminders() async throws -> [HealthReminder]

    // Facility directory
    func facilities(scheme: HealthScheme?, city: String?) async throws -> [HealthFacility]
}

enum SwasthyaServiceError: Error, Equatable {
    case offline
    case failed

    /// Returned when a call arrives after the health consent was withdrawn.
    /// The view models treat this the same as "consent is off" rather than as
    /// a failure, because it is not a failure — it is the user's decision
    /// being obeyed.
    case consentWithdrawn

    var isOffline: Bool { self == .offline }
}