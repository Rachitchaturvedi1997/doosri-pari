package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Plan section 6.10, the vitals log: blood pressure, sugar, weight and pulse,
 * with 30 days of history.
 *
 * This module records and shows. It never diagnoses, never interprets a blood
 * pressure, never suggests a dose change. The one thing it does beyond storing
 * the number is ask [VitalsLimits] whether the number is even plausible as
 * typed — and when it is not, the entry is flagged "show this to your doctor"
 * and nothing else is said. See the comment at the render site in
 * `VitalsScreen.kt` for why the line stops exactly there.
 */
data class VitalsUiState(
    val loading: Boolean = true,
    val consents: HealthConsents = HealthConsents(),
    val selectedKind: VitalKind = VitalKind.BloodPressure,
    /** Newest first, for the selected kind. */
    val entries: List<VitalEntry> = emptyList(),
    /** The entry sheet is open. */
    val sheetOpen: Boolean = false,
    val firstText: String = "",
    val secondText: String = "",
    val saving: Boolean = false,
    val validationError: String? = null,
    val error: HealthError? = null,
    /** The row the delete confirm is about. */
    val pendingDelete: VitalEntry? = null,
) {
    val isHealthDataOn: Boolean get() = consents.healthData

    /** True when the typed values fall inside [VitalsLimits]. */
    val draftIsPlausible: Boolean
        get() {
            val first = firstText.toDoubleOrNull() ?: return true
            val second = secondText.toDoubleOrNull()
            val firstOk = VitalsLimits.isPlausible(selectedKind, first)
            val secondOk = second == null || VitalsLimits.isPlausible(selectedKind, second)
            return firstOk && secondOk
        }

    val needsSecond: Boolean get() = selectedKind == VitalKind.BloodPressure

    val unit: String
        get() = when (selectedKind) {
            VitalKind.BloodPressure -> "mmHg"
            VitalKind.Sugar -> "mg/dL"
            VitalKind.Weight -> "kg"
            VitalKind.Pulse -> "bpm"
        }
}

class VitalsViewModel(
    private val repository: HealthRepository,
    private val now: Instant,
) : ViewModel() {

    private val _state = MutableStateFlow(VitalsUiState())
    val state: StateFlow<VitalsUiState> = _state.asStateFlow()

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
                        it.copy(loading = false, consents = consents, entries = emptyList())
                    }
                    return@launch
                }
                val kind = _state.value.selectedKind
                val entries = repository.vitals(kind, days = 30)
                _state.update {
                    it.copy(loading = false, consents = consents, entries = entries, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    fun onSelectKind(kind: VitalKind) {
        _state.update { it.copy(selectedKind = kind) }
        refresh()
    }

    fun openSheet() {
        _state.update {
            it.copy(sheetOpen = true, firstText = "", secondText = "", validationError = null)
        }
    }

    fun dismissSheet() {
        _state.update { it.copy(sheetOpen = false) }
    }

    fun onFirstChange(value: String) =
        _state.update { it.copy(firstText = value.filterDigitsAndDot(), validationError = null) }

    fun onSecondChange(value: String) =
        _state.update { it.copy(secondText = value.filterDigitsAndDot(), validationError = null) }

    fun save() {
        val current = _state.value
        val first = current.firstText.toDoubleOrNull()
        if (first == null) {
            _state.update { it.copy(validationError = "Enter the reading you see on the device.") }
            return
        }
        val second = current.secondText.toDoubleOrNull()
        if (current.needsSecond && second == null) {
            _state.update { it.copy(validationError = "Enter both numbers, like 130 and 80.") }
            return
        }

        _state.update { it.copy(saving = true, validationError = null, error = null) }
        viewModelScope.launch {
            try {
                val entry = repository.addVital(
                    VitalEntry(
                        id = "",
                        kind = current.selectedKind,
                        first = first,
                        second = if (current.needsSecond) second else null,
                        unit = current.unit,
                        at = now,
                        // The plausibility flag and nothing else. This is a
                        // typing guard, not a clinical judgement — see
                        // [VitalsLimits]. The doctor interprets; this app
                        // does not.
                        flaggedForDoctor = !current.draftIsPlausible,
                    ),
                )
                _state.update {
                    it.copy(
                        saving = false,
                        sheetOpen = false,
                        entries = listOf(entry) + it.entries,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.asHealthError()) }
            }
        }
    }

    fun onDeletePressed(entry: VitalEntry) {
        _state.update { it.copy(pendingDelete = entry) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /** Only reached through DoosriConfirmAlert, with verb buttons. */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update {
            it.copy(pendingDelete = null, entries = it.entries.filterNot { e -> e.id == target.id })
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

    private fun String.filterDigitsAndDot(): String =
        filter { it.isDigit() || it == '.' }.take(7)
}