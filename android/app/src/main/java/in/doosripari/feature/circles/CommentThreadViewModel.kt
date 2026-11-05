package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One post and its comments — the detail view the feed's cards open. Plan
 * section 6.6, under section 15.
 *
 * This is where a post's full text is shown (the feed previews three lines),
 * and it obeys the same handling rules as the feed: plain text, no markup, no
 * render-time links, and a held-for-review item shown as a notice rather than
 * as silence. Every comment here is also user-generated text from strangers,
 * including strangers replying to each other in a pensioner's thread.
 */
data class CommentThreadUiState(
    val loading: Boolean = true,
    val post: CirclePost? = null,
    val comments: List<CircleComment> = emptyList(),
    /** The box at the bottom. Sent only by the labelled button. */
    val draft: String = "",
    val sending: Boolean = false,
    val problem: String? = null,
) {
    val canSend: Boolean get() = draft.isNotBlank() && !sending

    val isEmptyAfterLoad: Boolean
        get() = !loading && post == null
}

sealed interface CommentThreadEvent {
    data class SetDraft(val text: String) : CommentThreadEvent

    /**
     * Send the comment. Fired only from the button; never on enter, never from
     * voice — sending somebody's words needs a visible confirmation.
     */
    data object Send : CommentThreadEvent

    data object Refresh : CommentThreadEvent
    data object DismissProblem : CommentThreadEvent
}

interface CommentThreadRepository {
    suspend fun post(postId: Int): CirclePost

    /** Oldest first: a conversation reads top to bottom. */
    suspend fun comments(postId: Int): List<CircleComment>

    /** Send one comment. Returns it as the server stored it (it may be held). */
    suspend fun addComment(postId: Int, text: String): CircleComment
}

class CommentThreadViewModel(
    private val repository: CommentThreadRepository,
    private val postId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(CommentThreadUiState())
    val state: StateFlow<CommentThreadUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: CommentThreadEvent) {
        when (event) {
            is CommentThreadEvent.SetDraft ->
                _state.update { it.copy(draft = event.text, problem = null) }
            CommentThreadEvent.Send -> send()
            CommentThreadEvent.Refresh -> refresh()
            CommentThreadEvent.DismissProblem ->
                _state.update { it.copy(problem = null) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val post = repository.post(postId)
                val comments = repository.comments(postId)
                _state.update {
                    it.copy(loading = false, post = post, comments = comments)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        problem = "This conversation did not load. Try again.",
                    )
                }
            }
        }
    }

    /**
     * Send, then clear the box only on success. A send that fails must leave
     * the user's words where they are — this audience types slowly and losing a
     * typed comment to a dropped packet is how they stop trying.
     */
    private fun send() {
        val current = _state.value
        if (!current.canSend) return

        _state.update { it.copy(sending = true, problem = null) }
        viewModelScope.launch {
            try {
                val comment = repository.addComment(postId, current.draft)
                _state.update {
                    it.copy(
                        sending = false,
                        draft = "",
                        comments = it.comments + comment,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        sending = false,
                        problem = "That did not send. Your words are still in the box — try again.",
                    )
                }
            }
        }
    }
}