package `in`.doosripari.feature.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.doosripari.data.CheckInPreferences
import `in`.doosripari.data.CheckInRepository
import `in`.doosripari.data.CheckInSnapshot
import `in`.doosripari.feature.checkin.CheckInMethod
import `in`.doosripari.feature.checkin.CheckInStage.CheckedIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the check-in screen shows. */
data class CheckInUiState(
    val loading: Boolean = true,
    val today: CheckInSnapshot? = null,
    val history: List<CheckInSnapshot> = emptyList(),
    val preferences: CheckInPreferences? = null,
    /** A check-in is being sent right now; the button is disabled. */
    val sending: Boolean = false,
    /** Set when a send failed. The screen offers another go. */
    val error: CheckInError? = null,
) {
    /**
     * Whether the day is already done. Deliberately not the same as "the stage
     * is terminal": a day closed by a helper reaching the user in person
     * ([CheckInStage.Resolved]) is not something this user did, and the
     * screen should still let them say they are fine themselves.
     */
    val isDone: Boolean get() = today?.isDone == true

    /** Somebody has been told this person may be unwell. */
    val isEscalated: Boolean get() = today?.isEscalated == true
}

enum class CheckInError { Offline, Failed }

class CheckInViewModel(
    private val repository: CheckInRepository,
    /**
     * The user's local day, as "YYYY-MM-DD". Injected rather than read from the
     * clock here so the screen can be tested at a fixed date, and so a caller
     * that already knows the local day does not recompute it.
     */
    private val localDay: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val today = repository.today(localDay)
                val history = repository.history(HISTORY_DAYS)
                _state.update {
                    it.copy(loading = false, today = today, history = history, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asCheckInError()) }
            }
        }
    }

    /**
     * The user pressed the big green button.
     *
     * The screen is updated optimistically — the button flips to a tick the
     * instant it is pressed — and rolled back if the send fails, because
     * waiting for a round trip to acknowledge a tap on a 96pt button feels
     * broken even when it is only a second.
     */
    fun checkIn() {
        if (_state.value.sending || _state.value.isDone) return

        val before = _state.value.today
        _state.update { current ->
            current.copy(
                sending = true,
                error = null,
                today = current.today?.copy(
                    stage = CheckedIn,
                    method = CheckInMethod.Tapped,
                ),
            )
        }

        viewModelScope.launch {
            try {
                val confirmed = repository.checkIn(CheckInMethod.Tapped)
                _state.update {
                    it.copy(sending = false, today = confirmed, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(sending = false, today = before, error = e.asCheckInError())
                }
            }
        }
    }

    private fun Exception.asCheckInError(): CheckInError =
        if (this is java.io.IOException) CheckInError.Offline else CheckInError.Failed

    companion object {
        /** The plan's Today screen shows two weeks of history. */
        const val HISTORY_DAYS = 14
    }
}
