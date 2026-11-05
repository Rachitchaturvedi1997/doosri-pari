// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriSecondaryButton` — plan section 4.4: "56 pt tall, outlined or
// tinted, label always visible."
//
// Why it exists at 56 rather than at the platform's 44: 44 is Apple's floor
// and plan section 4.1 sets this app's floor at 56 for any control. The
// secondary button is still a control a pensioner has to hit, and "it is only
// the secondary action" has never made anybody's thumb steadier.
//
// Why outlined rather than filled: the accent fill is the loudest thing on the
// screen and plan section 4.2 allows exactly one accent per screen. Two filled
// buttons are two main actions; one filled and one outlined is a main action
// and a way out, which is the choice the reader actually has.
//
// Why the label is always visible and never a lone glyph: plan section 4.3 —
// every icon has a text label beside it, there are no icon-only controls. The
// same audience that misses a 44 pt target cannot be asked to decode an
// ellipsis in a circle.

import SwiftUI

struct DoosriSecondaryButton: View {

    /// The label. Always drawn; never replaced by an icon.
    let text: String

    /// Optional SF Symbol on the left (plan section 4.4 puts icons on the left
    /// of labels). It is decoration beside the word, and hidden from VoiceOver
    /// so the word is not read twice.
    var icon: String?

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
            // Light, not medium: this is "a row was tapped" energy, not the
            // screen's main action. The vibration vocabulary is small on
            // purpose (DoosriHaptics.swift).
            DoosriHaptics.impact(.light)
            action()
        } label: {
            HStack(spacing: DoosriSpace.md) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 22, weight: .semibold))
                        .doosriDecorativeIcon()
                }
                Text(text)
                    .font(.doosriButton)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(DoosriPalette.accent(scheme))
            .frame(maxWidth: .infinity)
            .frame(minHeight: DoosriSize.minControlHeight)
            .padding(.horizontal, DoosriSpace.lg)
            .background(
                RoundedRectangle(cornerRadius: DoosriSize.buttonRadius)
                    .strokeBorder(
                        DoosriPalette.accent(scheme),
                        lineWidth: 2
                    )
            )
            .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .doosriButtonSemantics(label: text, hint: isEnabled ? nil : String(
            localized: "button_disabled_hint",
            defaultValue: "Not available yet."
        ))
    }
}