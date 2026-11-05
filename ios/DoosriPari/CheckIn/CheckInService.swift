// UNTESTED — never compiled. See ios/README.md.

import Foundation

protocol CheckInService {
    func today() async throws -> CheckInSnapshot
    func checkIn() async throws -> CheckInSnapshot
    func preferences() async throws -> CheckInPreferences
    func history(days: Int) async throws -> [CheckInSnapshot]
}

enum CheckInServiceError: LocalizedError, Equatable {
    case offline
    case server(status: Int)
    case decoding

    var errorDescription: String? {
        switch self {
        case .offline:
            return String(
                localized: "checkin_offline",
                comment: "Shown when the check-in cannot be sent because there is no connection"
            )
        case .server:
            return String(
                localized: "checkin_failed",
                comment: "Shown when the check-in could not be sent for any other reason"
            )
        case .decoding:
            return String(
                localized: "checkin_failed",
                comment: "Shown when the check-in could not be sent for any other reason"
            )
        }
    }
}

final class HTTPCheckInService: CheckInService {
    private let baseURL: URL
    private let session: URLSession
    private let userId: String

    /// - Parameter userId: There is no authentication yet — the plan puts OTP
    ///   in phase 1. Until then the app cannot prove who it is, and a release
    ///   built this way could read and close other people's alerts by guessing
    ///   an id. Do not ship a release with this in it. See
    ///   android/.../HttpCheckInRepository.kt, which carries the same warning.
    init(baseURL: URL, userId: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.userId = userId
        self.session = session
    }

    func today() async throws -> CheckInSnapshot {
        try await get("/checkin/today")
    }

    func checkIn() async throws -> CheckInSnapshot {
        try await post("/checkin", body: ["method": "tapped"])
    }

    func preferences() async throws -> CheckInPreferences {
        try await get("/checkin/settings")
    }

    func history(days: Int) async throws -> [CheckInSnapshot] {
        try await get("/checkin/history?days=\(days)")
    }

    // MARK: - Plumbing

    private func get<T: Decodable>(_ path: String) async throws -> T {
        var request = URLRequest(url: url(path))
        request.setValue(userId, forHTTPHeaderField: "X-User-Id")
        return try await send(request)
    }

    private func post<T: Decodable>(_ path: String, body: [String: String]) async throws -> T {
        var request = URLRequest(url: url(path))
        request.httpMethod = "POST"
        request.setValue(userId, forHTTPHeaderField: "X-User-Id")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            // URLError covers no connection, DNS failure and timeout. All of
            // them mean the same thing to the user here, and the plan's remedy
            // is the same: try again later.
            throw CheckInServiceError.offline
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw CheckInServiceError.server(status: http.statusCode)
        }

        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw CheckInServiceError.decoding
        }
    }

    private func url(_ path: String) -> URL {
        // force-unwrap is safe here: baseURL is a constant literal set at app
        // start. Anything else should be a programming error, not a runtime
        // condition to handle.
        URL(string: baseURL.absoluteString + path)!
    }
}
