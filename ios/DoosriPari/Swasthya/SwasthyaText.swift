// UNTESTED — never compiled. See ios/README.md.
//
// Every user-facing string in the Swasthya package goes through here, for the
// same reason `SaathiText.swift` exists: plan 3.5 ships ten languages and only
// English and Hindi are written, so a screen must never show a bare key while a
// translation is outstanding. The default value is what a reviewer reads; the
// `.strings` value is what the user reads. Grep these defaults out when the
// strings are written and check the two lists agree.

import Foundation

enum SwasthyaText {

    /// - Parameters:
    ///   - key: The identifier. Never reword a key that has shipped; doing so
    ///     orphans every translation of it.
    ///   - english: What the user reads in English, written to be read alone.
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