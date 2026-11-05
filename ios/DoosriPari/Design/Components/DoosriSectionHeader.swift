// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriSectionHeader` — the "Pension", "Today's practice", "From your
// circles" lines that break the Today screen into its ten cards (plan section
// 6.2), and the section titles everywhere else.
//
// Why it is its own component rather than a `Text` in each screen: the type
// scale (plan section 4.3) gives section titles 28 pt Bold and the plan is
// explicit that the scale is fixed — "The six fonts in DoosriMetrics are the
// whole scale. A seventh size is a decision, not an accident." Left to each
// screen, one section header grows to 30 pt and another shrinks to 24 to make
// room, and the app stops having a hierarchy. One component, one size, and the
// hierarchy is what the plan drew.
//
// Why the optional action is a **labelled text button** and not a chevron:
// plan section 4.3 — every icon has a text label beside it, and there are no
// icon-only controls anywhere in this app. "See all" costs three words and
// saves a pensioner from having to guess what a bare arrow wants. The target
// is at least 44 pt (plan section 6.16) even though the text is small, because
// the thumb does not know the label is a caption.
//
// The 28 pt title still grows with Dynamic Type — `Font.doosriTitle` is
// anchored to a system text style — and still never goes below the app's 16 pt
// floor in any of its smaller siblings. No light or thin weights appear in the
// scale at all (plan section 4.3): at 16 pt in a Devanagari face a light weight
// loses the matras, and the matras are what make the word readable.

import SwiftUI

struct DoosriSectionHeader: View {

    /// The section's name. Short: it is a heading, not a sentence.
    let title: String

    /// The words on the optional action — "See all", "History". Always a
    /// phrase; never a lone glyph.
    var actionTitle: String?

    var action: (() -> Void)?

    @Environment(\.colorScheme) private var scheme

    init(
        title: String,
        actionTitle: String? = nil,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.actionTitle = actionTitle
        self.action = action
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DoosriSpace.md) {
            Text(title)
                .font(.doosriTitle)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: DoosriSpace.sm)

            if let actionTitle, let action {
                Button {
                    DoosriHaptics.impact(.light)
                    action()
                } label: {
                    Text(actionTitle)
                        .font(.doosriCallout)
                        .foregroundStyle(DoosriPalette.accent(scheme))
                        // The label is callout-sized but the target is the
                        // 44 pt floor (plan section 6.16).
                        .frame(minHeight: DoosriTarget.minimumHitTarget)
                        .padding(.horizontal, DoosriSpace.xs)
                }
                .buttonStyle(.plain)
                .doosriButtonSemantics(
                    label: actionTitle,
                    hint: String(
                        localized: "see_all_hint",
                        defaultValue: "Shows everything in this section."
                    )
                )
            }
        }
        .frame(minHeight: DoosriTarget.minimumHitTarget)
    }
}