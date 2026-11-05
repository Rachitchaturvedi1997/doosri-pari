// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriPrimaryButton` — plan section 4.4: "Full width, 64 pt tall, accent
// fill, 20 pt semibold label, icon on the left. One per screen."
//
// Why the rules are written into the shape of the type rather than left to the
// screen author:
//
//   - **Full width, 64 pt.** Plan section 4.1 quotes Apple's button sizes
//     (mini 28 … extra large 64) and then sets the app's floor at 56 with 64
//     for main actions. This audience includes people with tremor and people
//     reading at arm's length in daylight; a 44 pt pill in the middle of a
//     screen is a target they miss, and a miss on "Send SOS" is not a small
//     thing. The height is not optional here for the same reason — a screen
//     that wants a smaller action wants `DoosriSecondaryButton`.
//
//   - **Icon on the left.** Plan section 4.4. It is a reading-order rule: in
//     every language this app ships, the eye lands on the glyph and then reads
//     the word, and an icon on the right makes the label look like it belongs
//     to whatever came before.
//
//   - **At most one per screen.** Plan section 3: "One main action per screen."
//     Two filled accent buttons side by side is a question, not an action. The
//     compiler cannot count buttons on a screen, so this is the rule that has
//     to survive code review — which is why it is stated here, where every
//     screen author's eyes pass it.
//
//   - **Haptics.** The medium impact on tap, because plan section 4.4's users
//     do not always see the screen change (see `DoosriHaptics.swift`): a tap
//     they can feel is the confirmation that the press landed.
//
//   - **Accessibility (plan section 6.16).** 64 pt is the target in every
//     state, including disabled — a disabled button does not get harder to
//     find, it gets announced as dimmed. The label is the sentence the
//     VoiceOver user hears; the icon is decoration and is hidden from the
//     reader because the word beside it is its name.

import SwiftUI

struct DoosriPrimaryButton: View {

    /// The label. A verb or a phrase naming the outcome — "Open the
    /// calculator", "Save", "Send SOS". This is what VoiceOver reads.
    let text: String

    /// An SF Symbol drawn to the left of the label. Optional, and never a
    /// substitute for it: plan section 4.3 says every icon has a text label
    /// next to it and there are no icon-only controls in this app.
    var icon: String?

    /// False dims the button but keeps it 64 pt tall and keeps its place. A
    /// button that disappears when disabled is a layout that jumps, and a
    /// jumping layout at large text sizes is a lost reader.
    var isEnabled: Bool = true

    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(
        text: String,
        icon: String? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.text = text
        self.icon = icon
        self.isEnabled = enabled
        self.action = action
    }

    var body: some View {
        Button {
            // The main action of a screen gets the medium impact: "you did the
            // thing". Disabled taps do not fire the action and do not buzz —
            // a buzz for nothing is a lie.
            DoosriHaptics.impact(.medium)
            action()
        } label: {
            HStack(spacing: DoosriSpace.md) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 24, weight: .semibold))
                        // The word beside it names it; reading both says the
                        // same thing twice (see DoosriAccessibility.swift).
                        .doosriDecorativeIcon()
                }
                Text(text)
                    .font(.doosriButton)
                    .multilineTextAlignment(.center)
            }
            // On the accent fill the text is the background colour: the
            // palette is a pair per scheme (deep teal with near-white in
            // light, pale teal with near-black in dark), so this reads at the
            // plan's 7:1 in both.
            .foregroundStyle(DoosriPalette.background(scheme))
            .frame(maxWidth: .infinity)
            .frame(minHeight: DoosriSize.primaryButtonHeight)
            .padding(.horizontal, DoosriSpace.lg)
            .background(
                // Plan section 4.3: radius 32 for large buttons.
                RoundedRectangle(cornerRadius: DoosriSize.buttonRadius)
                    .fill(DoosriPalette.accent(scheme))
            )
            .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .doosriButtonSemantics(
            label: text,
            // A disabled control is skipped by Switch Control entirely, so the
            // reason is worth a sentence when the caller has one. Passing nil
            // here keeps the hint empty rather than padding it (plan 6.16:
            // labels are written for a screen reader, not for a developer).
            hint: isEnabled ? nil : String(
                localized: "button_disabled_hint",
                defaultValue: "Not available yet."
            )
        )
    }
}