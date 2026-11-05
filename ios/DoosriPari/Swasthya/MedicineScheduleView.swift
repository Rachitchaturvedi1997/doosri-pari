// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: one medicine's daily schedule and its dose log — the
// "Taken / Skip / Later (15 min)" buttons and everything that follows from
// whether they were pressed.
//
// This screen is where the app's premise about missed doses is rendered, and
// where the guardian rule is decided. Both are commented where they happen.

import SwiftUI

/// One row of the day's log, as the screen draws it: the scheduled dose and
/// its status, ready-made so the view body stays free of logic.
struct DoseRow: Identifiable {
    let entry: DoseLogEntry
    let status: DoseStatus

    var id: String { entry.id }

    var timeLabel: String {
        Date(timeIntervalSince1970: entry.scheduledAt)
            .formatted(date: .omitted, time: .shortened)
    }
}

@MainActor
final class MedicineScheduleViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var rows: [DoseRow] = []
    @Published private(set) var loading = true
    @Published private(set) var savingID: String?
    @Published private(set) var error: LoadError?

    /// The medicine this schedule belongs to. The share toggle lives on it.
    let medicine: Medicine

    let service: SwasthyaService

    /// Injected for the guardian rule's "in a row" counting and for row
    /// status; defaulted so the view never has to supply it.
    private let now: () -> Date

    init(
        service: SwasthyaService,
        medicine: Medicine,
        now: @escaping () -> Date = { Date() }
    ) {
        self.service = service
        self.medicine = medicine
        self.now = now
    }

    func load() async {
        loading = true
        error = nil
        do {
            let start = Calendar.current.startOfDay(for: now())
            let end = start.addingTimeInterval(24 * 60 * 60)
            let entries = try await service.doseLog(from: start, to: end)
                .filter { $0.medicineID == medicine.id }
            let current = now()
            rows = entries
                .sorted { $0.scheduledAt < $1.scheduledAt }
                .map { DoseRow(entry: $0, status: $0.status(now: current)) }
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    func acknowledge(_ row: DoseRow, as acknowledgement: DoseAcknowledgement) async {
        guard savingID == nil else { return }
        savingID = row.id
        error = nil
        do {
            let updated = try await service.acknowledgeDose(
                entryID: row.id,
                as: acknowledgement
            )
            if let index = rows.firstIndex(where: { $0.id == row.id }) {
                rows[index] = DoseRow(
                    entry: updated,
                    status: updated.status(now: now())
                )
            }
            savingID = nil
        } catch let e as SwasthyaServiceError {
            savingID = nil
            error = e.isOffline ? .offline : .failed
        } catch {
            savingID = nil
            error = .failed
        }
    }

    // MARK: - The guardian rule

    /// Plan 6.10: "the guardian is told after **2 misses in a row**, if
    /// allowed". Two conditions, and both are load-bearing.
    ///
    /// The count: two in a row, not two in a day and not one. One missed dose
    /// is a nap, a phone that was in the other room, a day at the temple. Two
    /// in a row is a pattern worth somebody's attention, and that is the
    /// narrowest line that still catches the case the app exists for.
    ///
    /// The permission: `shareMissesWithGuardian`, the per-medicine toggle in
    /// the editor. If it is off, nobody is told no matter how many doses are
    /// missed. A person who kept their tablets private did not lose that
    /// privacy by adding their daughter to the family screen.
    var guardianAlert: GuardianMissedDoseAlert? {
        // Plan 6.10: "if allowed". The check comes first so that a shared
        // medicine's data is not even counted when sharing is off.
        guard medicine.shareMissesWithGuardian else { return nil }

        var run = 0
        for row in rows {
            if row.status == .missed {
                run += 1
                if run >= 2 {
                    return GuardianMissedDoseAlert(
                        medicineName: medicine.displayName,
                        missedInARow: run
                    )
                }
            } else {
                run = 0
            }
        }
        return nil
    }

    /// Plan 6.10: refill alert at 5 days of stock.
    var refillAlertText: String? {
        medicine.isRefillDueSoon
            ? SwasthyaText.t(
                "swa_refill_soon",
                "About 5 days of stock left. Time to refill."
            )
            : nil
    }
}

/// What the guardian is told, and the only thing they are told.
struct GuardianMissedDoseAlert: Equatable {
    let medicineName: String
    let missedInARow: Int

    var message: String {
        String(
            format: SwasthyaText.t(
                "swa_guardian_alert",
                "%1$@ was not taken %2$d times in a row. A gentle word may help."
            ),
            medicineName,
            missedInARow
        )
    }
}

struct MedicineScheduleView: View {
    @StateObject private var model: MedicineScheduleViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: MedicineScheduleViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            model.medicine.displayName,
            subtitle: SwasthyaText.t(
                "swa_schedule_subtitle",
                "Today's doses"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                summaryCard

                if let refill = model.refillAlertText {
                    DoosriCard {
                        Text(refill)
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                    }
                }

                if let alert = model.guardianAlert {
                    guardianCard(alert)
                }

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.rows.isEmpty {
                    DoosriEmptyState(
                        systemImage: "clock",
                        title: SwasthyaText.t(
                            "swa_schedule_empty_title",
                            "No doses scheduled for today"
                        ),
                        message: SwasthyaText.t(
                            "swa_schedule_empty_body",
                            "Doses appear here at their set times."
                        )
                    )
                } else {
                    doseList
                }
            }
        }
        .task {
            if model.rows.isEmpty { await model.load() }
        }
    }

    // MARK: - Pieces

    private var summaryCard: some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(model.medicine.dose.isEmpty
                    ? SwasthyaText.t("swa_dose_unspecified", "Dose not written")
                    : model.medicine.dose)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(model.medicine.foodTiming.displayName)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                Text(shareStatusLine)
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var shareStatusLine: String {
        model.medicine.shareMissesWithGuardian
            ? SwasthyaText.t(
                "swa_share_on_line",
                "Your family is told after 2 missed doses in a row."
            )
            : SwasthyaText.t(
                "swa_share_off_line",
                "Not shared with family. Nobody is told about missed doses."
            )
    }

    private func guardianCard(_ alert: GuardianMissedDoseAlert) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                Text(SwasthyaText.t(
                    "swa_guardian_title",
                    "Your family will be told"
                ))
                .font(.doosriHeadline)
                // Red here is `safety`, and it is not decoration: this is one
                // of the two states (plan 4.3) that mean somebody may be
                // unwell. If red starts meaning "a card about family", the
                // SOS button stops meaning anything.
                .foregroundStyle(DoosriPalette.safety(scheme))
                Text(alert.message)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(SwasthyaText.t(
                    "swa_guardian_scope",
                    "Only this medicine. You chose to share it in the medicine's details."
                ))
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    private var doseList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            ForEach(model.rows) { row in
                doseRow(row)
                Divider().background(DoosriPalette.divider(scheme))
            }
        }
    }

    @ViewBuilder
    private func doseRow(_ row: DoseRow) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            HStack(spacing: DoosriSpace.md) {
                statusGlyph(row.status)
                Text(row.timeLabel)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Spacer()
                Text(row.status.displayName)
                    .font(.doosriCallout)
                    .foregroundStyle(statusTextColour(row.status))
            }

            if row.status == .upcoming {
                HStack(spacing: DoosriSpace.sm) {
                    DoosriSecondaryButton(
                        DoseAcknowledgement.taken.displayName,
                        systemImage: "checkmark"
                    ) {
                        Task { await model.acknowledge(row, as: .taken) }
                    }
                    DoosriSecondaryButton(
                        DoseAcknowledgement.skipped.displayName,
                        systemImage: "minus"
                    ) {
                        Task { await model.acknowledge(row, as: .skipped) }
                    }
                    DoosriSecondaryButton(
                        DoseAcknowledgement.postponed.displayName,
                        systemImage: "clock.arrow.circlepath"
                    ) {
                        Task { await model.acknowledge(row, as: .postponed) }
                    }
                }
            }
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func statusGlyph(_ status: DoseStatus) -> some View {
        let name: String
        switch status {
        case .upcoming: name = "clock"
        case .taken: name = "checkmark.circle.fill"
        case .skipped: name = "minus.circle"
        // MISSED — and this is the whole point of this screen.
        //
        // A dose nobody ever acknowledged renders as MISSED. Not "skipped":
        // skipping is a decision this person made and told us about, and
        // almost nobody decides to skip their heart tablets without saying
        // so. The premise of this app is that a person who stopped taking
        // their tablets did not decide to stop — they forgot, they ran out,
        // the shop was shut, they felt too unwell to get up. So silence is
        // reported as MISSED, a state about what did not happen, never as a
        // state about what this person chose. Everything downstream follows
        // that: the wording, the guardian message ("was not taken", never
        // "refused"), and the rule that MISSED is the only status the app
        // infers rather than hears.
        case .missed: name = "exclamationmark.circle"
        }
        Image(systemName: name)
            .font(.system(size: 26))
            .foregroundStyle(statusTextColour(status))
            .frame(width: 32)
    }

    /// `ok` green only for a dose actually taken, `safety` red only for MISSED
    /// — a real report about a person's wellbeing. Skipped is neutral ink,
    /// because skipping was their decision and red is not for disagreeing
    /// with somebody's choices. Plan 4.3: semantic colours are never
    /// decoration.
    private func statusTextColour(_ status: DoseStatus) -> Color {
        switch status {
        case .taken: return DoosriPalette.ok(scheme)
        case .missed: return DoosriPalette.safety(scheme)
        case .skipped, .upcoming: return DoosriPalette.inkSecondary(scheme)
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: MedicineScheduleViewModel.LoadError) -> some View {
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