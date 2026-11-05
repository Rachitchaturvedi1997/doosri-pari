// UNTESTED — never compiled. See ios/README.md.
//
// Aaj's brain. The behavioural twin is
// `android/.../feature/today/TodayViewModel.kt`, and the shape follows it —
// one state struct, one `apply`, a pure ranker — while the file style follows
// `CheckIn/CheckInViewModel.swift`: an `ObservableObject` with `private(set)`
// published state and every mutation in a method. No view does arithmetic.
//
// The rule worth repeating from the Android twin: **a section that failed and a
// section with nothing in it must never look alike.** Everything below serves
// that, and plan 6.2's ordering rules, and plan 13's "works from cache and says
// so".

import Foundation

/// What `TodayView` renders. One struct so the ranker's output and the
/// sections it orders land in a single publish — a screen that re-renders its
/// cards twice, in two different orders, on every refresh, is a screen that
/// flickers at a pensioner.
struct TodayUiState {
    var loading = true
    var addressAs = ""
    var dateLabel = ""
    var vratLabel: String? = nil

    /// Sections in the order plan 6.2's logic decided on.
    var order: [TodaySection] = []
    var hidden: Set<TodaySection> = []

    var checkIn = TodayCheckIn()
    var pension: Loadable<PensionCard> = .loading
    var reminders: Loadable<[ReminderItem]> = .loading
    var practice: Loadable<PracticeItem?> = .loading
    var circles: Loadable<[CircleHighlight]> = .loading
    var news: Loadable<[NewsItem]> = .loading
    var weather: Loadable<WeatherSnapshot?> = .loading
    var scam: Loadable<ScamAlert?> = .loading
    var seva: Loadable<SevaMatch?> = .loading
    var liveRoom: Loadable<LiveRoom?> = .loading

    var creditPrompt: CreditPrompt = .notToday
    var connection: Connection = .checking

    /// Non-null only when there is nothing to show at all — no network and no
    /// cache. Every section still renders its own state; this banner is the
    /// screen-wide "we could not reach the server" line, which is a different
    /// sentence from "nothing today" and the difference is the whole point.
    var error: String? = nil

    /// The credit-day prompt wants the grievance helper after five "No"s.
    var noStreak: Int = 0
}

@MainActor
final class TodayViewModel: ObservableObject {

    /// How many "No" answers open the grievance helper. Plan 6.2.
    static let grievanceOfferAfterNo = 5

    @Published private(set) var ui = TodayUiState()

    /// Set when "Edit Today" is open. The sheet is view state the screen asks
    /// about, so it lives here rather than in a `@State` a view model cannot
    /// see.
    @Published var isEditingToday = false

    private let feed: TodayFeedService
    private let onDevice: OnDevicePensionSource
    private let checkInService: TodayCheckInService

    /// Injected so a test can pin the day and the clock. A UTC default would
    /// be the wrong day east or west of Greenwich, and "credit day" is the one
    /// calculation on this screen that is all about which day it is.
    private let calendar: Calendar
    private let now: () -> Date

    init(
        feed: TodayFeedService,
        onDevice: OnDevicePensionSource,
        checkInService: TodayCheckInService,
        calendar: Calendar = .autoupdatingCurrent,
        now: @escaping () -> Date = { Date() }
    ) {
        self.feed = feed
        self.onDevice = onDevice
        self.checkInService = checkInService
        self.calendar = calendar
        self.now = now
    }

    // MARK: - Lifecycle

    /// A fresh open of the screen, e.g. after returning from another tab.
    func onShown() async {
        await load()
    }

    func load() async {
        ui.loading = true
        ui.error = nil

        // The three sources are independent and none blocks the others: a
        // failure to read the on-device safe must not stop the news card, and
        // a check-in that cannot be reached must not stop the weather.
        // Plan 6.2's cards are peers, not a waterfall.
        async let feedLoad = loadFeed()
        async let localLoad = onDevice.read()
        async let checkInLoad = loadCheckIn()

        let feedResult = await feedLoad
        let local = await localLoad
        ui.checkIn = (try? await checkInLoad) ?? ui.checkIn

        switch feedResult {
        case let .success(result):
            apply(result, local: local)
        case let .failure(error):
            // The cache-backed service only throws when there is no network
            // **and** nothing saved. Say so plainly instead of spinning: a
            // pensioner who thinks the app has frozen will uninstall it, and
            // one who thinks their pension card is broken will call their
            // daughter.
            ui.loading = false
            ui.pension = .failed(message: error.localizedDescription)
            ui.reminders = .failed(message: error.localizedDescription)
            ui.practice = .failed(message: error.localizedDescription)
            ui.circles = .failed(message: error.localizedDescription)
            ui.news = .failed(message: error.localizedDescription)
            ui.weather = .failed(message: error.localizedDescription)
            ui.scam = .failed(message: error.localizedDescription)
            ui.seva = .failed(message: error.localizedDescription)
            ui.liveRoom = .failed(message: error.localizedDescription)
            ui.connection = .offline(savedAt: nil)
            ui.error = error.localizedDescription
        }
    }

    /// "Try again" on the offline banner. Bypasses any warm cache, because the
    /// user has just said the cached answer is not good enough.
    func retry() async {
        ui.loading = true
        ui.error = nil
        do {
            let result = try await feed.refresh()
            apply(result, local: await onDevice.read())
        } catch {
            ui.loading = false
            ui.connection = .offline(savedAt: ui.connection.savedAt)
            ui.error = error.localizedDescription
        }
    }

    /// Plan 6.2's "Edit Today".
    func setHidden(_ hidden: Bool, section: TodaySection) async {
        if hidden {
            ui.hidden.insert(section)
        } else {
            ui.hidden.remove(section)
        }
        ui.order = rankedOrder(feed: lastFeed)
        // The setting is remembered server-side when there is a server; the
        // screen does not wait for that, because a tap on "hide this card" that
        // does nothing until a round trip completes feels broken.
        try? await feed.setHidden(hidden, section: section)
    }

    // MARK: - The credit-day prompt

    /// Plan 6.2's prompt. Yes clears the streak; No adds to it, and five
    /// consecutive No's swap the prompt for the grievance helper. The counting
    /// is in memory for this process — a streak that survives relaunch would
    /// need a store, and five credit days is a conversation, not a session.
    func answerCredit(credited: Bool) async {
        let day = dayString(now())
        ui.noStreak = credited ? 0 : ui.noStreak + 1
        if ui.noStreak >= Self.grievanceOfferAfterNo {
            ui.creditPrompt = .offerGrievance(noStreak: ui.noStreak)
        } else {
            ui.creditPrompt = .answered(saidNo: !credited)
        }
        try? await feed.recordCreditAnswer(credited: credited, on: day)
    }

    /// A new morning resets the prompt; called by the screen on appear.
    func resetCreditPromptIfNewDay() {
        if case .answered = ui.creditPrompt {
            ui.creditPrompt = ui.pension.valueOrNull?.isCreditDayToday == true ? .due : .notToday
        }
    }

    // MARK: - Check-in

    /// The big green button. Optimistic for the same reason
    /// `CheckInViewModel.checkIn()` is: waiting for a round trip to
    /// acknowledge a tap on a 96 pt button feels broken even when it is only a
    /// second. The tick flips now and rolls back if the send fails.
    func checkInTapped() async {
        guard !ui.checkIn.sending, ui.checkIn.checkedAtLabel == nil else { return }

        var optimistic = ui.checkIn
        optimistic.sending = true
        optimistic.failed = false
        ui.checkIn = optimistic

        do {
            ui.checkIn = try await checkInService.recordCheckIn()
        } catch {
            var rolledBack = ui.checkIn
            rolledBack.sending = false
            rolledBack.failed = true
            ui.checkIn = rolledBack
        }
    }

    // MARK: - Applying a feed

    /// The last feed applied, so `setHidden` can re-rank without a reload.
    /// Kept in the class rather than the state struct because the screen never
    /// renders it directly.
    private var lastFeed: TodayFeed?

    private func apply(_ result: TodayFeedResult, local: PensionSnapshot) {
        let feed = result.feed
        lastFeed = feed

        let today = now()
        let isCreditDayToday = Self.isCreditDay(feed.creditDayOfMonth, today: today, calendar: calendar)
        // Plan 6.2 pins the pension card to the top on credit day and in a DR
        // revision week. A revision week is a property of the rate row's own
        // effective date, not a flag a server has to remember to send.
        let isDrRevisionWeek = Self.isRevisionWeek(
            effectiveFrom: Date(timeIntervalSince1970: feed.dr.effectiveFrom),
            today: today,
            calendar: calendar
        )

        let card = PensionCard(
            creditDayOfMonth: feed.creditDayOfMonth,
            nextCreditDate: Self.nextCreditDate(
                feed.creditDayOfMonth, today: today, calendar: calendar
            ),
            isCreditDayToday: isCreditDayToday,
            dr: feed.dr,
            headline: feed.headline,
            local: local
        )

        let source = result.source
        let cachedAt = feed.cachedAt.map { Date(timeIntervalSince1970: $0) }

        ui.loading = false
        ui.addressAs = feed.addressAs
        ui.dateLabel = feed.dateLabel
        ui.vratLabel = feed.vratLabel
        ui.pension = .ready(value: card, source: source, cachedAt: cachedAt)
        ui.reminders = .ready(value: feed.reminders, source: source, cachedAt: cachedAt)
        ui.practice = .ready(value: feed.practice, source: source, cachedAt: cachedAt)
        ui.circles = .ready(value: feed.circles, source: source, cachedAt: cachedAt)
        ui.news = .ready(value: feed.news, source: source, cachedAt: cachedAt)
        ui.weather = .ready(value: feed.weather, source: source, cachedAt: cachedAt)
        ui.scam = .ready(value: feed.scamAlert, source: source, cachedAt: cachedAt)
        ui.seva = .ready(value: feed.sevaMatch, source: source, cachedAt: cachedAt)
        ui.liveRoom = .ready(value: feed.liveRoom, source: source, cachedAt: cachedAt)
        ui.order = rankedOrder(feed: feed)
        ui.connection = source == .cache
            ? .offline(savedAt: cachedAt)
            : .online
        ui.error = nil

        if isCreditDayToday, ui.noStreak < Self.grievanceOfferAfterNo {
            ui.creditPrompt = .due
        }
    }

    private func rankedOrder(feed: TodayFeed?) -> [TodaySection] {
        guard let feed else { return [] }
        var opens: [TodaySection: Int] = [:]
        for (key, count) in feed.opensIn14Days {
            if let section = TodaySection(rawValue: key) { opens[section] = count }
        }
        var lastTapped: [TodaySection: Date] = [:]
        for (key, epoch) in feed.lastTapped {
            if let section = TodaySection(rawValue: key) {
                lastTapped[section] = Date(timeIntervalSince1970: epoch)
            }
        }
        let isCreditDayToday = Self.isCreditDay(
            feed.creditDayOfMonth, today: now(), calendar: calendar
        )
        let isDrRevisionWeek = Self.isRevisionWeek(
            effectiveFrom: Date(timeIntervalSince1970: feed.dr.effectiveFrom),
            today: now(),
            calendar: calendar
        )
        return TodayRanker.rank(
            opens: opens,
            lastTapped: lastTapped,
            isCreditDayToday: isCreditDayToday,
            isDrRevisionWeek: isDrRevisionWeek,
            today: now(),
            calendar: calendar
        ).filter { !ui.hidden.contains($0) && $0 != .checkIn }
    }

    // MARK: - Small async helpers

    private func loadFeed() async -> Result<TodayFeedResult, Error> {
        do {
            return .success(try await feed.load())
        } catch {
            return .failure(error)
        }
    }

    private func loadCheckIn() async throws -> TodayCheckIn {
        try await checkInService.todayCheckIn()
    }

    private func dayString(_ date: Date) -> String {
        Self.dayString(date, calendar: calendar)
    }

    static func dayString(_ date: Date, calendar: Calendar) -> String {
        // en_US_POSIX and a fixed pattern: "2026-09-29" is a wire value the
        // server compares, and a locale-formatted date is not.
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}

// MARK: - Date rules

extension TodayViewModel {

    /// "The credit day the user entered" — clamped to the 28th everywhere, for
    /// the reason the Android twin gives: a pensioner who entered the 31st
    /// because "the money comes at the end of the month" must not see a card
    /// confidently promising a date that some months do not have.
    static func isCreditDay(
        _ creditDayOfMonth: Int,
        today: Date,
        calendar: Calendar
    ) -> Bool {
        let day = calendar.component(.day, from: today)
        return day == min(max(creditDayOfMonth, 1), 28)
    }

    /// The next date the pension is due.
    static func nextCreditDate(
        _ creditDayOfMonth: Int,
        today: Date,
        calendar: Calendar
    ) -> Date? {
        let day = min(max(creditDayOfMonth, 1), 28)
        let components = DateComponents(day: day)
        guard let thisMonth = calendar.nextDate(
            after: calendar.startOfDay(for: today).addingTimeInterval(-1),
            matching: components,
            matchingPolicy: .nextTime
        ) else { return nil }
        return thisMonth
    }

    /// A revision week is the seven days from the rate's own effective date.
    /// The rate row is the admin's record of when the order took effect, so the
    /// screen never has to be told "this is a revision week" by a server that
    /// might forget.
    static func isRevisionWeek(
        effectiveFrom: Date,
        today: Date,
        calendar: Calendar
    ) -> Bool {
        let start = calendar.startOfDay(for: today)
        let end = calendar.date(byAdding: .day, value: 7, to: calendar.startOfDay(for: effectiveFrom))
        guard let end else { return false }
        return start >= calendar.startOfDay(for: effectiveFrom) && start < end
    }
}

// MARK: - Ranking

/// Plan section 6.2's order logic, in one pure function.
///
/// A later edit that drops this and renders the sections in enum order would
/// not fail a test; it would quietly stop the pension card rising to the top in
/// the two weeks when a pensioner most needs it, which is the whole reason the
/// rule exists. Keep it pure and keep it tested.
enum TodayRanker {

    /// "Cards never tapped in 30 days move down."
    static let demoteAfterDays = 30

    /// The cards that can be ranked. Check-in is not among them: its place is
    /// fixed by "check-in comes next while it is pending" (plan 6.2 order
    /// logic), which the screen renders above the ranked block.
    private static let cards: [TodaySection] = [
        .pension, .reminders, .practice, .circles, .liveRoom,
        .seva, .scam, .weather, .news,
    ]

    static func rank(
        opens: [TodaySection: Int],
        lastTapped: [TodaySection: Date],
        isCreditDayToday: Bool,
        isDrRevisionWeek: Bool,
        today: Date,
        calendar: Calendar
    ) -> [TodaySection] {
        cards.sorted { a, b in
            let pinA = (a == .pension && (isCreditDayToday || isDrRevisionWeek)) ? 0 : 1
            let pinB = (b == .pension && (isCreditDayToday || isDrRevisionWeek)) ? 0 : 1
            if pinA != pinB { return pinA < pinB }

            let staleA = isStale(a, lastTapped: lastTapped, today: today, calendar: calendar) ? 1 : 0
            let staleB = isStale(b, lastTapped: lastTapped, today: today, calendar: calendar) ? 1 : 0
            if staleA != staleB { return staleA < staleB }

            // The plan's actual signal: opens in the last 14 days.
            return (opens[b] ?? 0) < (opens[a] ?? 0)
        }
    }

    /// Whether the card has been untouched for `demoteAfterDays`.
    ///
    /// Never tapped counts as stale: a card the user has never opened has no
    /// evidence either way, and plan 6.2's default is to let the ones they do
    /// open rise above it.
    private static func isStale(
        _ section: TodaySection,
        lastTapped: [TodaySection: Date],
        today: Date,
        calendar: Calendar
    ) -> Bool {
        guard let last = lastTapped[section] else { return true }
        let days = calendar.dateComponents(
            [.day], from: calendar.startOfDay(for: last), to: calendar.startOfDay(for: today)
        ).day ?? 0
        return days > demoteAfterDays
    }
}