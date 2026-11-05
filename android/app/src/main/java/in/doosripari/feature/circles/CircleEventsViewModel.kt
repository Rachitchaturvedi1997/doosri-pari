package `in`.doosripari.feature.circles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The events list. Plan section 6.6's events: date, place and cost, with RSVP
 * limits, reminders a day and two hours before, and a shared album afterwards.
 *
 * The reminders and the album are the backend's business; this screen is the
 * list a pensioner scans to decide whether Saturday is free. So each row leads
 * with the date and the title and keeps the rest as one quiet line — a card
 * with a paragraph in it is not a calendar.
 */
data class CircleEventsUiState(
    val loading: Boolean = true,
    val events: List<CircleEventCard> = emptyList(),
    val problem: String? = null,
) {
    val isEmptyAfterLoad: Boolean
        get() = !loading && events.isEmpty()
}

sealed interface CircleEventsEvent {
    data object Refresh : CircleEventsEvent
    data object DismissProblem : CircleEventsEvent
}

interface CircleEventsRepository {
    /** Soonest first. Across the user's circles. */
    suspend fun events(): List<CircleEventCard>
}

class CircleEventsViewModel(
    private val repository: CircleEventsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CircleEventsUiState())
    val state: StateFlow<CircleEventsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun onEvent(event: CircleEventsEvent) {
        when (event) {
            CircleEventsEvent.Refresh -> refresh()
            CircleEventsEvent.DismissProblem ->
                _state.update { it.copy(problem = null) }
        }
    }

    private fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        viewModelScope.launch {
            try {
                val events = repository.events()
                _state.update { it.copy(loading = false, events = events) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        problem = "The events did not load. Try again.",
                    )
                }
            }
        }
    }
}