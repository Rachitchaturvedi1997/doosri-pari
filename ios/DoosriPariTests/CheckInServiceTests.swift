// UNTESTED — never compiled. See ios/README.md.

import XCTest
@testable import DoosriPari

/// Answers every request from memory, so the service can be tested without a
/// server and without the flakiness of one.
final class StubURLProtocol: URLProtocol {
    typealias Handler = (URLRequest) throws -> (Data, HTTPURLResponse)

    static var handler: Handler?
    static var lastRequest: URLRequest?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        StubURLProtocol.lastRequest = request
        guard let handler = StubURLProtocol.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        do {
            let (data, response) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

/// `HTTPCheckInService` against a stubbed network.
///
/// The payloads are the same ones the model tests use, copied from
/// `python/features/checkin.py`, so this is the end-to-end version of the bug
/// that used to stop the app working at all: a camelCase model with no key
/// mapping cannot decode a snake_case response, and the only symptom a user
/// sees is "Could not send. Please try again." forever.
final class CheckInServiceTests: XCTestCase {

    private var service: HTTPCheckInService!

    private static let dayJSON = """
    {"day": "2026-10-14", "stage": "Waiting", "checked_at": null, "method": null,
     "resolved_at": null, "events": []}
    """
    private static let settingsJSON = """
    {"window_start": "09:00", "window_mins": 180, "passive": false,
     "share_location": false, "paused_until": null}
    """

    override func setUp() {
        super.setUp()
        StubURLProtocol.handler = nil
        StubURLProtocol.lastRequest = nil

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        service = HTTPCheckInService(
            baseURL: URL(string: "http://127.0.0.1:8000")!,
            userId: "1",
            session: URLSession(configuration: config)
        )
    }

    override func tearDown() {
        StubURLProtocol.handler = nil
        StubURLProtocol.lastRequest = nil
        service = nil
        super.tearDown()
    }

    private func respond(_ body: String, status: Int = 200) -> StubURLProtocol.Handler {
        { _ in
            (
                Data(body.utf8),
                HTTPURLResponse(
                    url: URL(string: "http://127.0.0.1:8000")!,
                    statusCode: status,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!
            )
        }
    }

    // MARK: - The wire

    func testTodayDecodesWhatTheServerActuallySends() async throws {
        StubURLProtocol.handler = respond(Self.dayJSON)

        let snapshot = try await service.today()

        XCTAssertEqual(snapshot.day, "2026-10-14")
        XCTAssertEqual(snapshot.stage, .waiting)
    }

    func testPreferencesDecodeWhatTheServerActuallySends() async throws {
        StubURLProtocol.handler = respond(Self.settingsJSON)

        let prefs = try await service.preferences()

        XCTAssertEqual(prefs.windowStart, "09:00")
        XCTAssertEqual(prefs.windowMins, 180)
    }

    func testCheckInPostsTappedAndDecodesTheResult() async throws {
        StubURLProtocol.handler = respond(
            """
            {"day": "2026-10-14", "stage": "CheckedIn", "checked_at": 1792000120.0,
             "method": "tapped", "resolved_at": null, "events": []}
            """
        )

        let snapshot = try await service.checkIn()

        XCTAssertEqual(snapshot.stage, .checkedIn)
        XCTAssertEqual(snapshot.method, .tapped)

        let request = try XCTUnwrap(StubURLProtocol.lastRequest)
        XCTAssertEqual(request.httpMethod, "POST")
        let body = try XCTUnwrap(request.httpBody)
        XCTAssertTrue(
            String(decoding: body, as: UTF8.self).contains("tapped"),
            "the server records how the check-in happened"
        )
    }

    func testHistoryAsksForTheWindowOfDaysItWasGiven() async throws {
        StubURLProtocol.handler = { request in
            XCTAssertTrue(
                request.url?.query?.contains("days=14") == true,
                "the Today screen shows the last 14 days"
            )
            return (Data("[]".utf8), HTTPURLResponse(url: request.url!, statusCode: 200,
                                                    httpVersion: nil, headerFields: nil)!)
        }

        _ = try await service.history(days: 14)
    }

    /// The development shim, asserted so it is visible in the test report
    /// rather than only in a comment. This header is the reason the app cannot
    /// ship: anyone can read or close another person's alert by guessing an id.
    func testTheDevelopmentUserHeaderIsSent() async throws {
        StubURLProtocol.handler = respond(Self.dayJSON)

        _ = try await service.today()

        let request = try XCTUnwrap(StubURLProtocol.lastRequest)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-User-Id"), "1")
    }

    // MARK: - Failure

    func testAServerErrorBecomesAServerFailure() async {
        StubURLProtocol.handler = respond("{}", status: 503)

        await assertThrows(.server(status: 503)) { _ = try await self.service.today() }
    }

    func testAnUnreadableBodyBecomesADecodingFailure() async {
        StubURLProtocol.handler = respond("<html>gateway timeout</html>")

        await assertThrows(.decoding) { _ = try await self.service.today() }
    }

    /// Every transport problem looks the same to a pensioner, so they get the
    /// same words and the same remedy: try again later.
    func testALostConnectionBecomesOffline() async {
        StubURLProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }

        await assertThrows(.offline) { _ = try await self.service.today() }
    }

    func testATimeoutBecomesOfflineRatherThanAFailure() async {
        StubURLProtocol.handler = { _ in throw URLError(.timedOut) }

        await assertThrows(.offline) { _ = try await self.service.today() }
    }

    // MARK: - Helper

    private func assertThrows(
        _ expected: CheckInServiceError,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ body: () async throws -> Void
    ) async {
        do {
            try await body()
            XCTFail("expected \(expected)", file: file, line: line)
        } catch let error as CheckInServiceError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("expected \(expected), got \(error)", file: file, line: line)
        }
    }
}
