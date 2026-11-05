// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1: the programme list — chair yoga through Surya Namaskar on
// a chair — each one already filtered by the safety profile.
//
// ============================ WHY THIS SCREEN SHOWS ITS WORK ============================
//
// THE LIST MUST SHOW WHAT WAS WITHHELD: which pose, and why it was not
// offered. A silent omission reads as "there is nothing here", and that
// reading is the exact failure this design exists to prevent. A pensioner
// with a bad knee handed a squat is the headline disaster; the quiet version
// is a pensioner with a bad knee shown a shortened programme who concludes
// yoga is not for them, or that the app has nothing to teach. Either way the
// safety filter has done harm by being invisible.
//
// So every programme card carries its own "Not offered to you" list, in the
// pensioner's words ("No deep knee bends, kneeling or squats"), beside the
// poses that ARE offered. The withheld list is drawn even when it is long.
// If a future change makes the card look "cleaner" without it, the change is
// wrong — see `PoseFilter`, which returns both lists for exactly this reason.
//
// =======================================================================================

import SwiftUI

/// One programme as the list draws it: what is offered and what is withheld,
/// precomputed so the view body holds no logic.
struct ProgrammeCard: Identifiable {
    let programme: Programme
    let offered: [OfferedPose]
    let withheld: [WithheldPose]

    var id: String { programme.id }
}

@MainActor
final class ProgrammeListViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var cards: [ProgrammeCard] = []
    @Published private(set) var profile: SafetyProfile?
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    let service: YogService

    init(service: YogService) {
        self.service = service
    }

    /// True when the safety check has never been answered. The list then
    /// shows the check instead of programmes — plan 7.1 asks before the
    /// FIRST session, and a programme screen is where a first session starts.
    var needsSafetyCheck: Bool { profile?.isAnswered != true }

    func load() async {
        loading = true
        error = nil
        do {
            let saved = try await service.safetyProfile()
            profile = saved
            let programmes = try await service.programmes(profile: saved)

            // Filtered here as well as on the server. See `YogService` — the
            // check closest to the screen is the one that cannot be skipped.
            cards = programmes.map { programme in
                let partition = PoseFilter.partition(programme.poses, profile: saved)
                return ProgrammeCard(
                    programme: programme,
                    offered: partition.offered,
                    withheld: partition.withheld
                )
            }
            loading = false
        } catch let e as YogServiceError {
            loading = false
            if e == .safetyCheckRequired {
                profile = SafetyProfile()
            }
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }
}

struct ProgrammeListView: View {
    @StateObject private var model: ProgrammeListViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: ProgrammeListViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_programmes_title", "Programmes"),
            subtitle: KendraText.t(
                "ken_programmes_subtitle",
                "Slow, drawn, and filtered for your body"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if model.needsSafetyCheck {
                    DoosriEmptyState(
                        systemImage: "checklist",
                        title: KendraText.t(
                            "ken_programmes_need_check_title",
                            "A few questions first"
                        ),
                        message: KendraText.t(
                            "ken_programmes_need_check_body",
                            "Before your first session we ask about your knees, your back and your blood pressure, so the exercises suit you. It takes two minutes."
                        ),
                        actionTitle: KendraText.t("ken_programmes_do_check", "Answer the questions"),
                        action: { showingSafetyCheck = true }
                    )
                } else if let error = model.error {
                    errorBanner(error)
                } else {
                    ForEach(model.cards) { card in
                        programmeCard(card)
                    }
                }
            }
        }
        .sheet(isPresented: $showingSafetyCheck) {
            DoosriSheet(title: KendraText.t("ken_safety_title", "Before your first session")) {
                SafetyProfileView(
                    model: SafetyProfileViewModel(service: model.service),
                    onFinished: {
                        showingSafetyCheck = false
                        Task { await model.load() }
                    }
                )
            }
        }
        .task {
            if model.cards.isEmpty { await model.load() }
        }
    }

    @State private var showingSafetyCheck = false

    // MARK: - One programme

    private func programmeCard(_ card: ProgrammeCard) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                NavigationLink {
                    ProgrammeDetailView(
                        model: ProgrammeDetailViewModel(
                            service: model.service,
                            card: card
                        )
                    )
                } label: {
                    VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                        Text(card.programme.title)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                        Text(
                            card.programme.minutes.formatted() + " "
                                + KendraText.t("ken_minutes", "min")
                                + " · " + card.programme.level.displayName
                        )
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        Text(card.programme.summary)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .lineLimit(3)
                    }
                }

                if !card.withheld.isEmpty {
                    withheldList(card)
                }
            }
        }
    }

    /// SEE THE FILE COMMENT. This block is not decoration and not an apology
    /// — it is half the screen's job. Each withheld pose names the pose and
    /// the rule that withheld it, in full sentences.
    private func withheldList(_ card: ProgrammeCard) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(KendraText.t(
                "ken_withheld_title",
                "Not offered to you, and why"
            ))
            .font(.doosriCallout)
            .foregroundStyle(DoosriPalette.ink(scheme))

            ForEach(card.withheld) { withheld in
                HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.sm) {
                    // `safety` red is doing its one licensed job here: this
                    // is a statement about what was kept from somebody for
                    // their safety (plan 4.3). Nothing else on this card is
                    // coloured.
                    Image(systemName: "minus.circle")
                        .font(.system(size: 20))
                        .foregroundStyle(DoosriPalette.safety(scheme))
                    VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                        Text(withheld.pose.name)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                        Text(withheld.reason)
                            .font(.doosriCaption)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(
                    KendraText.spoken(
                        "ken_withheld_spoken",
                        "\(withheld.pose.name) is not offered: \(withheld.reason)"
                    )
                )
            }

            Text(KendraText.t(
                "ken_withheld_doctor",
                "If you think one of these is wrong for you now, ask your doctor and change your answers in the questions."
            ))
            .font(.doosriCaption)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
    }

    @ViewBuilder
    private func errorBanner(_ error: ProgrammeListViewModel.LoadError) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 24))
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
            Spacer()
            Button {
                Task { await model.load() }
            } label: {
                Text(KendraText.t("retry", "Try again"))
                    .font(.doosriCallout)
            }
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
        .foregroundStyle(DoosriPalette.ink(scheme))
    }
}