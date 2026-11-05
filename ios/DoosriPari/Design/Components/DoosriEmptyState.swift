// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriEmptyState` — plan section 4.4: "One line saying what goes here, one
// button to add the first item."
//
// Why the rule is that specific. A blank list is ambiguous, and ambiguity on a
// phone is read as breakage: "Medicines" with nothing under it can mean the
// app lost them, the sync failed, or the user has none yet, and the only one
// of those a pensioner should have to think about is the last one. One line
// that says what goes here ("Your medicines will appear here") settles it in
// the user's own language, and one button to add the first item is the whole
// remaining question.
//
// Why exactly one line and one button (plan sections 3 and 4.2: one focus per
// screen, progressive disclosure): a tutorial in the empty state is a wall of
// text in the one place the user has already understood. The button is the
// next step; the line is the reassurance.
//
// The action button is **secondary** by default. This component often sits
// inside a screen that already has its one primary button (plan section 4.4:
// at most one primary per screen), and a second filled accent button would
// make the screen a choice between two things when it is not. When the empty
// state *is* the whole screen — a first run of the document safe — pass
// `prominent: true` and it becomes the screen's one primary action.

import SwiftUI

struct DoosriEmptyState: View {

    /// One line saying what goes here. Written as the promise, not as the
    /// absence — "Your medicines will appear here", not "No medicines".
    let message: String

    /// The verb that adds the first item — "Add a medicine". Nil for a screen
    /// with nothing to add yet.
    var actionTitle: String?

    /// Secondary by default; `prominent: true` renders `DoosriPrimaryButton`
    /// instead, for the case where this is the screen's one main action.
    var prominent: Bool = false

    var action: (() -> Void)?

    @Environment(\.colorScheme) private var scheme

    init(
        message: String,
        actionTitle: String? = nil,
        prominent: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.message = message
        self.actionTitle = actionTitle
        self.prominent = prominent
        self.action = action
    }

    var body: some View {
        VStack(spacing: DoosriSpace.lg) {
            // The glyph is decoration; the sentence beside it is the label —
            // plan section 4.3 pairs every icon with text, and here the text
            // is the whole point.
            Image(systemName: "tray")
                .font(.system(size: 32))
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .doosriDecorativeIcon()

            Text(message)
                .font(.doosriBody)
                .doosriBodyLineHeight()
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            if let actionTitle, let action {
                if prominent {
                    DoosriPrimaryButton(text: actionTitle, icon: "plus", action: action)
                } else {
                    DoosriSecondaryButton(text: actionTitle, icon: "plus", action: action)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, DoosriSpace.xxl)
        .padding(.horizontal, DoosriSpace.lg)
    }
}