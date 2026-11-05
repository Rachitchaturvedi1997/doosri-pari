// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1, "Tracking progress": the monthly mobility check, from the
// Senior Fitness Test — the 30-second chair stand and the single-leg stand
// WITH support.
//
// Two rules are held here:
//
//   - The chart is months of YOUR numbers, never compared with anyone
//     (plan 7.1: "never compared with others"). No averages, no percentiles,
//     no "you are below…".
//   - The single-leg stand is explicitly with support — a chair or wall
//     within reach — because a self-test that ends in a fall is this app
//     injuring somebody. The screen says so before the timer starts.
//
// EXERCISE ONLY. This measures exercise capacity; it says nothing about
// anyone's spiritual practice and never will.

import SwiftUI

@MainActor
final class MobilityCheckViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var results: [MobilityResult] = []
    @Published private(set) var loading = true
    @Published private(set) var saving = false
    @Published private(set) var error: LoadError?

    /// The check in progress.
    @Published var chairStandsText = ""
    @Published var singleLegText = ""

    let service: YogService

    init(service: YogService) {
        self.service = service
    }

    func load() async {
        loading = true
        error = nil
        do {
            results = try await service.mobilityResults()
                .sorted { $0.takenAt > $1.takenAt }
            loading = false
        } catch let e as YogServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    func save() async {
        guard !saving else { return }
        guard let stands = Int(chairStandsText.trimmingCharacters(in: .whitespaces)),
              let seconds = Double(singleLegText.trimmingCharacters(in: .whitespaces))
        else {
            error = .failed
            return
        }
        saving = true
        error = nil
        let result = MobilityResult(
            id: UUID().uuidString,
            takenAt: Date().timeIntervalSince1970,
            chairStands: stands,
            singleLegStandSeconds: seconds
        )
        do {
            let saved = try await service.saveMobilityResult(result)
            results.insert(saved, at: 0)
            chairStandsText = ""
            singleLegText = ""
            saving = false
        } catch let e as YogServiceError {
            saving = false
            error = e.isOffline ? .offline : .failed
        } catch {
            saving = false
            error = .failed
        }
    }

    /// The monthly chart, as simple bars: one bar per month, scaled to this
    /// person's own highest value and to nothing else.
    var monthlyBars: [MobilityBar] {
        // Newest last, so the bar chart reads left to right as time passes.
        results.reversed().map {
            MobilityBar(date: Date(timeIntervalSince1970: $0.takenAt), result: $0)
        }
    }

    var maxChairStands: Int {
        max(results.map(\.chairStands).max() ?? 1, 1)
    }
}

struct MobilityBar: Identifiable {
    let date: Date
    let result: MobilityResult

    var id: String { result.id }
}

struct MobilityCheckView: View {
    @StateObject private var model: MobilityCheckViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: MobilityCheckViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_mobility_title", "Monthly check"),
            subtitle: KendraText.t(
                "ken_mobility_subtitle",
                "Your numbers, over your months"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                safetyLine

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else {
                    entryCard
                    if !model.monthlyBars.isEmpty {
                        chartCard
                    } else {
                        DoosriEmptyState(
                            systemImage: "chart.bar",
                            title: KendraText.t(
                                "ken_mobility_empty_title",
                                "No checks yet"
                            ),
                            message: KendraText.t(
                                "ken_mobility_empty_body",
                                "Do the two little tests below once a month and you will see your own line here."
                            )
                        )
                    }
                    if let error = model.error { errorLine(error) }
                }
            }
        }
        .task {
            if model.results.isEmpty { await model.load() }
        }
    }

    // MARK: - Pieces

    private var safetyLine: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(KendraText.t(
                    "ken_mobility_support",
                    "Hold a chair or a wall the whole time. Stop if you feel pain or dizziness."
                ))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
                Text(KendraText.t(
                    "ken_mobility_support_body",
                    "These are the Senior Fitness Test's own tests, and the test is done with support. This is not a balance exam."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var entryCard: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(KendraText.t("ken_mobility_new", "This month's check"))
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                Text(KendraText.t(
                    "ken_mobility_test_one",
                    "1. Stand up and sit down on a chair as many times as you can in 30 seconds."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                TextField(
                    KendraText.t("ken_mobility_stands", "How many times?"),
                    text: $model.chairStandsText
                )
                .font(.doosriBody)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)

                Text(KendraText.t(
                    "ken_mobility_test_two",
                    "2. Stand on one leg, holding the chair, and count the seconds until you put your foot down."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                TextField(
                    KendraText.t("ken_mobility_seconds", "How many seconds?"),
                    text: $model.singleLegText
                )
                .font(.doosriBody)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)

                DoosriPrimaryButton(
                    KendraText.t("ken_mobility_save", "Save this month's check"),
                    systemImage: "checkmark"
                ) {
                    Task { await model.save() }
                }
            }
        }
    }

    private var chartCard: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                DoosriSectionHeader(
                    KendraText.t("ken_mobility_chart", "Chair stands, month by month"),
                    subtitle: KendraText.t(
                        "ken_mobility_chart_sub",
                        "Only your own months. Nobody is compared with anyone."
                    )
                )
                HStack(alignment: .bottom, spacing: DoosriSpace.md) {
                    ForEach(model.monthlyBars) { bar in
                        VStack(spacing: DoosriSpace.xs) {
                            Text("\(bar.result.chairStands)")
                                .font(.doosriCaption)
                                .foregroundStyle(DoosriPalette.ink(scheme))
                            RoundedRectangle(cornerRadius: DoosriSpace.xs)
                                .fill(DoosriPalette.accent(scheme))
                                .frame(
                                    width: 28,
                                    height: barHeight(bar)
                                )
                            Text(monthLabel(bar.date))
                                .font(.doosriCaption)
                                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)

                ForEach(model.monthlyBars) { bar in
                    HStack {
                        Text(monthLabel(bar.date))
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        Spacer()
                        Text(
                            KendraText.t("ken_single_leg", "One-leg stand")
                                + " "
                                + bar.result.singleLegStandSeconds.formatted()
                                + " "
                                + KendraText.t("ken_seconds", "seconds")
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                    }
                    .frame(minHeight: DoosriSize.rowHeight)
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    private func barHeight(_ bar: MobilityBar) -> CGFloat {
        // Scaled to this person's own best month and nothing else.
        let ratio = Double(bar.result.chairStands) / Double(model.maxChairStands)
        return max(16, CGFloat(ratio) * 120)
    }

    private func monthLabel(_ date: Date) -> String {
        date.formatted(.dateTime.month(.abbreviated))
    }

    @ViewBuilder
    private func errorLine(_ error: MobilityCheckViewModel.LoadError) -> some View {
        Text(
            String(
                localized: String.LocalizationValue(
                    error == .offline ? "ken_offline" : "ken_failed"
                ),
                defaultValue: error == .offline
                    ? "No connection. Nothing has been lost."
                    : "Could not save. Please try again."
            )
        )
        .font(.doosriCallout)
        .foregroundStyle(DoosriPalette.safety(scheme))
    }
}