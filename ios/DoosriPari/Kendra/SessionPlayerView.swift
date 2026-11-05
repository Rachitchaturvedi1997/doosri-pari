// UNTESTED — never compiled. See ios/README.md.
//
// Plan section 7.1 and plan 4.5: the session player. Progress bar ("3 of
// 10"), the drawn instructor, the pose title at 28 pt, "Hold · 12 seconds",
// the voice cue as a word-for-word caption, and three labelled 64 pt
// controls.
//
// SCREEN OFF, AUDIO ON. A 20-minute practice is done with the phone face
// down on the mat or in a pocket; if the screen going dark stops the
// session, the app has failed the person it was built for (plan 7.1:
// "screen-off playback", plan 8.3: background audio). That requirement is
// held in three places:
//
//   1. `AVAudioSession` category `.playback`, activated when the session
//      starts — below, in `SessionPlayerViewModel.begin()`.
//   2. The app target's Info.plist must carry UIBackgroundModes = audio.
//      That file is owned by the app shell, not by this package; it is
//      called out here because this screen is the reason it exists.
//   3. The step timer runs in the view model, not in the view — a timer tied
//      to the render loop stops when the view leaves the screen, and a
//      paused practice would then resume at the wrong pose.
//
// EXERCISE ONLY. The poses here were filtered before this screen opened; the
// safety rules live upstream in `PoseFilter` and never in the player.

import AVFoundation
import SwiftUI

@MainActor
final class SessionPlayerViewModel: ObservableObject {

    let programme: Programme
    let steps: [SessionStep]

    @Published private(set) var stepIndex = 0
    @Published private(set) var isPaused = true
    @Published private(set) var isFinished = false

    /// Seconds elapsed in the current hold, for the "Hold · 12 seconds" line.
    @Published private(set) var elapsedInStep = 0

    /// Whether the voice cues are speaking at all. A silent session with
    /// captions is a supported state — plan 7.1 puts captions on screen word
    /// for word precisely because not everyone wants a voice.
    @Published var voiceOn = true

    private var ticker: Task<Void, Never>?
    private let synthesizer = AVSpeechSynthesizer()

    /// Plan 7.1: bilingual voice, the user's language plus English; rate
    /// 0.85 of the system's default. Captions carry the same words.
    private let cueLanguage: String

    init(
        programme: Programme,
        steps: [SessionStep],
        cueLanguage: String = "hi-IN"
    ) {
        self.programme = programme
        self.steps = steps
        self.cueLanguage = cueLanguage
    }

    var currentStep: SessionStep? {
        steps.indices.contains(stepIndex) ? steps[stepIndex] : nil
    }

    var progressLabel: String {
        "\(stepIndex + 1) of \(max(steps.count, 1))"
    }

    var currentCue: String { currentStep?.pose.voiceCue ?? "" }
    var currentTitle: String { currentStep?.pose.name ?? "" }
    var currentPhase: String { currentStep?.phase.displayName ?? "" }
    var holdLabel: String {
        guard let step = currentStep else { return "" }
        return KendraText.t("ken_hold", "Hold") + " · "
            + step.pose.holdSeconds.formatted() + " "
            + KendraText.t("ken_seconds", "seconds")
    }

    // MARK: - Transport

    func begin() {
        configureAudioSession()
        speak(
            KendraText.t(
                "ken_session_opening",
                "Stop if you feel pain or dizziness."
            )
        )
        resume()
    }

    func resume() {
        guard ticker == nil, !isFinished else { return }
        isPaused = false
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !self.isPaused else { continue }
                self.tick()
            }
        }
    }

    func pause() {
        isPaused = true
        synthesizer.pauseSpeaking(at: .word)
    }

    func goBack() {
        guard stepIndex > 0 else { return }
        stepIndex -= 1
        elapsedInStep = 0
        announceCurrentStep()
    }

    func goNext() {
        guard stepIndex + 1 < steps.count else {
            finish()
            return
        }
        stepIndex += 1
        elapsedInStep = 0
        announceCurrentStep()
    }

    func finish() {
        isFinished = true
        isPaused = true
        ticker?.cancel()
        ticker = nil
        speak(
            KendraText.t(
                "ken_session_close",
                "The session is over. Rest for a moment before you get up."
            )
        )
    }

    private func tick() {
        guard let step = currentStep else { return }
        elapsedInStep += 1
        if elapsedInStep >= step.pose.holdSeconds {
            goNext()
        }
    }

    private func announceCurrentStep() {
        guard let step = currentStep else { return }
        speak(step.pose.name + ". " + step.pose.voiceCue)
    }

    // MARK: - Audio

    /// THE SCREEN-OFF REQUIREMENT'S FIRST HALF. `.playback` keeps the audio
    /// route alive while the screen is locked and while other apps are
    /// silent. Mixing with others is off deliberately: a practice session
    /// should not compete with the radio, and plan 7.1 treats the player as
    /// the one thing on the phone during practice.
    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
        } catch {
            // A refused audio session must not stop the practice: the
            // captions are complete on their own (plan 7.1: "captions on
            // screen, word for word"), so the session continues silently and
            // the timer keeps its place.
        }
    }

    private func speak(_ text: String) {
        guard voiceOn else { return }
        let utterance = AVSpeechUtterance(string: text)
        // Plan 7.1: "speech rate 0.85". The constant is a multiplier of the
        // system default, not an absolute rate.
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.85
        utterance.voice = AVSpeechSynthesisVoice(language: cueLanguage)
        synthesizer.speak(utterance)
    }

    deinit {
        ticker?.cancel()
    }
}

struct SessionPlayerView: View {
    @StateObject private var model: SessionPlayerViewModel
    @Environment(\.colorScheme) private var scheme

    init(model: SessionPlayerViewModel) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        DoosriScreen(
            model.programme.title,
            largeTitle: false,
            showsBackButton: true,
            scrollable: false
        ) {
            VStack(alignment: .leading, spacing: DoosriSpace.lg) {
                progressBar
                instructorStage
                poseText
                controls
            }
        }
        .onAppear {
            if model.elapsedInStep == 0 && !model.isFinished {
                model.begin()
            }
        }
    }

    // MARK: - Pieces (plan 4.5's layout, top to bottom)

    private var progressBar: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.xs) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(DoosriPalette.divider(scheme))
                    Capsule()
                        .fill(DoosriPalette.accent(scheme))
                        .frame(width: proxy.size.width * fraction)
                }
            }
            .frame(height: 12)
            Text(model.progressLabel)
                .font(.doosriCaption)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
        }
    }

    private var fraction: Double {
        guard model.steps.count > 0 else { return 0 }
        return Double(model.stepIndex + 1) / Double(model.steps.count)
    }

    /// The drawn instructor's frame. Moholla's instructor engine renders
    /// inside it — Dadi or Dada, chair and wall drawn in when the pose group
    /// needs them (plan 7.1). The frame is fixed height so the layout does
    /// not jump between poses, and the pose name is shown whatever the engine
    /// is doing, because the name is the load-bearing part.
    private var instructorStage: some View {
        ZStack {
            RoundedRectangle(cornerRadius: DoosriSize.cardRadius)
                .fill(DoosriPalette.surface(scheme))
            VStack(spacing: DoosriSpace.sm) {
                Image(systemName: "figure.mind.and.body")
                    .font(.system(size: 64))
                    .foregroundStyle(DoosriPalette.accent(scheme))
                Text(model.currentPhase)
                    .font(.doosriCaption)
                    .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 220)
        .accessibilityLabel(
            KendraText.spoken(
                "ken_instructor_stage_spoken",
                "The instructor shows the pose: \(model.currentTitle)"
            )
        )
    }

    private var poseText: some View {
        VStack(alignment: .leading, spacing: DoosriSpace.sm) {
            // 28 pt — the plan's wireframe labels the pose title at 28.
            Text(model.currentTitle)
                .font(.doosriTitle)
                .foregroundStyle(DoosriPalette.ink(scheme))
            Text(model.holdLabel)
                .font(.doosriHeadline)
                .foregroundStyle(DoosriPalette.inkSecondary(scheme))
            // The caption of the voice, word for word (plan 7.1).
            Text(model.currentCue)
                .font(.doosriCallout)
                .foregroundStyle(DoosriPalette.ink(scheme))
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var controls: some View {
        VStack(spacing: DoosriSpace.md) {
            HStack(spacing: DoosriSpace.md) {
                DoosriSecondaryButton(
                    KendraText.t("ken_back_step", "Back"),
                    systemImage: "chevron.backward"
                ) {
                    model.goBack()
                }
                DoosriPrimaryButton(
                    model.isPaused
                        ? KendraText.t("ken_play", "Play")
                        : KendraText.t("ken_pause", "Pause"),
                    systemImage: model.isPaused ? "play.fill" : "pause.fill"
                ) {
                    if model.isPaused {
                        model.resume()
                    } else {
                        model.pause()
                    }
                }
                DoosriSecondaryButton(
                    KendraText.t("ken_next_step", "Next"),
                    systemImage: "chevron.forward"
                ) {
                    model.goNext()
                }
            }

            Toggle(isOn: $model.voiceOn) {
                Text(KendraText.t("ken_voice_on", "Voice"))
                    .font(.doosriBody)
                    .foregroundStyle(DoosriPalette.ink(scheme))
            }
            .toggleStyle(.switch)
            .frame(minHeight: DoosriSize.minControlHeight)

            Text(KendraText.t(
                "ken_stop_if",
                "Stop if you feel pain or dizziness."
            ))
            .font(.doosriCallout)
            .foregroundStyle(DoosriPalette.ink(scheme))
        }
    }
}