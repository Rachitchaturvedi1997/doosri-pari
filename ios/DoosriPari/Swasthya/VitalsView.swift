// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: the vitals log — BP, sugar, weight and pulse, with 30-day
// history, "usual range" references and "talk to your doctor", never a
// diagnosis. Plan 14 repeats it: general wellness information only.
//
// The module records and shows. Where the temptation to interpret lives is
// commented in `SwasthyaModels.swift` (`isOutsidePlausibleRange`) and again
// below, where the readings are drawn.

import SwiftUI

@MainActor
final class VitalsViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var readings: [VitalReading] = []
    @Published private(set) var loading = true
    @Published private(set) var saving = false
    @Published private(set) var exporting = false
    @Published private(set) var error: LoadError?

    /// The entry form's state. Kept here, not in the view, so validation and
    /// saving are one code path.
    @Published var entryKind: VitalKind = .bloodPressure
    @Published var entrySystolic = ""
    @Published var entryDiastolic = ""
    @Published var entryValue = ""
    @Published var entryNote = ""

    let service: SwasthyaService

    init(service: SwasthyaService) {
        self.service = service
    }

    /// The last 30 days, plan 6.10's chart window.
    func load() async {
        loading = true
        error = nil
        do {
            let to = Date()
            let from = to.addingTimeInterval(-30 * 24 * 60 * 60)
            readings = try await service.vitals(from: from, to: to)
                .sorted { $0.takenAt > $1.takenAt }
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Saves what is typed. A number that is not a number is refused here
    /// rather than sent and lost; a number that is implausible is still saved
    /// — see below.
    func saveEntry() async {
        guard !saving else { return }

        let reading: VitalReading
        switch entryKind {
        case .bloodPressure:
            guard let s = Int(entrySystolic.trimmingCharacters(in: .whitespaces)),
                  let d = Int(entryDiastolic.trimmingCharacters(in: .whitespaces))
            else {
                error = .failed
                return
            }
            reading = VitalReading(
                id: UUID().uuidString,
                kind: .bloodPressure,
                systolic: s,
                diastolic: d,
                value: nil,
                takenAt: Date().timeIntervalSince1970,
                note: entryNote.isEmpty ? nil : entryNote
            )
        case .sugar, .weight, .pulse:
            guard let v = Double(entryValue.trimmingCharacters(in: .whitespaces))
            else {
                error = .failed
                return
            }
            reading = VitalReading(
                id: UUID().uuidString,
                kind: entryKind,
                systolic: nil,
                diastolic: nil,
                value: v,
                takenAt: Date().timeIntervalSince1970,
                note: entryNote.isEmpty ? nil : entryNote
            )
        }

        saving = true
        error = nil
        do {
            let saved = try await service.saveVital(reading)
            readings.insert(saved, at: 0)
            entrySystolic = ""
            entryDiastolic = ""
            entryValue = ""
            entryNote = ""
            saving = false
        } catch let e as SwasthyaServiceError {
            saving = false
            error = e.isOffline ? .offline : .failed
        } catch {
            saving = false
            error = .failed
        }
    }

    /// Plan 6.10: PDF export for a doctor's visit. Generated on the phone
    /// from these readings; see `SwasthyaService.vitalsPDF`.
    func exportPDF() async -> Data? {
        guard !exporting else { return nil }
        exporting = true
        defer { exporting = false }
        do {
            let to = Date()
            let from = to.addingTimeInterval(-30 * 24 * 60 * 60)
            return try await service.vitalsPDF(from: from, to: to)
        } catch {
            error = .failed
            return nil
        }
    }
}

struct VitalsView: View {
    @StateObject private var model: VitalsViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: VitalsViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            SwasthyaText.t("swa_vitals_title", "My readings"),
            subtitle: SwasthyaText.t(
                "swa_vitals_subtitle",
                "Recorded as you write them. Nobody here reads them."
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                entryCard

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.readings.isEmpty {
                    DoosriEmptyState(
                        systemImage: "waveform.path.ecg",
                        title: SwasthyaText.t(
                            "swa_vitals_empty_title",
                            "No readings yet"
                        ),
                        message: SwasthyaText.t(
                            "swa_vitals_empty_body",
                            "Your last 30 days will be listed here, and you can print them for your doctor."
                        )
                    )
                } else {
                    historyList
                }
            }
        }
        .task {
            if model.readings.isEmpty { await model.load() }
        }
    }

    // MARK: - Entry

    private var entryCard: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                Text(SwasthyaText.t("swa_vitals_new", "Add a reading"))
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                Picker(
                    SwasthyaText.t("swa_vitals_kind", "What are you recording"),
                    selection: $model.entryKind
                ) {
                    ForEach(VitalKind.allCases, id: \.self) { kind in
                        Text(kind.displayName).tag(kind)
                    }
                }
                .pickerStyle(.menu)
                .frame(minHeight: DoosriSize.minControlHeight)

                if model.entryKind == .bloodPressure {
                    HStack(spacing: DoosriSpace.md) {
                        numberField(
                            SwasthyaText.t("swa_bp_top", "Top (systolic)"),
                            text: $model.entrySystolic
                        )
                        numberField(
                            SwasthyaText.t("swa_bp_bottom", "Bottom (diastolic)"),
                            text: $model.entryDiastolic
                        )
                    }
                } else {
                    numberField(
                        model.entryKind.displayName + " (" + model.entryKind.unit + ")",
                        text: $model.entryValue
                    )
                }

                TextField(
                    SwasthyaText.t("swa_vitals_note", "A note, if you want (optional)"),
                    text: $model.entryNote
                )
                .font(.doosriBody)
                .textFieldStyle(.roundedBorder)

                DoosriPrimaryButton(
                    SwasthyaText.t("swa_vitals_save", "Save this reading"),
                    systemImage: "square.and.pencil"
                ) {
                    Task { await model.saveEntry() }
                }

                Text(SwasthyaText.t(
                    "swa_vitals_no_advice",
                    "This app does not read your numbers and does not say whether they are good or bad. Take the readings to your doctor — they know you."
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private func numberField(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(label)
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            TextField("0", text: text)
                .font(.doosriBody)
                .keyboardType(.numbersAndPunctuation)
                .textFieldStyle(.roundedBorder)
        }
    }

    // MARK: - History

    private var historyList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            DoosriSectionHeader(
                SwasthyaText.t("swa_vitals_30_days", "Last 30 days"),
                subtitle: SwasthyaText.t(
                    "swa_vitals_30_days_sub",
                    "Exactly as you entered them"
                )
            )

            ForEach(model.readings) { reading in
                readingRow(reading)
                Divider().background(DoosriPalette.divider(scheme))
            }

            DoosriSecondaryButton(
                SwasthyaText.t("swa_vitals_export", "Print for my doctor (PDF)"),
                systemImage: "printer"
            ) {
                Task { _ = await model.exportPDF() }
            }
        }
    }

    @ViewBuilder
    private func readingRow(_ reading: VitalReading) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.md) {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(reading.kind.displayName)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                Text(displayValue(reading))
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(dateLabel(reading.takenAt))
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
            Spacer()
            if reading.isOutsidePlausibleRange() {
                // A reading outside a plausible range is flagged FOR THE
                // DOCTOR and not otherwise interpreted.
                //
                // This is where the temptation is loudest. The number on the
                // left is the number the person typed; the badge on the right
                // says only "check this with your doctor", because a mistyped
                // 1800 and a real 180 look identical to this app and it is
                // not its business to decide which is which. Plan 6.10's
                // words are "talk to your doctor", and they are the last
                // words here on purpose.
                Text(SwasthyaText.t(
                    "swa_vitals_flag",
                    "Please show this to your doctor"
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .padding(DoosriSpace.sm)
                .background(
                    RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                        .fill(DoosriPalette.safety(scheme).opacity(0.14))
                )
                .accessibilityLabel(
                    SwasthyaText.spoken(
                        "swa_vitals_flag_spoken",
                        "This reading looks unusual. Please show it to your doctor."
                    )
                )
            }
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .accessibilityElement(children: .combine)
    }

    private func displayValue(_ reading: VitalReading) -> String {
        switch reading.kind {
        case .bloodPressure:
            guard let s = reading.systolic, let d = reading.diastolic else {
                return SwasthyaText.t("swa_vitals_unreadable", "Not written")
            }
            return "\(s)/\(d) \(reading.kind.unit)"
        case .sugar, .weight, .pulse:
            guard let v = reading.value else {
                return SwasthyaText.t("swa_vitals_unreadable", "Not written")
            }
            return "\(v) \(reading.kind.unit)"
        }
    }

    private func dateLabel(_ epoch: Double) -> String {
        Date(timeIntervalSince1970: epoch).formatted(
            .dateTime.day().month(.wide).hour().minute()
        )
    }

    @ViewBuilder
    private func errorBanner(_ error: VitalsViewModel.LoadError) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 24))
            Text(
                String(
                    localized: String.LocalizationValue(
                        error == .offline ? "swa_offline" : "swa_failed"
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
                Text(SwasthyaText.t("retry", "Try again"))
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