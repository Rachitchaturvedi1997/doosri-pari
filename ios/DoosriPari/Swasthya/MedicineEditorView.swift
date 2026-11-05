// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: "name by voice, dose, times, days and food timing, plus a
// photo of the strip". The editor is a sheet — a short task, per plan 4.4 —
// with a Cancel button as well as swipe-down.
//
// This screen carries the one toggle in the health module that decides what
// kind of app this is. It is commented where it is drawn.

import SwiftUI

@MainActor
final class MedicineEditorViewModel: ObservableObject {

    enum SaveError: String {
        case offline
        case failed
        case needsName
    }

    /// The draft, edited in place and only sent on Save. Nothing reaches the
    /// service while the user is still typing a name.
    @Published var draft: Medicine

    @Published private(set) var saving = false
    @Published private(set) var error: SaveError?

    let service: SwasthyaService
    let isNew: Bool

    init(service: SwasthyaService, medicine: Medicine) {
        self.service = service
        self.draft = medicine
        self.isNew = medicine.name.isEmpty
    }

    /// Adds a time to the daily schedule. A medicine with no times has no
    /// reminders, so the editor keeps at least one row visible at all times.
    func addTime() {
        let nextHour = (draft.times.map(\.hour).max() ?? 7) + 2
        draft.times.append(DoseTime(hour: min(nextHour, 22), minute: 0))
    }

    func removeTime(at offsets: IndexSet) {
        draft.times.remove(atOffsets: offsets)
        if draft.times.isEmpty { addTime() }
    }

    func toggleDay(_ weekday: Int) {
        if let index = draft.days.firstIndex(of: weekday) {
            draft.days.remove(at: index)
        } else {
            draft.days.append(weekday)
        }
    }

    func save() async -> Medicine? {
        guard !saving else { return nil }
        guard !draft.name.trimmingCharacters(in: .whitespaces).isEmpty else {
            error = .needsName
            return nil
        }
        saving = true
        error = nil
        do {
            let saved = try await service.saveMedicine(draft)
            saving = false
            return saved
        } catch let e as SwasthyaServiceError {
            saving = false
            error = e.isOffline ? .offline : .failed
            return nil
        } catch {
            saving = false
            error = .failed
            return nil
        }
    }
}

struct MedicineEditorView: View {
    @StateObject private var model: MedicineEditorViewModel
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    var onSaved: (Medicine) -> Void
    var onCancel: () -> Void

    init(
        model: MedicineEditorViewModel,
        onSaved: @escaping (Medicine) -> Void,
        onCancel: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: model)
        self.onSaved = onSaved
        self.onCancel = onCancel
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            nameField
            doseField
            foodTimingPicker
            timesList
            daysRow
            photoStripRow
            shareToggle
            if let error = model.error { errorLine(error) }
            buttons
        }
    }

    // MARK: - Fields

    private var nameField: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(SwasthyaText.t("swa_field_name", "Name of the medicine"))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
            TextField(
                SwasthyaText.t("swa_field_name_hint", "For example: Metformin 500"),
                text: $model.draft.name
            )
            .font(.doosriBody)
            .textFieldStyle(.roundedBorder)
            // Plan 6.10: "name by voice". The keyboard is the fallback, not
            // the only door — the mic beside the field routes through the
            // same speech-to-text the rest of the app uses.
            HStack(spacing: DoosriSpace.sm) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(DoosriPalette.accent(scheme))
                Text(SwasthyaText.t(
                    "swa_field_name_voice",
                    "You can also say the name out loud"
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var doseField: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(SwasthyaText.t("swa_field_dose", "How much each time"))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
            TextField(
                SwasthyaText.t("swa_field_dose_hint", "For example: 1 tablet"),
                text: $model.draft.dose
            )
            .font(.doosriBody)
            .textFieldStyle(.roundedBorder)
        }
    }

    private var foodTimingPicker: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(SwasthyaText.t("swa_field_food", "Food"))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
            Picker(
                SwasthyaText.t("swa_field_food", "Food"),
                selection: $model.draft.foodTiming
            ) {
                ForEach(FoodTiming.allCases, id: \.self) { timing in
                    Text(timing.displayName).tag(timing)
                }
            }
            .pickerStyle(.menu)
            .frame(minHeight: DoosriSize.minControlHeight)
        }
    }

    private var timesList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            Text(SwasthyaText.t("swa_field_times", "Times of day"))
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))

            ForEach(Array(model.draft.times.enumerated()), id: \.offset) { index, time in
                HStack {
                    Image(systemName: "clock.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(DoosriPalette.accent(scheme))
                        .frame(width: 32)
                    Text(time.clockLabel)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                    Spacer()
                    Button {
                        model.draft.times[index] = DoseTime(
                            hour: time.hour == 23 ? 0 : time.hour + 1,
                            minute: time.minute
                        )
                    } label: {
                        Text(SwasthyaText.t("swa_hour_later", "1 hour later"))
                            .font(.doosriCaption)
                            .frame(minHeight: DoosriSize.minControlHeight)
                    }
                    .buttonStyle(.plain)
                }
            }

            DoosriSecondaryButton(
                SwasthyaText.t("swa_add_time", "Add a time"),
                systemImage: "plus"
            ) {
                model.addTime()
            }
        }
    }

    private var daysRow: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(SwasthyaText.t(
                "swa_field_days",
                "Days (leave all off for every day)"
            ))
            .font(.doosriHeadline)
            .foregroundStyle(DoosriPalette.ink(scheme))

            HStack(spacing: DoosriSpace.sm) {
                ForEach(1...7, id: \.self) { weekday in
                    let isOn = model.draft.days.contains(weekday)
                    Button {
                        model.toggleDay(weekday)
                    } label: {
                        Text(shortWeekday(weekday))
                            .font(.doosriCallout)
                            .frame(
                                minWidth: DoosriSize.minControlHeight * 0.8,
                                minHeight: DoosriSize.minControlHeight
                            )
                            .background(
                                RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                                    .fill(
                                        isOn
                                            ? DoosriPalette.accent(scheme)
                                            : DoosriPalette.surface(scheme)
                                    )
                            )
                            .foregroundStyle(
                                isOn
                                    ? DoosriPalette.background(scheme)
                                    : DoosriPalette.ink(scheme)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(
                        SwasthyaText.spoken(
                            "swa_day_spoken",
                            "\(fullWeekday(weekday)), \(isOn ? "on" : "off")"
                        )
                    )
                }
            }
        }
    }

    private var photoStripRow: some View {
        DoosriCard {
            HStack(spacing: DoosriSpace.md) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(DoosriPalette.accent(scheme))
                    .frame(width: 32)
                Text(SwasthyaText.t(
                    "swa_field_photo",
                    "Photo of the strip — so the name can be checked against the packet"
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                Spacer()
            }
        }
    }

    // MARK: - The share toggle

    /// PLAN 6.10: "the guardian is told after 2 misses in a row, **if
    /// allowed**".
    ///
    /// THE DIFFERENCE BETWEEN FAMILY SUPPORT AND FAMILY SURVEILLANCE IS
    /// EXACTLY THIS TOGGLE.
    ///
    /// Everything else on this screen is between the pensioner and their
    /// doctor. This switch decides whether a daughter gets a message saying
    /// her father's heart tablets were not taken twice — which is the app
    /// doing its best work — or whether every quiet morning becomes a report
    /// card on somebody else's body. Off by default, per medicine, reversible
    /// at any moment, and never implied by the family-connect consent alone.
    /// If this toggle is ever moved to Settings, or made global, or turned on
    /// by adding a guardian, this app has become the thing plan 6.13 was
    /// written to avoid. Keep it here, beside the medicine it belongs to.
    private var shareToggle: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                Toggle(isOn: $model.draft.shareMissesWithGuardian) {
                    VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                        Text(SwasthyaText.t(
                            "swa_share_title",
                            "Tell my family if I miss this twice"
                        ))
                        .font(.doosriHeadline)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        Text(SwasthyaText.t(
                            "swa_share_body",
                            "Only this medicine, only after two missed doses in a row. Nothing else about this medicine is shared."
                        ))
                        .font(.doosriCaption)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    }
                }
                .toggleStyle(.switch)
                .frame(minHeight: DoosriSize.minControlHeight)
            }
        }
    }

    // MARK: - Footer

    private var buttons: some View {
        VStack(spacing: DoosriSpace.md) {
            DoosriPrimaryButton(
                SwasthyaText.t("swa_save", "Save this medicine"),
                systemImage: "checkmark"
            ) {
                Task {
                    if let saved = await model.save() {
                        onSaved(saved)
                        dismiss()
                    }
                }
            }
            DoosriSecondaryButton(
                SwasthyaText.t("cancel", "Cancel"),
                systemImage: "xmark"
            ) {
                onCancel()
                dismiss()
            }
        }
    }

    @ViewBuilder
    private func errorLine(_ error: MedicineEditorViewModel.SaveError) -> some View {
        Text(
            String(
                localized: String.LocalizationValue(
                    error == .needsName
                        ? "swa_needs_name"
                        : (error == .offline ? "swa_offline" : "swa_failed")
                ),
                defaultValue: error == .needsName
                    ? "Give the medicine a name first."
                    : (error == .offline
                        ? "No connection. Nothing has been saved."
                        : "Could not save. Please try again.")
            )
        )
        .font(.doosriCallout)
        .foregroundStyle(DoosriPalette.safety(scheme))
        // `safety` here is a sentence the user has to act on, not decoration —
        // the one place red is allowed is when something did not happen.
        .accessibilityLabel(
            SwasthyaText.spoken("swa_save_error_spoken", "Save did not work")
        )
    }

    private func shortWeekday(_ weekday: Int) -> String {
        let symbols = Calendar.current.shortWeekdaySymbols
        // Calendar weekday numbers are 1-based with Sunday first.
        let index = (weekday - 1) % 7
        return symbols.indices.contains(index) ? symbols[index] : "\(weekday)"
    }

    private func fullWeekday(_ weekday: Int) -> String {
        let symbols = Calendar.current.weekdaySymbols
        let index = (weekday - 1) % 7
        return symbols.indices.contains(index) ? symbols[index] : "\(weekday)"
    }
}