// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 4.5's Today wireframe, and section 6.2's card list and order
// logic. Native SwiftUI, per plan section 8.1.
//
// The ordering on screen is the design — plan 6.2 is seven paragraphs about
// what comes first — so this view renders `TodayViewModel.ui.order` and does
// not re-decide anything. What it *does* own is the distinction plan 6.2 and
// 13 care about most: **"nothing today" and "we could not reach the server"
// must never look alike.** Every section has four states — loading, failed,
// empty, ready — and the failed state carries its own sentence and a Try again
// button, because a pensioner who depends on the pension card cannot be left
// guessing which of the two they are looking at.
//
// DESIGN CONTRACT — `Design/Components/` is being written in parallel. The
// call shapes assumed here follow the list recorded in
// `Saathi/SaathiModels.swift`, plus the two that list does not cover:
//
//   DoosriCheckInButton(checkedAtLabel: String?, sending: Bool = false,
//                       enabled: Bool = true, action: () -> Void)
//   DoosriFloatingMicButton(listening: Bool = false, action: () -> Void)
//
// Everything else — `DoosriScreen`, `DoosriCard`, `DoosriListRow`,
// `DoosriSecondaryButton`, `DoosriEmptyState`, `DoosriSheet`,
// `DoosriSectionHeader`, `DoosriToolbarAction.sos` — is called exactly as
// `SaathiModels.swift` records it.

import SwiftUI

/// Where each card goes when tapped.
///
/// A struct of closures rather than thirteen init parameters, and all of them
/// required, because the one thing worse than a dead button on a pensioner's
/// screen is a dead button that was quietly defaulted to doing nothing. The
/// app target wires this once; a test wires it to recorders.
struct TodayActions {
    var sos: () -> Void
    var bolo: () -> Void
    var openPension: () -> Void
    var openPensionNews: (PensionHeadline) -> Void
    var openSlipReader: () -> Void
    var openGrievance: () -> Void
    var openReminder: (ReminderItem) -> Void
    var openPractice: (PracticeItem) -> Void
    var openCircleItem: (CircleHighlight) -> Void
    var joinRoom: (LiveRoom) -> Void
    var openSeva: (SevaMatch) -> Void
    var openScam: (ScamAlert) -> Void
    var openNews: (NewsItem) -> Void
}

struct TodayView: View {
    @StateObject private var model: TodayViewModel
    @Environment(\.colorScheme) private var scheme

    let actions: TodayActions
    /// The mic's state belongs to the voice layer, not to Today — see the note
    /// on `TodayCheckIn`. Today draws the button and asks.
    let isBoloListening: Bool

    init(
        model: TodayViewModel,
        actions: TodayActions,
        isBoloListening: Bool = false
    ) {
        _model = StateObject(wrappedValue: model)
        self.actions = actions
        self.isBoloListening = isBoloListening
    }

    var body: some View {
        NavigationStack {
            DoosriScreen(
                greetingTitle,
                largeTitle: true,
                showsBackButton: false,
                toolbar: [DoosriToolbarAction.sos(action: actions.sos)]
            ) {
                VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                    dateLine

                    if model.ui.checkIn.enabled {
                        checkInSection
                    }

                    if let error = model.ui.error {
                        errorBanner(error)
                    }

                    ForEach(model.ui.order, id: \.self) { section in
                        sectionView(for: section)
                    }

                    editTodayRow
                }
            }
            .overlay(alignment: .bottomTrailing) {
                // Plan 4.5: the floating mic, above the tab bar. It is drawn
                // here because the wireframe puts it on this screen; if the tab
                // shell ends up owning the floating layer, delete this overlay
                // and pass nothing — the button is chrome, not content.
                DoosriFloatingMicButton(
                    listening: isBoloListening,
                    action: actions.bolo
                )
                .padding(.trailing, DoosriSpace.screenMargin)
                .padding(.bottom, DoosriSpace.xl)
            }
        }
        .tint(DoosriPalette.accent(scheme))
        .task {
            await model.onShown()
        }
        .sheet(isPresented: $model.isEditingToday) {
            editTodaySheet
        }
    }

    // MARK: - Greeting

    /// Plan 4.5: the large title is the greeting, "Namaste, Sharma ji".
    private var greetingTitle: String {
        let name = model.ui.addressAs
        guard !name.isEmpty else {
            return TodayText.t("app_name", "Doosri Pari")
        }
        return String(
            format: TodayText.t("today_greeting", "Namaste, %@"),
            name
        )
    }

    /// The date and the day's vrat, under the title. `dateLabel` is formatted
    /// server-side in the user's language; the local fallback exists so the
    /// screen is never blank while the feed loads.
    private var dateLine: some View {
        Text(dateLineText)
            .font(.doosriCaption)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            .doosriBodyLineHeight()
    }

    private var dateLineText: String {
        let date = model.ui.dateLabel.isEmpty
            ? Date.now.formatted(.dateTime.weekday(.wide).day().month(.wide))
            : model.ui.dateLabel
        guard let vrat = model.ui.vratLabel, !vrat.isEmpty else { return date }
        return date + " · " + vrat
    }

    // MARK: - Check-in

    /// Plan 4.4 and 4.5: the 96 pt green button, second only to the greeting.
    private var checkInSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriCheckInButton(
                checkedAtLabel: model.ui.checkIn.checkedAtLabel,
                sending: model.ui.checkIn.sending,
                enabled: !model.ui.checkIn.sending && model.ui.checkIn.checkedAtLabel == nil
            ) {
                Task { await model.checkInTapped() }
            }

            if model.ui.checkIn.paused {
                Text(
                    TodayText.t(
                        "checkin_paused_note",
                        "Check-ins are paused. Nobody will be alerted while you are away."
                    )
                )
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriBodyLineHeight()
            }

            if model.ui.checkIn.failed {
                // A failed check-in is the one failure on this screen that is
                // about a person, so it is a sentence with a remedy, not a
                // toast that disappears before it is read.
                HStack(spacing: DoosriSpace.sm) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .doosriDecorativeIcon()
                    Text(
                        TodayText.t(
                            "check_in_failed",
                            "Could not send. Please try again."
                        )
                    )
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .doosriBodyLineHeight()
                    Spacer()
                    Button {
                        Task { await model.checkInTapped() }
                    } label: {
                        Text(TodayText.t("retry", "Try again"))
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.accent(scheme))
                    }
                    .doosriButtonSemantics(
                        label: TodayText.t("retry", "Try again"),
                        hint: TodayText.t(
                            "retry_check_in_hint",
                            "Sends your check-in again."
                        )
                    )
                }
                .padding(DoosriSpace.md)
                .background(
                    RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                        .fill(DoosriPalette.surface(scheme))
                )
            }
        }
    }

    // MARK: - Offline banner

    /// Plan 13, said out loud. Plain words, not a spinner: a pensioner on a
    /// train must be able to see that this is old data rather than today's,
    /// and "no connection, and nothing saved" is a different problem from
    /// "showing what was saved" — the two get different sentences.
    private func errorBanner(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.md) {
                Image(systemName: "cloud.slash")
                    .font(.system(size: 32))
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriDecorativeIcon()
                Text(message)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .doosriBodyLineHeight()
            }
            DoosriSecondaryButton(
                TodayText.t("retry", "Try again"),
                systemImage: "arrow.clockwise"
            ) {
                Task { await model.retry() }
            }
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
    }

    /// Shown above a card whose data came off the phone's cache.
    private func cachedLine(_ cachedAt: Date?) -> some View {
        HStack(spacing: DoosriSpace.sm) {
            Image(systemName: "cloud.slash")
                .font(.system(size: 20))
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriDecorativeIcon()
            Text(
                cachedAt.map {
                    String(
                        format: TodayText.t(
                            "today_saved_on",
                            "Saved on this phone at %@"
                        ),
                        $0.formatted(date: .omitted, time: .shortened)
                    )
                } ?? TodayText.t("today_saved", "Saved on this phone")
            )
            .font(.doosriCaption)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            .doosriBodyLineHeight()
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func sectionView(for section: TodaySection) -> some View {
        switch section {
        case .checkIn:
            // Ranked out in the view model; the button lives at the top.
            EmptyView()
        case .pension:
            pensionSection
        case .reminders:
            TodaySectionCard(
                section: section,
                state: model.ui.reminders,
                onRetry: retry,
                isEmpty: { $0.isEmpty },
                emptyMessage: TodayText.t(
                    "today_no_reminders",
                    "Nothing is due today."
                )
            ) { reminders in
                VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                    ForEach(reminders) { reminder in
                        DoosriListRow(
                            systemImage: reminderIcon(reminder.kind),
                            title: reminder.title,
                            value: reminder.whenLabel
                        ) {
                            actions.openReminder(reminder)
                        }
                    }
                }
            }
        case .practice:
            TodaySectionCard(
                section: section,
                state: model.ui.practice,
                onRetry: retry,
                isEmpty: { $0 == nil },
                emptyMessage: TodayText.t(
                    "today_no_practice",
                    "Nothing planned today. A session will appear here."
                )
            ) { practice in
                if let practice {
                    VStack(alignment: .leading, spacing: DoosriSpace.md) {
                        Text(practice.title)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .doosriBodyLineHeight()
                        Text(practice.subtitle)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .doosriBodyLineHeight()
                        DoosriSecondaryButton(
                            String(
                                format: TodayText.t("today_start_minutes", "Start · %d min"),
                                practice.minutes
                            ),
                            systemImage: "play.fill"
                        ) {
                            actions.openPractice(practice)
                        }
                    }
                }
            }
        case .circles:
            TodaySectionCard(
                section: section,
                state: model.ui.circles,
                onRetry: retry,
                isEmpty: { $0.isEmpty },
                emptyMessage: TodayText.t(
                    "today_no_circles",
                    "Nothing new from your circles today."
                )
            ) { highlights in
                VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                    ForEach(highlights) { item in
                        DoosriListRow(
                            systemImage: circleIcon(item.kind),
                            title: item.line,
                            value: item.circle
                        ) {
                            actions.openCircleItem(item)
                        }
                    }
                }
            }
        case .liveRoom:
            TodaySectionCard(
                section: section,
                state: model.ui.liveRoom,
                onRetry: retry,
                isEmpty: { $0 == nil },
                emptyMessage: TodayText.t(
                    "today_no_room",
                    "No voice room is on right now."
                )
            ) { room in
                if let room {
                    VStack(alignment: .leading, spacing: DoosriSpace.md) {
                        Text(room.title)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .doosriBodyLineHeight()
                        Text(
                            String(
                                format: TodayText.t(
                                    "today_room_host",
                                    "Hosted by %@ · %d listening"
                                ),
                                room.host,
                                room.listeners
                            )
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        .doosriBodyLineHeight()
                        DoosriSecondaryButton(
                            TodayText.t("today_join_room", "Join"),
                            systemImage: "waveform"
                        ) {
                            actions.joinRoom(room)
                        }
                    }
                }
            }
        case .seva:
            TodaySectionCard(
                section: section,
                state: model.ui.seva,
                onRetry: retry,
                isEmpty: { $0 == nil },
                emptyMessage: TodayText.t(
                    "today_no_seva",
                    "No match today. New ones appear as they come."
                )
            ) { match in
                if let match {
                    VStack(alignment: .leading, spacing: DoosriSpace.md) {
                        Text(match.title)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .doosriBodyLineHeight()
                        Text(match.why)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .doosriBodyLineHeight()
                        DoosriSecondaryButton(
                            TodayText.t("today_see_match", "See this match"),
                            systemImage: "hands.sparkles"
                        ) {
                            actions.openSeva(match)
                        }
                    }
                }
            }
        case .scam:
            TodaySectionCard(
                section: section,
                state: model.ui.scam,
                onRetry: retry,
                isEmpty: { $0 == nil },
                emptyMessage: TodayText.t(
                    "today_no_scam",
                    "No scam alert this week."
                )
            ) { alert in
                if let alert {
                    VStack(alignment: .leading, spacing: DoosriSpace.md) {
                        Text(alert.title)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .doosriBodyLineHeight()
                        Text(alert.body)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .doosriBodyLineHeight()
                        DoosriSecondaryButton(
                            TodayText.t("today_read_warning", "Read this warning"),
                            systemImage: "shield.lefthalf.filled"
                        ) {
                            actions.openScam(alert)
                        }
                    }
                }
            }
        case .weather:
            TodaySectionCard(
                section: section,
                state: model.ui.weather,
                onRetry: retry,
                isEmpty: { $0 == nil },
                emptyMessage: TodayText.t(
                    "today_no_weather",
                    "Weather is not available right now."
                )
            ) { weather in
                if let weather {
                    VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                        Text(
                            String(
                                format: TodayText.t(
                                    "today_weather_line",
                                    "%@ · %@"
                                ),
                                weather.city,
                                weather.summary
                            )
                        )
                        .font(.doosriHeadline)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .doosriBodyLineHeight()
                        Text(
                            String(
                                format: TodayText.t(
                                    "today_weather_range",
                                    "Low %d° · High %d°"
                                ),
                                weather.minC,
                                weather.maxC
                            )
                        )
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .doosriBodyLineHeight()
                        if let air = weather.airQuality {
                            Text(
                                String(
                                    format: TodayText.t(
                                        "today_air_quality",
                                        "Air: %@"
                                    ),
                                    air
                                )
                            )
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .doosriBodyLineHeight()
                        }
                    }
                }
            }
        case .news:
            TodaySectionCard(
                section: section,
                state: model.ui.news,
                onRetry: retry,
                isEmpty: { $0.isEmpty },
                emptyMessage: TodayText.t(
                    "today_no_news",
                    "No pension news for today."
                )
            ) { items in
                VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                    ForEach(items) { item in
                        DoosriListRow(
                            systemImage: "newspaper",
                            title: item.headline,
                            value: item.source
                        ) {
                            actions.openNews(item)
                        }
                    }
                }
            }
        }
    }

    // MARK: - The pension card

    /// Plan 6.2's pension card, and the boundary plan 14 draws through it.
    ///
    /// The rate, the credit day and the headline are server data and are
    /// ordinary list rows. The rupee figures at the bottom are **this phone's**,
    /// read from the on-device document safe, and they are separated here by a
    /// divider, a lock icon and a sentence that says so — not by a comment
    /// alone. A later edit that turns `local` into a field of the feed, or that
    /// drops the "saved on this phone" block because the figures "should be on
    /// the server for backup", compiles cleanly and puts a pensioner's net
    /// pension into a database plan section 10 says must never hold it. The
    /// rule is said on the screen as well as in `TodayModels.swift` because
    /// this is where somebody will be standing when they decide to break it.
    private var pensionSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            if case let .ready(card, source, cachedAt) = model.ui.pension {
                DoosriSectionHeader(TodaySection.pension.label)
                if source == .cache {
                    cachedLine(cachedAt)
                }
                DoosriCard {
                    drRow(card)
                    if let headline = card.headline {
                        DoosriListRow(
                            systemImage: "doc.text",
                            title: headline.headline,
                            value: headline.source
                        ) {
                            actions.openPensionNews(headline)
                        }
                    }
                    creditPromptView(card)
                    Divider().background(DoosriPalette.divider(scheme))
                    localFigures(card)
                }
            } else {
                TodaySectionCard(
                    section: .pension,
                    state: model.ui.pension,
                    onRetry: retry,
                    isEmpty: { _ in false },
                    emptyMessage: ""
                ) { card in
                    EmptyView()
                }
            }
        }
    }

    /// "DR 58% · credits on the 1st" — a rate from the admin's table and a day
    /// the user entered. Neither is money, which is exactly why the server is
    /// allowed to hold them.
    private func drRow(_ card: PensionCard) -> some View {
        let percent = card.dr.ratePercent
            .formatted(.number.precision(.fractionLength(0)))
        let creditDay = min(max(card.creditDayOfMonth, 1), 28)
        return DoosriListRow(
            systemImage: "percent",
            title: String(
                format: TodayText.t("today_dr_line", "DR %@%% · credits on the %d"),
                percent,
                creditDay
            ),
            value: card.nextCreditDate.map {
                $0.formatted(.dateTime.day().month(.wide))
            }
        ) {
            actions.openPension()
        }
    }

    /// Plan 6.2: "on credit day, a 'Did your pension credit?' prompt; five days
    /// of 'No' links to the grievance helper."
    @ViewBuilder
    private func creditPromptView(_ card: PensionCard) -> some View {
        switch model.ui.creditPrompt {
        case .notToday:
            EmptyView()
        case .due:
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(
                    TodayText.t(
                        "today_credit_question",
                        "Did your pension credit today?"
                    )
                )
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .doosriBodyLineHeight()
                HStack(spacing: DoosriSpace.controlGap) {
                    DoosriSecondaryButton(
                        TodayText.t("today_credit_yes", "Yes, it came"),
                        systemImage: "checkmark"
                    ) {
                        Task { await model.answerCredit(credited: true) }
                    }
                    DoosriSecondaryButton(
                        TodayText.t("today_credit_no", "No, not yet"),
                        systemImage: "clock"
                    ) {
                        Task { await model.answerCredit(credited: false) }
                    }
                }
            }
        case let .answered(saidNo):
            HStack(spacing: DoosriSpace.sm) {
                Image(systemName: saidNo ? "clock" : "checkmark.circle.fill")
                    .font(.system(size: 24))
                    .foregroundStyle(
                        saidNo
                            ? DoosriPalette.inkSecondary(scheme)
                            : DoosriPalette.ok(scheme)
                    )
                    .doosriDecorativeIcon()
                Text(
                    saidNo
                        ? TodayText.t(
                            "today_credit_noted_no",
                            "Noted. We will ask again tomorrow."
                        )
                        : TodayText.t(
                            "today_credit_noted_yes",
                            "Good. Noted."
                        )
                )
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .doosriBodyLineHeight()
            }
        case let .offerGrievance(noStreak):
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(
                    String(
                        format: TodayText.t(
                            "today_credit_streak",
                            "You have told us %d times that the pension has not credited. Would you like help writing a grievance?"
                        ),
                        noStreak
                    )
                )
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .doosriBodyLineHeight()
                DoosriSecondaryButton(
                    TodayText.t("today_open_grievance", "Open the grievance helper"),
                    systemImage: "envelope"
                ) {
                    actions.openGrievance()
                }
            }
        }
    }

    /// The phone's own figures. Behind the boundary.
    @ViewBuilder
    private func localFigures(_ card: PensionCard) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            HStack(spacing: DoosriSpace.sm) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriDecorativeIcon()
                Text(
                    TodayText.t(
                        "today_local_boundary",
                        "These figures are saved on this phone only. They are never sent to us."
                    )
                )
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriBodyLineHeight()
            }

            if let local = card.local, local.hasAnyFigure {
                if let basic = local.basicPensionRupees {
                    localRow(
                        TodayText.t("today_basic_pension", "Basic pension"),
                        TodayText.rupees(basic)
                    )
                }
                if let net = local.netPensionRupees {
                    localRow(
                        TodayText.t("today_net_pension", "Net pension"),
                        TodayText.rupees(net)
                    )
                }
                if let readOn = local.readOn {
                    Text(
                        String(
                            format: TodayText.t(
                                "today_read_on",
                                "Read from your slip on %@"
                            ),
                            readOn.formatted(date: .abbreviated, time: .omitted)
                        )
                    )
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriBodyLineHeight()
                }
            } else {
                Text(
                    TodayText.t(
                        "today_no_local_figures",
                        "Your own pension figures are not on this phone yet."
                    )
                )
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .doosriBodyLineHeight()
                DoosriSecondaryButton(
                    TodayText.t("today_read_slip", "Read a pension slip"),
                    systemImage: "doc.viewfinder"
                ) {
                    actions.openSlipReader()
                }
            }
        }
    }

    private func localRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.doosriBody)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            Spacer()
            // Tabular figures: two money columns that jitter as the digits
            // change are harder to compare, and comparison is the only reason
            // this line exists.
            Text(value)
                .font(.doosriBody.monospacedDigit())
                .foregroundStyle(DoosriPalette.ink(scheme))
        }
        .frame(minHeight: DoosriSize.minControlHeight)
    }

    // MARK: - Edit Today

    private var editTodayRow: some View {
        DoosriListRow(
            systemImage: "slider.horizontal.3",
            title: TodayText.t("today_edit", "Edit Today"),
            value: TodayText.t("today_edit_value", "Hide cards you don't want")
        ) {
            model.isEditingToday = true
        }
    }

    /// Plan 6.2: "'Edit Today' lets the user hide cards." A sheet, because it
    /// is a short task and plan 4.4 says short tasks are sheets — with a Cancel
    /// that says what it does, because plan 3.4 rules out swipe-only actions.
    private var editTodaySheet: some View {
        DoosriSheet(
            title: TodayText.t("today_edit", "Edit Today"),
            onCancel: { model.isEditingToday = false }
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(
                    TodayText.t(
                        "today_edit_note",
                        "Turn a card off to stop it appearing here. Your pension and check-in stay."
                    )
                )
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriBodyLineHeight()

                ForEach(TodaySection.allCases, id: \.self) { section in
                    if section != .checkIn && section != .pension {
                        Toggle(isOn: visibilityBinding(for: section)) {
                            Text(section.label)
                                .font(.doosriBody)
                                .foregroundStyle(DoosriPalette.ink(scheme))
                        }
                        .tint(DoosriPalette.accent(scheme))
                        .frame(minHeight: DoosriSize.minControlHeight)
                    }
                }

                DoosriPrimaryButton(
                    TodayText.t("today_edit_done", "Done"),
                    systemImage: "checkmark"
                ) {
                    model.isEditingToday = false
                }
            }
        }
    }

    private func visibilityBinding(for section: TodaySection) -> Binding<Bool> {
        Binding(
            get: { !model.ui.hidden.contains(section) },
            set: { visible in
                Task { await model.setHidden(!visible, section: section) }
            }
        )
    }

    // MARK: - Icons

    private func reminderIcon(_ kind: ReminderKind) -> String {
        switch kind {
        case .lifeCertificate: return "person.text.rectangle"
        case .tax: return "doc.plaintext"
        case .cghsRenewal: return "cross.case"
        case .medical: return "pills"
        case .commutationRestoration: return "arrow.uturn.forward"
        case .additionalPension: return "gift"
        case .other: return "bell"
        }
    }

    private func circleIcon(_ kind: CircleHighlightKind) -> String {
        switch kind {
        case .post: return "bubble.left.and.bubble.right"
        case .birthday: return "gift"
        case .retirement: return "figure.walk.departure"
        }
    }

    private func retry() {
        Task { await model.retry() }
    }
}

// MARK: - The four states of every section

/// Loading, failed, empty and ready — the same four for every card, so that
/// "nothing today" (an empty state, one sentence, no red) can never be
/// mistaken for "we could not find out" (a sentence that says so, plus a Try
/// again button). Plan 6.2's trust rule: a section that failed is *visible* as
/// a failure, never as an empty card.
private struct TodaySectionCard<Value, CardContent: View>: View {
    let section: TodaySection
    let state: Loadable<Value>
    let onRetry: () -> Void
    let isEmpty: (Value) -> Bool
    let emptyMessage: String
    @ViewBuilder let content: (Value) -> CardContent

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        switch state {
        case .loading:
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                DoosriSectionHeader(section.label)
                DoosriCard {
                    HStack(spacing: DoosriSpace.md) {
                        ProgressView()
                            .tint(DoosriPalette.accent(scheme))
                        Text(
                            String(
                                format: TodayText.t(
                                    "today_loading_section",
                                    "Loading %@…"
                                ),
                                section.label.lowercased()
                            )
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        .doosriBodyLineHeight()
                    }
                }
            }

        case let .failed(message):
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                DoosriSectionHeader(section.label)
                DoosriCard {
                    HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.md) {
                        Image(systemName: "wifi.exclamationmark")
                            .font(.system(size: 28))
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .doosriDecorativeIcon()
                        // Not `emptyMessage`. A failed section says it could
                        // not be reached; an empty one says there is nothing.
                        // Collapsing the two is how an app loses a pensioner's
                        // trust in a morning.
                        Text(
                            message.isEmpty
                                ? TodayText.t(
                                    "today_section_failed",
                                    "We could not reach the server for this card."
                                )
                                : message
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .doosriBodyLineHeight()
                    }
                    DoosriSecondaryButton(
                        TodayText.t("retry", "Try again"),
                        systemImage: "arrow.clockwise",
                        action: onRetry
                    )
                }
            }

        case let .ready(value, source, cachedAt):
            if isEmpty(value) {
                DoosriEmptyState(
                    systemImage: "tray",
                    title: section.label,
                    message: emptyMessage
                )
            } else {
                VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                    DoosriSectionHeader(section.label)
                    if source == .cache {
                        cachedLine(cachedAt)
                    }
                    DoosriCard {
                        content(value)
                    }
                }
            }
        }
    }

    private func cachedLine(_ cachedAt: Date?) -> some View {
        HStack(spacing: DoosriSpace.sm) {
            Image(systemName: "cloud.slash")
                .font(.system(size: 20))
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriDecorativeIcon()
            Text(
                cachedAt.map {
                    String(
                        format: TodayText.t(
                            "today_saved_on",
                            "Saved on this phone at %@"
                        ),
                        $0.formatted(date: .omitted, time: .shortened)
                    )
                } ?? TodayText.t("today_saved", "Saved on this phone")
            )
            .font(.doosriCaption)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            .doosriBodyLineHeight()
        }
    }
}