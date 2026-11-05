// UNTESTED — never compiled. See ios/README.md.
//
// Every user-facing string in the Saathi package goes through here, and the
// reason is not tidiness.
//
// Plan 3.5 says the app ships in ten languages and only English and Hindi are
// written. `String(localized: "key", defaultValue: "…")` means a screen is
// never a bare key on screen while a translation is outstanding — it reads in
// English, and the key is right there for the translator. `Text("key")` does
// the same thing but the English lives only in the `.strings` file, so a
// screen cannot be read, reviewed or proofed without opening it.
//
// The default value is the source of truth for review and the `.strings` value
// is the source of truth at runtime. They have to be changed together. When
// the strings are written, `grep` these defaults out of this package to check
// the two lists agree — that is the same class of task as
// `python/tests/test_parity.py`, and it is manual until somebody automates it.

import Foundation

enum SaathiText {

    /// - Parameters:
    ///   - key: The identifier. Never reword a key that has shipped;
    ///     doing so orphans every translation of it.
    ///   - english: What the user reads in English. This is also what a
    ///     reviewer reads, so it is written to be read on its own.
    static func t(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }

    /// Spoken by VoiceOver. Where the visible label is a noun the user reads
    /// as a word, this is a full sentence with a verb in it, because a screen
    /// reader that says "Report" and stops leaves the user guessing what
    /// reporting does.
    static func spoken(_ key: String, _ english: String) -> String {
        String(
            localized: String.LocalizationValue(key),
            defaultValue: english
        )
    }
}
