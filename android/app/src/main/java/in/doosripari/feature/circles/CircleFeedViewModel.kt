package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * A circle's feed. Plan section 6.6 ("Feed: calm, not addictive") read under
 * section 15.
 *
 * ## What the feed is allowed to be
 *
 * Newest first. No engagement ranking — the boost for people the user talked to
 * in the last 30 days happens server-side in the order it sends, and this
 * screen shows that order as given rather than re-ranking it. After
 * [MAX_POSTS_PER_DAY] posts the state says the day is done and the screen prints
 * "You're all caught up. See you tomorrow." That cap is the plan's own, and it
 * is also what makes an unbounded feed safe to put in a scrolling page column
 * (see `CircleFeedScreen`).
 *
 * ## What the screen does with a stranger's words
 *
 * Nothing creative. The rules live on [CirclePost] — plain text rendered by
 * `Text`, no markup, no render-time linkification, photos behind an explicit
 * load tap, and a held-for-review post shown as a notice rather than as
 * silence. This ViewModel only moves those fields around; it never edits,
 * truncates or "cleans" a post, because a moderation tool that silently rewrites
 * a pensioner's post is a moderation tool nobody can appeal.
 */
data class CircleFeedUiState(
    val loading: Boolean = true,
    val circle: SaathiCircle? = null,
    /** Newest first, at most [CircleFeedViewModel.MAX_POSTS_PER_DAY] long. */
    val posts: List<CirclePost> = emptyList(),
    /** Ids whose photos the user has explicitly asked to load. */
    val revealedPhotos: Set<Int> = emptySet(),
    /** Ids this user has tapped "Namaste" on. A set, never a toggle: see rsvp. */
    val namasted: Set<Int> = emptySet(),
    /** The day is finished. The screen shows the caught-up line and stops. */
    val caughtUp: Boolean = false,
    /**
     * The moment this feed was opened, injected into the ViewModel. Carried in
     * state so every "3 days ago" on the screen is computed against the same
     * instant rather than against whatever the clock says mid-scroll.
     */
    val now: Instant = Instant.fromEpochMilliseconds(0L),
    val problem: String? = null,
) {
    val isEmptyAfterLoad: Boolean
        get() = !loading && posts.isEmpty()
}

sealed interface CircleFeedEvent {
    data object Refresh : CircleFeedEvent

    /**
     * The user tapped "Show photos" under a post.
     *
     * This is a privacy decision, not a lazy-loading one: see [CirclePost] on
     * why images from a stranger's URL are not fetched until asked for.
     */
    data class RevealPhotos(val postId: Int) : CircleFeedEvent

    /** The "Namaste" reaction. Tapping twice is one reaction — never an error. */
    data class Namaste(val postId: Int) : CircleFeedEvent

    data object DismissProblem : CircleFeedEvent
}

interface CircleFeedRepository {
    suspend fun circle(circleId: Int): SaathiCircle

    /** Newest first, already ordered by the server's calm ranking. */
    suspend fun feed(circleId: Int, limit: Int): List<CirclePost>

    /**
     * The photo references for one post. Called only after the user taps the
     * load control — see [CirclePost.photoRefs].
     */
    suspend fun photoRefs(postId: Int): List<String>

    /** One reaction. Returns the post as it stands afterwards. */
    suspend fun namaste(postId: Int): CirclePost
}

class CircleFeedViewModel(
    private val repository: CircleFeedRepository,
    private val circleId: Int,
    /**
     * The moment the feed was opened. Injected so "3 days ago" can be tested at
     * a fixed clock — the same reason `CheckInViewModel` takes the local day.
     */
    private val now: Instant,
) : ViewModel() {

    private val _state = MutableStateFlow(CircleFeedUiState())
    val state: StateFlow<CircleFeedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: CircleFeedEvent) {
        when (event) {
            CircleFeedEvent.Refresh -> refresh()
            is CircleFeedEvent.RevealPhotos -> revealPhotos(event.postId)
            is CircleFeedEvent.Namaste -> namaste(event.postId)
            CircleFeedEvent.DismissProblem -> _state.update { it.copy(problem = null) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val circle = repository.circle(circleId)
                val posts = repository.feed(circleId, MAX_POSTS_PER_DAY)
                _state.update {
                    it.copy(
                        loading = false,
                        circle = circle,
                        posts = posts,
                        caughtUp = posts.size >= MAX_POSTS_PER_DAY,
                        now = now,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        problem = "We could not reach Doosri Pari just now. Your circle is unchanged — try again.",
                    )
                }
            }
        }
    }

    private fun revealPhotos(postId: Int) {
        if (postId in _state.value.revealedPhotos) return
        viewModelScope.launch {
            try {
                val refs = repository.photoRefs(postId)
                _state.update {
                    it.copy(
                        revealedPhotos = it.revealedPhotos + postId,
                        posts = it.posts.map { post ->
                            if (post.id == postId) post.copy(photoRefs = refs) else post
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(problem = "Those photos did not load. Nothing was shown.")
                }
            }
        }
    }

    /**
     * "Namaste" is **set**, not toggled — the same rule as RSVP. This audience
     * double-taps, and a second tap that withdraws a greeting somebody already
     * saw is a small public embarrassment, not a feature. The repository is
     * called once; a repeat tap is a no-op rather than an error.
     */
    private fun namaste(postId: Int) {
        if (postId in _state.value.namasted) return
        _state.update { it.copy(namasted = it.namasted + postId) }
        viewModelScope.launch {
            try {
                val updated = repository.namaste(postId)
                _state.update {
                    it.copy(posts = it.posts.map { post -> if (post.id == postId) updated else post })
                }
            } catch (e: Exception) {
                // Roll the optimistic mark back and say so in words. A reaction
                // that silently vanished teaches people the app eats taps.
                _state.update {
                    it.copy(
                        namasted = it.namasted - postId,
                        problem = "That did not send. Tap Namaste again.",
                    )
                }
            }
        }
    }

    companion object {
        /**
         * The plan's cap: "after 30 posts: 'You're all caught up. See you
         * tomorrow.'" It is also the bound that lets the feed live in the
         * design system's scrolling page instead of a lazy list.
         */
        const val MAX_POSTS_PER_DAY = 30
    }
}