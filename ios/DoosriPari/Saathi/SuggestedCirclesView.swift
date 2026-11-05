// UNTESTED — never compiled. See ios/README.md.
//
// Plan 6.6 and section 15: suggested circles, each with the reason it is on
// the list.
//
// ─────────────────────────────────────────────────────────────────────────────
// WHY EVERY ROW CARRIES ITS REASON, AND WHY UNEXPLAINABLE CIRCLES ARE DROPPED
// ─────────────────────────────────────────────────────────────────────────────
//
// This community is retired government employees. Almost every one of them is
// identifiable from three facts a colleague already has: department, city and
// service years. In that population an unexplained recommendation is not a
// UX gap, it is a moderation problem, for two separate reasons:
//
//   1. It is an unchecked claim about the user. "We think you want this" with
//      no stated basis is a claim the user cannot verify, contest or turn off.
//      In a small, structured community the claim is also *about* them — the
//      fact that a ranking engine put "Railways 2019–21" in front of somebody
//      is itself a statement about their employer and posting station. If the
//      reason is not shown, the statement is not even attributable, so nobody
//      can be asked to answer for it.
//   2. It is a targeting list with a feed-shaped front end. A pensioner who
//      cannot see why a circle found them cannot tell the difference between a
//      batch circle that knows their batch and a stranger who does. The first
//      is the product; the second is how a scammer builds trust before a
//      "pension office" call.
//
// So the rule in `SaathiModels.swift` is enforced here in the view model: a
// circle with no reason is **not rendered** — not sorted to the bottom. It is
// counted, and the screen says how many were left out and why. There is no
// `popularNearby`, no `similarPeople`, no fallback copy. If a future
// recommender wants to add one, the enum's exhaustive switches fail to compile
// first, which is the moment to ask whether the new reason is something a user
// could be shown and could check.
//
// The reasons themselves are a closed set of facts the user supplied or can
// check in one tap, and each row shows the sentence before any tap.

import SwiftUI

// MARK: - View model

@MainActor
final class SuggestedCirclesViewModel: ObservableObject {

    /// Only circles that can say why they are here. See the note above.
    @Published private(set) var circles: [SaathiCircle] = []
    /// How many were dropped for having no reason. Shown on the screen — a
    /// silent filter is a filter nobody can audit.
    @Published private(set) var hiddenCount = 0
    @Published private(set) var loading = false
    @Published private(set) var errorMessage: String?
    /// In-flight joins, so a double tap on Join is a no-op rather than two
    /// join calls and an error on the second.
    @Published private(set) var joiningIDs: Set<String> = []
    /// Set when the user taps "See why". Drives the evidence sheet.
    @Published var evidenceReason: RecommendationReason?

    let viewerTrustLevel: TrustLevel

    private let service: SaathiService

    init(service: SaathiService, viewerTrustLevel: TrustLevel) {
        self.service = service
        self.viewerTrustLevel = viewerTrustLevel
    }

    func load() async {
        loading = true
        errorMessage = nil
        do {
            let fetched = try await service.suggestedCircles()
            var explainable: [SaathiCircle] = []
            var hidden = 0
            for circle in fetched {
                // The gate. A suggested circle that cannot state its reason is
                // dropped here and counted — see the file note.
                if circle.reason == nil {
                    hidden += 1
                } else {
                    explainable.append(circle)
                }
            }
            circles = explainable
            hiddenCount = hidden
            loading = false
        } catch let e as SaathiServiceError {
            loading = false
            errorMessage = e.errorDescription
        } catch {
            loading = false
            errorMessage = SaathiServiceError.server(status: 0).errorDescription
        }
    }

    /// Join, or request to join for association circles. Idempotent at the
    /// button: a second tap while a join is in flight does nothing.
    func join(_ circle: SaathiCircle) async {
        guard !joiningIDs.contains(circle.id) else { return }
        joiningIDs.insert(circle.id)
        defer { joiningIDs.remove(circle.id) }

        do {
            let updated: SaathiCircle
            switch circle.joining {
            case .requestApproval:
                updated = try await service.requestToJoinCircle(id: circle.id)
            case .open, .joinOpenPostNeedsT1, .inviteLink:
                updated = try await service.joinCircle(id: circle.id)
            }
            replace(updated)
        } catch let e as SaathiServiceError {
            errorMessage = e.errorDescription
        } catch {
            errorMessage = SaathiServiceError.server(status: 0).errorDescription
        }
    }

    private func replace(_ circle: SaathiCircle) {
        guard let index = circles.firstIndex(where: { $0.id == circle.id }) else { return }
        circles[index] = circle
    }
}

// MARK: - Model helpers

extension SaathiCircle {
    /// A copy marked joined. `SaathiCircle` is deliberately immutable
    /// (`SaathiModels.swift`), so a join is a replacement value rather than a
    /// field assignment — which also makes the optimistic UI honest: the row
    /// shows the server's returned circle, never a locally guessed one.
    func withJoined() -> SaathiCircle {
        SaathiCircle(
            id: id,
            name: name,
            type: type,
            city: city,
            memberCount: memberCount,
            joining: joining,
            joined: true,
            reason: reason,
            symbolName: symbolName
        )
    }
}

// MARK: - The screen

struct SuggestedCirclesView: View {
    @StateObject private var model: SuggestedCirclesViewModel

    /// Opens the circle's feed. Navigation is the caller's business (the tab
    /// root owns the `NavigationStack`), so the screen takes a closure.
    let openCircle: (SaathiCircle) -> Void

    @Environment(\.colorScheme) private var scheme

    init(
        model: SuggestedCirclesViewModel,
        openCircle: @escaping (SaathiCircle) -> Void
    ) {
        _model = StateObject(wrappedValue: model)
        self.openCircle = openCircle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            intro
            if let errorMessage = model.errorMessage {
                errorBanner(errorMessage)
            }
            if model.hiddenCount > 0 {
                hiddenNote
            }
            if model.circles.isEmpty && !model.loading {
                DoosriEmptyState(
                    systemImage: "person.3",
                    title: SaathiText.t("saathi_suggested_empty_title", "Nothing new right now"),
                    message: SaathiText.t(
                        "saathi_suggested_empty_body",
                        "When a batch, a department or an association circle fits your service, it appears here with the reason."
                    )
                )
            }
            ForEach(model.circles) { circle in
                circleRow(circle)
            }
        }
        .task {
            if model.circles.isEmpty { await model.load() }
        }
        .sheet(item: $model.evidenceReason) { reason in
            // WHY the sheet exists: a reason nobody can check is decoration.
            // Plan 4.3 gives icons labels; the same instinct says a
            // recommendation must come with its evidence. The sentence is the
            // claim; this sheet is where the user checks it.
            DoosriSheet(
                title: SaathiText.t("saathi_why_title", "Why you are seeing this"),
                onCancel: { model.evidenceReason = nil }
            ) {
                VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                    Text(reason.sentence)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .doosriBodyLineHeight()
                    Text(
                        SaathiText.t(
                            "saathi_why_check",
                            "This comes from what you told us about your service and your circles — your department, your city, your years and the interests you chose. You can change any of it in Main › Profile."
                        )
                    )
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriBodyLineHeight()
                    DoosriSecondaryButton(
                        SaathiText.t("saathi_why_done", "Close"),
                        systemImage: "checkmark"
                    ) {
                        model.evidenceReason = nil
                    }
                }
            }
        }
    }

    // MARK: Pieces

    private var intro: some View {
        Text(
            SaathiText.t(
                "saathi_suggested_intro",
                "Each circle below says why it is here. Nothing is suggested without a reason you can check."
            )
        )
        .font(.doosriCallout)
        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        .doosriBodyLineHeight()
    }

    /// The dropped count, said out loud. This is the audit trail for the
    /// moderation rule at the top of this file: a user who is told "3 circles
    /// were left out because they could not explain themselves" can hold the
    /// app to that, and a moderator can see the recommender failing closed.
    private var hiddenNote: some View {
        DoosriStateBadge(
            state: .neutral(
                SaathiText.t(
                    "saathi_suggested_hidden",
                    "\(model.hiddenCount) \(model.hiddenCount == 1 ? "circle was" : "circles were") left out because they could not say why."
                )
            )
        )
    }

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
                        Text(circle.type.displayName + " · " + memberLine(circle))
                            .font(.doosriCaption)
                            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                }

                // The reason, before any tap and before the join button. The
                // order is the point: nobody should have joined to find out
                // why they were offered.
                if let reason = circle.reason {
                    reasonBlock(reason)
                }

                HStack(spacing: DoosriSpace.md) {
                    if circle.joining != .inviteLink {
                        DoosriPrimaryButton(
                            circle.joinActionWord,
                            systemImage: circle.joined ? "arrow.right" : "person.badge.plus"
                        ) {
                            Task { await model.join(circle) }
                        }
                        .disabled(model.joiningIDs.contains(circle.id) || circle.joined)
                    } else {
                        DoosriSecondaryButton(
                            circle.joinActionWord,
                            systemImage: "lock"
                        ) {
                            Task { await model.join(circle) }
                        }
                    }
                }
            }
        }
    }

    private func reasonBlock(_ reason: RecommendationReason) -> some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            HStack(spacing: DoosriSpace.sm) {
                Image(systemName: "questionmark.circle")
                    .font(.system(size: 22))
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriDecorativeIcon()
                Text(reason.sentence)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .multilineTextAlignment(.leading)
            }
            // "See why" only where there is evidence to see. A link that goes
            // nowhere teaches people that the reasons are theatre
            // (`RecommendationReason.hasVisibleEvidence`).
            if reason.hasVisibleEvidence {
                Button {
                    model.evidenceReason = reason
                } label: {
                    HStack(spacing: DoosriSpace.xs) {
                        Image(systemName: "doc.text.magnifyingglass")
                            .font(.system(size: 20))
                        Text(SaathiText.t("saathi_see_why", "See why"))
                            .font(.doosriCaption)
                    }
                    .foregroundStyle(DoosriPalette.accent(scheme))
                    .frame(minHeight: DoosriTarget.minimumHitTarget)
                }
                .buttonStyle(.plain)
                .doosriButtonSemantics(
                    label: SaathiText.t("saathi_see_why", "See why"),
                    hint: SaathiText.t(
                        "saathi_see_why_hint",
                        "Shows where this reason comes from."
                    )
                )
            }
        }
        .padding(DoosriSpace.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.divider(scheme).opacity(0.35))
        )
    }

    private func memberLine(_ circle: SaathiCircle) -> String {
        SaathiText.t(
            "saathi_member_count",
            "\(circle.memberCount) \(circle.memberCount == 1 ? "member" : "members")"
        )
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

// MARK: - Evidence sheet needs Identifiable

extension RecommendationReason: Identifiable {
    /// The sentence is unique per rendered row, and the enum has no ids of its
    /// own. `.sheet(item:)` needs one; hashing the sentence is stable and
    /// needs no new stored property on a model this package does not own.
    var id: String { String(hashValue) }
}