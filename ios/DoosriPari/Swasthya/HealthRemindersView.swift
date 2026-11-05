// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: reminders — dose times, refill alerts at 5 days of stock,
// and a nudge to record readings. Plan 6.15 governs when they may fire:
// medicines are *time-sensitive* and ignore quiet hours, everything else here
// does not.

import SwiftUI

@MainActor
final class HealthRemindersViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var reminders: [HealthReminder] = []
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    let service: SwasthyaService

    init(service: SwasthyaService) {
        self.service = service
    }

    func load() async {
        loading = true
        error = nil
        do {
            reminders = try await service.reminders()
                .sorted { $0.fireAt < $1.fireAt }
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Plan 6.15: at most 4 non-urgent notifications a day. Dose and refill
    /// reminders are the urgent kind here — they are about a tablet due now —
    /// so they are counted separately from the rest of the app's noise.
    var todayCount: Int {
        let startOfDay = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
        return reminders.filter { $0.fireAt >= startOfDay }.count
    }
}

struct HealthRemindersView: View {
    @StateObject private var model: HealthRemindersViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: HealthRemindersViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            SwasthyaText.t("swa_reminders_title", "Reminders"),
            subtitle: SwasthyaText.t(
                "swa_reminders_subtitle",
                "Doses, refills and readings"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.reminders.isEmpty {
                    DoosriEmptyState(
                        systemImage: "bell",
                        title: SwasthyaText.t(
                            "swa_reminders_empty_title",
                            "Nothing to remind you about"
                        ),
                        message: SwasthyaText.t(
                            "swa_reminders_empty_body",
                            "Add a medicine and its times become reminders here."
                        )
                    )
                } else {
                    DoosriSectionHeader(
                        SwasthyaText.t("swa_reminders_upcoming", "Coming up"),
                        subtitle: SwasthyaText.t(
                            "swa_reminders_urgent_note",
                            "Medicine reminders are never silenced by quiet hours"
                        )
                    )
                    ForEach(model.reminders) { reminder in
                        reminderRow(reminder)
                        Divider().background(DoosriPalette.divider(scheme))
                    }
                }
            }
        }
        .task {
            if model.reminders.isEmpty { await model.load() }
        }
    }

    @ViewBuilder
    private func reminderRow(_ reminder: HealthReminder) -> some View {
        HStack(spacing: DoosriSpace.md) {
            Image(systemName: icon(for: reminder.kind))
                .font(.system(size: 26))
                .foregroundStyle(DoosriPalette.accent(scheme))
                .frame(width: 32)
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(reminder.title)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(reminder.detail)
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
            Spacer()
            Text(
                Date(timeIntervalSince1970: reminder.fireAt)
                    .formatted(date: .abbreviated, time: .shortened)
            )
            .font(.doosriCaption)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .accessibilityElement(children: .combine)
    }

    private func icon(for kind: HealthReminder.Kind) -> String {
        switch kind {
        case .medicineDose: return "pills.fill"
        case .refill: return "shippingbox.fill"
        case .vitalsCheck: return "waveform.path.ecg"
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: HealthRemindersViewModel.LoadError) -> some View {
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