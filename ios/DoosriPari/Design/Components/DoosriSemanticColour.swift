// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 4.3, colour, and the rule that keeps the two semantic colours
// worth anything. The hex values live in `DoosriPalette.swift` and are not
// repeated here. What is here is the machinery that turns "semantic colours are
// never decoration" from a sentence somebody is meant to remember into
// something the compiler will not let you get wrong.

import SwiftUI

// MARK: - The one accessor DoosriPalette does not expose

extension DoosriPalette {

    /// Marigold, resolved against the current scheme.
    ///
    /// `DoosriPalette.swift` holds `lightMarigold` and `darkMarigold` but, as
    /// written, no scheme-taking function for them. Every other token has one,
    /// and the components need this one too, so it is added here as an
    /// extension rather than by editing that file: the palette is transcribed
    /// straight from the plan and `python/tests/test_parity.py` pins it against
    /// the Android theme, and a second copy of a rule is a second thing to keep
    /// in step.
    ///
    /// **Marigold is never body text on a light background.** On `#FFFFFF`,
    /// `#B97700` works out at about 4.4:1 — just under the 4.5:1 WCAG asks of
    /// body text, and a long way under the 7:1 plan section 4.1 aims at. A
    /// pensioner with early cataract reads it as beige. It is for the spiritual
    /// centre, for a highlight, and for non-text marks. In dark mode the same
    /// token clears 7:1 against `#0F1414` and is fine as a highlight there.
    static func marigold(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkMarigold : lightMarigold
    }
}

// MARK: - Decoration

/// The three colours an icon tile, a highlight or a neutral state may be
/// tinted with.
///
/// This is an enum rather than a `Color` parameter on purpose. `safety` and
/// `ok` are missing from the list, so a component written against it *cannot*
/// be handed a red or a green to decorate with — the compiler says no before
/// anybody has to say anything. Plan section 4.3 gives the rule; this gives it
/// teeth.
enum DoosriTint {

    /// Ink, washed back. The default for a list-row icon: a list is not a
    /// place for colour, it is a place for reading.
    case neutral

    /// The one accent colour, for a tile that means "this is the thing you
    /// came for".
    case accent

    /// The spiritual centre and nothing else. See `DoosriPalette.marigold`.
    case marigold

    func resolve(_ scheme: ColorScheme) -> Color {
        switch self {
        case .neutral: return DoosriPalette.inkSecondary(scheme)
        case .accent: return DoosriPalette.accent(scheme)
        case .marigold: return DoosriPalette.marigold(scheme)
        }
    }

    /// The pale square behind a glyph. 12% keeps the symbol readable against it
    /// in both schemes without the tile starting to look like a status colour.
    func wash(_ scheme: ColorScheme) -> Color {
        resolve(scheme).opacity(0.12)
    }
}

// MARK: - The semantic pair

/// The only two colours in the app that are allowed to mean something.
///
/// `safety` is the SOS button and a missed check-in. `ok` is "I'm fine" and a
/// completed thing. Nothing else.
///
/// The reason is in plan section 4.3 and it is worth stating plainly: a red
/// that means "something happened" one day and "brand accent" the next stops
/// meaning anything. And the person who learns to ignore red here is a
/// pensioner who ignores the SOS button, which is the one control in this app
/// that must never be ignored.
///
/// Reach for this only when you are reporting on somebody's safety. If you
/// want a green because the thing is nice, that is `DoosriTint.accent`.
enum DoosriStateColour {

    /// "I'm fine", a check-in that landed, a saved document.
    case safe

    /// SOS. A window that closed with nobody in it. A day somebody came looking.
    case alert

    func resolve(_ scheme: ColorScheme) -> Color {
        switch self {
        case .safe: return DoosriPalette.ok(scheme)
        case .alert: return DoosriPalette.safety(scheme)
        }
    }

    /// The wash behind a status glyph, for the same reason `DoosriTint` has one.
    func wash(_ scheme: ColorScheme) -> Color {
        resolve(scheme).opacity(0.14)
    }
}
