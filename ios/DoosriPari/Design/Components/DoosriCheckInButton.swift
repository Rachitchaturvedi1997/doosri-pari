// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriCheckInButton` — plan section 4.4: "96 pt tall, green, 'Main theek
// hoon · I'm fine'. Once pressed, 'Checked in at 8:12' with a tick."
//
// This is the one control in the app that a family's peace of mind hangs on,
// and it is the reason the whole design is as large and plain as it is. Savitri
// Devi, 71, in Patna, presses it every morning; her daughter in Pune gets a
// quiet notification. Everything about its shape comes from that pair:
//
//   - **96 pt.** Twice the app's 44 floor and half again the 64 main-action
//     figure (plan section 4.4 gives it its own number). It is the largest
//     control in the app because it is the one pressed by the least able user
//     at the least attentive moment — first thing in the morning, often before
//     glasses, sometimes with tremor. A press that misses is not an
//     inconvenience here: the window closes, an automated call comes, then
//     guardians are woken.
//
//   - **Green is allowed here and almost nowhere else.** Plan section 4.3 is
//     explicit that `safety` and `ok` are never decoration. This button is one
//     of the three places `ok` earns its meaning — "I'm fine" is the sentence
//     the green was minted for (the others: a check-in that landed, and
//     nothing else). If green also meant "nice" or "selected", the green here
//     would mean nothing, and the one colour her daughter can screenshot and
//     understand would stop working.
//
//   - **Never colour alone (plan section 6.16).** `ok` green and `safety` red
//     are the one pair a colour-blind user cannot rely on — and they are the
//     pair this app leans on hardest, because they are the difference between
//     "your mother is fine" and "somebody has been called". So the done state
//     carries a tick glyph and the words "Checked in at 8:12" as well as the
//     colour. Greyscale, sunlight, a projector, any form of colour blindness:
//     the state is still readable.
//
//   - **Haptics.** The strong impact on press. She may not be looking at the
//     screen when she presses it; the tap she can feel under her thumb is what
//     answers "did that go through?" — and the tick only helps once her eyes
//     find it.

import SwiftUI

struct DoosriCheckInButton: View {

    /// Nil while today is still open. The label the *screen* wants on the done
    /// state — typically "Checked in at 8:12", already localised and already
    /// carrying the time, because the format of a time is the caller's
    /// language's business, not this component's.
    ///
    /// Non-nil means done: the tick appears and the button stops taking taps.
    /// The state is deliberately carried by this one optional String rather
    /// than by a `Bool` plus a label, so there is no construction in which the
    /// button is green, ticked and still asking to be pressed.
    let checkedAtLabel: String?

    /// What "I'm fine" does. The network call lives in the screen's model;
    /// this component's job is to be the thing that is impossible to miss.
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    init(checkedAtLabel: String? = nil, action: @escaping () -> Void) {
        self.checkedAtLabel = checkedAtLabel
        self.action = action
    }

    private var isDone: Bool { checkedAtLabel != nil }

    var body: some View {
        Button {
            DoosriHaptics.impact(.strong)
            action()
        } label: {
            HStack(spacing: DoosriSpace.md) {
                if isDone {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 40))
                        // The tick is the state for anyone who cannot see the
                        // green; it is in the spoken label below as "Done.",
                        // so it is hidden here to avoid saying it twice.
                        .doosriDecorativeIcon()
                }
                Text(checkedAtLabel ?? String(
                    localized: "check_in_button",
                    defaultValue: "Main theek hoon · I'm fine"
                ))
                .font(.doosriBody)
                // The English is the shortest of the ten languages. Hindi and
                // Tamil wrap, and truncating either would cut the meaning off
                // the one sentence that must not be cut off — so the label is
                // allowed its two lines and the button grows rather than the
                // text shrinking. Nothing in this app is below 16 pt.
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: DoosriSize.checkInButtonHeight)
            .padding(.horizontal, DoosriSpace.lg)
            .background(
                // Capsule, like the plan's wireframe (section 4.5). `ok`
                // green, per the rule above — this is "I'm fine", the use the
                // colour exists for.
                Capsule().fill(DoosriPalette.ok(scheme))
            )
            // Background colour as the label: the palette is a per-scheme pair
            // (near-white on deep green, near-black on light green), so the
            // label clears 7:1 in both schemes.
            .foregroundStyle(DoosriPalette.background(scheme))
            .opacity(isDone ? 0.85 : 1)
        }
        .buttonStyle(.plain)
        .disabled(isDone)
        .doosriButtonSemantics(
            label: isDone
                ? String(localized: "check_in_done_prefix", defaultValue: "Done. ")
                    + (checkedAtLabel ?? "")
                : String(
                    localized: "check_in_button_description",
                    defaultValue: "I'm fine. Tell my family I am safe."
                ),
            hint: isDone
                ? nil
                : String(
                    localized: "check_in_button_hint",
                    defaultValue: "Sends your family a quiet message that you are well."
                )
        )
    }
}