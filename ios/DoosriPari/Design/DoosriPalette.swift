// Plan section 4.3, transcribed. UNTESTED — never compiled.
//
// These are the same values as android/app/src/main/java/in/doosripari/ui/
// theme/Color.kt. Both were transcribed from the plan, so if one is changed
// the other should be, and python/tests/test_parity.py is the pattern for
// making that automatic.

import SwiftUI

enum DoosriPalette {

    // MARK: Light

    static let lightBackground = Color(hex: 0xFAFAF7)
    static let lightSurface = Color(hex: 0xFFFFFF)
    static let lightInk = Color(hex: 0x13201F)
    static let lightInkSecondary = Color(hex: 0x3F4E4C)
    static let lightAccent = Color(hex: 0x0B6E66)
    static let lightMarigold = Color(hex: 0xB97700)
    static let lightSafety = Color(hex: 0xC0311B)
    static let lightOk = Color(hex: 0x1E7A34)
    static let lightDivider = Color(hex: 0xE1E7E5)

    // MARK: Dark

    static let darkBackground = Color(hex: 0x0F1414)
    static let darkSurface = Color(hex: 0x1A2121)
    static let darkInk = Color(hex: 0xEEF3F2)
    static let darkInkSecondary = Color(hex: 0xB8C6C4)
    static let darkAccent = Color(hex: 0x6FD3C9)
    static let darkMarigold = Color(hex: 0xF2B544)
    static let darkSafety = Color(hex: 0xFF8A73)
    static let darkOk = Color(hex: 0x7BD38B)
    static let darkDivider = Color(hex: 0x2A3332)

    // MARK: Semantic

    /// Resolved against the current colour scheme. Call from a View, not from
    /// a model, so it re-reads the environment when the scheme flips.
    static func background(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkBackground : lightBackground
    }

    static func surface(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkSurface : lightSurface
    }

    static func ink(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkInk : lightInk
    }

    static func inkSecondary(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkInkSecondary : lightInkSecondary
    }

    static func accent(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkAccent : lightAccent
    }

    static func ok(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkOk : lightOk
    }

    /// SOS and missed check-ins only. Never decoration — the plan is explicit,
    /// and a red that means "something happened" one day and "brand accent" the
    /// next stops meaning anything.
    static func safety(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkSafety : lightSafety
    }

    static func divider(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? darkDivider : lightDivider
    }
}

extension Color {
    /// `Color(hex: 0x0B6E66)`. The plan's tokens are all written this way.
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}
