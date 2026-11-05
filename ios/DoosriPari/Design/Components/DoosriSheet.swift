// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriSheet` — plan section 4.4: "For short tasks (a reminder, a reaction).
// Grabber visible, a Cancel button as well as swipe-down, medium height first."
//
// Why both exits. Plan section 3: "Nothing hidden. No swipe-only or long-press-
// only actions." Swipe-to-dismiss is the native habit and must keep working —
// a sheet that cannot be pulled down is a sheet people get stuck in, and a
// pensioner who feels stuck in a screen closes the whole app. But swipe is
// *invisible*: there is nothing on the screen that says the sheet will move.
// So the Cancel button is drawn on purpose, in words, at 44 pt, and the grabber
// is drawn on purpose too rather than trusted to the system's — a sheet with
// `.large` detents does not always get one, and "the grabber is visible" is a
// rule in the plan, not a hope.
//
// Why "medium height first": a sheet that covers the screen is not a sheet, it
// is a screen without a back button. `.medium` leaves the user looking at what
// they were doing, which is the difference between "I am adding a reminder"
// and "where am I now". The caller may still pull it up to `.large`.
//
// Cancel is spelled out as a verb-button here even though plan section 4.4's
// "verbs, never OK/Cancel alone" rule is about *alerts*. The difference is the
// stakes: an alert guards an irreversible act and its buttons must name the
// outcomes ("Send SOS", "Keep"); a sheet is a short task and its exit is an
// exit. What is never allowed is a sheet that can only be escaped by a gesture.

import SwiftUI

struct DoosriSheet<Content: View>: View {

    /// What this short task is — "New reminder", "React to this post".
    let title: String

    /// Closes the sheet without doing anything. Wired to the same state as the
    /// swipe-down so the two exits cannot disagree.
    let onCancel: () -> Void

    @ViewBuilder var content: Content

    @Environment(\.colorScheme) private var scheme

    init(
        title: String,
        onCancel: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.onCancel = onCancel
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.lg) {
            grabber
            header
            content
            Spacer(minLength: 0)
        }
        .padding(.horizontal, DoosriSpace.screenMargin)
        .padding(.top, DoosriSpace.sm)
        .padding(.bottom, DoosriSpace.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(DoosriPalette.surface(scheme))
        // Medium first, per plan section 4.4. Applied here rather than at the
        // call site so every sheet in the app starts the same size.
        .presentationDetents([.medium, .large])
        .doosriTextUpperLimit()
    }

    /// The grabber, drawn rather than assumed. 36x5 is the system's own
    /// proportion; it is hidden from VoiceOver because the Cancel button is
    /// the spoken way out and a "drag handle" announcement teaches nobody
    /// anything.
    private var grabber: some View {
        Capsule()
            .fill(DoosriPalette.divider(scheme))
            .frame(width: 36, height: 5)
            .frame(maxWidth: .infinity)
            .doosriDecorativeIcon()
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.md) {
            // Cancel is on the leading edge, where the plan's wireframes put
            // the left-hand escape (section 4.5's yoga player's "✕" is the
            // same idea) and where a reader of left-to-right languages meets
            // it first.
            Button {
                DoosriHaptics.impact(.light)
                onCancel()
            } label: {
                Text(String(localized: "sheet_cancel", defaultValue: "Cancel"))
                    .font(.doosriButton)
                    .foregroundStyle(DoosriPalette.accent(scheme))
                    .frame(minHeight: DoosriTarget.minimumHitTarget)
                    .padding(.horizontal, DoosriSpace.xs)
            }
            .buttonStyle(.plain)
            .doosriButtonSemantics(
                label: String(localized: "sheet_cancel", defaultValue: "Cancel"),
                hint: String(
                    localized: "sheet_cancel_hint",
                    defaultValue: "Closes this without saving."
                )
            )

            Spacer(minLength: DoosriSpace.sm)

            Text(title)
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .multilineTextAlignment(.trailing)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}