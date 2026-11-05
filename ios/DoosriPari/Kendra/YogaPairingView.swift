// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1, "Together": yoga saathi — pair with a friend, see whether
// the other practised today, send a "Shabash".
//
// What this screen deliberately does NOT have: streaks, counts, ranks, or any
// number that can be lost. Plan 7.1 says progress is "never compared with
// others", and a pair is the smallest possible "others". One yes-or-no per
// day and one button that says well done. A friend who did not practise
// today is not behind; they had a day.

import SwiftUI

@MainActor
final class YogaPairingViewModel: ObservableObject {

    enum LoadError: String {
        case offline
        case failed
    }

    @Published private(set) var partners: [YogaPartner] = []
    @Published private(set) var loading = true
    @Published private(set) var sendingID: String?
    @Published private(set) var error: LoadError?

    let service: YogService

    init(service: YogService) {
        self.service = service
    }

    func load() async {
        loading = true
        error = nil
        do {
            partners = try await service.partners()
            loading = false
        } catch let e as YogServiceError {
            loading = false
            error = e.isOffline ? .offline : .failed
        } catch {
            loading = false
            error = .failed
        }
    }

    /// Plan 7.1: "can send a 'Shabash' (well done)". One per day, and only on
    /// a day the partner practised — congratulating somebody for a day they
    /// did not practise is a small cruelty.
    func sendShabash(to partner: YogaPartner) async {
        guard sendingID == nil, partner.canSendShabash else { return }
        sendingID = partner.id
        error = nil
        do {
            let updated = try await service.sendShabash(to: partner.id)
            if let index = partners.firstIndex(where: { $0.id == partner.id }) {
                partners[index] = updated
            }
            sendingID = nil
        } catch let e as YogServiceError {
            sendingID = nil
            error = e.isOffline ? .offline : .failed
        } catch {
            sendingID = nil
            error = .failed
        }
    }
}

struct YogaPairingView: View {
    @StateObject private var model: YogaPairingViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: YogaPairingViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            KendraText.t("ken_pairing_title", "Yoga saathi"),
            subtitle: KendraText.t(
                "ken_pairing_subtitle",
                "A friend to practise beside, in spirit"
            )
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                if model.loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, alignment: .center)
                } else if let error = model.error {
                    errorBanner(error)
                } else if model.partners.isEmpty {
                    DoosriEmptyState(
                        systemImage: "person.2",
                        title: KendraText.t(
                            "ken_pairing_empty_title",
                            "No saathi yet"
                        ),
                        message: KendraText.t(
                            "ken_pairing_empty_body",
                            "Pair with a friend and you will each see whether the other practised today."
                        ),
                        actionTitle: KendraText.t("ken_pairing_invite", "Invite a friend"),
                        action: { inviting = true }
                    )
                } else {
                    partnerList
                }
            }
        }
        .sheet(isPresented: $inviting) {
            DoosriSheet(title: KendraText.t("ken_pairing_invite", "Invite a friend")) {
                Text(KendraText.t(
                    "ken_pairing_invite_body",
                    "Show this code to your friend, or say their name out loud to send an invitation. They have to say yes before anything is shared."
                ))
                .font(.doosriBody)
                .foregroundStyle(DoosriPalette.ink(scheme))
            }
        }
        .task {
            if model.partners.isEmpty { await model.load() }
        }
    }

    @State private var inviting = false

    private var partnerList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            ForEach(model.partners) { partner in
                partnerRow(partner)
                Divider().background(DoosriPalette.divider(scheme))
            }
        }
    }

    @ViewBuilder
    private func partnerRow(_ partner: YogaPartner) -> some View {
        HStack(spacing: DoosriSpace.md) {
            Image(systemName: partner.practicedToday ? "figure.mind.and.body" : "person")
                .font(.system(size: 28))
                .foregroundStyle(DoosriPalette.accent(scheme))
                .frame(width: 36)
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text(partner.name)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                Text(partner.practicedToday
                    ? KendraText.t("ken_pairing_did", "Practised today")
                    : KendraText.t("ken_pairing_not_yet", "Not yet today"))
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
            Spacer()
            if partner.canSendShabash {
                DoosriSecondaryButton(
                    KendraText.t("ken_shabash", "Shabash"),
                    systemImage: "hands.clap"
                ) {
                    Task { await model.sendShabash(to: partner) }
                }
            } else {
                Text(KendraText.t("ken_shabash_sent", "Shabash sent"))
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func errorBanner(_ error: YogaPairingViewModel.LoadError) -> some View {
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