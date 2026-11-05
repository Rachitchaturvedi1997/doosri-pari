// Plan section 4.3, spacing and type. UNTESTED — never compiled.

import SwiftUI

/// Plan section 4.3: a spacing scale of 4, 8, 12, 16, 24, 32, 48. Side
/// margins are 20.
enum DoosriSpace {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
    static let huge: CGFloat = 48

    static let screenMargin: CGFloat = 20
}

enum DoosriSize {
    /// Controls at least 56 tall, 12 between them.
    static let minControlHeight: CGFloat = 56
    static let controlGap: CGFloat = 12

    /// Main actions are 64 tall and full width.
    static let primaryButtonHeight: CGFloat = 64

    /// The check-in button, plan section 4.4.
    static let checkInButtonHeight: CGFloat = 96

    /// List rows are at least 64 tall — the plan's minimum comfortable target.
    static let rowHeight: CGFloat = 64

    static let cardRadius: CGFloat = 14
    static let buttonRadius: CGFloat = 32

    static let micButtonSize: CGFloat = 64
}

/// Plan section 4.3's type scale.
///
/// The plan's sizes are in points *before* Dynamic Type. Each is attached to a
/// text style via `relativeTo` so it still grows when the user turns text up —
/// which, for a 60+ audience, several of them will have done. The font names
/// below are SF Pro's internal file names and are NOT a documented public API;
/// they are the most likely thing here to need changing once this is compiled.
/// See ios/README.md.
extension Font {
    static let doosriLargeTitle = Font.custom("SFProDisplay-Bold", size: 36, relativeTo: .largeTitle)
    static let doosriTitle = Font.custom("SFProDisplay-Bold", size: 28, relativeTo: .title)
    static let doosriHeadline = Font.custom("SFProText-Semibold", size: 22, relativeTo: .headline)
    static let doosriBody = Font.custom("SFProText-Regular", size: 20, relativeTo: .body)
    static let doosriCallout = Font.custom("SFProText-Regular", size: 18, relativeTo: .callout)
    static let doosriCaption = Font.custom("SFProText-Regular", size: 16, relativeTo: .caption)

    /// Button labels. The plan says 20 semibold.
    static let doosriButton = Font.custom("SFProText-Semibold", size: 20, relativeTo: .body)
}
