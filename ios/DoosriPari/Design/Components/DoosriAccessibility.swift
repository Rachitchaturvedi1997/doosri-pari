// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 6.16, and the reason this app exists for the people it is for.
// Accessibility is not a checklist at the end of a screen here; it is the
// brief. Apple's guidelines are the floor and this app's users are the reason
// for going past it, so the rules live in a file every component can reach
// rather than in each screen author's memory.
//
// Plan section 6.16 in full, because the parts that are easy to skip are the
// parts that matter:
//
//   - Dynamic Type and the Android font scale up to the largest sizes
//   - VoiceOver labels, written for a screen reader
//   - Bold Text, Reduce Motion, Reduce Transparency, Increase Contrast
//   - Voice Control and Switch Control on every screen
//   - "Read this screen aloud" on every screen
//
// Three of the rules below are the ones that are easiest to get wrong:
//
//   1. **Every target is at least 44 pt.** 44 is Apple's floor, not this
//      app's target. The plan's 56 and 64 figures are the *Android* figures
//      (section 4.1 quotes Apple's 44x44 against Android's 56/64), but they
//      are the better number and there is no reason for the iPhone to ship a
//      smaller target than the Android. Where a component is a primary
//      control, this file uses the plan's larger figure.
//
//   2. **No pure-colour signalling.** `ok` green and `safety` red are the one
//      pair a colour-blind user cannot rely on, and they are the pair this app
//      leans on hardest — they are the difference between "your mother is
//      fine" and "somebody has been called". So state is *always* carried by an
//      icon or by a word as well as by a colour, and `DoosriStateBadge` makes
//      that a type error: the text is a parameter of the case, so there is no
//      way to construct a green badge that says nothing.
//
//   3. **Labels are written for a screen reader, not for a developer.**
//      VoiceOver reads the label. "SOS" alone leaves a first-time listener
//      knowing they have found a control and nothing about what it does;
//      "Emergency help, SOS" is a phrase that can be acted on without seeing.

import SwiftUI

// MARK: - Button semantics

extension View {

    /// Everything a custom control needs to announce itself properly, in one
    /// call.
    ///
    /// SwiftUI gets a plain `Button` almost right on its own. These components
    /// build their labels out of an `HStack` of glyphs and text inside
    /// `.buttonStyle(.plain)`, which drops several things VoiceOver depends
    /// on, so they are put back here:
    ///
    /// - `label` replaces the concatenation of every sub-view's own
    ///   description. Without it, a row announces as "arrow right, checkmark
    ///   circle, text, 8:12" — the parts, in reading order, with the meaning
    ///   nowhere in it.
    /// - `value` carries state that is not in the label: the time a check-in
    ///   happened, how many items a circle has, whether a switch is on.
    /// - `hint` says what happens, in the future and in the user's terms.
    ///   "Opens the calculator", not "opens CalculatorView".
    /// - `.isButton` is added because `.buttonStyle(.plain)` drops it on some
    ///   of these, and Switch Control and Voice Control both navigate by
    ///   control type. A control that is not announced as a button cannot be
    ///   switched to.
    ///
    /// - Parameters:
    ///   - label: What this control *is*, as a sentence a stranger can act on.
    ///   - value: The state, if the label does not already carry it.
    ///   - hint: What will happen when it is used. Omit rather than pad it.
    ///   - isEnabled: Announced as "dimmed" by VoiceOver automatically, but
    ///     Switch Control skips dimmed controls, so a control that is disabled
    ///     for a *reason* should say why in its hint.
    func doosriButtonSemantics(
        label: String,
        value: String? = nil,
        hint: String? = nil,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> some View {
        #if DEBUG
        // A VoiceOver label that is just the symbol's name is the common
        // failure. "Bolt", "chevron right" and "SOS" are the three that
        // survive review least often, so they are named here.
        assert(
            !label.trimmingCharacters(in: .whitespaces).isEmpty,
            "A control with an empty accessibility label announces as nothing.",
            file: file,
            line: line
        )
        #endif
        return self
            .accessibilityLabel(label)
            .accessibilityValue(value ?? "")
            .accessibilityHint(hint ?? "")
            .accessibilityAddTraits(.isButton)
    }

    /// Hides a decorative glyph from the screen reader.
    ///
    /// Every icon in this app has a text label beside it (plan section 4.3),
    /// and the label is the name. Reading the glyph as well makes VoiceOver
    /// say the same thing twice, which is worse than saying nothing: a
    /// pensioner listening to "arrow right, Pension Sahayak, arrow right"
    /// stops trusting that the app is telling them what is on the screen.
    func doosriDecorativeIcon() -> some View {
        accessibilityHidden(true)
    }
}

// MARK: - Never colour alone

/// A state chip: a colour, **a glyph, and a word**, always all three.
///
/// The text is a parameter of the case, so a green badge that says nothing
/// cannot be written. That is the entire point — see the note on pure-colour
/// signalling at the top of this file.
struct DoosriStateBadge: View {

    enum State {
        /// `ok` green. "Checked in at 8:12". A thing that worked.
        case safe(String)

        /// `safety` red. "Missed — nobody has heard". SOS. A window that
        /// closed with nobody in it.
        case alert(String)

        /// Ink, washed. "Paused until 12 October". Nothing is wrong, and it
        /// matters.
        case neutral(String)

        /// The glyph that carries the state for a person who cannot see the
        /// colour. A tick, a warning triangle, an open circle — three shapes
        /// that stay apart in greyscale, in a projector, in sunlight and with
        /// every form of colour blindness there is.
        var systemImage: String {
            switch self {
            case .safe: return "checkmark.circle.fill"
            case .alert: return "exclamationmark.triangle.fill"
            case .neutral: return "circle.dashed"
            }
        }

        var colour: DoosriStateColour? {
            switch self {
            case .safe: return .safe
            case .alert: return .alert
            case .neutral: return nil
            }
        }
    }

    let state: State

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: DoosriSpace.sm) {
            Image(systemName: state.systemImage)
                .font(.system(size: 24))
                .foregroundStyle(tint)
                .doosriDecorativeIcon()

            Text(text)
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, DoosriSpace.md)
        .padding(.vertical, DoosriSpace.sm)
        .frame(minHeight: DoosriSize.minControlHeight)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(state.colour?.wash(scheme) ?? DoosriPalette.divider(scheme).opacity(0.4))
        )
        // One element, not three. VoiceOver reads the sentence; the glyph and
        // the colour are in the sentence's meaning, not in the reading order.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
    }

    private var text: String {
        switch state {
        case .safe(let t), .alert(let t), .neutral(let t): return t
        }
    }

    private var tint: Color {
        state.colour?.resolve(scheme) ?? DoosriPalette.inkSecondary(scheme)
    }

    /// The word is already in the label, so the colour is not repeated — but
    /// the *kind* of state is, because "Checked in" and "Missed" are the two
    /// sentences in this app a family member most needs to hear correctly, and
    /// a badge that is only ever read as its own text is a badge a listener
    /// cannot skim.
    private var accessibilityLabel: String {
        switch state {
        case .safe: return String(localized: "state_safe_prefix", defaultValue: "Done. ") + text
        case .alert: return String(localized: "state_alert_prefix", defaultValue: "Needs attention. ") + text
        case .neutral: return text
        }
    }
}

// MARK: - Targets

enum DoosriTarget {

    /// Apple's floor: "A button needs a hit region of at least 44x44 pt"
    /// (plan section 4.1, quoting the HIG).
    ///
    /// Nothing interactive in this app is smaller than this. Where the plan
    /// asks for more — 56 for a control, 64 for a main action, 96 for
    /// check-in — that larger figure is what the component uses. There is no
    /// reason for the iPhone to be more cramped than the Android.
    static let minimumHitTarget: CGFloat = 44
}
