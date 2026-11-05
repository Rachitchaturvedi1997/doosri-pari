package `in`.doosripari.feature.sahayak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Sahayak screens show.
 *
 * The question and its answer live in one state object because they are one
 * task: the plan's wireframe (section 4.5) shows the question echoed at the top
 * of the answer, and a pensioner who taps Back is going back to *their
 * question*, not to a blank form. [showingAnswer] is therefore derived rather
 * than stored — a second "screen" boolean is how a codebase ends up with a
 * state machine that can show an answer about a question nobody can see.
 */
data class SahayakUiState(
    /** The text box on the ask screen. */
    val question: String = "",
    /** Set while the text is unacceptable; see [QuestionValidator]. */
    val problem: QuestionProblem? = null,

    val asking: Boolean = false,
    /** What was actually asked — echoed at the top of the answer screen. */
    val askedQuestion: String = "",
    val answer: SahayakAnswer? = null,
    val error: SahayakError? = null,

    val feedback: FeedbackState = FeedbackState.None,
    val escalation: EscalationState = EscalationState.None,

    val recent: List<SahayakQuestion> = emptyList(),
) {
    /** The answer side of the flow owns the screen once an ask is in flight or done. */
    val showingAnswer: Boolean
        get() = asking || answer != null || error != null
}

class SahayakViewModel(
    private val repository: SahayakRepository,
    /**
     * The user's language for retrieval and reply (plan 6.4: "detect it and
     * translate the question for retrieval; answer in the original language").
     * Injected so a test does not have to fake a locale.
     */
    private val language: String? = null,
    /**
     * Plan 5's "Bolo" voice routing deep-links into the question screen with
     * the question already in the box, so the user sees exactly what will be
     * asked before it is asked. Seeded here rather than mutated from the
     * composable: a voice question is input state, not a navigation side effect.
     */
    initialQuestion: String = "",
) : ViewModel() {

    private val _state = MutableStateFlow(SahayakUiState(question = initialQuestion))
    val state: StateFlow<SahayakUiState> = _state.asStateFlow()

    init {
        refreshRecent()
    }

    fun refreshRecent() {
        viewModelScope.launch {
            runCatching { repository.recent() }
                .onSuccess { list -> _state.update { it.copy(recent = list) } }
                // The recent list is a convenience. A failure here must not
                // paint the ask screen red — there is nothing wrong with the
                // question box just because history did not load.
                .onFailure { }
        }
    }

    fun onQuestionChange(text: String) {
        _state.update {
            // Clear the problem as they fix it rather than on submit. Telling
            // somebody twice about a number they have already deleted is how a
            // validation message becomes wallpaper.
            it.copy(question = text, problem = null)
        }
    }

    /** Fill the box from one of the example questions. */
    fun useExample(text: String) = onQuestionChange(text)

    fun ask() {
        val text = _state.value.question.trim()
        val problem = QuestionValidator.problemWith(text)
        if (problem != null) {
            _state.update { it.copy(problem = problem) }
            return
        }

        _state.update {
            it.copy(
                asking = true,
                problem = null,
                askedQuestion = text,
                answer = null,
                error = null,
                feedback = FeedbackState.None,
                escalation = EscalationState.None,
            )
        }
        submit(text)
    }

    /** "Try again" on the answer screen's could-not-reach card. The typed question is kept. */
    fun retry() {
        val text = _state.value.askedQuestion.ifBlank { _state.value.question }.trim()
        _state.update { it.copy(asking = true, error = null) }
        submit(text)
    }

    private fun submit(text: String) {
        viewModelScope.launch {
            runCatching { repository.ask(text, language) }
                .onSuccess { answer ->
                    _state.update {
                        it.copy(asking = false, answer = answer, error = null)
                    }
                    refreshRecent()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(asking = false, error = e.asSahayakError())
                    }
                }
        }
    }

    /** Back from the answer to the question, keeping the text so it can be rephrased. */
    fun backToQuestion() {
        _state.update {
            it.copy(
                asking = false,
                answer = null,
                error = null,
                feedback = FeedbackState.None,
                escalation = EscalationState.None,
            )
        }
    }

    /**
     * "Was this helpful? Yes / No" — plan 6.4's feedback control.
     *
     * Optimistic, because the answer is already on screen and making the user
     * wait for a round trip to say "yes" is a punishment for answering. A
     * failure flips the state to [FeedbackState.Failed] so the row can say so
     * instead of silently swallowing the tap.
     */
    fun sendFeedback(helpful: Boolean) {
        val questionId = _state.value.answer?.questionId ?: return
        _state.update { it.copy(feedback = FeedbackState.Sent(helpful)) }
        viewModelScope.launch {
            runCatching { repository.sendFeedback(questionId, helpful) }
                .onFailure { _state.update { it.copy(feedback = FeedbackState.Failed) } }
        }
    }

    /**
     * "Ask a volunteer" — plan 6.4's human escalation to verified volunteers
     * (T2+ retired pension or accounts officers, answers within 48 hours).
     */
    fun escalate() {
        if (_state.value.escalation is EscalationState.Sending) return
        val questionId = _state.value.answer?.questionId
        val question = _state.value.askedQuestion.ifBlank { _state.value.question }
        _state.update { it.copy(escalation = EscalationState.Sending) }
        viewModelScope.launch {
            runCatching { repository.escalate(question, questionId) }
                .onSuccess { sent -> _state.update { it.copy(escalation = sent) } }
                .onFailure { _state.update { it.copy(escalation = EscalationState.Failed) } }
        }
    }

    private fun Exception.asSahayakError(): SahayakError =
        if (this is java.io.IOException) SahayakError.Offline else SahayakError.Failed
}