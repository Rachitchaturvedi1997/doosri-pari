// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10 (Health): the health hub. Four doors — medicines, vitals,
// reminders, the CGHS/ECHS directory — and one gate in front of all of them.
//
// DESIGN CONTRACT — `Design/Components/` is being written in parallel and this
// package uses exactly the call shapes recorded in `Saathi/SaathiModels.swift`:
// `DoosriScreen`, `DoosriCard`, `DoosriPrimaryButton`, `DoosriSecondaryButton`,
// `DoosriListRow`, `DoosriSosButton`, `DoosriEmptyState`, `DoosriSheet`,
// `DoosriConfirmAlert`, `DoosriSectionHeader`. `DoosriConfirmAlert` is assumed
// to be a view placed in front of the content (see Saathi's note) and is
// mounted in a `ZStack`.
//
// THE GATE. Plan section 14: health data is a separate named consent. The
// screen a pensioner meets before that consent must explain itself —
// a blank list with no explanation reads as "the app is broken", and the next
// most likely reading is "they already have my health data, it is just not
// showing". Both are worse than the truth, so the truth is written out.

import SwiftUI

@MainActor
final class SwasthyaHubViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var consent: HealthConsentState?
    @Published private(set) var loading = true
    @Published private(set) var saving = false
    @Published private(set) var error: LoadError?

    /// Handed to the pushed screens so all four doors share one seam. Making
    /// it non-private is deliberate: the hub constructs each child view model
    /// rather than reach into a shared singleton.
    let service: SwasthyaService

    init(service: SwasthyaService) {
        self.service = service
    }

    var isHealthOn: Bool { consent?.isGranted == true }

    func load() async {
        loading = true
        error = nil
        do {
            consent = try await service.healthConsent()
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Turning health data on is the named consent from plan 14 being granted,
    /// in one visible tap with the consequences on screen first. Turning it
    /// off again lives in Settings, where the other two consents sit beside
    /// it — see `SettingsView`.
    func turnOnHealthData() async {
        guard !saving, !isHealthOn else { return }
        saving = true
        error = nil
        do {
            consent = try await service.setHealthConsent(.granted)
            saving = false
        } catch let e as SwasthyaServiceError {
            saving = false
            error = e.isOffline ? .offline : .failed
        } catch {
            saving = false
            error = .failed
        }
    }
}

struct SwasthyaHubView: View {
    @StateObject private var model: SwasthyaHubViewModel
    @Environment(\.colorScheme) private var scheme

    /// The app's SOS is an action, not a tab (plan 5). Every main screen
    /// carries it; this one is a no-op here and wired by the app shell.
    var onSOS: () -> Void = {}

    init(model: SwasthyaHubViewModel, onSOS: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: model)
        self.onSOS = onSOS
    }

    var body: some View {
        NavigationStack {
            DoosriScreen(
                SwasthyaText.t("swa_hub_title", "Swasthya · Health"),
                subtitle: SwasthyaText.t(
                    "swa_hub_subtitle",
                    "Your medicines, your readings, your papers"
                ),
                toolbar: [.sos(action: onSOS)]
            ) {
                VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                    if model.loading {
                        ProgressView()
                            .frame(maxWidth: .infinity, alignment: .center)
                    } else if let error = model.error {
                        errorBanner(error)
                    } else if !model.isHealthOn {
                        consentGate
                    } else {
                        hub
                    }
                }
            }
            .task {
                if model.consent == nil { await model.load() }
            }
        }
    }

    // MARK: - The "you have not turned this on" state

    private var consentGate: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            DoosriEmptyState(
                systemImage: "heart.text.square",
                title: SwasthyaText.t(
                    "swa_consent_off_title",
                    "You have not turned on health data"
                ),
                message: SwasthyaText.t(
                    "swa_consent_off_body",
                    "Nothing about your medicines or your blood pressure has been collected. Health data is a separate choice, not part of signing in, and you can turn it off again at any time from Settings."
                ),
                actionTitle: SwasthyaText.t("swa_consent_on", "Turn on health data"),
                action: {
                    Task { await model.turnOnHealthData() }
                }
            )

            DoosriCard {
                VStack(alignment: .leading, spacing: DoosriSpace.sm) {
                    Text(SwasthyaText.t(
                        "swa_consent_means_title",
                        "What turning it on means"
                    ))
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))

                    Text(SwasthyaText.t(
                        "swa_consent_means_body",
                        "Your medicine times and the readings you type are stored so reminders work and so you can show your doctor. They are not shared with family unless you choose that separately, per medicine. This app records and shows — it never diagnoses."
                    ))
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                }
            }

            if model.saving {
                ProgressView()
            }
        }
    }

    // MARK: - The hub

    private var hub: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            DoosriSectionHeader(
                SwasthyaText.t("swa_hub_section", "Your health"),
                subtitle: SwasthyaText.t(
                    "swa_hub_section_sub",
                    "Records and reminders only — no advice here"
                )
            )

            NavigationLink {
                MedicinesView(model: MedicinesViewModel(service: model.service))
            } label: {
                DoosriListRow(
                    systemImage: "pills.fill",
                    title: SwasthyaText.t("swa_row_medicines", "My medicines"),
                    value: SwasthyaText.t("swa_row_medicines_value", "Schedule and doses")
                )
            }

            NavigationLink {
                VitalsView(model: VitalsViewModel(service: model.service))
            } label: {
                DoosriListRow(
                    systemImage: "waveform.path.ecg",
                    title: SwasthyaText.t("swa_row_vitals", "My readings"),
                    value: SwasthyaText.t("swa_row_vitals_value", "BP, sugar, weight, pulse")
                )
            }

            NavigationLink {
                HealthRemindersView(model: HealthRemindersViewModel(service: model.service))
            } label: {
                DoosriListRow(
                    systemImage: "bell.fill",
                    title: SwasthyaText.t("swa_row_reminders", "Reminders"),
                    value: SwasthyaText.t("swa_row_reminders_value", "Doses and refills")
                )
            }

            NavigationLink {
                FacilityDirectoryView(model: FacilityDirectoryViewModel(service: model.service))
            } label: {
                DoosriListRow(
                    systemImage: "cross.case.fill",
                    title: SwasthyaText.t("swa_row_facilities", "CGHS and ECHS centres"),
                    value: SwasthyaText.t("swa_row_facilities_value", "Official list")
                )
            }

            DoosriCard {
                Text(SwasthyaText.t(
                    "swa_hub_not_a_doctor",
                    "This part of the app records and shows. It never diagnoses, never reads your numbers, and never suggests a change to your medicines. For that, talk to your doctor."
                ))
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: SwasthyaHubViewModel.LoadError) -> some View {
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