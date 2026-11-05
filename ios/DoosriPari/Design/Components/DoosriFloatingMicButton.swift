// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriFloatingMicButton` — plan section 4.4: "A floating round button,
// 64 pt, above the tab bar, labelled 'Bolo'. Glass material on iOS 26, tonal
// surface on Android."
//
// Why this button exists at all, and why it is not a tab: plan section 4.1
// quotes Apple — "Use a tab bar to support navigation, not to provide
// actions" — so the mic is an *action* (speak a request) floating above the
// navigation layer, the same as SOS lives in the toolbar. The four tabs stay
// navigation; "Bolo" is what the user does.
//
// Why 64 pt and round: it is the plan's main-action figure (section 4.1's
// "extra large"), it floats over content so it must be a shape that reads as
// separate from the text beneath it, and it is pressed by people who are
// holding the phone one-handed and speaking — the thumb finds a circle faster
// than a capsule because it has no orientation.
//
// Why it carries the word "Bolo": plan section 4.3 — every icon has a text
// label beside it, no icon-only controls. A microphone glyph alone is
// understood by people who have used voice search before and by nobody else;
// this audience did not grow up with a microphone icon meaning "speak now".
// The word is in the user's own language in nine of the ten the app ships.
//
// Why glass only here and on the navigation layer: plan section 4.1 —
// "Glass only on the floating navigation layer (tab bar, toolbars, the mic
// button). All content stays on solid, high-contrast surfaces." A frosted
// content card is a contrast bug at 20 pt. And Reduce Transparency is
// respected — see the background builder below: the system's material is the
// wrong answer for a user who has asked for less of it.
//
// Accessibility (plan section 6.16): 64 pt target; the label says what the
// button *is* and the hint what will happen; the glyph is hidden from
// VoiceOver so the word is not read twice.

import SwiftUI

struct DoosriFloatingMicButton: View {

    /// Opens the voice flow. Plan section 5 is firm that the voice layer never
    /// posts, calls, pays or sends an SOS without a visible confirmation — so
    /// this action opens the flow, it never *is* the action.
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    init(action: @escaping () -> Void) {
        self.action = action
    }

    var body: some View {
        Button {
            DoosriHaptics.impact(.medium)
            action()
        } label: {
            VStack(spacing: 2) {
                Image(systemName: "mic.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .doosriDecorativeIcon()
                Text(String(localized: "bolo_button", defaultValue: "Bolo"))
                    .font(.doosriCaption)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(DoosriPalette.accent(scheme))
            .frame(width: DoosriSize.micButtonSize, height: DoosriSize.micButtonSize)
            .background(background)
            .overlay(
                // A hairline so the circle has an edge over busy content in
                // both schemes; the glass alone loses its rim in light mode.
                Circle().strokeBorder(DoosriPalette.divider(scheme), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .doosriButtonSemantics(
            label: String(
                localized: "bolo_button_description",
                defaultValue: "Bolo. Speak to the app."
            ),
            hint: String(
                localized: "bolo_button_hint",
                defaultValue: "Opens the voice screen. Hold the button and speak."
            )
        )
    }

    /// Liquid Glass on iOS 26; a system material below it; and a solid surface
    /// whenever the user has asked for Reduce Transparency — on any version.
    ///
    /// The `glassEffect` signature is the most likely line in this file to
    /// need a small fix at first compile (see the note in `DoosriMetrics.swift`
    /// about APIs that have never been through a compiler here). If it does,
    /// the `.ultraThinMaterial` branch is the working fallback and the shape
    /// of the rule does not change: glass over content, solid for accessibility.
    @ViewBuilder
    private var background: some View {
        if reduceTransparency {
            // Plan section 6.16: Reduce Transparency is respected. A solid
            // surface keeps the 7:1 contrast the plan aims at (section 4.1).
            Circle().fill(DoosriPalette.surface(scheme))
        } else if #available(iOS 26.0, *) {
            // Glass, the plan's iOS 26 look for the floating navigation layer.
            Circle()
                .fill(.ultraThinMaterial)
                .glassEffect(in: Circle())
        } else {
            Circle().fill(.ultraThinMaterial)
        }
    }
}