// UNTESTED — never compiled. See ios/README.md.
//
// ─────────────────────────────────────────────────────────────────────────────
// MONEY. Held in integer paise. Displayed in rupees. Never a float.
// ─────────────────────────────────────────────────────────────────────────────
//
// Plan 6.8 moves real money: a seeker pays for a session, the mentor is paid
// 48 hours later minus a 12% fee, and disputes end in refunds. `SaathiEvent`
// holds its cost in `costPaise: Int64` and points here — one type for every
// amount in the app, not one per screen.
//
// The rule this file exists to enforce: **a float rupee in a payout path is a
// rounding bug that eventually costs somebody their rent.** `Double` cannot
// hold 0.10, let alone ₹1,234.56 across a fee split and a refund; the error is
// small enough to survive review and large enough, over a few thousand
// sessions, to be somebody's electricity bill. So:
//
//   - the wire format is paise (int64), the storage is paise, the arithmetic
//     is integer arithmetic;
//   - the *only* place rupees appear is `Paise.rupees(_:)`, which builds a
//     string from integer digits and never from a division that could round;
//   - the only place a rupee string comes back in is `Paise.paise(fromRupees:)`,
//     which parses digits without touching a floating-point type.
//
// Every conversion below is a named function on purpose. A `Double(paise) /
// 100` written at a call site is one line long, looks obviously right, and is
// exactly the line this file is here to make unnecessary.

import Foundation

enum Paise {

    /// The scale. The name is used in every comment instead of the literal
    /// 100, because "divide by 100" and "multiply by 100" in the same file is
    /// how a conversion ends up inverted in one of them.
    static let paisePerRupee: Int64 = 100

    /// The platform's share of a paid session. Plan 6.8: "the mentor is paid
    /// 48 hours later, minus a 12% fee". Integer arithmetic only — the fee is
    /// computed on paise and rounded down, so the fee can never exceed the
    /// amount it is taken from and the payout is `amount - fee` rather than a
    /// second, separately rounded number.
    static let platformFeePercent: Int64 = 12

    // MARK: - Display

    /// The named conversion, paise to the rupee string a human reads.
    ///
    /// "₹1,250", "₹1,250.50", "₹0". Indian digit grouping (1,23,456) because
    /// that is how these users read an amount on a bank statement.
    ///
    /// The whole part and the fraction are computed by integer division and
    /// remainder and then *printed as digits*. There is no `String(format:
    /// "%f")` in this function, and there must never be one: "%f" of a Double
    /// is where the rounding bug gets back in.
    static func rupees(_ paise: Int64) -> String {
        let negative = paise < 0
        let magnitude = paise.magnitude
        let whole = magnitude / UInt64(paisePerRupee)
        let fraction = magnitude % UInt64(paisePerRupee)

        var text = "₹" + indianGrouped(String(whole))
        if fraction != 0 {
            text += "." + twoDigits(fraction)
        }
        return negative ? "-" + text : text
    }

    /// "₹500 per session" style line, with the free case spelled out as a word
    /// rather than as ₹0 — "₹0" reads as a price the user failed to fill in.
    static func perSession(_ paise: Int64) -> String {
        isFree(paise)
            ? SevaText.t("seva_money_free", "Free")
            : rupees(paise) + " " + SevaText.t("seva_money_per_session", "per session")
    }

    static func isFree(_ paise: Int64) -> Bool { paise == 0 }

    // MARK: - Parsing (no floats)

    /// The reverse conversion, for the rupee field on the create-listing form.
    ///
    /// Parses digits and at most one decimal point, pads the fraction to two
    /// places with integer arithmetic, and returns nil on anything it does not
    /// fully understand — including a stray currency symbol, a negative sign
    /// and "1.2.3". A silent `Double("12.999")` rounding to 1300 paise is
    /// precisely the class of bug this app cannot afford in a payout, so an
    /// unparseable amount is a form error the user is shown, never a number
    /// the app quietly guesses.
    static func paise(fromRupees text: String) -> Int64? {
        let cleaned = text
            .replacingOccurrences(of: ",", with: "")
            .trimmingCharacters(in: .whitespaces)
        guard !cleaned.isEmpty else { return nil }

        let pieces = cleaned.split(separator: ".", omittingEmptySubsequences: false)
        guard pieces.count <= 2 else { return nil }
        guard let wholePart = pieces.first, !wholePart.isEmpty,
              wholePart.allSatisfy({ $0.isASCII && $0.isNumber }),
              let whole = Int64(wholePart) else { return nil }

        var fraction: Int64 = 0
        if pieces.count == 2 {
            let fractionPart = pieces[1]
            guard fractionPart.count <= 2,
                  fractionPart.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
            var digits = String(fractionPart)
            while digits.count < 2 { digits.append("0") }
            // Two digits read straight into an integer. "5" -> "50" -> 50 paise.
            guard let value = Int64(digits) else { return nil }
            fraction = value
        }
        return whole * paisePerRupee + fraction
    }

    // MARK: - Fee (plan 6.8)

    /// The platform's 12%, computed on paise and rounded down.
    static func platformFee(_ paise: Int64) -> Int64 {
        max(0, paise) * platformFeePercent / 100
    }

    /// What the mentor receives, 48 hours after both sides confirm.
    static func payoutAfterFee(_ paise: Int64) -> Int64 {
        paise - platformFee(paise)
    }

    // MARK: - Digits

    /// Two digits, always. `twoDigits(5)` is "05". Integer to string, no
    /// formatting library in the middle.
    private static func twoDigits(_ value: UInt64) -> String {
        value < 10 ? "0\(value)" : String(value)
    }

    /// Indian digit grouping: the last three digits, then groups of two.
    /// 1234567 -> "12,34,567".
    private static func indianGrouped(_ digits: String) -> String {
        guard digits.count > 3 else { return digits }
        let lastThree = String(digits.suffix(3))
        var head = digits.dropLast(3)
        var groups: [String] = []
        while head.count > 2 {
            groups.insert(String(head.suffix(2)), at: 0)
            head = head.dropLast(2)
        }
        if !head.isEmpty { groups.insert(String(head), at: 0) }
        return groups.joined(separator: ",") + "," + lastThree
    }
}