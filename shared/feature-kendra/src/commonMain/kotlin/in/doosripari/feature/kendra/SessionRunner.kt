package `in`.doosripari.feature.kendra

import `in`.doosripari.core.model.AppLanguage
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Running one class: what is on screen, what is being said, and what the three
 * buttons do. Plan section 7.1's session player, with the touch targets from
 * plan section 4.3.
 *
 * ## This is a state machine, and it takes the clock as an argument
 *
 * Nothing here reads `Clock.System`. Every function takes `now: Instant`, and
 * the platform layer decides what a second is — a real timer while the class is
 * on screen, a virtual clock in the tests, a value restored from saved state
 * when the process was killed mid-hold. That last case is the reason this is not
 * a coroutine with a `delay` in it: somebody whose phone rings during a hold
 * should come back to the class where they left it, not to the beginning of it.
 *
 * ## The timer counts the hold, not the pose
 *
 * A pose is a length of time and a number of repetitions, and the difference
 * changes what the screen draws. During a hold the big number goes *down*,
 * because that is the number a person needs to hold still for. During
 * repetitions there is no countdown at all — a countdown running while somebody
 * counts along with it gives two competing numbers, and the one they get wrong
 * is the one that makes them stop early.
 *
 * ## Everything is captioned, word for word
 *
 * Plan section 7.1 is explicit: captions word for word, not summarised. For
 * this audience that is not an accessibility afterthought, it is the primary
 * channel. A great many of the people using this cannot follow a spoken
 * instruction at 0.85 rate because they cannot follow it at any rate. There is
 * no summarised variant of [Caption] anywhere in this module.
 */
object SessionRunner {

    // ------------------------------------------------------------------ controls --

    /**
     * The minimum size of anything tappable, in points. Plan section 4.3.
     *
     * Sixty-four, and a floor rather than a preference. The plan's reasoning is
     * that a shaking hand and a thick thumb are the norm here, and a
     * 48-point control is a missed control. It is a property of the control
     * rather than of a layout so that a screen cannot forget it: [SessionControl]
     * has no size field a caller can override.
     */
    const val MIN_TOUCH_TARGET_PT: Int = 64

    /**
     * Plan section 4.3's secondary size, 56pt. Still well above every stock
     * accessibility guideline, and named so that a screen reaching for 48 is
     * visibly reaching for something else.
     */
    const val MIN_SECONDARY_PT: Int = 56

    /** Plan section 4.3's body size. The instruction is read, not skimmed. */
    const val INSTRUCTION_PT: Int = 20

    /**
     * The timer, larger than anything else on the screen.
     *
     * Well past the plan's 20pt body, because it is the one thing somebody with
     * the class on a phone across the room and their eyes on the ceiling needs
     * to read.
     */
    const val TIMER_PT: Int = 48

    /**
     * The three buttons visible mid-class.
     *
     * Previous, Pause, Next, and nothing else. Plan section 4.3 keeps the class
     * itself the largest thing on screen; a class with six controls on it is a
     * control panel with a class behind it, and somebody who is worried about
     * their balance while holding a tree pose should have exactly one obvious
     * thing to press.
     */
    val midClassControls: List<SessionControl> = listOf(
        SessionControl(code = "previous", label = Phrase(KEYS.PREVIOUS), role = ControlRole.Secondary),
        SessionControl(code = "pause", label = Phrase(KEYS.PAUSE), role = ControlRole.Primary),
        SessionControl(code = "next", label = Phrase(KEYS.NEXT), role = ControlRole.Primary),
    )

    /** The one control before the class starts. */
    val startControls: List<SessionControl> = listOf(
        SessionControl(code = "start", label = Phrase(KEYS.START), role = ControlRole.Primary),
    )

    /** The one control at the end, and it is the primary one — it is all there is. */
    val finishControls: List<SessionControl> = listOf(
        SessionControl(code = "finish", label = Phrase(KEYS.FINISH), role = ControlRole.Primary),
    )

    /**
     * A control's size, resolved from its role.
     *
     * Plan section 4.3: main action 64pt, secondary 56pt.
     */
    fun sizePt(role: ControlRole): Int = when (role) {
        ControlRole.Primary -> MIN_TOUCH_TARGET_PT
        ControlRole.Secondary -> MIN_SECONDARY_PT
    }

    /** Catalogue keys. Every control is labelled; none is an icon on its own. */
    object KEYS {
        const val START: String = "kendra.session.start"
        const val PAUSE: String = "kendra.session.pause"
        const val NEXT: String = "kendra.session.next"
        const val PREVIOUS: String = "kendra.session.previous"
        const val FINISH: String = "kendra.session.finish"

        /** A spoken instruction, so speech and captions come from one place. */
        const val SPOKEN_INSTRUCTION: String = "kendra.session.spoken_instruction"
    }

    // ------------------------------------------------------------------ lifecycle --

    /**
     * Open a class.
     *
     * The first thing shown is plan section 7.1's safety line, every time, and
     * the class does not begin until the user has acknowledged it. Not
     * dismissible, not auto-advancing, no countdown running underneath it. It
     * is two seconds of a 70-year-old's attention and it is the cheapest safety
     * mechanism in the app.
     */
    fun start(plan: ProgrammePlan, instructor: Instructor, languages: List<AppLanguage>): SessionState =
        SessionState(
            plan = plan,
            instructor = instructor,
            voiceLanguages = languages,
            stepIndex = 0,
            phase = SessionPhase.AcknowledgingSafety,
            caption = Caption(lines = emptyList()),
            safetyLine = Phrase(SafetyGate.KEYS.STOP_IF_PAIN),
            stepStartedAt = null,
            elapsedInStep = Duration.ZERO,
        )

    /**
     * Apply one thing that happened, at [now]. The whole runner is this
     * function.
     *
     * A button press, a timer tick and a restore-from-background are all just
     * events, which is what guarantees the class cannot behave differently
     * depending on how the user arrived where they are.
     */
    fun reduce(state: SessionState, event: SessionEvent, now: Instant): SessionState = when (event) {
        is SessionEvent.AcknowledgeSafety ->
            if (state.phase == SessionPhase.AcknowledgingSafety) {
                beginStep(state, stepIndex = 0, now = now)
            } else {
                state
            }

        is SessionEvent.Tick -> if (state.phase.runs) advanceTime(state, now) else state

        is SessionEvent.Pause ->
            if (state.phase == SessionPhase.Running) state.copy(phase = SessionPhase.Paused) else state

        is SessionEvent.Resume ->
            if (state.phase == SessionPhase.Paused) resume(state, now) else state

        is SessionEvent.Next -> moveTo(state, state.stepIndex + 1, now)
        is SessionEvent.Previous -> moveTo(state, state.stepIndex - 1, now)

        is SessionEvent.Finish -> state.copy(phase = SessionPhase.Finished, stepStartedAt = null)

        /**
         * The process died, or the user came back after an interruption.
         *
         * [now] is the real current time and [SessionEvent.Restore] carries the
         * time already spent in this step, so a class that was running for an
         * hour in the background is correctly finished rather than silently
         * reset. Neither case is a reason to start again.
         */
        is SessionEvent.Restore -> state.copy(
            stepStartedAt = now - event.elapsedInStep,
            elapsedInStep = event.elapsedInStep,
        )
    }

    /**
     * The caption for a step: what is being said, word for word.
     *
     * The pose's own instruction — the catalogue's translated string, or its
     * modification when the gate chose the gentler form — is the caption, and
     * the breathing cue is the second line rather than a replacement.
     */
    fun captionFor(step: ProgrammeStep): Caption = Caption(
        lines = buildList {
            add(step.pose.text)
            step.pose.breathingCue?.let { add(it) }
        },
        isModification = step.pose.isModified,
        modificationNote = step.pose.modificationPhrase,
    )

    /** What the voice says for a step: the same words, so a caption is never a summary. */
    fun spokenFor(step: ProgrammeStep): Phrase =
        Phrase(KEYS.SPOKEN_INSTRUCTION, mapOf("instruction" to step.pose.text))

    // ------------------------------------------------------------------ internals --

    /**
     * How many steps a single tick may consume before this gives up.
     *
     * A cap rather than a `while` loop's own termination, because a class
     * restored from a month-old saved state would otherwise spend a second
     * walking through three hundred steps that nobody was there to watch.
     */
    private const val MAX_CATCHUP_STEPS: Int = 32

    private fun beginStep(state: SessionState, stepIndex: Int, now: Instant): SessionState {
        val step = state.plan.steps.getOrNull(stepIndex)
            ?: return state.copy(phase = SessionPhase.Finished, stepStartedAt = null)
        return state.copy(
            stepIndex = stepIndex,
            phase = SessionPhase.Running,
            caption = captionFor(step),
            spoken = spokenFor(step),
            safetyLine = null,
            stepStartedAt = now,
            elapsedInStep = Duration.ZERO,
        )
    }

    /**
     * Consume however much time has passed, possibly over several steps.
     *
     * A tick that overshoots carries the overshoot forward, so a class
     * backgrounded for thirty seconds does not lose thirty seconds of the next
     * movement, and one backgrounded for long enough finishes rather than
     * jumping.
     */
    private fun advanceTime(state: SessionState, now: Instant): SessionState {
        var current = state
        var remaining = now - (state.stepStartedAt ?: return state)
        var consumed = 0

        while (consumed < MAX_CATCHUP_STEPS) {
            val step = current.plan.steps.getOrNull(current.stepIndex) ?: break
            val length = step.seconds.toDuration()
            if (remaining < length) {
                current = current.copy(elapsedInStep = remaining)
                break
            }
            remaining -= length + Programme.TRANSITION_SECONDS.seconds
            val next = beginStep(current, current.stepIndex + 1, now)
            if (next.phase == SessionPhase.Finished) return next
            current = next.copy(elapsedInStep = remaining)
            consumed++
        }

        return if (current.phase == SessionPhase.Running) {
            current.copy(stepStartedAt = now)
        } else {
            current
        }
    }

    /**
     * Resume without losing the time already held.
     *
     * A paused class does not tick, so [SessionState.elapsedInStep] is already
     * the value it had when it was paused. Resuming restarts the clock from
     * [now] against that same elapsed value, which is why a class paused across
     * a lunch break comes back to the same second of the same hold — the thing
     * somebody who walked away from the phone expects, and the thing a
     * "restart the step on resume" implementation quietly refuses to give them.
     */
    private fun resume(state: SessionState, now: Instant): SessionState = state.copy(
        phase = SessionPhase.Running,
        stepStartedAt = now,
    )

    /** Next and Previous. Neither does anything before the safety line is acknowledged. */
    private fun moveTo(state: SessionState, stepIndex: Int, now: Instant): SessionState {
        if (state.phase == SessionPhase.AcknowledgingSafety) return state
        return beginStep(state, stepIndex, now)
    }
}

// ------------------------------------------------------------------- vocabulary --

/**
 * One tappable thing in the session player.
 *
 * [label] is mandatory and there is no constructor without it, because the one
 * thing plan section 4.3 rules out for this audience is a control whose meaning
 * lives only in an icon. A pause bar means nothing to somebody who has never
 * used this app; the word does.
 */
data class SessionControl(
    val code: String,
    val label: Phrase,
    val role: ControlRole,
) {
    /** The floor from plan section 4.3, which a layout cannot go below. */
    val minTargetPt: Int get() = SessionRunner.sizePt(role)
}

/** How important a control is, which is what sets its size. */
enum class ControlRole(val code: String) {
    /** 64pt. The thing you are here to press. */
    Primary("primary"),

    /** 56pt. The things around it. */
    Secondary("secondary");

    companion object {
        fun fromCode(code: String?): ControlRole =
            entries.firstOrNull { it.code == code } ?: Secondary
    }
}

/** Where the class has got to. */
enum class SessionPhase(val code: String) {
    /** Waiting for the safety line to be acknowledged. Never auto-advances. */
    AcknowledgingSafety("acknowledging_safety"),

    Running("running"),
    Paused("paused"),
    Finished("finished");

    /** Whether the timer is counting. */
    val runs: Boolean get() = this == Running

    companion object {
        fun fromCode(code: String?): SessionPhase =
            entries.firstOrNull { it.code == code } ?: AcknowledgingSafety
    }
}

/**
 * What is on screen right now.
 *
 * Immutable, and carrying everything a screen needs to draw itself without going
 * back to the plan: which step, the caption, whether there is a countdown, and
 * which buttons exist.
 */
data class SessionState(
    val plan: ProgrammePlan,
    val instructor: Instructor,
    val voiceLanguages: List<AppLanguage>,
    val stepIndex: Int,
    val phase: SessionPhase,
    val caption: Caption,
    /** The spoken words for the current step, or null before it begins. */
    val spoken: Phrase? = null,
    /** The opening safety line, non-null only while it is on screen. */
    val safetyLine: Phrase? = null,
    /** When this step began, or null while paused or not yet started. */
    val stepStartedAt: Instant? = null,
    val elapsedInStep: Duration = Duration.ZERO,
) {
    val currentStep: ProgrammeStep? get() = plan.steps.getOrNull(stepIndex)

    val stepCount: Int get() = plan.steps.size

    /**
     * How far through the class, as a fraction.
     *
     * A bar and not a number, because a class is not scored and there is nothing
     * useful to say about the distance left beyond "you are nearly done".
     */
    val progress: Float
        get() = if (plan.steps.isEmpty()) 1f else (stepIndex + 1).toFloat() / plan.steps.size

    /**
     * The hold countdown, or null.
     *
     * Null during repetitions, while paused, and for a step with no hold. The
     * screen draws nothing rather than drawing a zero, and never reuses the
     * previous step's number.
     */
    fun countdown(at: Instant): Int? {
        if (phase != SessionPhase.Running) return null
        val step = currentStep ?: return null
        if (step.pose.holdSeconds == null) return null
        val left = (step.seconds.toDuration() - elapsedInStep).inWholeSeconds
        return if (left > 0) left.toInt() else 0
    }

    /**
     * The buttons to draw, which is a function of where the class is.
     *
     * One before it, three during, one after. A screen that offers Pause during
     * the safety line is offering a control that does nothing.
     */
    fun controls(): List<SessionControl> = when (phase) {
        SessionPhase.AcknowledgingSafety -> SessionRunner.startControls
        SessionPhase.Running, SessionPhase.Paused -> SessionRunner.midClassControls
        SessionPhase.Finished -> SessionRunner.finishControls
    }

    /** Whether there is anything worth writing to storage yet. */
    val isWorthSaving: Boolean
        get() = phase != SessionPhase.AcknowledgingSafety && phase != SessionPhase.Finished
}

/**
 * The words under the instructor.
 *
 * [lines] are the caption, in the order they are spoken, complete. There is
 * deliberately no shorter form: a summary is a translation into fewer words, and
 * this is the audience the plan says must not be summarised.
 */
data class Caption(
    val lines: List<String>,
    val isModification: Boolean = false,
    val modificationNote: Phrase? = null,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Flattened, for a screen with one text view and no room for two. */
    val flat: String get() = lines.joinToString("\n")
}

/** Something that happened during a class. */
sealed interface SessionEvent {
    /** The user has read the safety line. Nothing else moves the class off it. */
    data object AcknowledgeSafety : SessionEvent

    /** The platform's timer, once a second while the class is on screen. */
    data object Tick : SessionEvent

    data object Pause : SessionEvent
    data object Resume : SessionEvent
    data object Next : SessionEvent
    data object Previous : SessionEvent
    data object Finish : SessionEvent

    /**
     * Rebuilt after the process died, carrying the time already spent in this
     * step. See [SessionRunner.reduce].
     */
    data class Restore(val elapsedInStep: Duration) : SessionEvent
}
