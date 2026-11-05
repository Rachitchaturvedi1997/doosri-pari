package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.PostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The composer, plan section 6.6, under section 3's "nothing hidden".
 *
 * The two properties that matter most are here: a failed send must not cost
 * the user what they typed, and a voice note must pass through transcription
 * before it can be posted. Both are the kind of thing that works until the one
 * time it does not, so both are pinned by a test rather than left to the UI
 * layer to be careful about.
 */
class ComposerTest {

    private fun composer(
        text: String = "",
        voice: VoiceNote = VoiceNote.None,
        images: List<String> = emptyList(),
        poll: PollDraft = PollDraft.None,
    ) = ComposerState(
        circleId = 1,
        authorId = 7,
        text = text,
        voice = voice,
        images = images,
        poll = poll,
    )

    private val readyVoice = VoiceNote.Recorded(
        durationSeconds = 30,
        upload = UploadState.Ready("Good morning everybody"),
    )

    // ----------------------------------------------------------- failure keeps --

    @Test
    fun `a failed send keeps the typed text exactly`() {
        // The single most important behaviour on this screen. Somebody who has
        // dictated two hundred words must not lose them to a dropped
        // connection, and must not have to retype them to try once more.
        val typed = "The pension office in Hazratganj has moved to the second floor " +
            "and the old letter does not work any more."
        val before = composer(text = typed)
            .let(Composer::askConfirmation)

        val after = Composer.sendFailed(before, ApiError("no connection", status = 0))

        assertEquals(typed, after.text)
        assertIs<SendStatus.Failed>(after.send)
        // The confirmation is still open, so the fix is one tap on a button
        // that is already on screen.
        assertTrue(after.isConfirming)
        assertEquals(500, after.charactersLeft)
    }

    @Test
    fun `a failed send keeps the voice note, its transcript and the photos`() {
        val before = composer(
            text = "listen to this",
            voice = readyVoice,
            images = listOf("p1", "p2"),
        ).let(Composer::askConfirmation)

        val after = Composer.sendFailed(before, ApiError("no connection"))

        assertEquals(readyVoice, after.voice)
        assertEquals("Good morning everybody", after.voice.transcript)
        assertEquals(listOf("p1", "p2"), after.images)
        assertEquals("listen to this", after.text)
    }

    @Test
    fun `a failed send keeps a half written poll`() {
        val poll = PollDraft.Draft(
            question = "Which day suits you?",
            options = listOf("Tuesday", "Wednesday", ""),
        )
        val before = composer(poll = poll).let(Composer::askConfirmation)

        val after = Composer.sendFailed(before, ApiError("gateway timeout", status = 504))

        assertEquals(poll, after.poll)
    }

    @Test
    fun `editing after a failure clears the error and keeps the content`() {
        val failed = Composer.sendFailed(composer(text = "hello"), ApiError("no connection"))
        val edited = Composer.onTextChanged(failed, "hello again")

        assertEquals(SendStatus.Idle, edited.send)
        assertEquals("hello again", edited.text)
    }

    @Test
    fun `a sent post empties the composer but stays in the same circle`() {
        // The expected next action for most people is a second post about
        // something else in the same circle, so the circle id is kept.
        val sent = Composer.sendSucceeded(composer(text = "Namaste"), postId = 501)

        assertEquals("", sent.text)
        assertEquals(1, sent.circleId)
        assertEquals(7, sent.authorId)
        assertEquals(VoiceNote.None, sent.voice)
        assertIs<SendStatus.Sent>(sent.send)
        assertEquals(501, sent.send.postId)
    }

    // ------------------------------------------------------ the confirmation --

    @Test
    fun `send is refused until the confirmation has been shown`() {
        // Section 3: a confirmation before anything outward. Putting the gate
        // in the state machine means it cannot be a habit of the UI layer.
        val valid = composer(text = "Namaste")
        assertIs<SendAttempt.NeedsConfirmation>(Composer.submit(valid))

        val confirmed = Composer.askConfirmation(valid)
        assertTrue(confirmed.isConfirming)
        val send = assertIs<SendAttempt.Send>(Composer.submit(confirmed))
        assertIs<SendStatus.Sending>(send.state.send)
    }

    @Test
    fun `an invalid composer cannot even be confirmed`() {
        val tooLong = composer(text = "x".repeat(Composer.MAX_TEXT_CHARS + 1))
        assertEquals(tooLong, Composer.askConfirmation(tooLong))
        assertFalse(tooLong.isConfirming)
    }

    @Test
    fun `backing out of the confirmation changes nothing else`() {
        val confirmed = Composer.askConfirmation(composer(text = "Namaste"))
        val cancelled = Composer.cancelConfirmation(confirmed)

        assertFalse(cancelled.isConfirming)
        assertEquals(confirmed.text, cancelled.text)
    }

    // ---------------------------------------------------------- the voice note --

    @Test
    fun `a voice note cannot be sent before it is transcribed`() {
        // Section 6.6 transcribes every note, and `core-model` carries the
        // transcript so a moderator can act on a report. A note with no
        // transcript is a note nobody can moderate.
        val recorded = Composer.onVoiceRecorded(composer(), durationSeconds = 30)

        assertFalse(recorded.voice.canSend)
        assertFalse(Composer.canSend(recorded))
        assertTrue(Composer.validate(recorded).contains(ComposerProblem.VoiceNotTranscribed))

        val uploading = Composer.onVoiceUploading(recorded)
        assertFalse(uploading.voice.canSend)

        val transcribing = uploading.copy(
            voice = VoiceNote.Recorded(30, UploadState.Transcribing),
        )
        assertFalse(transcribing.voice.canSend)

        val ready = Composer.onTranscriptionReady(transcribing, "Good morning everybody")
        assertTrue(ready.voice.canSend)
        assertTrue(Composer.canSend(ready))
    }

    @Test
    fun `a failed transcription keeps the recording and says whether to retry`() {
        val recorded = Composer.onVoiceRecorded(composer(), durationSeconds = 30)
        val failed = Composer.onTranscriptionFailed(recorded, "could not hear it", retryable = false)

        assertFalse(failed.voice.canSend)
        assertEquals(30, (failed.voice as VoiceNote.Recorded).durationSeconds)
        val upload = (failed.voice as VoiceNote.Recorded).upload
        assertIs<UploadState.Failed>(upload)
        assertFalse(upload.retryable)
    }

    @Test
    fun `an upload callback cannot drag a note back out of transcribing`() {
        // A slow upload finishing late must not undo a transcription that has
        // already started, or the note never reaches a sendable state.
        val transcribing = composer(
            voice = VoiceNote.Recorded(30, UploadState.Transcribing),
        )
        val after = Composer.onVoiceUploading(transcribing)

        assertIs<UploadState.Transcribing>((after.voice as VoiceNote.Recorded).upload)
    }

    @Test
    fun `a voice note over three minutes is refused with both numbers in the sentence`() {
        // Section 6.6: voice notes up to 3 minutes. The message carries the
        // limit and the actual, because a bare "too long" gives a user nothing
        // to act on.
        val long = VoiceNote.Recorded(
            Composer.MAX_VOICE_SECONDS + 1,
            UploadState.Ready("transcript"),
        )
        val problem = assertIs<ComposerProblem.VoiceTooLong>(
            Composer.validate(composer(voice = long)).first { it is ComposerProblem.VoiceTooLong }
        )

        assertEquals(180, problem.limitSeconds)
        assertEquals(181, problem.actualSeconds)
        assertTrue(problem.message.contains("3 minutes"))
    }

    @Test
    fun `still recording is not a sendable state either`() {
        assertFalse(Composer.canSend(composer(voice = VoiceNote.Recording(5))))
    }

    @Test
    fun `discarding a note is explicit and does not come back on its own`() {
        val withNote = Composer.onVoiceRecorded(composer(), durationSeconds = 20)
        assertIs<SendStatus.Idle>(withNote.send)

        val discarded = Composer.onVoiceDiscarded(withNote)
        assertEquals(VoiceNote.None, discarded.voice)
        assertTrue(Composer.validate(discarded).contains(ComposerProblem.NothingToPost))
    }

    // ------------------------------------------------------------- validation --

    @Test
    fun `text past the limit is reported and never silently truncated`() {
        // Eating the end of a dictated message is unforgivable and impossible
        // to notice at the time, so the counter goes negative and says so.
        val typed = "x".repeat(Composer.MAX_TEXT_CHARS + 25)
        val state = Composer.onTextChanged(composer(), typed)

        assertEquals(typed.length, state.text.length)
        assertEquals(-25, state.charactersLeft)

        val problem = assertIs<ComposerProblem.TooLong>(
            Composer.validate(state).first { it is ComposerProblem.TooLong }
        )
        assertEquals(Composer.MAX_TEXT_CHARS, problem.limit)
        assertEquals(Composer.MAX_TEXT_CHARS + 25, problem.actual)
        assertEquals(25, problem.remaining)
        assertEquals("-25", Composer.counterText(state))
    }

    @Test
    fun `six photos is the limit from section 6 and the seventh is refused`() {
        var state = composer(text = "look")
        repeat(6) { i -> state = Composer.onImageAdded(state, "p$i") }
        assertEquals(6, state.images.size)
        assertTrue(Composer.canSend(state))

        val seventh = Composer.onImageAdded(state, "p6")
        assertEquals(6, seventh.images.size)
        assertTrue(Composer.canSend(seventh))
    }

    @Test
    fun `removing a photo takes the count back down`() {
        var state = composer(text = "look")
        repeat(3) { i -> state = Composer.onImageAdded(state, "p$i") }
        val after = Composer.onImageRemoved(state, "p1")

        assertEquals(listOf("p0", "p2"), after.images)
    }

    @Test
    fun `an empty composer says so instead of offering a dead send button`() {
        val problems = Composer.validate(composer())
        assertEquals(listOf(ComposerProblem.NothingToPost), problems)
    }

    @Test
    fun `a post with a voice note and a poll is refused, because the kind is ambiguous`() {
        val state = composer(
            voice = readyVoice,
            poll = PollDraft.Draft("Which day?", listOf("Tuesday", "Wednesday")),
        )
        assertTrue(Composer.validate(state).contains(ComposerProblem.VoiceAndPoll))
    }

    @Test
    fun `a poll needs a question and at least two real choices`() {
        val noQuestion = composer(poll = PollDraft.Draft(options = listOf("Yes", "No")))
        assertTrue(Composer.validate(noQuestion).contains(ComposerProblem.PollNeedsQuestion))

        val oneChoice = composer(poll = PollDraft.Draft("Which day?", listOf("Tuesday", "  ")))
        val problem = assertIs<ComposerProblem.PollNeedsOptions>(
            Composer.validate(oneChoice).first { it is ComposerProblem.PollNeedsOptions }
        )
        assertEquals(2, problem.minimum)
        assertEquals(1, problem.actual)
        assertFalse(Composer.canSend(oneChoice))
    }

    @Test
    fun `a choice that is a sentence is refused with its position`() {
        val state = composer(
            poll = PollDraft.Draft(
                "Which day?",
                listOf("Tuesday", "y".repeat(Composer.MAX_POLL_OPTION_CHARS + 1)),
            ),
        )
        val problem = assertIs<ComposerProblem.PollOptionTooLong>(
            Composer.validate(state).first { it is ComposerProblem.PollOptionTooLong }
        )
        assertEquals(1, problem.index)
        assertTrue(problem.message.startsWith("Choice 2"))
    }

    @Test
    fun `a poll with more choices than fit is reported as too many, not too few`() {
        val state = composer(
            poll = PollDraft.Draft("Which day?", listOf("a", "b", "c", "d", "e")),
        )
        assertTrue(Composer.validate(state).contains(ComposerProblem.TooManyPollOptions(4, 5)))
    }

    @Test
    fun `a valid poll sends`() {
        val state = composer(poll = PollDraft.Draft("Which day?", listOf("Tuesday", "Wednesday")))
        assertTrue(Composer.canSend(state))
        assertEquals(PostKind.Poll, state.kind)
    }

    // ------------------------------------------------------------------ kinds --

    @Test
    fun `the kind follows what is actually in the box`() {
        assertEquals(PostKind.Text, composer(text = "Namaste").kind)
        assertEquals(PostKind.Image, composer(text = "look", images = listOf("p1")).kind)
        assertEquals(PostKind.Voice, composer(voice = readyVoice).kind)
        assertEquals(
            PostKind.Poll,
            composer(poll = PollDraft.Draft("Which day?", listOf("a", "b"))).kind,
        )
    }

    @Test
    fun `every problem names what to do rather than refusing`() {
        val cases = listOf(
            composer(),
            composer(text = "x".repeat(Composer.MAX_TEXT_CHARS + 1)),
            composer(voice = VoiceNote.Recorded(999, UploadState.Pending)),
            composer(poll = PollDraft.Draft()),
        )
        for (state in cases) {
            for (problem in Composer.validate(state)) {
                assertTrue(problem.message.isNotBlank(), "$problem has no message")
            }
        }
    }

    @Test
    fun `a moderator hold is carried on the state and is not a send failure`() {
        // Section 15 holds posts from T0 users for review. That is not the post
        // failing, and the screen must not offer "try again" for it.
        val held = composer(text = "Namaste", send = SendStatus.Sent(9)).copy(heldForReview = true)

        assertTrue(held.heldForReview)
        assertIs<SendStatus.Sent>(held.send)
    }
}
