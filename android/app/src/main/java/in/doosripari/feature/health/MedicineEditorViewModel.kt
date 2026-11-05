package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plan section 6.10, the medicine editor: name, dose, times, days, food
 * timing, stock and a photo of the strip.
 *
 * The draft lives in this ViewModel rather than in the composable, because a
 * half-filled medicine form is a real thing this audience will leave and come
 * back to: the doorbell rings mid-form and the name must still be there. It
 * also means the validation below can be tested without a screen.
 */
data class MedicineEditorUiState(
    val loading: Boolean = false,
    /** Set when editing an existing medicine; null when adding. */
    val editingId: String? = null,
    val name: String = "",
    val doseLabel: String = "",
    /** Local "HH:mm", ascending. At least one or the form cannot be saved. */
    val times: List<String> = emptyList(),
    /** The time field the user is typing into. Kept in state so it survives re-render. */
    val newTime: String = "",
    /** ISO days, 1 = Monday. Empty means every day. */
    val days: Set<Int> = emptySet(),
    val withFood: FoodTiming = FoodTiming.WithFood,
    val stockDaysText: String = "",
    /**
     * A photo of the strip, taken on the phone and kept on the phone. See the
     * document-safe boundary comment in `feature/me/MeRepository.kt`.
     */
    val photoOnDeviceUri: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** A plain sentence about what the user still has to fill in. */
    val validationError: String? = null,
    val error: HealthError? = null,
) {
    val isEditing: Boolean get() = editingId != null

    val canSave: Boolean get() = name.isNotBlank() && times.isNotEmpty()
}

class MedicineEditorViewModel(
    private val repository: HealthRepository,
    private val editingId: String? = null,
    /** Pre-fill from a voice reminder (plan section 5, "Bolo" routing). */
    prefillName: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MedicineEditorUiState(
            loading = editingId != null,
            editingId = editingId,
            name = prefillName.orEmpty(),
        ),
    )
    val state: StateFlow<MedicineEditorUiState> = _state.asStateFlow()

    init {
        if (editingId != null) loadExisting()
    }

    private fun loadExisting() {
        viewModelScope.launch {
            try {
                val medicine = repository.medicine(editingId)
                if (medicine == null) {
                    _state.update { it.copy(loading = false, error = HealthError.Failed) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        name = medicine.name,
                        doseLabel = medicine.doseLabel,
                        times = medicine.times,
                        days = medicine.days,
                        withFood = medicine.withFood,
                        stockDaysText = medicine.stockDaysLeft?.toString().orEmpty(),
                        photoOnDeviceUri = medicine.photoOnDeviceUri,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, validationError = null) }

    fun onDoseChange(value: String) = _state.update { it.copy(doseLabel = value) }

    fun onNewTimeChange(value: String) = _state.update { it.copy(newTime = value) }

    /** Accepts "8:00", "08:00" and "8 am" loosely; the schedule stores "HH:mm". */
    fun onAddTime() {
        val parsed = parseTime(_state.value.newTime) ?: run {
            _state.update { it.copy(validationError = "Write a time like 8:00 or 8 pm.") }
            return
        }
        _state.update {
            it.copy(
                times = (it.times + parsed).distinct().sorted(),
                newTime = "",
                validationError = null,
            )
        }
    }

    fun onRemoveTime(time: String) =
        _state.update { it.copy(times = it.times - time) }

    fun onToggleDay(day: Int) = _state.update {
        val days = if (day in it.days) it.days - day else it.days + day
        it.copy(days = days)
    }

    fun onFoodTimingChange(value: FoodTiming) = _state.update { it.copy(withFood = value) }

    fun onStockDaysChange(value: String) =
        _state.update { it.copy(stockDaysText = value.filter { c -> c.isDigit() }.take(3)) }

    fun onPhotoChange(uri: String?) = _state.update { it.copy(photoOnDeviceUri = uri) }

    /**
     * Saves and reports back. The screen then navigates away on [saved].
     *
     * Nothing here inspects the name against any drug list, and nothing here
     * looks at the dose. This module records what a person told it; the
     * clinical judgement belongs to their doctor and is never inferred from a
     * form field.
     */
    fun save() {
        val current = _state.value
        when {
            current.name.isBlank() ->
                _state.update { it.copy(validationError = "Enter the medicine's name.") }
            current.times.isEmpty() ->
                _state.update { it.copy(validationError = "Add at least one time of day.") }
            else -> {
                _state.update { it.copy(saving = true, validationError = null, error = null) }
                viewModelScope.launch {
                    try {
                        val stock = current.stockDaysText.toIntOrNull()
                        val saved = repository.saveMedicine(
                            Medicine(
                                id = current.editingId ?: "",
                                name = current.name.trim(),
                                doseLabel = current.doseLabel.trim(),
                                times = current.times,
                                days = current.days,
                                withFood = current.withFood,
                                stockDaysLeft = stock,
                                photoOnDeviceUri = current.photoOnDeviceUri,
                                // A new medicine is never shared by default.
                                // See [Medicine.shareWithGuardian].
                                shareWithGuardian = false,
                            ),
                        )
                        _state.update {
                            it.copy(saving = false, saved = true, editingId = saved.id)
                        }
                    } catch (e: Exception) {
                        _state.update { it.copy(saving = false, error = e.asHealthError()) }
                    }
                }
            }
        }
    }

    private fun Exception.asHealthError(): HealthError =
        if (this is java.io.IOException) HealthError.Offline else HealthError.Failed

    companion object {
        /** "8:00" / "08:00" / "8 pm" / "8:30 pm" → "HH:mm", else null. */
        internal fun parseTime(raw: String): String? {
            val text = raw.trim().lowercase()
            val evening = text.endsWith("pm")
            val morning = text.endsWith("am")
            val core = text.removeSuffix("pm").removeSuffix("am").trim()
            val parts = core.split(":")
            val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null
            val hour24 = when {
                evening && hour < 12 -> hour + 12
                morning && hour == 12 -> 0
                else -> hour
            }
            return "%02d:%02d".format(hour24, minute)
        }
    }
}