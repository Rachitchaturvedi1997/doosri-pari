package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.PostKind

/**
 * The post composer. Plan section 6.6, under the discipline of section 3.
 *
 * Section 6.6 fixes the content: text, up to six photos, voice notes up to
 * three minutes (auto-transcribed), polls and events. Section 3 fixes the
 * interaction: *nothing hidden* — no swipe-only or long-press-only actions,
 * and a confirmation before anything outward-facing, which a post is.
 *
 * Three consequences run through this file.
 *
 *  1. **Sending is two steps and both are visible.** [ComposerState] has to be
 *     in [isConfirming] before [Composer.send] will do anything, so the
 *     confirmation is a property of the state machine rather than a habit of
 *     the UI layer. A composer that posts on a swipe would pass every test and
 *     still be a hidden affordance.
 *  2. **Nothing the user typed is ever thrown away.** [Composer.sendFailed]
 *     returns the state with the text, the voice note, the photos and the poll
 *     exactly as they were. Losing a two-hundred-word message to a dropped
 *     connection is the single worst thing this screen can do to somebody who
 *     took ten minutes to dictate it.
 *  3. **Text is never silently truncated.** Going past the limit turns a
 *     counter red and produces a [ComposerProblem]; it does not eat the
 *     characters. The same reasoning rules out collapsing the composer behind
 *     a gesture.
 */
data class ComposerState(
    val circleId: Int,
    val authorId: Int,
    val text: String = "",
    val voice: VoiceNote = VoiceNote.None,
    val images: List<String> = emptyList(),
    val poll: PollDraft = PollDraft.None,
    val send: SendStatus = SendStatus.Idle,
    /** The confirmation step of section 3's "confirm before anything outward". */
    val isConfirming: Boolean = false,
    /** Set once a moderator or the server has held the post for review. */
    val heldForReview: Boolean = false,
) {
    /**
     * What the post will be, decided from what is in the box.
     *
     * A poll wins over images, and a voice note wins over text: a voice note
     * *is* the content — its transcript is only there so a moderator can read
     * it, which is what section 6.6's transcription requirement is for.
     */
    val kind: PostKind
        get() = when {
            poll != PollDraft.None -> PostKind.Poll
            voice !is VoiceNote.None -> PostKind.Voice
            images.isNotEmpty() -> PostKind.Image
            else -> PostKind.Text
        }

    /** Whether there is anything at all to send. */
    val hasContent: Boolean
        get() = text.isNotBlank() || voice !is VoiceNote.None ||
            images.isNotEmpty() || poll != PollDraft.None

    /** Characters left against the limit, for the counter. May go negative. */
    val charactersLeft: Int get() = Composer.MAX_TEXT_CHARS - text.length

    val problems: List<ComposerProblem> get() = Composer.validate(this)
}

/**
 * A voice note, and the transcription that has to happen before it can be
 * posted.
 *
 * Section 6.6 says voice notes are auto-transcribed, and `core-model`'s [Post]
 * carries the transcript with a note that section 6.6 requires it before a
 * note can be *reported on*. That requirement is the reason `Ready` is a
 * distinct state here rather than a flag: an untranscribed note cannot be
 * moderated and an unmoderatable voice post in a circle of trust is precisely
 * what the section is guarding against.
 */
sealed interface VoiceNote {

    /** No note attached. */
    data object None : VoiceNote

    /**
     * Still recording. The length counter runs here and turns red past
     * [Composer.MAX_VOICE_SECONDS] — the recording is not cut off silently,
     * because a message cut off mid-sentence is worse than one that stopped
     * with a warning the user could see.
     */
    data class Recording(val elapsedSeconds: Int) : VoiceNote

    /** Recorded, and going through upload and transcription. */
    data class Recorded(val durationSeconds: Int, val upload: UploadState) : VoiceNote

    /** The one state from which the post can be sent. */
    val canSend: Boolean
        get() = this is Recorded && upload is UploadState.Ready

    /** The text a moderator and a screen reader will see. Null until ready. */
    val transcript: String? get() = (this as? Recorded)?.upload?.transcript
}

sealed interface UploadState {
    /** The file is on the device and has not been sent anywhere yet. */
    data object Pending : UploadState

    data object Uploading : UploadState

    /**
     * Section 6.12's `transcribe` job, running. There is no progress figure in
     * `core-model` and inventing a percentage would be a lie about a
     * server-side job we cannot see; the state itself is the honest signal, and
     * the UI shows words rather than a bar.
     */
    data object Transcribing : UploadState

    data class Ready(val transcript: String) : UploadState {
        override val transcript: String? get() = transcript
    }

    /**
     * [retryable] decides whether the button says "Try again" or "Record again".
     * A failed *upload* is worth retrying; a failed transcription of a file that
     * is still on the device usually is not, and offering "try again" there
     * makes somebody wait out a timeout for nothing.
     */
    data class Failed(val message: String, val retryable: Boolean = true) : UploadState
}

/** A poll being written. Not a `core-model` [Poll] — that one already exists. */
sealed interface PollDraft {
    data object None : PollDraft

    data class Draft(
        val question: String = "",
        val options: List<String> = emptyList(),
    ) : PollDraft {
        /** Options with nothing typed in them, which are not really options. */
        val filledOptions: List<String> get() = options.filter { it.isNotBlank() }
    }
}

/** Where the post is in its journey to the server. */
sealed interface SendStatus {
    data object Idle : SendStatus

    /**
     * In flight. The composer stays fully populated — the text does not vanish
     * behind a spinner, because the user may want to read it back while it
     * sends.
     */
    data object Sending : SendStatus

    data class Failed(val error: ApiError) : SendStatus

    /** The post exists. The composer is reset to empty by the transition. */
    data class Sent(val postId: Int) : SendStatus
}

/**
 * Everything that stops a post being sent, as a typed value.
 *
 * The messages name the limit and the number, because a counter the user can
 * see going the wrong way and a sentence that says "900 of 1000" are the
 * difference between a fixable mistake and a dead button. None of these is a
 * modal; they render inline under the field.
 */
sealed interface ComposerProblem {
    val message: String

    data class TooLong(val limit: Int, val actual: Int) : ComposerProblem {
        override val message: String
            get() = "$actual characters. This circle takes $limit. Take $remaining off."
        val remaining: Int get() = actual - limit
    }

    data class TooManyImages(val limit: Int, val actual: Int) : ComposerProblem {
        override val message: String get() = "$actual photos. You can add $limit."
    }

    data class VoiceTooLong(val limitSeconds: Int, val actualSeconds: Int) : ComposerProblem {
        override val message: String
            get() = "${actualSeconds / 60}m ${actualSeconds % 60}s. Voice notes go up to " +
                "${limitSeconds / 60} minutes."
    }

    /**
     * A voice note that exists but has not been through transcription yet.
     * Named separately from "not finished" because the fix is a wait, not an
     * action, and the UI must not offer a button that does nothing.
     */
    data object VoiceNotTranscribed : ComposerProblem {
        override val message: String get() = "Wait for the words to appear before you post."
    }

    data object VoiceAndPoll : ComposerProblem {
        override val message: String
            get() = "A post is either a voice note or a poll, not both."
    }

    data object NothingToPost : ComposerProblem {
        override val message: String get() = "There is nothing to post yet."
    }

    data object PollNeedsQuestion : ComposerProblem {
        override val message: String get() = "Write the question for your poll."
    }

    data class PollNeedsOptions(val minimum: Int, val actual: Int) : ComposerProblem {
        override val message: String
            get() = "A poll needs at least $minimum choices. You have $actual."
    }

    data class TooManyPollOptions(val limit: Int, val actual: Int) : ComposerProblem {
        override val message: String
            get() = "$actual choices. A poll takes at most $limit."
    }

    data class PollOptionTooLong(val limit: Int, val index: Int) : ComposerProblem {
        override val message: String
            get() = "Choice ${index + 1} is longer than $limit characters."
    }
}

/**
 * The composer's transitions and its rules, as pure functions.
 *
 * Every `onX` takes the current state and returns a new one; none of them
 * throws, none of them reach the network, and none of them discard what the
 * user typed. That is what makes the failure path testable without a server,
 * which plan section 20 asks for.
 */
object Composer {

    /**
     * Plan section 6.6: "up to 6 photos".
     */
    const val MAX_IMAGES: Int = 6

    /**
     * Plan section 6.6: "voice notes up to 3 minutes".
     */
    const val MAX_VOICE_SECONDS: Int = 180

    /**
     * Text length.
     *
     * The plan does not fix a number, and this is a house decision rather than
     * a plan decision, so it is worth saying why: a circle post should read as
     * a message, not a document. A thousand characters is about a page of
     * dictated speech, which is roughly as long as this audience will read
     * before scrolling, and it is a round number a person can hold in their
     * head while watching the counter.
     */
    const val MAX_TEXT_CHARS: Int = 1000

    /**
     * Choices on a poll. Also a house decision.
     *
     * Four is what fits on a phone screen above the fold for somebody reading
     * at the largest text size section 4.3 allows; a seventh option would
     * push the last one off the bottom, and a choice nobody can see is a
     * choice nobody makes.
     */
    const val MAX_POLL_OPTIONS: Int = 4

    /** A poll with fewer than this is not a poll. */
    const val MIN_POLL_OPTIONS: Int = 2

    /** A choice is a phrase, not a sentence. */
    const val MAX_POLL_OPTION_CHARS: Int = 60

    /**
     * The text the user typed changed.
     *
     * Deliberately does *not* truncate. The characters stay exactly as typed
     * and the counter is left to go negative, because silently eating the end
     * of a message somebody dictated is unforgivable and impossible to notice
     * at the time.
     */
    fun onTextChanged(state: ComposerState, text: String): ComposerState =
        state.copy(text = text, send = SendStatus.Idle)

    /**
     * The recording stopped. The note is not sendable until transcription
     * finishes; [onTranscriptionReady] is the only way there.
     */
    fun onVoiceRecorded(state: ComposerState, durationSeconds: Int): ComposerState =
        state.copy(
            voice = VoiceNote.Recorded(durationSeconds, UploadState.Pending),
            send = SendStatus.Idle,
        )

    /**
     * The file is on its way up. A no-op once transcription has started, so a
     * late upload callback cannot drag a note backwards out of [UploadState.Transcribing].
     */
    fun onVoiceUploading(state: ComposerState): ComposerState {
        val recorded = state.voice as? VoiceNote.Recorded ?: return state
        if (recorded.upload != UploadState.Pending) return state
        return state.copy(voice = recorded.copy(upload = UploadState.Uploading))
    }

    /** Section 6.12's `transcribe` job came back. The only path to [VoiceNote.canSend]. */
    fun onTranscriptionReady(state: ComposerState, transcript: String): ComposerState =
        state.withVoiceUpload(UploadState.Ready(transcript))

    fun onTranscriptionFailed(
        state: ComposerState,
        message: String,
        retryable: Boolean = true,
    ): ComposerState = state.withVoiceUpload(UploadState.Failed(message, retryable))

    /**
     * Someone removed the note, or changed their mind. Explicit, and separate
     * from a failed send, so a user who dictated a note and then deleted it is
     * never left with a silently reappearing attachment.
     */
    fun onVoiceDiscarded(state: ComposerState): ComposerState =
        state.copy(voice = VoiceNote.None, send = SendStatus.Idle)

    /** A photo was added, and the limit is enforced by refusing the seventh. */
    fun onImageAdded(state: ComposerState, imageRef: String): ComposerState =
        if (state.images.size >= MAX_IMAGES) state
        else state.copy(images = state.images + imageRef, send = SendStatus.Idle)

    fun onImageRemoved(state: ComposerState, imageRef: String): ComposerState =
        state.copy(images = state.images - imageRef, send = SendStatus.Idle)

    fun onPollStarted(state: ComposerState): ComposerState =
        state.copy(poll = PollDraft.Draft(), send = SendStatus.Idle)

    fun onPollEdited(state: ComposerState, draft: PollDraft.Draft): ComposerState =
        state.copy(poll = draft, send = SendStatus.Idle)

    fun onPollCancelled(state: ComposerState): ComposerState =
        state.copy(poll = PollDraft.None, send = SendStatus.Idle)

    /**
     * Step one of section 3's confirmation: the user has asked to post and is
     * now looking at what they are about to make outward.
     */
    fun askConfirmation(state: ComposerState): ComposerState =
        if (validate(state).isEmpty()) state.copy(isConfirming = true) else state

    /** Step two's other half — "Not yet", which must be as easy to reach. */
    fun cancelConfirmation(state: ComposerState): ComposerState =
        state.copy(isConfirming = false)

    /**
     * The user pressed send.
     *
     * Returns the problems rather than sending when the confirmation is
     * missing, so the two-step is a gate in the state machine and not a
     * convention the UI layer is trusted to honour.
     */
    fun submit(state: ComposerState): SendAttempt {
        val problems = validate(state)
        if (problems.isNotEmpty()) return SendAttempt.Blocked(problems)
        if (!state.isConfirming) return SendAttempt.NeedsConfirmation
        return SendAttempt.Send(state.copy(send = SendStatus.Sending))
    }

    /**
     * The post failed.
     *
     * Everything the user wrote is still in the returned state — the text, the
     * voice note with its transcript, the photos, the poll — and the
     * confirmation is still open, so the fix is one tap on a button that is
     * already on screen. This is the most important function in the file.
     */
    fun sendFailed(state: ComposerState, error: ApiError): ComposerState =
        state.copy(send = SendStatus.Failed(error), isConfirming = true)

    /**
     * The post landed. The composer empties and stays in the same circle, which
     * is the expected next action for most people — a second post about
     * something else in the same circle.
     */
    fun sendSucceeded(state: ComposerState, postId: Int): ComposerState = ComposerState(
        circleId = state.circleId,
        authorId = state.authorId,
    ).copy(send = SendStatus.Sent(postId))

    /**
     * Everything standing between this state and a post.
     *
     * Order matters: content problems before housekeeping ones, so the first
     * line under the field is the one the user can act on.
     */
    fun validate(state: ComposerState): List<ComposerProblem> {
        val problems = mutableListOf<ComposerProblem>()

        if (!state.hasContent) problems += ComposerProblem.NothingToPost

        if (state.text.length > MAX_TEXT_CHARS) {
            problems += ComposerProblem.TooLong(MAX_TEXT_CHARS, state.text.length)
        }

        if (state.images.size > MAX_IMAGES) {
            problems += ComposerProblem.TooManyImages(MAX_IMAGES, state.images.size)
        }

        when (val voice = state.voice) {
            is VoiceNote.None -> Unit
            is VoiceNote.Recording ->
                problems += ComposerProblem.VoiceNotTranscribed

            is VoiceNote.Recorded -> {
                if (voice.durationSeconds > MAX_VOICE_SECONDS) {
                    problems += ComposerProblem.VoiceTooLong(
                        MAX_VOICE_SECONDS, voice.durationSeconds,
                    )
                }
                if (!voice.canSend) problems += ComposerProblem.VoiceNotTranscribed
            }
        }

        if (state.poll is PollDraft.Draft) {
            val draft = state.poll
            if (state.voice !is VoiceNote.None) problems += ComposerProblem.VoiceAndPoll

            if (draft.question.isBlank()) problems += ComposerProblem.PollNeedsQuestion
            if (draft.filledOptions.size < MIN_POLL_OPTIONS) {
                problems += ComposerProblem.PollNeedsOptions(
                    MIN_POLL_OPTIONS, draft.filledOptions.size,
                )
            }
            draft.filledOptions.forEachIndexed { index, option ->
                if (option.length > MAX_POLL_OPTION_CHARS) {
                    problems += ComposerProblem.PollOptionTooLong(MAX_POLL_OPTION_CHARS, index)
                }
            }
            if (draft.filledOptions.size > MAX_POLL_OPTIONS) {
                problems += ComposerProblem.TooManyPollOptions(
                    MAX_POLL_OPTIONS, draft.filledOptions.size,
                )
            }
        }

        return problems
    }

    /** Whether the send button is lit. Never the only thing telling the user why not. */
    fun canSend(state: ComposerState): Boolean = validate(state).isEmpty()

    /** The counter's text. A number, not a percentage — percentages mislead here. */
    fun counterText(state: ComposerState): String = state.charactersLeft.toString()

    private fun ComposerState.withVoiceUpload(upload: UploadState): ComposerState {
        val recorded = voice as? VoiceNote.Recorded ?: return this
        return copy(voice = recorded.copy(upload = upload), send = SendStatus.Idle)
    }
}

/**
 * What [Composer.submit] decided.
 *
 * [NeedsConfirmation] is a first-class outcome rather than an error, because
 * it is the normal first step of every post and the screen it leads to is a
 * normal screen.
 */
sealed interface SendAttempt {

    /** Not valid yet. The problems render inline, not as an alert. */
    data class Blocked(val problems: List<ComposerProblem>) : SendAttempt

    /** Valid, but the user has not seen the confirmation. */
    data object NeedsConfirmation : SendAttempt

    /** Go. The returned state is in [SendStatus.Sending] and still holds the text. */
    data class Send(val state: ComposerState) : SendAttempt
}
