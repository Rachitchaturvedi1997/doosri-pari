// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1: one programme with its poses.
//
// Shows, in order: the poses that WILL be offered (with any change note),
// the poses that were withheld and why — same rule as the list, never
// silently — the doctor's note verbatim (plan 7.1: "it shows on the programme
// screen"), and one primary action to begin.
//
// EXERCISE ONLY. The safety machinery on this screen belongs to exercise;
// nothing here reaches Adhyatm Kendra.

import SwiftUI

@MainActor
final class ProgrammeDetailViewModel: ObservableObject {

    let card: ProgrammeCard
    let service: YogService

    @Published private(set) var doctorNote: String?

    init(service: YogService, card: ProgrammeCard) {
        self.service = service
        self.card = card
        self.doctorNote = nil
    }

    /// The doctor's note is shown as written. Not summarised, not checked
    /// against the pose list, not used to change anything: plan 7.1 asks for
    /// a field the user controls, and the moment the app "applies" it, the
    /// app is practising medicine.
    func loadDoctorNote() async {
        guard doctorNote == nil else { return }
        if let profile = try? await service.safetyProfile() {
            doctorNote = profile.doctorNote
        }
    }

    /// The steps the player will run, in the plan's fixed order of phases
    /// (settle → warm-up → main → cool-down → breath → rest). Built here so
    /// the player has nothing to decide.
    var steps: [SessionStep] {
        card.offered.enumerated().map { index, offered in
            SessionStep(
                id: "\(card.programme.id)-\(index)",
                phase: phase(for: index, of: card.offered.count),
                pose: offered.pose
            )
        }
    }

    private func phase(for index: Int, of total: Int) -> SessionPhase {
        guard total > 0 else { return .main }
        let share = Double(index) / Double(total)
        switch share {
        case ..<0.1: return .settle
        case ..<0.25: return .warmUp
        case ..<0.6: return .main
        case ..<0.75: return .coolDown
        case ..<0.9: return .breath
        default: return .rest
        }
    }
}

struct ProgrammeDetailView: View {
    @StateObject private var model: ProgrammeDetailViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: ProgrammeDetailViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            model.card.programme.title,
            subtitle: model.card.programme.summary
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                if let note = model.doctorNote {
                    doctorNoteCard(note)
                }

                DoosriSectionHeader(
                    KendraText.t("ken_poses_offered", "Your session will include"),
                    subtitle: KendraText.t(
                        "ken_poses_offered_sub",
                        "Slow, with long holds. Only as far as feels comfortable."
                    )
                )
                ForEach(model.card.offered) { offered in
                    poseRow(offered)
                    Divider().background(DoosriPalette.divider(scheme))
                }

                if !model.card.withheld.isEmpty {
                    withheldSection
                }

                DoosriPrimaryButton(
                    KendraText.t("ken_start_session", "Start this session"),
                    systemImage: "play.fill"
                ) {
                    startingSession = true
                }
            }
        }
        .navigationDestination(isPresented: $startingSession) {
            SessionPlayerView(
                model: SessionPlayerViewModel(
                    programme: model.card.programme,
                    steps: model.steps
                )
            )
        }
        .task {
            await model.loadDoctorNote()
        }
    }

    @State private var startingSession = false

    // MARK: - Pieces

    private func doctorNoteCard(_ note: String) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(KendraText.t("ken_doctor_note", "Your doctor's note"))
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(note)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(KendraText.t(
                    "ken_doctor_note_yours",
                    "You wrote this. It is shown as you wrote it."
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    @ViewBuilder
    private func poseRow(_ offered: OfferedPose) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(offered.pose.name)
                .font(.doosriBody)
                .foregroundStyle(DoosriPalette.ink(scheme))
            Text(offered.pose.group.displayName)
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            if let change = offered.changeNote {
                // The changed variant, in the plan's instruction register.
                // Accent, not red: nothing is wrong, something is adapted.
                HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.sm) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 18))
                        .foregroundStyle(DoosriPalette.accent(scheme))
                    Text(change)
                        .font(.doosriCaption)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                }
            }
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .accessibilityElement(children: .combine)
    }

    /// The withheld list, repeated here because the detail screen is where a
    /// person decides whether to press Start. Same rule as the list screen:
    /// never a silent omission.
    private var withheldSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriSectionHeader(KendraText.t(
                "ken_withheld_title",
                "Not offered to you, and why"
            ))
            ForEach(model.card.withheld) { withheld in
                HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.sm) {
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
            }
        }
    }
}