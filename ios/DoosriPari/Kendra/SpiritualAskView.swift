// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.2: "Ask the Scriptures" — questions answered from the
// verses, with citations (Moholla's feature).
//
// ============================ THE CENTRAL RULE ============================
//
//   EXERCISE IS SAFETY-CRITICAL; PRACTICE IS NOT.
//
// No safety profile, no doctor's note, no safety gate, and — the version of
// the rule that matters most on THIS screen — no medical hedging dressed up
// as scripture. If somebody asks about an illness here, the answer is the
// verse and its citation, and the disclaimer at the foot of the answer does
// the rest. The exercise code's habit of filtering and withholding does not
// apply: plan 7.2's rule is "suggestions, not claims", which is a register,
// not a gate.
//
// ==========================================================================

import SwiftUI

@MainActor
final class SpiritualAskViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published var question = ""
    @Published private(set) var current: ScriptureAnswer?
    @Published private(set) var recent: [ScriptureAnswer] = []
    @Published private(set) var asking = false
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    let service: AdhyatmService
    private let paths: [SpiritualPath]

    init(service: AdhyatmService, paths: [SpiritualPath]) {
        self.service = service
        self.paths = paths
    }

    func load() async {
        loading = true
        error = nil
        do {
            recent = try await service.recentScriptureAnswers()
            loading = false
        } catch let e as AdhyatmServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Re-opens an earlier answer. The list is history, not a second feed.
    func select(_ answer: ScriptureAnswer) {
        current = answer
    }

    func ask() async {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !asking else { return }
        asking = true
        error = nil
        do {
            let answer = try await service.askScriptures(trimmed, paths: paths)
            current = answer
            recent.insert(answer, at: 0)
            question = ""
            asking = false
        } catch let e as AdhyatmServiceError {
            asking = false
            error = e.isOffline ? .offline : .failed
        } catch {
            asking = false
            error = .failed
        }
    }
}

struct SpiritualAskView: View {
    @StateObject private var model: SpiritualAskViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: SpiritualAskViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_ask_title", "Ask the Scriptures"),
            subtitle: KendraText.t(
                "ken_ask_subtitle",
                "Answers from the verses, with their references"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                questionBox

                if model.asking {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                }

                if let answer = model.current {
                    answerCard(answer)
                }

                if !model.recent.isEmpty {
                    recentList
                }

                if let error = model.error { errorLine(error) }

                // THE ONE PLACE THIS LINE LIVES (see `AdhyatmDisclaimer`).
                // Plain text at the foot of the answer, full size, no
                // dismiss, no tap-to-hide. Plan 7.2 wants it non-dismissive:
                // a person who needed to read it once should not be able to
                // make it vanish.
                Text(AdhyatmDisclaimer.generalInformation)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .task {
            if model.recent.isEmpty { await model.load() }
        }
    }

    // MARK: - Pieces

    private var questionBox: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(KendraText.t("ken_ask_your_question", "Your question"))
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                TextField(
                    KendraText.t(
                        "ken_ask_hint",
                        "Type it, or say it out loud"
                    ),
                    text: $model.question,
                    axis: .vertical
                )
                .font(.doosriBody)
                .textFieldStyle(.roundedBorder)
                DoosriPrimaryButton(
                    KendraText.t("ken_ask_go", "Ask"),
                    systemImage: "text.magnifyingglass"
                ) {
                    Task { await model.ask() }
                }
            }
        }
    }

    private func answerCard(_ answer: ScriptureAnswer) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                Text(answer.question)
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                Text(answer.answerText)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                DoosriSectionHeader(KendraText.t("ken_ask_sources", "From"))
                ForEach(answer.citations, id: \.self) { citation in
                    HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.sm) {
                        Image(systemName: "book.closed")
                            .font(.system(size: 18))
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        Text(citation)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    }
                }
            }
        }
    }

    private var recentList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriSectionHeader(KendraText.t("ken_ask_recent", "Earlier questions"))
            ForEach(model.recent) { answer in
                Text(answer.question)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .frame(minHeight: DoosriSize.rowHeight)
                    .onTapGesture {
                        model.select(answer)
                    }
                Divider().background(DoosriPalette.divider(scheme))
            }
        }
    }

    @ViewBuilder
    private func errorLine(_ error: SpiritualAskViewModel.LoadError) -> some View {
        Text(
            String(
                localized: String.LocalizationValue(
                    error == .offline ? "ken_offline" : "ken_failed"
                ),
                defaultValue: error == .offline
                    ? "No connection. Nothing has been lost."
                    : "Could not answer just now. Please try again."
            )
        )
        .font(.doosriCallout)
        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
    }
}