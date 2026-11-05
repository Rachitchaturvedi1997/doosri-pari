// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriSosButton` — plan section 4.4: "In the top toolbar, red, labelled
// 'SOS' (never an icon alone). Opens the hold-and-countdown screen."
//
// The toolbar form lives in `DoosriToolbarAction.sos`; this is the same
// control for everywhere else one is needed — Simple mode's six huge buttons
// (plan section 6.16), the check-in screen's fallback, the countdown screen's
// own call to 112.
//
// Why the rules, and they are the sharpest in the app:
//
//   - **LABELLED "SOS". Never an icon alone.** A red exclamation mark with no
//     word is the pattern this app must not reproduce. Three reasons and each
//     is sufficient: a person who cannot distinguish red from the background
//     still has the word; a person who cannot read Latin still has the word
//     "SOS", which is one of the few strings carried across every one of the
//     ten languages unchanged; and a person new to smartphones has no idea
//     what a triangle means until it is written down. The icon is decoration
//     that happens to point the same way as the text. Neither is load-bearing
//     alone; together they are unmissable.
//
//   - **`safety` red is earned here.** Plan section 4.3: semantic colours are
//     never decoration. `safety` is the SOS button and a missed check-in,
//     full stop. The reason is not tidiness: the person who learns that red
//     sometimes means "brand accent" is the pensioner who ignores this button,
//     and this is the one control in the app that must never be ignored.
//
//   - **Colour is never the only signal (plan section 6.16).** Red/green is
//     the one pair a colour-blind user cannot rely on. The word "SOS" and the
//     warning triangle carry the meaning in greyscale; the red only shouts it.
//
//   - **A tap does something visible, always.** Plan section 6.3 says the
//     emergency flow is a press-and-hold *on the countdown screen* with a 5
//     second cancel. The hold does not live on this button: a control that
//     silently does nothing until it is held for two seconds is invisible to
//     anybody who has not been told, and the first tap must always respond.
//     This button opens the countdown; the countdown has the Cancel button.
//
//   - **Strong haptic on press.** Somebody with tremor presses this and is
//     not sure it fired. The feel under the finger is the last signal before
//     a phone call to a family member.

import SwiftUI

struct DoosriSosButton: View {

    /// Opens the full-screen countdown (plan section 6.3). The countdown — not
    /// this button — is where the 5-second Cancel lives.
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(action: @escaping () -> Void) {
        self.action = action
    }

    var body: some View {
        Button {
            DoosriHaptics.impact(.strong)
            action()
        } label: {
            HStack(spacing: DoosriSpace.md) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 24, weight: .bold))
                    .doosriDecorativeIcon()
                // The word. Not a symbol, not an initialism the user has to
                // decode — the three letters every phone user on earth knows.
                Text(String(localized: "sos_button", defaultValue: "SOS"))
                    .font(.doosriButton)
            }
            .foregroundStyle(DoosriPalette.background(scheme))
            .frame(maxWidth: .infinity)
            .frame(minHeight: DoosriSize.primaryButtonHeight)
            .padding(.horizontal, DoosriSpace.lg)
            .background(
                RoundedRectangle(cornerRadius: DoosriSize.buttonRadius)
                    .fill(DoosriPalette.safety(scheme))
            )
        }
        .buttonStyle(.plain)
        .doosriButtonSemantics(
            // "SOS" alone tells a first-time listener they have found a
            // control and nothing about what it does. "Emergency help. SOS."
            // can be acted on without seeing the screen (plan 6.16: labels are
            // written for a screen reader, not for a developer).
            label: String(
                localized: "sos_button_description",
                defaultValue: "Emergency help. SOS."
            ),
            hint: String(
                localized: "sos_button_hint",
                defaultValue: "Opens the emergency screen. You will have time to cancel."
            )
        )
    }
}