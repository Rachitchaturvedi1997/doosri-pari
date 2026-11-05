// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 4.3, typography, and the two rules that go with it: nothing
// below 16 pt, and Dynamic Type is never clamped downwards.
//
// No point size is set in this file. The sizes live in `DoosriMetrics.swift`
// as `Font.custom(_:size:relativeTo:)`, and the `relativeTo:` argument is the
// whole reason this app's text grows: it attaches each size to a system text
// style, so a user who has turned their phone up to accessibility5 gets
// accessibility5. A bare `.system(size: 20)` would look identical at every
// setting and would be a bug that only shows up on somebody's phone.

import SwiftUI

enum DoosriType {

    // MARK: The floor

    /// **The smallest text in this app is 16 pt.** Not "16 pt is a good idea" —
    /// 16 pt is the floor, and there is no text below it in any of the ten
    /// languages, in any state, at any Dynamic Type setting.
    ///
    /// It is the plan's caption size (section 4.3) and the smallest figure the
    /// accessibility research in section 4.1 accepts for this audience. Apple
    /// permits 11 pt; permitting it is not the same as being right for a
    /// seventy-one-year-old in Patna reading her phone at arm's length in
    /// daylight.
    ///
    /// The six fonts in `DoosriMetrics.swift` are the whole scale. A seventh
    /// size is a decision, not an accident, and it belongs in the plan.
    static let minimumPointSize: CGFloat = 16

    /// Plan section 4.3: body is 20 pt. Named here so the line-height modifier
    /// can be derived from it rather than from a number typed twice.
    ///
    /// If `DoosriMetrics.doosriBody` changes, this changes with it. The DEBUG
    /// audit below is the belt to that braces.
    static let bodyPointSize: CGFloat = 20

    // MARK: Line height

    /// Plan section 4.3: "Line height 1.4x for body."
    ///
    /// 1.4 on 20 pt means 8 pt of extra leading above the font's own line
    /// height. At 1.2 — what you get for free — two lines of Devanagari or
    /// Bengali touch, and a pensioner with a phone at arm's length loses the
    /// start of the second line.
    static let bodyLineHeight: CGFloat = 1.4

    // MARK: Dynamic Type

    /// The one clamp in the app, and it clamps **upwards**.
    ///
    /// Plan section 6.16 says system settings are respected, so the app must
    /// not stop the user making the text bigger. `accessibility3` is where the
    /// clamp sits: past that the layouts in the plan stop being checkable
    /// against a 4.7-inch screen, and the answer to "what breaks at
    /// accessibility5" is a layout fix, not a smaller font.
    ///
    /// There is deliberately no matching lower clamp. Nothing in this app
    /// shrinks text to make something fit, ever, for anybody.
    static let maximumDynamicTypeSize = DynamicTypeSize.accessibility3

    // MARK: Weight

    /// The lightest weight the plan allows, and therefore the only one this
    /// file will produce. Plan section 4.3: "No light or thin weights."
    ///
    /// Regular is the floor for weight, not just for size. At 16 pt in a
    /// Devanagari face, light weights lose the matras — the vowel signs
    /// above and below the letters — which is the part that makes the word
    /// readable rather than the part that carries the meaning.
    static let minimumWeight: Font.Weight = .regular

    // MARK: DEBUG

    #if DEBUG
    /// Tripwire for the floor.
    ///
    /// The sizes are buried inside `Font.custom` calls, so the type system
    /// cannot stop somebody writing 14. This is called from the line-height
    /// modifier below, which every block of body text in the app goes through,
    /// so a wrong size fails a debug build rather than shipping.
    static func auditFloor(
        _ pointSize: CGFloat,
        _ name: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        assert(
            pointSize >= minimumPointSize,
            "Doosri Pari sets no text below \(Int(minimumPointSize)) pt (plan 4.3). "
                + "\(name) asks for \(pointSize) pt.",
            file: file,
            line: line
        )
    }
    #endif
}

// MARK: - The two modifiers every screen uses

extension View {

    /// The Dynamic Type ceiling, and the only place `.dynamicTypeSize` is
    /// allowed in this codebase.
    ///
    /// See `DoosriType.maximumDynamicTypeSize` for why it is a ceiling and not
    /// a floor. `...x` is a partial range, so everything from `.xSmall` up to
    /// `accessibility3` is permitted; the range reads as a no-op until the
    /// constant is moved, which is the point — the intent is written down in
    /// one place instead of being re-decided in twenty.
    func doosriTextUpperLimit() -> some View {
        dynamicTypeSize(...DoosriType.maximumDynamicTypeSize)
    }

    /// 1.4x line height, for reading text.
    ///
    /// `lineSpacing` takes a fixed number of points and does **not** scale with
    /// Dynamic Type on its own. Set it to 8 flat and a pensioner at
    /// accessibility sizes gets body text packed onto roughly 1.2x while
    /// everything around it grows — which is exactly the bug that makes big
    /// text hard to read rather than merely large.
    ///
    /// `@ScaledMetric` is what keeps the ratio honest. It scales the 8 pt by
    /// the same factor the font scales by, because it is anchored to `.body`
    /// and so is `DoosriMetrics.doosriBody`.
    func doosriBodyLineHeight() -> some View {
        modifier(DoosriBodyLineHeight())
    }
}

private struct DoosriBodyLineHeight: ViewModifier {

    /// 20 pt of body at 1.4 wants 0.4 x 20 = 8 pt of extra leading.
    @ScaledMetric(relativeTo: .body) private var extraLeading: CGFloat =
        DoosriType.bodyPointSize * (DoosriType.bodyLineHeight - 1)

    func body(content: Content) -> some View {
        #if DEBUG
        DoosriType.auditFloor(DoosriType.bodyPointSize, "body line height")
        #endif
        content.lineSpacing(extraLeading)
    }
}
