package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plan section 6.10, reminders: refills at five days of stock, CGHS/ECHS
 * renewals, check-ups and "take a reading".
 *
 * A reminder is a nudge the pensioner asked for. Nothing here escalates to
 * anybody — escalation lives in the dose log, and only for shared medicines.
 */
data class HealthRemindersUiState(
    val loading: Boolean = true,
    val consents: HealthConsents = HealthConsents(),
    val reminders: List<HealthReminder> = emptyList(),
    /** The add sheet is open. */
    val sheetOpen: Boolean = false,
    val newTitle: String = "",
    val newKind: HealthReminderKind = HealthReminderKind.Refill,
    val newWhen: String = "",
    val saving: Boolean = false,
    val validationError: String? = null,
    val error: HealthError? = null,
    val pendingDelete: HealthReminder? = null,
) {
    val isHealthDataOn: Boolean get() = consents.healthData

    val upcoming: List<HealthReminder>
        get() = reminders.filter { !it.done }.sortedBy { it.at }

    val done: List<HealthReminder>
        get() = reminders.filter { it.done }.sortedByDescending { it.at }
}

class HealthRemindersViewModel(
    private val repository: HealthRepository,
    /**
     * Pre-fill from a voice reminder (plan section 5, "Bolo" routing:
     * "Shaam 8 baje BP ki dawai" → new reminder, confirm screen).
     */
    prefillTitle: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HealthRemindersUiState(sheetOpen = prefillTitle != null, newTitle = prefillTitle.orEmpty()),
    )
    val state: StateFlow<HealthRemindersUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val consents = repository.consents()
                if (!consents.healthData) {
                    _state.update {
                        it.copy(loading = false, consents = consents, reminders = emptyList())
                    }
                    return@launch
                }
                val reminders = repository.reminders()
                _state.update {
                    it.copy(loading = false, consents = consents, reminders = reminders, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    fun openSheet() {
        _state.update {
            it.copy(sheetOpen = true, newTitle = "", newWhen = "", validationError = null)
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetOpen = false) }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(newTitle = value, validationError = null) }

    fun onKindChange(value: HealthReminderKind) = _state.update { it.copy(newKind = value) }

    /** Free text for now: "tomorrow evening", "1 Nov". A picker lands with the scheduler. */
    fun onWhenChange(value: String) = _state.update { it.copy(newWhen = value, validationError = null) }

    fun add() {
        val current = _state.value
        if (current.newTitle.isBlank()) {
            _state.update { it.copy(validationError = "Say what the reminder is for.") }
            return
        }
        _state.update { it.copy(saving = true, validationError = null) }
        viewModelScope.launch {
            try {
                // The "when" the person said is kept in the words for now and
                // parsed into `at` by the scheduler in the full build. It is
                // never dropped: it rides along in the title so the person can
                // see the reminder they asked for, exactly as they said it.
                val spoken = current.newWhen.trim()
                val title = if (spoken.isEmpty()) {
                    current.newTitle.trim()
                } else {
                    "${current.newTitle.trim()} · $spoken"
                }
                val saved = repository.saveReminder(
                    HealthReminder(
                        id = "",
                        kind = current.newKind,
                        title = title,
                        at = kotlinx.datetime.Clock.System.now(),
                    ),
                )
                _state.update {
                    it.copy(
                        saving = false,
                        sheetOpen = false,
                        reminders = listOf(saved) + it.reminders,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.asHealthError()) }
            }
        }
    }

    fun setDone(id: String, done: Boolean) {
        val before = _state.value.reminders
        _state.update { current ->
            current.copy(
                reminders = current.reminders.map {
                    if (it.id == id) it.copy(done = done) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                repository.setReminderDone(id, done)
            } catch (e: Exception) {
                _state.update { it.copy(reminders = before, error = e.asHealthError()) }
            }
        }
    }

    fun onDeletePressed(reminder: HealthReminder) {
        _state.update { it.copy(pendingDelete = reminder) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /** Only reached through DoosriConfirmAlert, with verb buttons. */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update {
            it.copy(
                pendingDelete = null,
                reminders = it.reminders.filterNot { r -> r.id == target.id },
            )
        }
    }

    /**
     * Turning health data on. Named, separate, and withdrawable in Settings
     * (plan section 14).
     */
    fun grantHealthConsent() {
        viewModelScope.launch {
            try {
                val consents = repository.grantHealthConsent()
                _state.update { it.copy(consents = consents) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.asHealthError()) }
            }
        }
    }

    private fun Exception.asHealthError(): HealthError =
        if (this is java.io.IOException) HealthError.Offline else HealthError.Failed
}