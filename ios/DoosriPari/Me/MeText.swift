// UNTESTED — never compiled. See ios/README.md.
//
// Every user-facing string in the Me package goes through here, for the same
// reason as `SaathiText.swift`: plan 3.5 ships ten languages and only English
// and Hindi are written, so a screen must never show a bare key while a
// translation is outstanding.

import Foundation

enum MeText {

    static func t(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }

    /// Spoken by VoiceOver: a full sentence with a verb in it, not a noun.
    static func spoken(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }
}