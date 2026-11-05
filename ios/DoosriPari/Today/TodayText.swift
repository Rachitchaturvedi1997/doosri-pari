// UNTESTED — never compiled. See ios/README.md.
//
// Every user-facing string in the Today package goes through here, for the
// reason `SaathiText.swift` gives at length: `String(localized:defaultValue:)`
// means a screen is never a bare key on screen while a translation is
// outstanding, and the English default is right there for the reviewer and the
// translator. Plan section 3.5 ships ten languages; only English and Hindi are
// written, so the default is what a Hindi reviewer is handed alongside the key.
//
// When the `.strings` files are written, grep these defaults out of this
// package and check the two lists agree.

import Foundation

enum TodayText {

    /// - Parameters:
    ///   - key: The identifier. Never reword a key that has shipped; doing so
    ///     orphans every translation of it.
    ///   - english: What the user reads in English, and what a reviewer reads.
    static func t(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }

    /// Rupees, in the user's locale. An Indian phone groups in lakhs and
    /// crores automatically from `hi_IN` or `en_IN`, and a pensioner reading a
    /// net figure of ₹23,456 is reading 2,34,566 in their head — the grouping
    /// has to match or the number looks wrong before it is even compared.
    ///
    /// Whole rupees, because every figure this app shows comes off a pension
    /// slip, and paise on a pension card is precision nobody asked for.
    static func rupees(_ amount: Double) -> String {
        amount.formatted(
            .currency(code: "INR")
                .precision(.fractionLength(0))
        )
    }
}