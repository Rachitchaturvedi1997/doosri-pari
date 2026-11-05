// UNTESTED — never compiled. See ios/README.md.
//
// The seam for the Anubhav board's network calls. Same shape and same reason
// as `Saathi/SaathiService.swift`: `Networking/` is being written in parallel
// and its `DoosriService` is not known from here, so this package declares its
// own narrow protocol. When `Networking/` lands, one adapter implements this
// and the view models do not change.
//
// Every method is `async throws` and every failure arrives as
// `SevaServiceError`, so each view model has one error case to handle and the
// words for "no connection" are written once.
//
// Section 15 is deliberately *not* re-declared here: reports are one pipeline
// for the whole app, and `ReportDraft`/`ReportReceipt` live in
// `SaathiModels.swift` / `SaathiService.swift`. `sendReport` exists on this
// protocol only so a Seva screen can hand a report to whichever service the
// app wired it to, without reaching across packages for the other one.

import Foundation

protocol SevaService {
    // Board
    func listings(filter: SevaFilter) async throws -> [SevaListing]
    func listing(id: String) async throws -> SevaListing

    // Listings
    @discardableResult
    func createListing(_ draft: SevaListingDraft) async throws -> SevaListing

    // Bookings
    @discardableResult
    func requestBooking(_ draft: SevaBookingDraft) async throws -> SevaBooking
    func myBookings() async throws -> [SevaBooking]
    /// The server treats an identical repeated transition as a no-op and
    /// returns the same booking. The client also short-circuits before
    /// calling, in the view models, because a second tap that waits on the
    /// network still looks like an error on a slow connection.
    @discardableResult
    func setBookingState(id: String, to state: BookingState) async throws -> SevaBooking

    // Money (plan 6.8)
    /// A server-minted quote for the pay step. The client never computes the
    /// amount it is about to charge.
    func paymentQuote(bookingID: String) async throws -> SevaPaymentQuote
    /// Executes the gateway payment for a quote already shown on the confirm
    /// step. Idempotent server-side: a double-tapped confirm pays once.
    @discardableResult
    func pay(bookingID: String) async throws -> SevaPaymentReceipt

    // Matching (plan 6.8)
    func seekerProfile() async throws -> SevaSeekerProfile

    // Reports — section 15, one pipeline for the whole app.
    @discardableResult
    func sendReport(_ draft: ReportDraft) async throws -> ReportReceipt
}

enum SevaServiceError: LocalizedError, Equatable {
    case offline
    case server(status: Int)
    case decoding
    /// The user may not do this: a non-partner posting paid work, a listing
    /// asking the pensioner to pay, a booking on a listing that was taken
    /// down. The message is the server's, in the user's language, because the
    /// server is the only side that knows which rule fired.
    case notAllowed(String)
    /// The gateway said no — expired quote, refused card, UPI cancelled. This
    /// is not a fault in the app and is worded as an outcome, not a crash.
    case paymentDeclined(String)

    var errorDescription: String? {
        switch self {
        case .offline:
            return SevaText.t(
                "seva_error_offline",
                "No connection. We will try again when you are back online."
            )
        case .server, .decoding:
            return SevaText.t("seva_error_failed", "Could not load. Please try again.")
        case .notAllowed(let message):
            return message
        case .paymentDeclined(let message):
            return message
        }
    }

    /// "Try again" versus "there is nothing to do". A declined payment and a
    /// lost connection are different sentences and deserve different buttons.
    var isRetryable: Bool {
        switch self {
        case .offline, .server, .decoding: return true
        case .notAllowed, .paymentDeclined: return false
        }
    }
}