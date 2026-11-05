// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 15: "Reports on every post, comment, room, listing and profile."
// One sheet, one view model, five kinds of call site — Saathi posts and
// comments, voice rooms, member profiles, and Seva listings all open this
// same sheet. A report flow that differs between a post and a listing is two
// flows to mistrain somebody on, and this is the flow a frightened user meets
// after something upsetting, which is the worst possible moment to be
// learning a new interface.
//
// The control that opens this sheet is a **visible button with a label**, on
// every post and every comment, not a long-press or a swipe action. Plan
// section 3.4 forbids hidden actions outright, and the reason is concrete: a
// pensioner with a tremor cannot hold a touch steady long enough to find a
// long-press menu, and a user who cannot find the report button reads the
// abuse and leaves the app instead.
//
// Sending is the outward step, so the sheet itself IS the visible confirmation:
// it shows exactly what is being sent — which post, which reason, what note —
// and the button that sends it is a verb ("Send report"), never "OK".

import SwiftUI

// MARK: - What is being reported

/// A request to report one thing. Held by a parent view model as
/// `reportRequest` and presented with `.sheet(item:)`.
struct ReportRequest: Identifiable, Hashable {
    let subject: ReportSubject
    let subjectID: String
    /// What the sheet's title says is being reported — "your post",
    /// "Sharma ji's comment", "the Lucknow walking circle's room". Words the
    /// user recognises, not an id.
    let subjectTitle: String

    var id: String { "\(subject.rawValue):\(subjectID)" }
}

// MARK: - View model

@MainActor
final class SaathiReportViewModel: ObservableObject {

    @Published private(set) var draft: ReportDraft
    /// The note is `@Published` in its own right so the TextField binds to it
    /// directly; it is folded into the draft at send time, in one place.
    @Published var note: String = ""
    @Published private(set) var sending = false
    @Published private(set) var receipt: ReportReceipt?
    @Published private(set) var errorMessage: String?

    let subjectTitle: String

    private let send: (ReportDraft) async throws -> ReportReceipt

    init(
        request: ReportRequest,
        send: @escaping (ReportDraft) async throws -> ReportReceipt
    ) {
        self.draft = ReportDraft(
            subject: request.subject,
            subjectID: request.subjectID,
            reason: nil,
            note: ""
        )
        self.subjectTitle = request.subjectTitle
        self.send = send
    }

    var screenTitle: String {
        SaathiText.t(
            "saathi_report_title",
            "Report this \(draft.subject.displayName)"
        )
    }

    /// The line that names the thing being reported, so nobody sends a report
    /// about the wrong post because they opened the sheet on the wrong row.
    var subjectLine: String {
        SaathiText.t("saathi_report_about", "About: \(subjectTitle)")
    }

    var selectedReason: ReportReason? { draft.reason }

    /// Nothing is sent without a reason. The button stays disabled rather than
    /// sending and then asking, because a report with no reason is a report
    /// the two-hour scam queue cannot triage.
    var canSend: Bool {
        draft.reason != nil && !sending && receipt == nil
    }

    var isFinished: Bool { receipt != nil }

    func select(_ reason: ReportReason) {
        draft.reason = reason
        errorMessage = nil
    }

    func send() async {
        guard let reason = draft.reason, !sending, receipt == nil else { return }
        var outgoing = draft
        outgoing.reason = reason
        outgoing.note = note.trimmingCharacters(in: .whitespacesAndNewlines)
        sending = true
        errorMessage = nil
        do {
            receipt = try await send(outgoing)
            sending = false
        } catch {
            sending = false
            errorMessage = (error as? SaathiServiceError)?.errorDescription
                ?? SaathiText.t("saathi_error_failed", "Could not load. Please try again.")
        }
    }
}

// MARK: - The sheet

struct SaathiReportSheet: View {
    @StateObject private var model: SaathiReportViewModel

    /// Dismiss. The sheet is presented by the caller with `.sheet(item:)`, so
    /// closing is a callback rather than a call into UIKit.
    let onDone: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(
        request: ReportRequest,
        send: @escaping (ReportDraft) async throws -> ReportReceipt,
        onDone: @escaping () -> Void
    ) {
        _model = StateObject(
            wrappedValue: SaathiReportViewModel(request: request, send: send)
        )
        self.onDone = onDone
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            Text(model.subjectLine)
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriBodyLineHeight()

            if model.isFinished {
                finished
            } else {
                reasonList
                noteField
                if let errorMessage = model.errorMessage {
                    errorLine(errorMessage)
                }
                sendButton
            }
        }
    }

    // MARK: Pieces

    /// The reasons are section 15's own checks in the user's words — reasons,
    /// not accusations, so the reporter is never asked to judge a colleague.
    private var reasonList: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            DoosriSectionHeader(
                SaathiText.t("saathi_report_why", "What is wrong?")
            )
            ForEach(ReportReason.allCases) { reason in
                reasonRow(reason)
            }
        }
    }

    private func reasonRow(_ reason: ReportReason) -> some View {
        let isSelected = model.selectedReason == reason
        return Button {
            model.select(reason)
        } label: {
            HStack(alignment: .top, spacing: DoosriSpace.md) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 26))
                    .foregroundStyle(
                        isSelected
                            ? DoosriPalette.accent(scheme)
                            : DoosriPalette.inkSecondary(scheme)
                    )
                    .doosriDecorativeIcon()

                VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                    Text(reason.displayName)
                        .font(.doosriBody)
                        .foregroundStyle(DoosriPalette.ink(scheme))
                        .multilineTextAlignment(.leading)
                    // The response window goes on the row, before the report is
                    // sent. "We look at scam reports within 2 hours" is a
                    // promise a user can hold the app to; "we will look at it"
                    // is not.
                    Text(reason.responseWindow)
                        .font(.doosriCaption)
                        .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(DoosriSpace.md)
            .frame(minHeight: DoosriSize.minControlHeight)
            .background(
                RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                    .fill(
                        isSelected
                            ? DoosriPalette.accent(scheme).opacity(0.12)
                            : DoosriPalette.surface(scheme)
                    )
            )
        }
        .buttonStyle(.plain)
        .doosriButtonSemantics(
            label: reason.displayName,
            value: isSelected
                ? SaathiText.t("saathi_report_selected", "Selected")
                : SaathiText.t("saathi_report_not_selected", "Not selected"),
            hint: SaathiText.t(
                "saathi_report_reason_hint",
                "Chooses this as the reason for the report."
            )
        )
    }

    private var noteField: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            DoosriSectionHeader(
                SaathiText.t("saathi_report_note", "Anything to add?")
            )
            TextField(
                SaathiText.t("saathi_report_note_placeholder", "Optional"),
                text: $model.note,
                axis: .vertical
            )
            .font(.doosriBody)
            .foregroundStyle(DoosriPalette.ink(scheme))
            .lineLimit(3...6)
            .padding(DoosriSpace.md)
            .frame(minHeight: DoosriSize.minControlHeight)
            .background(
                RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                    .fill(DoosriPalette.surface(scheme))
            )
        }
    }

    private var sendButton: some View {
        DoosriPrimaryButton(
            SaathiText.t("saathi_report_send", "Send report"),
            systemImage: "flag"
        ) {
            Task { await model.send() }
        }
        .disabled(!model.canSend)
    }

    /// The acknowledgement: what was sent and when it will be looked at. The
    /// window comes from the server's receipt, not from the reason — the
    /// server knows whether a report that arrived at 23:00 is in the two-hour
    /// queue, and a client guessing "2 hours" would be promising something the
    /// moderation rota cannot deliver (see `ReportReceipt`).
    private var finished: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            if let receipt = model.receipt {
                DoosriStateBadge(
                    state: .safe(
                        SaathiText.t("saathi_report_sent", "Report sent. Thank you — this helps keep Doosri Pari safe.")
                    )
                )
                Text(receipt.responseWindow)
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .doosriBodyLineHeight()
            }
            DoosriSecondaryButton(
                SaathiText.t("saathi_report_done", "Done"),
                systemImage: "checkmark"
            ) {
                onDone()
            }
        }
    }

    private func errorLine(_ message: String) -> some View {
        HStack(spacing: DoosriSpace.md) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 24))
                .foregroundStyle(DoosriPalette.safety(scheme))
                .doosriDecorativeIcon()
            Text(message)
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .doosriBodyLineHeight()
            Spacer(minLength: 0)
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
    }
}

// MARK: - Presentation helper

extension View {
    /// Presents the report sheet for whatever the parent view model has set.
    ///
    /// The `DoosriSheet` container is the design system's sheet chrome
    /// (grabber, title, Cancel). It takes no dismiss binding per the recorded
    /// contract, so dismissal is driven by the two closures the caller passes
    /// in — see the report this package filed on the component contract.
    func saathiReportSheet(
        request: Binding<ReportRequest?>,
        send: @escaping (ReportDraft) async throws -> ReportReceipt
    ) -> some View {
        sheet(item: request) { item in
            DoosriSheet(
                title: SaathiText.t("saathi_report_title_short", "Report"),
                onCancel: { request.wrappedValue = nil }
            ) {
                SaathiReportSheet(
                    request: item,
                    send: send,
                    onDone: { request.wrappedValue = nil }
                )
            }
        }
    }
}