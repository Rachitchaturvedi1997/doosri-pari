package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One event and its RSVP. Plan section 6.6.
 *
 * ## RSVP is a set, never a toggle
 *
 * See [RsvpChoice] for the argument. This app's users double-tap, often because
 * the first tap did not look like it registered. So:
 *
 *  - Tapping the answer you already gave does **nothing** — not a withdrawal,
 *    not a second booking, and never an error dialog. Two taps on "I'm going"
 *    are one RSVP; the state is set the first time and the second tap is
 *    absorbed silently. (There is not even a toast: scolding a pensioner for
 *    tapping twice is how you teach them to fear taps.)
 *  - Tapping a *different* answer changes it. The last deliberate choice wins,
 *    and the change is optimistic — the words flip immediately and roll back
 *    with an apology if the network refuses.
 *  - The repository is called once per answer, so a double tap cannot book two
 *    places at a full event.
 */
data class CircleEventDetailUiState(
    val loading: Boolean = true,
    val event: CircleEventCard? = null,
    /** The answer being sent right now, if any. */
    val sendingRsvp: RsvpChoice? = null,
    val problem: String? = null,
)

sealed interface CircleEventDetailEvent {
    /** Set my answer. Idempotent for the answer already given — see the KDoc. */
    data class Rsvp(val choice: RsvpChoice) : CircleEventDetailEvent

    data object Refresh : CircleEventDetailEvent
    data object DismissProblem : CircleEventDetailEvent
}

interface CircleEventDetailRepository {
    suspend fun event(eventId: Int): CircleEventCard

    /** Set the answer. Returns the event as it stands afterwards. */
    suspend fun rsvp(eventId: Int, choice: RsvpChoice): CircleEventCard
}

class CircleEventDetailViewModel(
    private val repository: CircleEventDetailRepository,
    private val eventId: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(CircleEventDetailUiState())
    val state: StateFlow<CircleEventDetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: CircleEventDetailEvent) {
        when (event) {
            is CircleEventDetailEvent.Rsvp -> rsvp(event.choice)
            CircleEventDetailEvent.Refresh -> refresh()
            CircleEventDetailEvent.DismissProblem ->
                _state.update { it.copy(problem = null) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val event = repository.event(eventId)
                _state.update { it.copy(loading = false, event = event) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, problem = "This event did not load. Try again.")
                }
            }
        }
    }

    /**
     * Set the answer. The idempotent behaviour promised in the class KDoc lives
     * in exactly these two lines: the same answer returns before any network
     * call and before any state change, so a second tap is one RSVP.
     */
    fun rsvp(choice: RsvpChoice) {
        val current = _state.value.event ?: return
        if (current.myRsvp == choice) return
        if (_state.value.sendingRsvp != null) return

        // Optimistic: the words flip now, because waiting for a round trip to
        // say "You are going" makes a tap feel broken even at one second.
        val before = current
        _state.update {
            it.copy(
                event = current.copy(myRsvp = choice),
                sendingRsvp = choice,
                problem = null,
            )
        }

        viewModelScope.launch {
            try {
                val confirmed = repository.rsvp(eventId, choice)
                _state.update {
                    it.copy(event = confirmed, sendingRsvp = null, problem = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        event = before,
                        sendingRsvp = null,
                        problem = "That answer did not send. Tap it again.",
                    )
                }
            }
        }
    }
}