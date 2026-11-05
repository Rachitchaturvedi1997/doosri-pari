// UNTESTED — never compiled. See ios/README.md.
//
// `DoosriScreen` — the frame every screen in the app sits in.
//
// Plan section 3.1, "Big and calm by default": one focus per screen, one main
// action, and nothing hidden. Plan section 3.4: a visible back and home on
// every screen, no swipe-only actions, a confirmation before anything
// irreversible. Plan section 4.3: side margins are 20. Plan section 6.16: the
// system text size is respected up to the largest settings.
//
// What this view takes over, so that no screen has to remember:
//
//   - the background, resolved through `DoosriPalette` so dark mode is not an
//     afterthought in one place and a forgotten `.white` in another
//   - the 20 pt side margins
//   - the large title, and the back button that goes with it
//   - the Dynamic Type ceiling
//   - a toolbar whose actions are text-labelled, because the SOS button is
//     red and says "SOS" and never appears as a bare glyph
//
// What it deliberately does *not* do: it does not create a `NavigationStack`,
// and it does not lay the content out in a column. A screen owns its own
// `VStack` and its own ordering, because the ordering is the design — plan
// section 6.2 is seven paragraphs about what comes first on Today and no
// generic container has any business overruling that. See `CheckInView` for
// the shape this is meant to sit in:
//
//     NavigationStack {
//         DoosriScreen("Namaste, Sharma ji", toolbar: [.sos { ... }]) {
//             CheckInView(...)          // or any composition
//         }
//     }

import SwiftUI

// MARK: - Screen

struct DoosriScreen<Content: View>: View {

    /// The screen title. Shown by the navigation bar; a screen that also wants
    /// a large in-content heading (Today greets by name) uses one, not both.
    let title: String

    /// One line under the title. Optional, and it is `.doosriCaption` — the
    /// 16 pt floor.
    var subtitle: String?

    /// Large for a top-level screen, inline for a pushed one. Plan section
    /// 4.5 shows Today with a large title and a detail screen with an inline
    /// one; that is the switch.
    var largeTitle: Bool

    /// Plan section 3.4: "A visible back and home on every screen." The back
    /// button is on by default and turning it off is a decision, not an
    /// oversight. "Home" is the tab bar, which is always visible and never
    /// hidden (plan section 4.1: "Keep it visible, don't hide or disable
    /// tabs").
    var showsBackButton: Bool

    /// Text-labelled buttons in the top bar. `DoosriToolbarAction.sos(_:)` is
    /// the one every main screen carries.
    var toolbar: [DoosriToolbarAction]

    /// False for a screen that manages its own scrolling — the yoga player,
    /// which has to control the scroll position to drive a progress bar.
    var scrollable: Bool

    @ViewBuilder var content: Content

    @Environment(\.colorScheme) private var scheme

    init(
        _ title: String,
        subtitle: String? = nil,
        largeTitle: Bool = true,
        showsBackButton: Bool = true,
        toolbar: [DoosriToolbarAction] = [],
        scrollable: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.title = title
        self.subtitle = subtitle
        self.largeTitle = largeTitle
        self.showsBackButton = showsBackButton
        self.toolbar = toolbar
        self.scrollable = scrollable
        self.content = content()
    }

    var body: some View {
        Group {
            if scrollable {
                ScrollView {
                    padded
                }
            } else {
                padded
            }
        }
        .background(DoosriPalette.background(scheme))
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(largeTitle ? .large : .inline)
        .navigationBarBackButtonVisible(showsBackButton)
        .toolbar {
            // Plan section 4.1 quotes Apple: "Use a tab bar to support
            // navigation, not to provide actions" — and the same reasoning
            // puts the actions here rather than in a bar of their own.
            ForEach(toolbar) { action in
                ToolbarItem(placement: action.placement) {
                    action.button
                }
            }
            if let subtitle {
                ToolbarItem(placement: .principal) { EmptyView() }
                    .hidden
            }
        }
        .doosriTextUpperLimit()
        .tint(DoosriPalette.accent(scheme))
    }

    /// The 20 pt side margin from plan section 4.3, and 16 pt top and bottom so
    /// a scrolled screen does not start flush against the nav bar.
    private var padded: some View {
        content
            .padding(.horizontal, DoosriSpace.screenMargin)
            .padding(.vertical, DoosriSpace.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Toolbar actions

/// One button in a `DoosriScreen`'s top bar.
///
/// It is a `View` in its own right so it can go straight into a
/// `ToolbarItem`, and it carries a **text label beside any glyph** because
/// plan section 4.3 says every icon has a label next to it and there are no
/// icon-only controls anywhere in this app. Apple's own toolbars are
/// icon-only and VoiceOver labels them; that is not a licence here, because
/// the audience includes people who are new to this phone and reading a
/// toolbar for the first time.
///
/// The one hard requirement of the house style: **the SOS button is a red
/// capsule with the word "SOS" on it.** A red exclamation mark on its own is
/// the pattern this app must not reproduce.
struct DoosriToolbarAction: View, Identifiable {

    enum Placement {
        case leading
        case trailing
    }

    let id: String

    /// The word drawn on the button. Never empty.
    let label: String

    /// Optional SF Symbol, drawn to the **left** of the label, as plan section
    /// 4.4 requires for buttons. Always accompanied by the label.
    var systemImage: String?

    /// What VoiceOver says. Written as a phrase, not as the symbol's name —
    /// see the note at the top of `DoosriAccessibility.swift`.
    var accessibilityLabel: String?

    /// What happens, in the user's terms. "Opens the check-in history."
    var accessibilityHint: String?

    /// Red. Reserved for the two things that mean a person may be in trouble,
    /// which in a toolbar is only SOS.
    var isDestructive: Bool

    /// Off for a navigation back link, on for anything that does work.
    var playsHaptic: Bool

    var placement: Placement

    let action: () -> Void

    init(
        id: String,
        label: String,
        systemImage: String? = nil,
        accessibilityLabel: String? = nil,
        accessibilityHint: String? = nil,
        isDestructive: Bool = false,
        playsHaptic: Bool = true,
        placement: Placement = .trailing,
        action: @escaping () -> Void
    ) {
        self.id = id
        self.label = label
        self.systemImage = systemImage
        self.accessibilityLabel = accessibilityLabel
        self.accessibilityHint = accessibilityHint
        self.isDestructive = isDestructive
        self.playsHaptic = playsHaptic
        self.placement = placement
        self.action = action
    }

    var body: some View {
        Button {
            if playsHaptic { DoosriHaptics.impact(.light) }
            action()
        } label: {
            HStack(spacing: DoosriSpace.xs) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 20))
                }
                Text(label)
                    .font(.doosriCallout)
            }
            .foregroundStyle(ink)
            // 44 pt is Apple's floor. A bare `Text` in a toolbar is about 32,
            // and 12 pt of a pensioner's thumb is the difference between a tap
            // and a miss.
            .frame(minHeight: DoosriTarget.minimumHitTarget)
            .padding(.horizontal, DoosriSpace.sm)
        }
        .buttonStyle(.plain)
        .doosriButtonSemantics(
            label: accessibilityLabel ?? label,
            hint: accessibilityHint
        )
    }

    @Environment(\.colorScheme) private var scheme

    private var ink: Color {
        isDestructive ? DoosriPalette.safety(scheme) : DoosriPalette.ink(scheme)
    }
}

extension DoosriToolbarAction {

    /// Plan section 4.4: SOS sits in the top toolbar of every main screen,
    /// red, and labelled "SOS".
    ///
    /// **Never an icon alone.** The word is the label; the triangle beside it
    /// is decoration that happens to point at the same thing. A person who
    /// cannot distinguish the red from the background still has the word, and
    /// a person who cannot read English still has the triangle — which is why
    /// both are here and neither is load-bearing.
    ///
    /// The tap opens the press-and-hold countdown screen (plan section 6.3).
    /// The hold itself lives there, not here: a toolbar control that only
    /// fires after two seconds of pressure is invisible to somebody who does
    /// not know to do it, so the first tap must always do something.
    static func sos(action: @escaping () -> Void) -> DoosriToolbarAction {
        DoosriToolbarAction(
            id: "sos",
            label: String(localized: "sos_button", defaultValue: "SOS"),
            systemImage: "exclamationmark.triangle.fill",
            accessibilityLabel: String(
                localized: "sos_button_description",
                defaultValue: "Emergency help. SOS."
            ),
            accessibilityHint: String(
                localized: "sos_button_hint",
                defaultValue: "Opens the emergency screen. You will have time to cancel."
            ),
            isDestructive: true,
            playsHaptic: true,
            placement: .trailing,
            action: action
        )
    }

    /// Plan section 6.16: "Read this screen aloud" on every screen.
    ///
    /// Labelled rather than a bare speaker, for the same reason SOS is
    /// labelled — the audience includes people reading a toolbar for the
    /// first time, and "Bolo" and "Sunain" are two words in a row.
    static func readAloud(
        label: String = String(
            localized: "read_aloud",
            defaultValue: "Sunain"
        ),
        action: @escaping () -> Void
    ) -> DoosriToolbarAction {
        DoosriToolbarAction(
            id: "read-aloud",
            label: label,
            systemImage: "speaker.wave.2.fill",
            accessibilityLabel: String(
                localized: "read_aloud_description",
                defaultValue: "Read this screen aloud"
            ),
            playsHaptic: true,
            placement: .leading,
            action: action
        )
    }
}
