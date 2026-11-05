// UNTESTED — never compiled. See ios/README.md.
//
// The Seva half of `SaathiText.swift`, with the same contract: every
// user-facing string in this package goes through `SevaText.t(_:_:)`, the key
// is the identifier and the English default is what a reviewer reads. Keys are
// `seva_*` so a translator can tell which package a string came from, and a
// key that has shipped is never reworded — doing so orphans every translation
// of it. See the note at the top of `Saathi/SaathiText.swift`.

import Foundation

enum SevaText {

    /// - Parameters:
    ///   - key: The identifier. Never reword a key that has shipped.
    ///   - english: What the user reads in English, also the review source.
    static func t(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }

    /// Spoken by VoiceOver. Full sentences with verbs, because a screen reader
    /// that says "Pay" and stops leaves the user guessing what paying does.
    static func spoken(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }
}