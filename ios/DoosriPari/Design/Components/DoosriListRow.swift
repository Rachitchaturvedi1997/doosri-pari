// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriListRow` — plan section 4.4: "At least 64 pt tall: icon in a tinted
// rounded square, title, optional value, chevron. The whole row is tappable."
//
// Why each part of that:
//
//   - **64 pt minimum.** This row is how a pensioner reaches Pension Sahayak,
//     the document safe, the medicine list. It is navigation, and navigation
//     they cannot hit is navigation they do not find. 64 is well past Apple's
//     44 floor and is the plan's figure (sections 4.1 and 4.4).
//
//   - **Icon in a tinted rounded square.** Not a bare glyph on the row: at
//     20 pt body text an untethered symbol floats between the margin and the
//     words, and the square gives every row the same left edge so the titles
//     line up into a column the eye can run down. The tile is tinted through
//     `DoosriTint`, which deliberately has no red and no green — see
//     `DoosriSemanticColour.swift`. A settings list in which one row is red is
//     a settings list that says something is wrong when nothing is.
//
//   - **The whole row tappable, not the chevron.** The chevron is a signpost,
//     not a control. People tap the words, because the words are what they
//     want; a tap on the words that does nothing teaches them the app is
//     broken.
//
//   - **Icon, title and chevron all present.** Plan section 4.3: every icon
//     has a text label beside it and there are no icon-only controls. The
//     chevron is the one glyph here with no word, which is why it is hidden
//     from VoiceOver and why the row announces as a button with a hint instead.

import SwiftUI

struct DoosriListRow: View {

    /// SF Symbol name. Always accompanied by the title beside it.
    let icon: String

    /// What this row is. The words are the control; the icon is the signpost.
    let title: String

    /// The state that is not in the title — "8:12", "3", "On". Shown on the
    /// right, and passed to VoiceOver as the value rather than as part of the
    /// label, so a listener hears "Check-in window, 9:00" rather than one long
    /// run-on.
    var value: String?

    /// The tile tint. See `DoosriTint` for the three colours allowed here.
    var tint: DoosriTint = .neutral

    /// Nil for a row that only displays — a summary line inside a card. A row
    /// with an action gets the chevron and button semantics; one without gets
    /// neither, because a row that announces "button" and does nothing is how
    /// a pensioner learns not to trust the screen.
    var action: (() -> Void)?

    @Environment(\.colorScheme) private var scheme

    init(
        icon: String,
        title: String,
        value: String? = nil,
        tint: DoosriTint = .neutral,
        action: (() -> Void)? = nil
    ) {
        self.icon = icon
        self.title = title
        self.value = value
        self.tint = tint
        self.action = action
    }

    var body: some View {
        if let action {
            Button {
                DoosriHaptics.impact(.light)
                action()
            } label: {
                row
            }
            .buttonStyle(.plain)
            .doosriButtonSemantics(
                label: title,
                value: value,
                hint: String(
                    localized: "list_row_hint",
                    defaultValue: "Opens this item."
                )
            )
        } else {
            row
                // Display-only rows still need to read as one sentence, and
                // the glyph still must not be read aloud beside its own label.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(title)
                .accessibilityValue(value ?? "")
        }
    }

    private var row: some View {
        HStack(spacing: DoosriSpace.md) {
            iconTile
            Text(title)
                .font(.doosriBody)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: DoosriSpace.sm)
            if let value {
                Text(value)
                    .font(.doosriCallout)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .multilineTextAlignment(.trailing)
            }
            if action != nil {
                Image(systemName: "chevron.right")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                    .doosriDecorativeIcon()
            }
        }
        .frame(minHeight: DoosriSize.rowHeight)
        .contentShape(Rectangle())
    }

    /// The tinted rounded square behind the glyph. 40 pt is big enough for a
    /// 22 pt symbol to sit in without the tile starting to look like a
    /// button of its own — it is a signpost, and the row is the control.
    private var iconTile: some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(tint.wash(scheme))
            .frame(width: 40, height: 40)
            .overlay(
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(tint.resolve(scheme))
            )
            .doosriDecorativeIcon()
    }
}