// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1, "Safety first": the health check before the first
// session, the injuries and joints, balance, blood pressure, and the
// doctor's note that shows on the programme screen.
//
// EXERCISE ONLY. This screen is the entry to the safety-critical half of the
// CENTRAL RULE (see `YogModels.swift`). Its answers filter EXERCISE. They
// filter nothing in Adhyatm Kendra — a bad knee changes your yoga, not your
// bhajan.
//
// "Check with your doctor before starting a new exercise programme" (plan
// 7.1) is on this screen and stays on it.

import SwiftUI

@MainActor
final class SafetyProfileViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published var draft: SafetyProfile
    @Published private(set) var loading = true
    @Published private(set) var saving = false
    @Published private(set) var error: LoadError?

    @Published var injuryDraft = ""
    @Published var jointDraft = ""

    let service: YogService

    init(service: YogService) {
        self.service = service
        self.draft = SafetyProfile()
    }

    func load() async {
        loading = true
        error = nil
        do {
            draft = try await service.safetyProfile()
            loading = false
        } catch let e as YogServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    func toggleFlag(_ flag: SafetyFlag) {
        if draft.flags.contains(flag) {
            draft.flags.remove(flag)
        } else {
            draft.flags.insert(flag)
        }
    }

    func addInjury() {
        let text = injuryDraft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft.injuries.append(text)
        injuryDraft = ""
    }

    func addJoint() {
        let text = jointDraft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft.joints.append(text)
        jointDraft = ""
    }

    func removeInjury(at offsets: IndexSet) {
        draft.injuries.remove(atOffsets: offsets)
    }

    func removeJoint(at offsets: IndexSet) {
        draft.joints.remove(atOffsets: offsets)
    }

    /// Saves and stamps `answeredAt`. The stamp is what tells the programme
    /// screen the check exists at all (plan 7.1: before the FIRST session).
    func save() async {
        guard !saving else { return }
        saving = true
        error = nil
        var toSave = draft
        toSave.answeredAt = Date().timeIntervalSince1970
        do {
            draft = try await service.saveSafetyProfile(toSave)
            saving = false
        } catch let e as YogServiceError {
            saving = false
            error = e.isOffline ? .offline : .failed
        } catch {
            saving = false
            error = .failed
        }
    }
}

struct SafetyProfileView: View {
    @StateObject private var model: SafetyProfileViewModel
    @Environment(\.colorScheme) private var scheme

    var onFinished: () -> Void = {}

    init(model: SafetyProfileViewModel, onFinished: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: model)
        self.onFinished = onFinished
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_safety_title", "Before your first session"),
            subtitle: KendraText.t(
                "ken_safety_subtitle",
                "These answers change the exercises offered to you — nothing else"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                doctorLine

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else {
                    flagList
                    injuriesSection
                    jointsSection
                    balanceSection
                    bloodPressureSection
                    doctorNoteSection
                    if let error = model.error { errorLine(error) }
                    saveButton
                }
            }
        }
        .task {
            if !model.draft.isAnswered { await model.load() }
        }
    }

    // MARK: - Pieces

    /// Plan 7.1's required line, on the first screen of the check. Red only
    /// because it is a stop condition ("check with your doctor"), which is a
    /// safety statement, not a decorative header.
    private var doctorLine: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(KendraText.t(
                    "ken_safety_doctor_first",
                    "Check with your doctor before starting a new exercise programme."
                ))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
                Text(KendraText.t(
                    "ken_safety_doctor_first_body",
                    "And during any session: stop if you feel pain or dizziness. The voice will say this at the start of every session too."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var flagList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            DoosriSectionHeader(
                KendraText.t("ken_safety_questions", "Please answer these"),
                subtitle: KendraText.t(
                    "ken_safety_questions_sub",
                    "Yes or no. You can change your answers any time."
                )
            )
            ForEach(SafetyFlag.allCases, id: \.self) { flag in
                Toggle(isOn: Binding(
                    get: { model.draft.flags.contains(flag) },
                    set: { _ in model.toggleFlag(flag) }
                )) {
                    Text(flag.question)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                }
                .toggleStyle(.switch)
                .frame(minHeight: DoosriSize.minControlHeight)
                // The rule that follows each answer is shown only after the
                // answer, not as a warning before it: the plan is asking a
                // question, not issuing a warning label.
                if model.draft.flags.contains(flag) {
                    Text(flag.removalRule)
                        .font(.doosriCaption)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        .padding(.leading, DoosriSpace.lg)
                }
            }
        }
    }

    private var injuriesSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriSectionHeader(KendraText.t("ken_safety_injuries", "Past injuries"))
            ForEach(Array(model.draft.injuries.enumerated()), id: \.offset) { _, injury in
                Text(injury)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
            }
            HStack(spacing: DoosriSpace.sm) {
                TextField(
                    KendraText.t("ken_safety_injury_hint", "For example: left shoulder, 2019"),
                    text: $model.injuryDraft
                )
                .font(.doosriBody)
                .textFieldStyle(.roundedBorder)
                DoosriSecondaryButton(
                    KendraText.t("ken_safety_add", "Add"),
                    systemImage: "plus"
                ) {
                    model.addInjury()
                }
            }
        }
    }

    private var jointsSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriSectionHeader(KendraText.t("ken_safety_joints", "Joints that trouble you"))
            ForEach(Array(model.draft.joints.enumerated()), id: \.offset) { _, joint in
                Text(joint)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
            }
            HStack(spacing: DoosriSpace.sm) {
                TextField(
                    KendraText.t("ken_safety_joint_hint", "For example: both knees"),
                    text: $model.jointDraft
                )
                .font(.doosriBody)
                .textFieldStyle(.roundedBorder)
                DoosriSecondaryButton(
                    KendraText.t("ken_safety_add", "Add"),
                    systemImage: "plus"
                ) {
                    model.addJoint()
                }
            }
        }
    }

    private var balanceSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            DoosriSectionHeader(
                KendraText.t("ken_safety_balance", "Balance"),
                subtitle: KendraText.t(
                    "ken_safety_balance_sub",
                    "Standing poses are offered with a chair or wall either way"
                )
            )
            Picker(
                KendraText.t("ken_safety_balance", "Balance"),
                selection: $model.draft.balance
            ) {
                ForEach(BalanceStatus.allCases, id: \.self) { status in
                    Text(status.displayName).tag(status)
                }
            }
            .pickerStyle(.segmented)
            .frame(minHeight: DoosriSize.minControlHeight)
        }
    }

    private var bloodPressureSection: some View {
        Toggle(isOn: $model.draft.bloodPressureMentioned) {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(KendraText.t(
                    "ken_safety_bp",
                    "My doctor has mentioned my blood pressure"
                ))
                .font(.doosriBody)
                .foregroundStyle(DoosriPalette.ink(scheme))
                // And the boundary again: this app does not read, store or
                // interpret blood pressure here. It records that the word was
                // mentioned, for the pose filter's one question. The numbers
                // live in Swasthya and are read by nobody but the doctor.
                Text(KendraText.t(
                    "ken_safety_bp_note",
                    "We do not ask for the numbers and we never interpret them here."
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
        .toggleStyle(.switch)
        .frame(minHeight: DoosriSize.minControlHeight)
    }

    private var doctorNoteSection: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            DoosriSectionHeader(
                KendraText.t("ken_safety_note", "Doctor's note"),
                subtitle: KendraText.t(
                    "ken_safety_note_sub",
                    "Anything your doctor told you about exercise. Shown as you write it."
                )
            )
            TextField(
                KendraText.t("ken_safety_note_hint", "Type it or say it out loud"),
                text: Binding(
                    get: { model.draft.doctorNote ?? "" },
                    set: { model.draft.doctorNote = $0.isEmpty ? nil : $0 }
                )
            )
            .font(.doosriBody)
            .textFieldStyle(.roundedBorder)
        }
    }

    private var saveButton: some View {
        DoosriPrimaryButton(
            KendraText.t("ken_safety_save", "Save my answers"),
            systemImage: "checkmark"
        ) {
            Task {
                await model.save()
                onFinished()
            }
        }
    }

    @ViewBuilder
    private func errorLine(_ error: SafetyProfileViewModel.LoadError) -> some View {
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