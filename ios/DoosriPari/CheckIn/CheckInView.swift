// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 4.5, the Today screen, for the check-in slice. Native SwiftUI,
// per plan section 8.1 — not Compose Multiplatform, because Apple's HIG differs
// in ways that matter for a 60+ audience and because plan 8.1 says so.

import SwiftUI

struct CheckInView: View {
    @StateObject private var model: CheckInViewModel
    @Environment(\.colorScheme) private var scheme

    /// How the app should greet them — "Sharma ji". Nil while signed out.
    var addressAs: String?

    init(model: CheckInViewModel, addressAs: String?) {
        _model = StateObject(wrappedValue: model)
        self.addressAs = addressAs
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                    greeting
                    checkInButton
                    if let error = model.error { errorBanner(error) }
                    if model.preferences?.isPaused == true { pausedNote }
                    history
                }
                .padding(.horizontal, DoosriSpace.screenMargin)
                .padding(.vertical, DoosriSpace.lg)
            }
            .background(DoosriPalette.background(scheme))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    // Plan 4.4: SOS is red and labelled "SOS" — never an icon
                    // alone. It is a no-op in this slice; see the note in
                    // DoosriPariApp. It must not be mistaken for working.
                    Button {} label: {
                        Text("SOS")
                            .font(.doosriButton)
                            .foregroundStyle(DoosriPalette.background(scheme))
                            .frame(
                                minHeight: DoosriSize.minControlHeight,
                                minWidth: 64
                            )
                            .background(
                                Capsule().fill(DoosriPalette.safety(scheme))
                            )
                    }
                    .accessibilityLabel(
                        String(localized: "sos_button")
                    )
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .tint(DoosriPalette.accent(scheme))
        .task {
            if model.snapshot == nil { await model.load() }
        }
    }

    // MARK: - Pieces

    /// Plan 4.5: a large title, "Namaste, Sharma ji", and the date under it.
    private var greeting: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            Text(greetingText)
                .font(.doosriLargeTitle)
                .foregroundStyle(DoosriPalette.ink(scheme))
            Text(todayLine)
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        }
    }

    private var greetingText: String {
        guard let addressAs else { return String(localized: "app_name") }
        return String(
            format: String(localized: "today_greeting"),
            addressAs
        )
    }

    private var todayLine: String {
        Date.now.formatted(
            .dateTime.weekday(.wide).day().month(.wide)
        )
    }

    /// Plan 4.4: 96 tall, green, "Main theek hoon · I'm fine". Once pressed it
    /// reads "Checked in at 8:12" with a tick.
    ///
    /// The label is allowed two lines. The English string is the shortest of
    /// the ten languages, and truncating Hindi or Tamil here would be worse
    /// than a taller button.
    private var checkInButton: some View {
        Button {
            Task { await model.checkIn() }
        } label: {
            HStack(spacing: DoosriSpace.md) {
                if model.sending {
                    ProgressView()
                        .tint(DoosriPalette.background(scheme))
                } else if model.isDone {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 40))
                    Text(checkedInText)
                        .font(.doosriBody)
                        .multilineTextAlignment(.leading)
                } else {
                    Text("check_in_button")
                        .font(.doosriBody)
                        .multilineTextAlignment(.leading)
                }
            }
            .frame(maxWidth: .infinity, minHeight: DoosriSize.checkInButtonHeight)
            .padding(.horizontal, DoosriSpace.lg)
            .background(
                Capsule().fill(DoosriPalette.ok(scheme))
            )
            .foregroundStyle(DoosriPalette.background(scheme))
        }
        .buttonStyle(.plain)
        .disabled(model.isDone || model.sending)
        .accessibilityLabel(greetingAccessibilityLabel)
    }

    private var greetingAccessibilityLabel: String {
        model.isDone
            ? String(localized: "check_in_done", defaultValue: "Checked in")
            : String(localized: "check_in_button")
    }

    private var checkedInText: String {
        guard let checkedAt = model.snapshot?.checkedAt else {
            return String(localized: "check_in_done", defaultValue: "Checked in")
        }
        let time = Date(timeIntervalSince1970: checkedAt).formatted(
            date: .omitted, time: .shortened
        )
        return String(
            format: String(localized: "check_in_done_at"),
            time
        )
    }

    @ViewBuilder
    private func errorBanner(_ error: CheckInViewModel.LoadError) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 24))
            // A ternary of two literals picks one, not a key, so the localiser
            // needs the key. `Localizable.strings` carries both messages.
            Text(
                String(
                    localized: String.LocalizationValue(
                        error == .offline ? "check_in_offline" : "check_in_failed"
                    )
                )
            )
                .font(.doosriCallout)
            Spacer()
            Button {
                Task { await model.load() }
            } label: {
                Text("retry")
                    .font(.doosriCallout)
            }
        }
        .padding(DoosriSpace.md)
        .background(
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
        )
        .foregroundStyle(DoosriPalette.ink(scheme))
    }

    private var pausedNote: some View {
        Text("checkin_paused_note")
            .font(.doosriCallout)
            .foregroundStyle(DoosriPalette.inkSecondary(scheme))
    }

    /// Plan 4.4: a day somebody else resolved shows in the safety colour, so
    /// the user can see at a glance that somebody came looking.
    @ViewBuilder
    private var history: some View {
        if !model.history.isEmpty {
            VStack(alignment: .leading, spacing: DoosriSpace.xs) {
                Text("history_title")
                    .font(.doosriTitle)
                    .foregroundStyle(DoosriPalette.ink(scheme))
                ForEach(model.history) { day in
                    HStack(spacing: DoosriSpace.md) {
                        Image(systemName: icon(for: day))
                            .font(.system(size: 26))
                            .foregroundStyle(tint(for: day))
                            .frame(width: 32)
                        Text(day.day)
                            .font(.doosriBody)
                            .foregroundStyle(DoosriPalette.ink(scheme))
                    }
                    .frame(minHeight: DoosriSize.rowHeight)
                    .accessibilityElement(children: .combine)
                }
                Divider().background(DoosriPalette.divider(scheme))
            }
        } else if !model.loading {
            // Plan 4.4 empty state: one line saying what goes here.
            Text("history_empty")
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        }
    }

    private func icon(for day: CheckInSnapshot) -> String {
        if day.isDone { return "checkmark.circle.fill" }
        if day.isEscalated { return "exclamationmark.triangle.fill" }
        return "circle.dashed"
    }

    private func tint(for day: CheckInSnapshot) -> Color {
        if day.isDone { return DoosriPalette.ok(scheme) }
        if day.isEscalated { return DoosriPalette.safety(scheme) }
        return DoosriPalette.inkSecondary(scheme)
    }
}
