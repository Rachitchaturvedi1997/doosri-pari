// UNTESTED — never compiled. See ios/README.md.
//
// Plan 6.6: the circles the user already belongs to. Quiet, list-shaped, and
// the one screen in Saathi where nothing is being recommended — so it is also
// the screen that needs no reason column.
//
// Leaving is outward and destructive to the user's own history in a circle, so
// it goes through `DoosriConfirmAlert` with verbs on the buttons ("Leave the
// circle", "Stay") rather than a swipe. Plan section 3.4: no swipe-only
// actions, and section 4.4: alerts use verbs, never OK/Cancel alone.

import SwiftUI

// MARK: - View model

@MainActor
final class MyCirclesViewModel: ObservableObject {

    @Published private(set) var circles: [SaathiCircle] = []
    @Published private(set) var loading = false
    @Published private(set) var errorMessage: String?
    /// The circle the confirm alert is about. Nil when no alert is up.
    @Published var leavingCircle: SaathiCircle?
    @Published private(set) var leaving = false

    private let service: SaathiService

    init(service: SaathiService) {
        self.service = service
    }

    func load() async {
        loading = true
        errorMessage = nil
        do {
            circles = try await service.myCircles()
            loading = false
        } catch let e as SaathiServiceError {
            loading = false
            errorMessage = e.errorDescription
        } catch {
            loading = false
            errorMessage = SaathiServiceError.server(status: 0).errorDescription
        }
    }

    /// The row's Leave button opens the alert; this runs only on the verb.
    func confirmLeave() async {
        guard let circle = leavingCircle, !leaving else { return }
        leaving = true
        do {
            try await service.leaveCircle(id: circle.id)
            circles.removeAll { $0.id == circle.id }
            leavingCircle = nil
            leaving = false
        } catch let e as SaathiServiceError {
            leaving = false
            errorMessage = e.errorDescription
        } catch {
            leaving = false
            errorMessage = SaathiServiceError.server(status: 0).errorDescription
        }
    }
}

// MARK: - The screen

struct MyCirclesView: View {
    @StateObject private var model: MyCirclesViewModel

    let openCircle: (SaathiCircle) -> Void
    let openSuggested: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(
        model: MyCirclesViewModel,
        openCircle: @escaping (SaathiCircle) -> Void,
        openSuggested: @escaping () -> Void
    ) {
        _model = StateObject(wrappedValue: model)
        self.openCircle = openCircle
        self.openSuggested = openSuggested
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            if let errorMessage = model.errorMessage {
                errorBanner(errorMessage)
            }
            if model.circles.isEmpty && !model.loading {
                DoosriEmptyState(
                    systemImage: "person.3",
                    title: SaathiText.t("saathi_mine_empty_title", "You have not joined a circle yet"),
                    message: SaathiText.t(
                        "saathi_mine_empty_body",
                        "Circles are groups of colleagues, pensioners from your city, associations and interests."
                    ),
                    actionTitle: SaathiText.t("saathi_mine_empty_action", "See suggested circles")
                ) {
                    openSuggested()
                }
            }
            ForEach(model.circles) { circle in
                circleRow(circle)
            }
        }
        .task {
            if model.circles.isEmpty { await model.load() }
        }
        // The leave confirmation. Button labels are verbs (plan 4.4).
        .background(
            DoosriConfirmAlert(
                title: SaathiText.t(
                    "saathi_leave_title",
                    "Leave \(model.leavingCircle?.name ?? "")?"
                ),
                message: SaathiText.t(
                    "saathi_leave_message",
                    "You will stop seeing this circle's posts and events. You can ask to join again later."
                ),
                confirmTitle: SaathiText.t("saathi_leave_confirm", "Leave the circle"),
                dismissTitle: SaathiText.t("saathi_leave_cancel", "Stay"),
                isPresented: Binding(
                    get: { model.leavingCircle != nil },
                    set: { if !$0 { model.leavingCircle = nil } }
                )
            ) {
                Task { await model.confirmLeave() }
            }
        )
    }

    // MARK: Pieces

    private func circleRow(_ circle: SaathiCircle) -> some View {
        DoosriCard {
            VStack(alignment: .leading, spacing: DoosriSpace.md) {
                HStack(alignment: .top, spacing: DoosriSpace.md) {
                    Image(systemName: circle.symbolName)
                        .font(.system(size: 28))
                        .foregroundStyle(DoosriPalette.accent(scheme))
                        .frame(width: 44)
                        .doosriDecorativeIcon()

                    VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                        Text(circle.name)
                            .font(.doosriHeadline)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                            .multilineTextAlignment(.leading)
                        Text(subtitle(circle))
                            .font(.doosriCaption)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                }

                HStack(spacing: DoosriSpace.md) {
                    DoosriPrimaryButton(
                        SaathiText.t("saathi_action_open", "Open"),
                        systemImage: "text.bubble"
                    ) {
                        openCircle(circle)
                    }
                    DoosriSecondaryButton(
                        SaathiText.t("saathi_action_leave", "Leave"),
                        systemImage: "rectangle.portrait.and.arrow.right"
                    ) {
                        model.leavingCircle = circle
                    }
                    .disabled(model.leaving)
                }
            }
        }
    }

    private func subtitle(_ circle: SaathiCircle) -> String {
        let count = SaathiText.t(
            "saathi_member_count",
            "\(circle.memberCount) \(circle.memberCount == 1 ? "member" : "members")"
        )
        return circle.type.displayName + " · " + count
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: DoosriSpace.md) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 24))
                .doosriDecorativeIcon()
            Text(message)
                .font(.doosriCallout)
                .doosriBodyLineHeight()
            Spacer(minLength: 0)
            Button {
                Task { await model.load() }
            } label: {
                Text(SaathiText.t("saathi_retry", "Try again"))
                    .font(.doosriCallout)
            }
        }
        .foregroundStyle(DoosriPalette.ink(scheme))
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
    }
}