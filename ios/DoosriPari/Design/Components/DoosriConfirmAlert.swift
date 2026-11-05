// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriConfirmAlert` — plan section 4.4: "Alerts are only for irreversible
// actions: 'Send SOS now?', 'Delete this document?'. Buttons use verbs
// ('Send SOS', 'Keep'), never OK/Cancel alone."
//
// Two rules, both with teeth:
//
//   1. **Only for irreversible or outward actions** (plan section 3:
//      "a confirmation before anything irreversible or outward: posting,
//      calling, paying, SOS"). An alert on every save is an alert people learn
//      to dismiss without reading, and the day the alert says "Send SOS now?"
//      is the day that habit costs something. Navigation, scrolling, opening
//      things — never an alert.
//
//   2. **Verb buttons. Never "OK" / "Cancel" alone.** This is the rule the
//      component refuses to let you break: `confirmTitle` and `cancelTitle` are
//      both required and neither may be "OK". Why: an "OK" button confirms
//      nothing. It is what people press to make a dialog go away, so it turns
//      every irreversible action into a reflex. A verb names the outcome —
//      "Send SOS" and "Keep", "Delete" and "Keep this document" — so the tap
//      is a choice the user can make in the half-second they give it. On a
//      screen read at 20 pt by somebody deciding whether strangers are about
//      to be telephoned, the word on the button is the whole decision.
//      The DEBUG assert below is the compiler's stand-in for the reviewer.
//
// The safe option is the one that changes nothing, and it is listed second so
// it is nearest the thumb on the right of the stack — but both buttons say
// what they do, so neither can be pressed by mistake.

import SwiftUI

/// Usage: attach it to the screen, read-only and invisible:
///
///     .background {
///         DoosriConfirmAlert(
///             isPresented: $confirmingSOS,
///             title: "Send SOS now?",
///             message: "Your family and helpers will be called.",
///             confirmTitle: "Send SOS",
///             cancelTitle: "Keep",
///             isDestructive: true,
///             onConfirm: { model.sendSOS() }
///         )
///     }
struct DoosriConfirmAlert: View {

    let isPresented: Binding<Bool>

    /// The question. Phrased as one, in the user's terms: "Send SOS now?"
    let title: String

    /// What will happen, one or two sentences. Say who is affected — the
    /// plan's users are deciding on behalf of their families.
    let message: String?

    /// A **verb naming the outcome**. "Send SOS", "Delete this document",
    /// "Post to the circle". Not "OK", not "Yes".
    let confirmTitle: String

    /// The outcome that changes nothing, also spelled as an outcome:
    /// "Keep", "Keep this document", "Don't send". Not "Cancel" alone — the
    /// point of the rule is that both buttons carry their meaning on their
    /// faces.
    let cancelTitle: String

    /// Red for the destructive role. Only where the action is irreversible;
    /// `safety` red is not decoration (plan section 4.3), and the system
    /// destructive role is the same colour speaking the same sentence.
    var isDestructive: Bool = false

    var onCancel: (() -> Void)?

    let onConfirm: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(
        isPresented: Binding<Bool>,
        title: String,
        message: String? = nil,
        confirmTitle: String,
        cancelTitle: String,
        isDestructive: Bool = false,
        onCancel: (() -> Void)? = nil,
        onConfirm: @escaping () -> Void
    ) {
        self.isPresented = isPresented
        self.title = title
        self.message = message
        self.confirmTitle = confirmTitle
        self.cancelTitle = cancelTitle
        self.isDestructive = isDestructive
        self.onCancel = onCancel
        self.onConfirm = onConfirm

        #if DEBUG
        // The two words plan section 4.4 bans. "Yes" and "No" are banned too:
        // they are questions answered, not outcomes chosen, and they put the
        // meaning of the button in the title where it can be scrolled off.
        let banned = ["ok", "yes", "no"]
        assert(
            !banned.contains(confirmTitle.trimmingCharacters(in: .whitespaces).lowercased()),
            "Doosri Pari alerts name their outcomes (plan 4.4). "
                + "\"\(confirmTitle)\" confirms nothing — try a verb like \"Send SOS\".",
            file: #filePath,
            line: #line
        )
        assert(
            !banned.contains(cancelTitle.trimmingCharacters(in: .whitespaces).lowercased()),
            "Doosri Pari alert buttons use verbs (plan 4.4). "
                + "\"\(cancelTitle)\" says nothing about the outcome — try \"Keep\".",
            file: #filePath,
            line: #line
        )
        #endif
    }

    /// Zero-size: the alert itself is the system's, and this view exists so
    /// that one component owns the rule rather than every screen re-deciding
    /// what its buttons are called.
    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .alert(title, isPresented: isPresented) {
                Button(confirmTitle, role: isDestructive ? .destructive : .default) {
                    // Strong: this is the one tap in the flow that cannot be
                    // taken back, and the user may be making it without
                    // looking (DoosriHaptics.swift).
                    DoosriHaptics.impact(.strong)
                    onConfirm()
                }
                Button(cancelTitle, role: .cancel) {
                    onCancel?()
                }
            } message: {
                if let message {
                    Text(message)
                }
            }
    }
}