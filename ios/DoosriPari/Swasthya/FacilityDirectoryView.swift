// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.10: the CGHS/ECHS finder — "from official lists refreshed
// monthly, each showing 'last updated'".
//
// A directory is a promise that somebody will keep it current, which is why
// the refresh date is on every row and not in a footnote. A pensioner who
// takes three buses to a clinic that moved has been failed more completely by
// a stale list than by no list at all.

import SwiftUI

@MainActor
final class FacilityDirectoryViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var facilities: [HealthFacility] = []
    @Published private(set) var loading = true
    @Published private(set) var error: LoadError?

    @Published var schemeFilter: HealthScheme?
    @Published var cityFilter = ""

    let service: SwasthyaService

    init(service: SwasthyaService) {
        self.service = service
    }

    func load() async {
        loading = true
        error = nil
        do {
            let city = cityFilter.trimmingCharacters(in: .whitespaces)
            facilities = try await service.facilities(
                scheme: schemeFilter,
                city: city.isEmpty ? nil : city
            )
            loading = false
        } catch let e as SwasthyaServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    func setFilter(_ scheme: HealthScheme?) async {
        schemeFilter = scheme
        await load()
    }

    /// The most recent refresh date in what we hold. Shown at the top so the
    /// answer to "how fresh is this?" does not require opening every row.
    var newestLastUpdated: String? {
        // The dates are strings as the official lists print them ("1 March
        // 2026"), so "newest" is the last row that reported one, not a parse.
        facilities.last?.lastUpdated
    }
}

struct FacilityDirectoryView: View {
    @StateObject private var model: FacilityDirectoryViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: FacilityDirectoryViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            SwasthyaText.t("swa_facilities_title", "CGHS and ECHS centres"),
            subtitle: SwasthyaText.t(
                "swa_facilities_subtitle",
                "From the official lists"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                filterRow

                if let updated = model.newestLastUpdated {
                    Text(
                        String(
                            format: SwasthyaText.t(
                                "swa_facilities_updated",
                                "List last updated: %@"
                            ),
                            updated
                        )
                    )
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                }

                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.facilities.isEmpty {
                    DoosriEmptyState(
                        systemImage: "cross.case",
                        title: SwasthyaText.t(
                            "swa_facilities_empty_title",
                            "Nothing found"
                        ),
                        message: SwasthyaText.t(
                            "swa_facilities_empty_body",
                            "Try another city, or clear the filter."
                        ),
                        actionTitle: SwasthyaText.t("swa_facilities_clear", "Show everything"),
                        action: {
                            model.cityFilter = ""
                            Task { await model.setFilter(nil) }
                        }
                    )
                } else {
                    facilityList
                }
            }
        }
        .task {
            if model.facilities.isEmpty { await model.load() }
        }
    }

    private var filterRow: some View {
        HStack(spacing: DoosriSpace.sm) {
            ForEach([HealthScheme?.none] + HealthScheme.allCases.map { HealthScheme?($0) }, id: \.self) { scheme in
                Button {
                    Task { await model.setFilter(scheme) }
                } label: {
                    Text(scheme?.displayName ?? SwasthyaText.t("swa_facilities_all", "All"))
                        .font(.doosriCallout)
                        .frame(minHeight: DoosriSize.minControlHeight)
                        .padding(.horizontal, DoosriSpace.md)
                        .background(
                            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                                .fill(
                                    model.schemeFilter == scheme
                                        ? DoosriPalette.accent(schemeColorScheme)
                                        : DoosriPalette.surface(schemeColorScheme)
                                )
                        )
                        .foregroundStyle(
                            model.schemeFilter == scheme
                                ? DoosriPalette.background(schemeColorScheme)
                                : DoosriPalette.ink(schemeColorScheme)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// `scheme` is the colour scheme from the environment and `scheme` on the
    /// loop above is a health scheme. They collide in scope, so the palette
    /// calls go through this alias.
    private var schemeColorScheme: ColorScheme { scheme }

    private var facilityList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            ForEach(model.facilities) { facility in
                facilityCard(facility)
            }
        }
    }

    private func facilityCard(_ facility: HealthFacility) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(facility.name)
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(facility.scheme.displayName + " · " + facility.city)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                Text(facility.address)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                Text(
                    String(
                        format: SwasthyaText.t(
                            "swa_facilities_row_updated",
                            "Last updated: %@"
                        ),
                        facility.lastUpdated
                    )
                )
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))

                // The number is shown as text and callable with one tap. No
                // pensioner should have to copy a phone number by hand.
                Link(destination: URL(string: "tel://\(facility.phone.filter { $0.isNumber })")!) {
                    HStack(spacing: DoosriSpace.sm) {
                        Image(systemName: "phone.fill")
                            .font(.system(size: 20))
                        Text(facility.phone)
                            .font(.doosriBody)
                    }
                    .frame(minHeight: DoosriSize.minControlHeight)
                }
                .accessibilityLabel(
                    SwasthyaText.spoken(
                        "swa_facilities_call_spoken",
                        "Call \(facility.name) on \(facility.phone)"
                    )
                )
            }
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: FacilityDirectoryViewModel.LoadError) -> some View {
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