// UNTESTED — never compiled. See ios/README.md.
//
// Today's edges: the feed over HTTP, the cache that keeps the screen alive on a
// train (plan 13), the on-device pension figures (plan 6.5 and 14), and the one
// adapter that knows the check-in feature exists.
//
// `DoosriService` in `Networking/` is being written by another agent and its
// exact shape is not known from here, so this file talks plain `URLSession`,
// as `CheckIn/CheckInService.swift` does. When `Networking/` lands, only the
// HTTP struct below changes; the view model speaks `TodayFeedService`.

import Foundation

// MARK: - Errors

enum TodayServiceError: LocalizedError, Equatable {
    case offline
    case server(status: Int)
    case decoding

    var errorDescription: String? {
        switch self {
        case .offline:
            return TodayText.t(
                "today_offline",
                "No connection. We could not reach the server."
            )
        case .server, .decoding:
            return TodayText.t(
                "today_load_failed",
                "We could not load today. Please try again."
            )
        }
    }

    /// Whether this is a lost connection rather than a server problem. The two
    /// get the same remedy — try again — but only one of them is worth showing
    /// a cached screen beside.
    var isOffline: Bool { self == .offline }
}

// MARK: - HTTP feed

/// The server half, against an endpoint that does not exist yet.
///
/// `python/features/` has no today module, so the paths below pin the intended
/// contract alongside the keys in `TodayModels.swift`. The response is
/// `TodayFeed`'s own wire form.
final class HTTPTodayFeedService: TodayFeedService {
    private let baseURL: URL
    private let session: URLSession
    private let userId: String

    /// - Parameter userId: There is no authentication yet — the plan puts OTP
    ///   in phase 1. See `HTTPCheckInService` for why a release built this way
    ///   would be a problem. The same warning applies to every call here.
    init(baseURL: URL, userId: String, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.userId = userId
        self.session = session
    }

    func load() async throws -> TodayFeedResult {
        TodayFeedResult(feed: try await get("/today/feed"), source: .live)
    }

    func refresh() async throws -> TodayFeedResult {
        TodayFeedResult(feed: try await get("/today/feed"), source: .live)
    }

    func setHidden(_ hidden: Bool, section: TodaySection) async throws {
        let _: TodayAck = try await post(
            "/today/hidden",
            body: ["section": section.rawValue, "hidden": hidden ? "1" : "0"]
        )
    }

    func recordCreditAnswer(credited: Bool, on day: String) async throws {
        let _: TodayAck = try await post(
            "/today/credit_answer",
            body: ["day": day, "credited": credited ? "1" : "0"]
        )
    }

    // MARK: Plumbing

    private struct TodayAck: Decodable {
        let ok: Bool
    }

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
            // them mean the same thing to the user here.
            throw TodayServiceError.offline
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw TodayServiceError.server(status: http.statusCode)
        }

        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw TodayServiceError.decoding
        }
    }

    private func url(_ path: String) -> URL {
        // force-unwrap is safe: baseURL is a constant literal set at app start.
        URL(string: baseURL.absoluteString + path)!
    }
}

// MARK: - Cache

/// Yesterday's Today, on the phone.
protocol TodayFeedCache {
    func read() -> TodayFeed?
    func write(_ feed: TodayFeed)
}

/// A JSON file in Application Support.
///
/// Not the document safe: this is a public feed — weather, news, a published DR
/// rate — so plain file storage is right, and spending the safe's key material
/// on it would be the wrong instinct pointed at the wrong data. It is written
/// atomically and re-read on launch, which is all plan 13 asks of it.
final class FileTodayFeedCache: TodayFeedCache {
    private let fileURL: URL
    private let queue = DispatchQueue(label: "doosripari.today.cache")

    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        )[0].appendingPathComponent("Today", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        self.fileURL = dir.appendingPathComponent("today-feed.json")
    }

    func read() -> TodayFeed? {
        queue.sync {
            guard let data = try? Data(contentsOf: fileURL) else { return nil }
            return try? JSONDecoder().decode(TodayFeed.self, from: data)
        }
    }

    func write(_ feed: TodayFeed) {
        queue.sync {
            guard let data = try? JSONEncoder().encode(feed) else { return }
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}

/// Cache-first, then the network — the shape plan 13 needs for a screen that
/// "works offline" and says so.
///
/// `load()` returns the cache immediately if the network is not reachable, with
/// `source == .cache` so the screen can print the saved-at line. It throws only
/// when there is no connection **and** nothing saved, which is the one case the
/// screen has to turn into a plain "we could not reach the server" — a
/// pensioner must never be left unable to tell "nothing today" from "we could
/// not find out".
final class CachingTodayFeedService: TodayFeedService {
    private let remote: TodayFeedService
    private let cache: TodayFeedCache

    init(remote: TodayFeedService, cache: TodayFeedCache) {
        self.remote = remote
        self.cache = cache
    }

    func load() async throws -> TodayFeedResult {
        do {
            let result = try await remote.load()
            cache.write(result.feed)
            return result
        } catch {
            if let saved = cache.read() {
                return TodayFeedResult(feed: saved, source: .cache)
            }
            throw error
        }
    }

    func refresh() async throws -> TodayFeedResult {
        do {
            let result = try await remote.refresh()
            cache.write(result.feed)
            return result
        } catch {
            if let saved = cache.read() {
                return TodayFeedResult(feed: saved, source: .cache)
            }
            throw error
        }
    }

    func setHidden(_ hidden: Bool, section: TodaySection) async throws {
        try await remote.setHidden(hidden, section: section)
    }

    func recordCreditAnswer(credited: Bool, on day: String) async throws {
        try await remote.recordCreditAnswer(credited: credited, on: day)
    }
}

// MARK: - The phone's half

/// The on-device pension figures — plan 14's "pension amounts, slips and
/// documents stay on the phone", as code.
///
/// The file is `pension-figures.json` in Application Support with Data
/// Protection **complete** (`FileProtectionType.complete`), which is what plan
/// 6.5 names for iOS: it is unreadable whenever the phone is locked. The real
/// key hierarchy — Keychain plus Secure Enclave behind the document safe — is
/// plan 6.5's production step and lives with `Toolkit/DocumentSafeStore.swift`;
/// this reader reaches the same file the slip reader writes.
///
/// **The file format is a contract with the Toolkit package.** Both sides
/// spell the same four keys; neither imports the other. Keep them in step the
/// way `CheckInModels.swift` is kept in step with the shared Kotlin — by
/// comment and by test, since there is no compiler joining the two.
struct LocalPensionFiguresFile: Codable {
    let basicPensionRupees: Double?
    let netPensionRupees: Double?
    let lastCreditedOn: Double?
    let readOn: Double?

    enum CodingKeys: String, CodingKey {
        case basicPensionRupees = "basic_pension_rupees"
        case netPensionRupees = "net_pension_rupees"
        case lastCreditedOn = "last_credited_on"
        case readOn = "read_on"
    }

    var snapshot: PensionSnapshot {
        PensionSnapshot(
            basicPensionRupees: basicPensionRupees,
            netPensionRupees: netPensionRupees,
            lastCreditedOn: lastCreditedOn.map { Date(timeIntervalSince1970: $0) },
            readOn: readOn.map { Date(timeIntervalSince1970: $0) }
        )
    }
}

final class LocalPensionFiguresSource: OnDevicePensionSource {
    private let fileURL: URL

    /// - Parameter directory: Injectable for tests. Production uses the same
    ///   Application Support folder as the slip reader.
    init(directory: URL? = nil) {
        let dir = directory ?? FileManager.default.urls(
            for: .applicationSupportDirectory, in: .userDomainMask
        )[0].appendingPathComponent("Today", isDirectory: true)
        try? FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        self.fileURL = dir.appendingPathComponent("pension-figures.json")

        // Belt to the braces: if the file already exists, make sure it carries
        // the protection the plan promises, in case an earlier build wrote it
        // without.
        try? FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: fileURL.path
        )
    }

    /// Never throws. A pensioner whose file cannot be read right now sees "we
    /// do not know yet", not a broken Today screen — see the protocol.
    func read() async -> PensionSnapshot {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(LocalPensionFiguresFile.self, from: data)
        else {
            return .empty
        }
        return file.snapshot
    }
}

// MARK: - Check-in adapter

/// The one file in this package that knows `CheckIn/` exists.
///
/// Today narrows check-in to the button's five facts (`TodayCheckIn`); the
/// stage machine, the escalation and the server's truth stay in the check-in
/// slice. If Today ever starts deciding anything about escalation, the
/// boundary has been crossed and two screens will begin disagreeing about
/// whether somebody is safe.
final class CheckInBackedTodayCheckIn: TodayCheckInService {
    private let service: CheckInService

    init(service: CheckInService) {
        self.service = service
    }

    func todayCheckIn() async throws -> TodayCheckIn {
        async let snapshot = service.today()
        async let preferences = service.preferences()
        let day: CheckInSnapshot = try await snapshot
        let settings: CheckInPreferences = try await preferences
        return Self.narrow(day: day, preferences: settings)
    }

    @discardableResult
    func recordCheckIn() async throws -> TodayCheckIn {
        let day = try await service.checkIn()
        // Preferences are not re-fetched: the tap answered the question the
        // button asks, and a settings round trip would delay the tick — the one
        // thing the optimistic button exists to avoid.
        return Self.narrow(day: day, preferences: nil)
    }

    private static func narrow(day: CheckInSnapshot, preferences: CheckInPreferences?) -> TodayCheckIn {
        TodayCheckIn(
            checkedAtLabel: day.checkedAt.map {
                Date(timeIntervalSince1970: $0).formatted(date: .omitted, time: .shortened)
            },
            sending: false,
            failed: false,
            paused: preferences?.isPaused ?? false,
            // "Hidden if check-in is off" (plan 6.2 card 2) is a Main-tab
            // setting that does not exist yet. Until it does, the button shows
            // whenever the server has a row for today — a button that does
            // nothing is worse than no button.
            enabled: true
        )
    }
}