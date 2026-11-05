package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Saathi tab's root screen: the circles this user is already in. Plan
 * section 6.6.
 *
 * Deliberately the dullest screen in the feature. It lists what the user joined
 * and nothing else — no suggestions, no "you might like", no unread counts in
 * red. Section 6.6's feed rules ("calm, not addictive", a daily catch-up line,
 * no engagement ranking) start here: a tab that opens on a list of strangers'
 * prompts is the addictive shape the plan is avoiding.
 */
data class MyCirclesUiState(
    val loading: Boolean = true,
    /** The user's circles, memberships first, pending requests last. */
    val circles: List<SaathiCircle> = emptyList(),
    val problem: String? = null,
) {
    /** Joined and usable right now. */
    val joined: List<SaathiCircle>
        get() = circles.filter { it.isJoined }

    /** Asked for, waiting for an office-bearer (association circles, section 6.6). */
    val pending: List<SaathiCircle>
        get() = circles.filter { !it.isJoined && it.isPending }

    val isEmptyAfterLoad: Boolean
        get() = !loading && circles.isEmpty()
}

sealed interface MyCirclesEvent {
    data object Refresh : MyCirclesEvent
    data object DismissProblem : MyCirclesEvent
}

/**
 * What the screen reads. An interface so a test passes a fake — the reason
 * `CheckInRepository` gives, applied here.
 */
interface MyCirclesRepository {
    /** The user's circles. Newest membership first. */
    suspend fun myCircles(): List<SaathiCircle>
}

class MyCirclesViewModel(
    private val repository: MyCirclesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MyCirclesUiState())
    val state: StateFlow<MyCirclesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: MyCirclesEvent) {
        when (event) {
            MyCirclesEvent.Refresh -> refresh()
            MyCirclesEvent.DismissProblem -> _state.update { it.copy(problem = null) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val circles = repository.myCircles()
                _state.update {
                    it.copy(
                        loading = false,
                        circles = circles.sortedBy { circle -> !circle.isJoined },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        problem = "We could not reach Doosri Pari just now. Your circles are unchanged — try again.",
                    )
                }
            }
        }
    }
}