package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The suggested-circles screen. Plan section 6.1 step 8 ("Suggested circles:
 * batch, city and interests, all pre-ticked") and section 6.6, read under
 * section 15.
 *
 * Onboarding pre-ticks the suggestions. This screen is the moment the user gets
 * to find out *why* each one was ticked, and section 3's "nothing hidden"
 * turns the "why" from a nicety into the whole content of the screen.
 */
data class SuggestedCirclesUiState(
    val loading: Boolean = true,
    /** Everything the server suggested, in the order it sent them. */
    val all: List<CircleSuggestion> = emptyList(),
    /** Ids the user has switched on. Defaults to whatever the server pre-ticked. */
    val chosen: Set<Int> = emptySet(),
    val joining: Int? = null,
    val problem: String? = null,
    val finished: Boolean = false,
) {
    /**
     * Suggestions that can be explained.
     *
     * The reason is not decoration on the card; it is the difference between
     * a user being able to say "yes, that is my batch" and a user clicking a
     * name that means nothing to them. See `SuggestionReason` for the full
     * argument about why this is a moderation problem and not a design one.
     */
    val explained: List<CircleSuggestion>
        get() = all.filter { it.reason !is SuggestionReason.NoReason }

    /**
     * Suggestions with no reason we can give.
     *
     * A separate list rather than a badge on the same list, because they are
     * not the same kind of object: the first group the user can verify against
     * their own life, the second they cannot verify at all.
     */
    val unexplained: List<CircleSuggestion>
        get() = all.filter { it.reason is SuggestionReason.NoReason }

    val chosenCount: Int get() = chosen.size
}

/** Everything the screen can be told to do. */
sealed interface SuggestedCirclesEvent {
    /** The toggle beside a card. */
    data class ToggleChosen(val circleId: Int) : SuggestedCirclesEvent

    /**
     * Join now, without waiting for the "done" button.
     *
     * Only offered for explained circles. For the rest, the button is
     * "Open this circle first", because joining something we cannot explain is
     * not a decision the user has enough information to make.
     */
    data class JoinNow(val circleId: Int) : SuggestedCirclesEvent

    /** Section 6.1 step 8: accept every pre-ticked suggestion at once. */
    data object AcceptAll : SuggestedCirclesEvent

    data object Finish : SuggestedCirclesEvent
    data object DismissProblem : SuggestedCirclesEvent
}

/**
 * The screen's data source.
 *
 * An interface beside the ViewModel rather than a concrete repository, for the
 * reason `CheckInRepository` gives: a test passes a fake and the screen never
 * learns whether it is talking to a network.
 */
interface SuggestedCirclesRepository {
    /** Suggestions, each with the reason it was made. See `SuggestionReason`. */
    suspend fun suggestions(): List<CircleSuggestion>

    /** Ids the server pre-ticked at onboarding. */
    suspend fun preselected(): Set<Int>

    /** Join now. Returns the circle as it stands afterwards. */
    suspend fun join(circleId: Int): SaathiCircle

    /** Join every still-open choice in one call. */
    suspend fun joinAll(circleIds: Set<Int>): Int
}

class SuggestedCirclesViewModel(
    private val repository: SuggestedCirclesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SuggestedCirclesUiState())
    val state: StateFlow<SuggestedCirclesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val suggestions = repository.suggestions()
                val preselected = repository.preselected()
                _state.update {
                    it.copy(
                        loading = false,
                        all = suggestions,
                        // Only the pre-ticked ones start on. Nothing the user
                        // has not seen the reason for is ticked for them.
                        chosen = suggestions.map { it.circle.id }
                            .filter { it in preselected }
                            .toSet(),
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = readError(e)) }
            }
        }
    }

    fun onEvent(event: SuggestedCirclesEvent) {
        when (event) {
            is SuggestedCirclesEvent.ToggleChosen -> toggle(event.circleId)
            is SuggestedCirclesEvent.JoinNow -> joinNow(event.circleId)
            SuggestedCirclesEvent.AcceptAll -> acceptAll()
            SuggestedCirclesEvent.Finish -> finish()
            SuggestedCirclesEvent.DismissProblem ->
                _state.update { it.copy(problem = null) }
        }
    }

    private fun toggle(circleId: Int) {
        _state.update { current ->
            val next = if (circleId in current.chosen) {
                current.chosen - circleId
            } else {
                current.chosen + circleId
            }
            current.copy(chosen = next, problem = null)
        }
    }

    private fun acceptAll() {
        _state.update { current ->
            current.copy(chosen = current.explained.map { it.circle.id }.toSet())
        }
    }

    private fun joinNow(circleId: Int) {
        val current = _state.value
        val suggestion = current.all.firstOrNull { it.circle.id == circleId } ?: return
        if (current.joining != null) return

        _state.update { it.copy(joining = circleId, problem = null) }
        viewModelScope.launch {
            try {
                val joined = repository.join(circleId)
                _state.update { state ->
                    state.copy(
                        joining = null,
                        all = state.all.map {
                            if (it.circle.id != circleId) it
                            else it.copy(circle = joined)
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(joining = null, problem = readError(e)) }
            }
        }
    }

    private fun finish() {
        val current = _state.value
        if (current.joining != null) return
        if (current.chosen.isEmpty()) {
            _state.update {
                it.copy(problem = "Choose at least one circle, or come back to this later.")
            }
            return
        }

        _state.update { it.copy(joining = null) }
        viewModelScope.launch {
            try {
                repository.joinAll(current.chosen)
                _state.update { it.copy(finished = true) }
            } catch (e: Exception) {
                _state.update { it.copy(problem = readError(e)) }
            }
        }
    }

    private fun readError(e: Exception): String =
        "We could not reach Doosri Pari just now. Your circles are unchanged — try again."
}

/** Shown above a screen while it loads. Not an error. */
val SuggestedCirclesUiState.isEmptyAfterLoad: Boolean
    get() = !loading && all.isEmpty()
