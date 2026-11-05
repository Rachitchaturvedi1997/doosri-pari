// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.2: the festival and vrat calendar, by region — Ekadashi,
// Pradosh, Navratri, Ramzan, Gurpurab, Christmas, Paryushan and the rest —
// with reminders the evening before.
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// No safety profile, no doctor's note, no safety gate belongs on this
// calendar. See `AdhyatmModels.swift`'s header.
//
// ==========================================================================
//
// THE OTHER RULE, SPECIFIC TO THIS FILE: THE DATES ARE NEVER COMPUTED HERE.
//
// Every date on this screen is a string that came from the licensed panchang
// source, and it stays a string all the way to the pixels. An auspicious date
// that is wrong is real harm to somebody who planned a trip around it — a
// fast kept on the wrong day, a family arriving at a gurdwara for an empty
// hall, a train booked for the wrong Ekadashi. Plan 7.2 says the tithi and
// panchang come from a licensed source and that any astronomy library's
// licence must be checked before use; the app's job is to display and
// attribute, nothing more.
//
// If the feed cannot be reached, the screen says so and shows nothing else.
// It must never fall back to computing a date locally. If somebody adds that
// fallback, this app starts lying to its most trusting users on the days that
// matter most to them.

import SwiftUI

@MainActor
final class FestivalCalendarViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
        /// The licensed source itself is down. Separate from `failed`, with
        /// its own wording, because the remedy is "wait", not "try again in
        /// a moment".
        case sourceUnavailable
    }

    @Published private(set) var festivals: [FestivalDay] = []
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    @Published var region: String
    @Published private(set) var paths: [SpiritualPath] = []

    let service: AdhyatmService

    init(service: AdhyatmService, region: String) {
        self.service = service
        self.region = region
    }

    func load() async {
        loading = true
        error = nil
        do {
            paths = try await service.chosenPaths()
            festivals = try await service.festivals(region: region, paths: paths)
            loading = false
        } catch let e as AdhyatmServiceError {
            loading = false
            switch e {
            case .offline: error = .offline
            case .sourceUnavailable: error = .sourceUnavailable
            case .failed: error = .failed
            }
        } catch {
            loading = false
            error = .failed
        }
    }

    func setRegion(_ newRegion: String) async {
        region = newRegion
        await load()
    }

    /// Plan 7.2: "reminders the evening before", opt-in per day.
    func setReminder(_ festival: FestivalDay, on: Bool) async {
        do {
            let updated = try await service.setFestivalReminder(
                festivalID: festival.id,
                on: on
            )
            if let index = festivals.firstIndex(where: { $0.id == festival.id }) {
                festivals[index] = updated
            }
        } catch let e as AdhyatmServiceError {
            error = e.isOffline ? .offline : .failed
        } catch {
            error = .failed
        }
    }

    /// The regions the source covers. This is a display list from the
    /// service's data, not a list the app invents: region names and their
    /// observances are the source's business (plan 7.2: "by region").
    var regions: [String] {
        var seen: [String] = []
        for festival in festivals where !seen.contains(festival.region) {
            seen.append(festival.region)
        }
        return seen
    }
}

struct FestivalCalendarView: View {
    @StateObject private var model: FestivalCalendarViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: FestivalCalendarViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_festival_title", "Festivals and vrats"),
            subtitle: KendraText.t(
                "ken_festival_subtitle",
                "Dates from the licensed panchang, by region"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                sourceLine

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.festivals.isEmpty {
                    DoosriEmptyState(
                        systemImage: "calendar",
                        title: KendraText.t(
                            "ken_festival_empty_title",
                            "Nothing listed for this region"
                        ),
                        message: KendraText.t(
                            "ken_festival_empty_body",
                            "The source's list covers regions one at a time. Try another region."
                        )
                    )
                } else {
                    regionRow
                    festivalList
                }
            }
        }
        .task {
            if model.festivals.isEmpty { await model.load() }
        }
    }

    // MARK: - Attribution, always visible

    private var sourceLine: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(KendraText.t(
                    "ken_festival_source_title",
                    "Where these dates come from"
                ))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
                Text(KendraText.t(
                    "ken_festival_source_body",
                    "Every date here comes from a licensed panchang source and is shown exactly as that source gives it. This app never calculates a tithi or a festival date itself — a wrong date would send somebody on a journey for nothing."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var regionRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DoosriSpace.sm) {
                ForEach(model.regions, id: \.self) { region in
                    Button {
                        Task { await model.setRegion(region) }
                    } label: {
                        Text(region)
                            .font(.doosriCallout)
                            .frame(minHeight: DoosriSize.minControlHeight)
                            .padding(.horizontal, DoosriSpace.md)
                            .background(
                                RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                                    .fill(
                                        model.region == region
                                            ? DoosriPalette.accent(scheme)
                                            : DoosriPalette.surface(scheme)
                                    )
                            )
                            .foregroundStyle(
                                model.region == region
                                    ? DoosriPalette.background(scheme)
                                    : DoosriPalette.ink(scheme)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var festivalList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            ForEach(model.festivals) { festival in
                festivalCard(festival)
            }
        }
    }

    private func festivalCard(_ festival: FestivalDay) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(festival.name)
                    .font(.doosriHeadline)
                    // Marigold: the spiritual centre's colour, used as a
                    // highlight on a name. Never body text on a light
                    // background (plan 4.3) — the sentences below are ink.
                    .foregroundStyle(DoosriPalette.marigold(scheme))

                // The date as the source spells it. Shown verbatim; see the
                // file comment for why it is never derived here.
                Text(festival.dateText)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                Text(festival.kindLabel + " · " + festival.region)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))

                Text(
                    KendraText.t("ken_festival_source_credit", "Source")
                        + ": " + festival.sourceName
                        + " · " + festival.sourceLastUpdated
                )
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))

                Toggle(isOn: Binding(
                    get: { festival.remindEveningBefore },
                    set: { on in
                        Task { await model.setReminder(festival, on: on) }
                    }
                )) {
                    Text(KendraText.t(
                        "ken_festival_remind",
                        "Remind me the evening before"
                    ))
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                }
                .toggleStyle(.switch)
                .frame(minHeight: DoosriSize.minControlHeight)
            }
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: FestivalCalendarViewModel.LoadError) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: "wifi.exclamationmark")
                    .font(.system(size: 24))
                Text(errorText(error))
                    .font(.doosriCallout)
                Spacer()
            }
            if error != .sourceUnavailable {
                Button {
                    Task { await model.load() }
                } label: {
                    Text(KendraText.t("retry", "Try again"))
                        .font(.doosriCallout)
                }
            }
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
        .foregroundStyle(DoosriPalette.ink(scheme))
    }

    private func errorText(_ error: FestivalCalendarViewModel.LoadError) -> String {
        switch error {
        case .offline:
            return KendraText.t(
                "ken_offline",
                "No connection. Nothing has been lost."
            )
        case .sourceUnavailable:
            // The calendar shows NOTHING rather than a guess. See the file
            // comment.
            return KendraText.t(
                "ken_festival_unavailable",
                "The calendar source is unavailable today. No dates are shown, because we will not guess a date that matters this much."
            )
        case .failed:
            return KendraText.t("ken_failed", "Could not load. Please try again.")
        }
    }
}