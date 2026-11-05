package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who can read a post. Plan section 6.6's posts live inside a circle, so
 * "visibility" is who inside the circle sees it.
 *
 * ## There is no default, on purpose
 *
 * A default visibility is a choice the user did not make, made silently, about
 * who reads a pensioner's words. Section 3's "nothing hidden" and section 15's
 * moderation rules both land here: until the user has picked one of these by
 * hand, the Post button stays off and the screen says why in words. The same
 * rule the plan states for voice applies — nothing is posted without a visible
 * confirmation — and an unchosen audience is not a visible confirmation of
 * anything.
 */
enum class PostVisibility(val code: String, val label: String, val explanation: String) {
    Everyone(
        "everyone",
        "Everyone in this circle",
        "Anybody who has joined the circle can read this.",
    ),
    KnownOnly(
        "known_only",
        "Only members I know",
        "Only members you have talked to in the last 30 days.",
    ),
    ModeratorsOnly(
        "moderators_only",
        "Only the circle's moderators",
        "A private question to the office-bearers. Not shown in the feed.",
    ),
}

/**
 * The composer. Plan section 6.6's posts, and the `post` intent of section 5's
 * Bolo voice routing.
 *
 * ## The voice layer pre-fills and never posts
 *
 * Bolo's `post` intent arrives here as [initialText] — "Batch group mein likho,
 * kal milte hain" is already in the box when the screen opens. Nothing else
 * happens without a tap: the plan's rule is that the voice layer never posts,
 * calls, pays or sends without a visible confirmation, and the visible
 * confirmation here is the Post button, which says what it will do ("Post to
 * this circle") and stays disabled until the text and the audience are both
 * chosen. There is no code path in this ViewModel that posts from `init`.
 */
data class PostComposerUiState(
    val loading: Boolean = true,
    val text: String = "",
    val kind: PostKind = PostKind.Text,
    /** Null until the user picks one. Never defaulted — see [PostVisibility]. */
    val visibility: PostVisibility? = null,
    /** Set when the composer was opened from a circle. */
    val targetCircleId: Int? = null,
    /** The user's circles, for the "post to" chooser on the Bolo path. */
    val myCircles: List<SaathiCircle> = emptyList(),
    val posting: Boolean = false,
    /** Set once the post has been accepted. The screen confirms and leaves. */
    val posted: Boolean = false,
    val problem: String? = null,
) {
    val canPost: Boolean
        get() = text.isNotBlank() && visibility != null && !posting && !posted

    /** Words for the disabled Post button, so it never disables silently. */
    val whyNotReady: String?
        get() = when {
            posted -> null
            posting -> null
            text.isBlank() -> "Write something first."
            visibility == null -> "Choose who can see this before posting."
            else -> null
        }

    /** Where the post goes, in the user's words. */
    val destinationLabel: String?
        get() = targetCircleId?.let { id -> myCircles.firstOrNull { it.id == id }?.name }
}

sealed interface PostComposerEvent {
    data class SetText(val text: String) : PostComposerEvent
    data class SetKind(val kind: PostKind) : PostComposerEvent
    data class SetVisibility(val visibility: PostVisibility) : PostComposerEvent

    /** Bolo path: the user names the circle to post into. */
    data class ChooseCircle(val circleId: Int) : PostComposerEvent

    /**
     * Post now. Only ever fired from the labelled button — there is no auto
     * post, no send-on-enter, and no path from voice to this event.
     */
    data object Post : PostComposerEvent

    data object DismissProblem : PostComposerEvent
}

interface PostComposerRepository {
    /** For the "post to" chooser when the composer was opened without a circle. */
    suspend fun myCircles(): List<SaathiCircle>

    /** Publish. The server applies section 15's checks and may hold the post. */
    suspend fun createPost(
        circleId: Int,
        text: String,
        kind: PostKind,
        visibility: PostVisibility,
    ): CirclePost
}

class PostComposerViewModel(
    private val repository: PostComposerRepository,
    /**
     * The circle to post into, or null when the composer was opened from Bolo
     * without one. Null does not mean "everywhere" — it means the user must
     * choose, and the Post button waits for that too.
     */
    private val initialCircleId: Int?,
    /**
     * Bolo's spoken text. It lands in the box and nowhere else; see the class
     * KDoc for why nothing posts from here.
     */
    initialText: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        PostComposerUiState(
            text = initialText.orEmpty(),
            targetCircleId = initialCircleId,
        ),
    )
    val state: StateFlow<PostComposerUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: PostComposerEvent) {
        when (event) {
            is PostComposerEvent.SetText ->
                _state.update { it.copy(text = event.text, problem = null) }
            is PostComposerEvent.SetKind ->
                _state.update { it.copy(kind = event.kind, problem = null) }
            is PostComposerEvent.SetVisibility ->
                _state.update { it.copy(visibility = event.visibility, problem = null) }
            is PostComposerEvent.ChooseCircle ->
                _state.update { it.copy(targetCircleId = event.circleId, problem = null) }
            PostComposerEvent.Post -> post()
            PostComposerEvent.DismissProblem ->
                _state.update { it.copy(problem = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val circles = repository.myCircles()
                _state.update {
                    it.copy(
                        loading = false,
                        myCircles = circles.filter { circle -> circle.isJoined },
                        // One circle and no fixed target: no need to ask.
                        targetCircleId = it.targetCircleId
                            ?: circles.singleOrNull()?.id,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, problem = "Your circles did not load. Try again.")
                }
            }
        }
    }

    private fun post() {
        val current = _state.value
        val visibility = current.visibility ?: return
        val circleId = current.targetCircleId ?: run {
            _state.update {
                it.copy(problem = "Choose which circle to post to.")
            }
            return
        }
        if (!current.canPost) return

        _state.update { it.copy(posting = true, problem = null) }
        viewModelScope.launch {
            try {
                repository.createPost(circleId, current.text, current.kind, visibility)
                _state.update { it.copy(posting = false, posted = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        posting = false,
                        problem = "That did not post. Your words are still here — try again.",
                    )
                }
            }
        }
    }
}