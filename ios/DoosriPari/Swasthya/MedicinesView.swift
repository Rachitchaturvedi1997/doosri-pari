// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: the medicines list. One row per medicine, its dose and
// its times, and one door to the daily schedule behind each row.

import SwiftUI

@MainActor
final class MedicinesViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var medicines: [Medicine] = []
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    /// Set when a delete is in flight, so the row can show it and a second
    /// tap cannot queue a second delete.
    @Published private(set) var deletingID: String?

    let service: SwasthyaService

    init(service: SwasthyaService) {
        self.service = service
    }

    func load() async {
        loading = true
        error = nil
        do {
            medicines = try await service.medicines()
                .sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Deleting a medicine deletes the schedule and the dose history with it.
    /// The view puts `DoosriConfirmAlert` in front of this, with verbs.
    func delete(_ medicine: Medicine) async {
        guard deletingID == nil else { return }
        deletingID = medicine.id
        error = nil
        do {
            try await service.deleteMedicine(id: medicine.id)
            medicines.removeAll { $0.id == medicine.id }
            deletingID = nil
        } catch let e as SwasthyaServiceError {
            deletingID = nil
            error = e.isOffline ? .offline : .failed
        } catch {
            deletingID = nil
            error = .failed
        }
    }
}

struct MedicinesView: View {
    @StateObject private var model: MedicinesViewModel
    @Environment(\.colorScheme) private var scheme

    @State private var editorDraft: Medicine?
    @State private var medicinePendingDelete: Medicine?
    @State private var confirmingDelete = false

    init(model: MedicinesViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            SwasthyaText.t("swa_medicines_title", "My medicines"),
            subtitle: SwasthyaText.t(
                "swa_medicines_subtitle",
                "What you take, and when"
            )
        ) {
            ZStack {
                VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                    if model.loading {
                        ProgressView()
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else if let error = model.error {
                        errorBanner(error)
                    } else if model.medicines.isEmpty {
                        DoosriEmptyState(
                            systemImage: "pills",
                            title: SwasthyaText.t(
                                "swa_medicines_empty_title",
                                "No medicines added yet"
                            ),
                            message: SwasthyaText.t(
                                "swa_medicines_empty_body",
                                "Add one medicine and this list will hold its times and doses."
                            ),
                            actionTitle: SwasthyaText.t("swa_add_medicine", "Add a medicine"),
                            action: { editorDraft = Medicine.emptyDraft() }
                        )
                    } else {
                        list
                    }

                    if !model.medicines.isEmpty {
                        DoosriPrimaryButton(
                            SwasthyaText.t("swa_add_medicine", "Add a medicine"),
                            systemImage: "plus"
                        ) {
                            editorDraft = Medicine.emptyDraft()
                        }
                    }
                }

                // Plan 4.4: alerts are only for irreversible actions, and the
                // buttons are verbs — "Delete this medicine" / "Keep it",
                // never OK / Cancel.
                DoosriConfirmAlert(
                    title: SwasthyaText.t(
                        "swa_delete_medicine_title",
                        "Delete this medicine?"
                    ),
                    message: SwasthyaText.t(
                        "swa_delete_medicine_body",
                        "Its schedule and its dose history will go too. Nobody is told."
                    ),
                    confirmTitle: SwasthyaText.t("swa_delete_confirm", "Delete this medicine"),
                    dismissTitle: SwasthyaText.t("swa_delete_dismiss", "Keep it"),
                    isPresented: $confirmingDelete,
                    onConfirm: {
                        if let pending = medicinePendingDelete {
                            Task { await model.delete(pending) }
                        }
                        medicinePendingDelete = nil
                    }
                )
            }
        }
        .sheet(item: $editorDraft) { draft in
            DoosriSheet(
                title: SwasthyaText.t("swa_editor_sheet_title", "Medicine details")
            ) {
                MedicineEditorView(
                    model: MedicineEditorViewModel(
                        service: model.service,
                        medicine: draft
                    ),
                    onSaved: { saved in
                        editorDraft = nil
                        Task { await model.load() }
                        _ = saved
                    },
                    onCancel: { editorDraft = nil }
                )
            }
        }
        .task {
            if model.medicines.isEmpty { await model.load() }
        }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            ForEach(model.medicines) { medicine in
                HStack(spacing: DoosriSpace.md) {
                    NavigationLink {
                        MedicineScheduleView(
                            model: MedicineScheduleViewModel(
                                service: model.service,
                                medicine: medicine
                            )
                        )
                    } label: {
                        DoosriListRow(
                            systemImage: "pills.fill",
                            title: medicine.displayName,
                            value: scheduleSummary(medicine)
                        )
                    }

                    // Plan 3.4: no swipe-only actions. A visible delete
                    // button, behind the same confirmation the swipe would
                    // have needed.
                    Button {
                        medicinePendingDelete = medicine
                        confirmingDelete = true
                    } label: {
                        Text(SwasthyaText.t("swa_delete_word", "Delete"))
                            .font(.doosriCallout)
                            .foregroundStyle(DoosriPalette.safety(scheme))
                            .frame(minHeight: DoosriSize.minControlHeight)
                            .padding(.horizontal, DoosriSpace.sm)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(
                        SwasthyaText.spoken(
                            "swa_delete_medicine_spoken",
                            "Delete \(medicine.displayName)"
                        )
                    )
                }
            }
        }
    }

    private func scheduleSummary(_ medicine: Medicine) -> String {
        let times = medicine.times.sorted().map(\.clockLabel)
            .joined(separator: ", ")
        return times.isEmpty
            ? SwasthyaText.t("swa_no_times", "No times set")
            : times
    }

    @ViewBuilder
    private func errorBanner(_ error: MedicinesViewModel.LoadError) -> some View {
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

extension Medicine {
    /// A blank medicine for the editor. Every field has a real default rather
    /// than an optional the editor would have to unwrap: a person adding their
    /// first medicine should meet a filled-in form, not a form full of blanks.
    static func emptyDraft() -> Medicine {
        Medicine(
            id: UUID().uuidString,
            name: "",
            dose: "",
            foodTiming: .anyTime,
            times: [DoseTime(hour: 8, minute: 0)],
            days: [],
            photoStripPath: nil,
            // OFF by default. Plan 6.13: sharing with a guardian is opt-in.
            // A medicine remembered in silence is nobody else's business
            // until the pensioner says so.
            shareMissesWithGuardian: false,
            dosesRemaining: nil
        )
    }
}