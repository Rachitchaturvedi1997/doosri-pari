// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.2: the spiritual state — chosen paths, today's spiritual
// start, japa and sankalp.
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// The exercise code above this file (YogModels, SafetyProfileView,
// ProgrammeListView, SessionPlayerView) is built on withholding things for
// safety. NONE of that applies here. No safety profile, no doctor's note, no
// mobility check, no pose filter, no safety flag, no gate. A suggestion on
// this screen is offered in plan 7.2's register — "traditionally…", "many
// people find…" — and never vetted, never promised, never medical.
//
// ==========================================================================

import SwiftUI

@MainActor
final class SpiritualStateViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var paths: [SpiritualPath] = []
    @Published private(set) var start: SpiritualStart?
    @Published private(set) var japa: JapaState?
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    let service: AdhyatmService

    init(service: AdhyatmService) {
        self.service = service
    }

    var hasChosenPaths: Bool { !paths.isEmpty }

    func load() async {
        loading = true
        error = nil
        do {
            let chosen = try await service.chosenPaths()
            paths = chosen
            if !chosen.isEmpty {
                let weekday = Calendar.current.component(.weekday, from: Date())
                async let startCall = service.spiritualStart(paths: chosen, weekday: weekday)
                async let japaCall = service.japaState(paths: chosen)
                start = try await startCall
                japa = try await japaCall
            }
            loading = false
        } catch let e as AdhyatmServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    func togglePath(_ path: SpiritualPath) {
        if let index = paths.firstIndex(of: path) {
            paths.remove(at: index)
        } else {
            paths.append(path)
        }
    }

    func savePaths() async {
        error = nil
        do {
            try await service.setChosenPaths(paths)
            await load()
        } catch let e as AdhyatmServiceError {
            error = e.isOffline ? .offline : .failed
        } catch {
            error = .failed
        }
    }

    /// One tap of the mala. The count is the user's own doing — there is
    /// nothing to win here and no score, so this is a counter and a haptic,
    /// not a game.
    func recordTap() async {
        guard japa != nil else { return }
        do {
            japa = try await service.recordJapaCount(1)
        } catch let e as AdhyatmServiceError {
            error = e.isOffline ? .offline : .failed
        } catch {
            error = .failed
        }
    }
}

struct SpiritualStateView: View {
    @StateObject private var model: SpiritualStateViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: SpiritualStateViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_spiritual_title", "Adhyatm Kendra"),
            subtitle: KendraText.t(
                "ken_spiritual_subtitle",
                "Your path, your practice, your pace"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if !model.hasChosenPaths {
                    pathPicker
                } else {
                    todayCard
                    japaCard
                    pathPicker
                    if let error = model.error { errorLine(error) }
                }
            }
        }
        .task {
            await model.load()
        }
    }

    // MARK: - Paths (plan 7.2: "the whole centre follows the choice")

    private var pathPicker: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            DoosriSectionHeader(
                KendraText.t("ken_paths_title", "Your path or paths"),
                subtitle: KendraText.t(
                    "ken_paths_sub",
                    "One, several, or none of these — the centre follows what you choose"
                )
            )
            ForEach(SpiritualPath.allCases, id: \.self) { path in
                Toggle(isOn: Binding(
                    get: { model.paths.contains(path) },
                    set: { _ in model.togglePath(path) }
                )) {
                    Text(path.displayName)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                }
                .toggleStyle(.switch)
                .frame(minHeight: DoosriSize.minControlHeight)
            }
            DoosriPrimaryButton(
                KendraText.t("ken_paths_save", "Save my paths"),
                systemImage: "checkmark"
            ) {
                Task { await model.savePaths() }
            }
        }
    }

    // MARK: - Today's spiritual start

    @ViewBuilder
    private var todayCard: some View {
        if let start = model.start {
            DoosriCard {
                VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                    Text(KendraText.t("ken_today_start", "Today's spiritual start"))
                        .font(.doosriHeadline)
                        // Marigold is the spiritual centre's highlight. It is
                        // never body text on a light background (plan 4.3) —
                        // a heading is the highlight, the sentences below it
                        // are ink.
                        .foregroundStyle(DoosriPalette.marigold(scheme))

                    Text(start.suggestionText)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))

                    Text(start.mantraText)
                        .font(.doosriHeadline)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                    Text(start.mantraMeaning)
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    Text(start.sourceCredit)
                        .font(.doosriCaption)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))

                    Divider().background(DoosriPalette.divider(scheme))

                    Text(start.twoMinutePractice)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                }
            }
        }
    }

    // MARK: - Japa and sankalp

    @ViewBuilder
    private var japaCard: some View {
        if let japa = model.japa {
            DoosriCard {
                VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                    Text(KendraText.t("ken_japa_title", "Japa"))
                        .font(.doosriHeadline)
                        .foregroundStyle(DoosriPalette.ink(scheme))

                    Text(
                        "\(japa.countToday.formatted()) / \(japa.dailyGoal.formatted())"
                            + " · "
                            + KendraText.t("ken_japa_today", "today")
                    )
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                    if let total = japa.sankalpTotal, let count = japa.sankalpCount {
                        Text(
                            KendraText.t("ken_sankalp", "Sankalp")
                                + ": \(count.formatted()) / \(total.formatted())"
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        if let estimate = japa.sankalpFinishEstimate {
                            Text(estimate)
                                .font(.doosriCaption)
                                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        }
                    }

                    DoosriPrimaryButton(
                        KendraText.t("ken_japa_tap", "One round of the mala"),
                        systemImage: "circle.circle"
                    ) {
                        Task { await model.recordTap() }
                    }
                    .accessibilityLabel(
                        KendraText.spoken(
                            "ken_japa_tap_spoken",
                            "Count one round of the mala"
                        )
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func errorLine(_ error: SpiritualStateViewModel.LoadError) -> some View {
        Text(
            String(
                localized: String.LocalizationValue(
                    error == .offline ? "ken_offline" : "ken_failed"
                ),
                defaultValue: error == .offline
                    ? "No connection. Nothing has been lost."
                    : "Could not load. Please try again."
            )
        )
        .font(.doosriCallout)
        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
    }
}