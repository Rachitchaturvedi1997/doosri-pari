// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriCard` — plan section 4.4: "Surface colour, 14 pt radius, 20 pt
// padding, one title, at most 3 lines of text, one action."
//
// Why the rule is that strict, and not "roughly like this": the card is the
// unit the whole Today screen is built out of (plan section 6.2 lists ten of
// them in a column), and the plan's design principle 1 is "one focus per
// screen". A card that carries two titles is two places to look; a card with a
// paragraph in it is a wall of text on a 4.7-inch screen at the largest text
// size; a card with two actions is a decision the reader did not come for. The
// plan's answer to "I want one more line" is another card or a detail screen,
// not a taller card.
//
// The other reason is reading order. Ten cards in a column at 20 pt body is
// already a lot for a seventy-one-year-old reading at arm's length. The
// three-line ceiling is what keeps a glance of the screen a glance rather than
// a reading session.
//
// Dark mode: the surface is resolved through `DoosriPalette.surface(_:)` like
// everything else. A card that hard-codes `.white` is the bug that makes half
// the app unreadable at night, and night is when a lot of this app is read.

import SwiftUI

struct DoosriCard<Content: View>: View {

    /// The one title. Nil for a card whose content is self-labelling (a photo
    /// card with a caption), but never pass two — split the card instead.
    let title: String?

    /// The whole card is tappable when this is set, which is what plan section
    /// 4.4 means by "one action". A card that is not tappable passes nil and
    /// gets no chevron and no button semantics, because a card that announces
    /// itself as a button and then does nothing is how a pensioner learns to
    /// distrust the screen.
    let action: (() -> Void)?

    @ViewBuilder var content: Content

    @Environment(\.colorScheme) private var scheme

    init(
        title: String? = nil,
        action: (() -> Void)? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.action = action
        self.content = content()
    }

    var body: some View {
        if let action {
            // The tap target is the whole card, not the chevron: plan section
            // 4.4 says the row is tappable end to end, and a 44 pt target
            // inside a 120 pt card wastes the card.
            Button {
                DoosriHaptics.impact(.light)
                action()
            } label: {
                card
            }
            .buttonStyle(.plain)
            // One VoiceOver element: title, content and chevron read as one
            // sentence instead of three fragments. `isButton` is added because
            // `.buttonStyle(.plain)` drops it on some of these and Switch
            // Control navigates by control type.
            .doosriButtonSemantics(
                label: title ?? "",
                hint: String(
                    localized: "card_opens_hint",
                    defaultValue: "Opens this item."
                )
            )
        } else {
            card
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.md) {
            if let title {
                Text(title)
                    .font(.doosriHeadline)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                    .fixedSize(horizontal: false, vertical: true)
            }
            content
        }
        // Plan section 4.4: 20 pt padding on every side.
        .padding(DoosriSpace.xl)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            // 14 pt radius, plan section 4.3. Not a capsule: a capsule on a
            // text card clips the last line of Devanagari at the corners.
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
    }
}

extension View {

    /// The card body style: 20 pt body text at 1.4x line height (plan section
    /// 4.3), capped at **three lines** (plan section 4.4).
    ///
    /// The cap lives in a modifier rather than in prose so that "at most three
    /// lines" is one thing to apply rather than one thing to remember. If a
    /// body needs four lines, the fix is a shorter body or a detail screen —
    /// not `lineLimit(4)`.
    ///
    /// `multilineTextAlignment(.leading)` is explicit because the label stacks
    /// can centre under some parent alignments, and Hindi and Tamil read
    /// ragged-right in this app like every other left-to-right Indic script
    /// here.
    func doosriCardBody() -> some View {
        self
            .font(.doosriBody)
            .doosriBodyLineHeight()
            .lineLimit(3)
            .multilineTextAlignment(.leading)
    }
}