// UNTESTED — never compiled. See ios/README.md.
//
// Haptics. For most apps this is polish. In Doosri Pari it is a second channel
// for a piece of information the user may not be able to see, and that is the
// whole justification for it being in the component library rather than in one
// screen.
//
// The case: a seventy-one-year-old presses a 96 pt green button at eight in
// the morning in a bright kitchen. The screen changes — the tick appears, the
// label becomes "Checked in at 8:12" — but she is looking at her tea, or at
// the person across the table, or her eyes are simply not resolving a 20 pt
// change from across a room. A tap she can *feel* under her thumb answers the
// question "did that go through?" without her having to find the change and
// read it.
//
// The other case is worse than awkward. Somebody with tremor presses SOS and
// it does not fire. The sound is the first thing that has already failed at
// that point; the feel under the finger is the next signal, and it is the last
// one before a phone call to a family member.

import UIKit

/// The only haptic vocabulary in the app.
///
/// `UIImpactFeedbackGenerator.FeedbackStyle` is deliberately not in the
/// signatures, so that no component file needs to import UIKit and no caller
/// can reach past these five words to something subtler.
enum DoosriHaptics {

    /// How hard, and what it means. Named for the moment, not for the physics.
    enum Strength {
        /// A row was tapped, a picker moved, a secondary action ran. Barely
        /// there. It confirms the tap registered; it does not ask for anything.
        case light

        /// The main action on a screen. The one primary button, the mic, a
        /// sheet's save. "You did the thing."
        case medium

        /// Check-in and SOS. The two presses this app most needs to be sure
        /// about, and the two a person is most likely to make with a hand that
        /// is not entirely steady.
        case strong
    }

    /// A tap on a control that changed something the user asked for.
    ///
    /// The generator is created and `prepare()`d on the spot rather than kept
    /// around. `prepare()` warms the Taptic Engine so the first tap is not
    /// late by a few milliseconds, which on a 96 pt button is the difference
    /// between "that worked" and "did that work?" — and a prepared generator
    /// held indefinitely stops being prepared anyway.
    static func impact(_ strength: Strength) {
        let style: UIImpactFeedbackGenerator.FeedbackStyle
        switch strength {
        case .light: style = .light
        case .medium: style = .medium
        case .strong: style = .heavy
        }
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        generator.impactOccurred()
    }

    /// A press that was *rejected* or that the app could not carry out —
    /// nothing saved, nothing sent.
    ///
    /// This one is easy to leave out and it is not decoration either. A silent
    /// failure that only shows as a line of small grey text is a failure a
    /// pensioner will simply not notice and will believe went through.
    static func failure() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.error)
    }

    /// A pull-to-refresh that came back with something. Soft, so it is not
    /// mistaken for an alert.
    static func arrived() {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(.success)
    }

    /// Japa and mala taps (plan section 7.2). Held by the caller and reused
    /// across a session, because a hundred and eight taps through a fresh
    /// generator each time is both slow and pointlessly hot.
    ///
    /// There is no system "selection" tick that fits a japa count, so this is
    /// the light impact at a deliberately low intensity.
    static func makeCounter() -> UIImpactFeedbackGenerator {
        let generator = UIImpactFeedbackGenerator(style: .rigid)
        generator.prepare()
        return generator
    }
}
