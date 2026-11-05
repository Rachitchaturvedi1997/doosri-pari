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
 * Plan section 6.10, one medicine's daily schedule: the times it is taken at,
 * the days it runs, the food timing, and today's slots for that medicine.
 *
 * This is also where the guardian rule becomes visible. Two missed doses in a
 * row is when a family member is told — and only for medicines the pensioner
 * chose to share. The screen states both halves of that, because a user who
 * cannot say whether their family is being told has not meaningfully chosen.
 */
data class MedicineScheduleUiState(
    val loading: Boolean = true,
    val medicine: Medicine? = null,
    /** Today's slots for this medicine, resolved against the clock once. */
    val todaySlots: List<DoseSlotView> = emptyList(),
    val adherence: AdherenceSummary = AdherenceSummary(),
    val error: HealthError? = null,
) {
    /** Days the medicine runs. Empty means every day. */
    val everyDay: Boolean get() = medicine?.days.isNullOrEmpty()

    val scheduleSummary: String
        get() {
            val m = medicine ?: return ""
            val food = when (m.withFood) {
                FoodTiming.BeforeFood -> "before food"
                FoodTiming.AfterFood -> "after food"
                FoodTiming.WithFood -> "with food"
                FoodTiming.Anytime -> "any time"
            }
            return "${m.times.joinToString(", ")} · $food"
        }
}

class MedicineScheduleViewModel(
    private val repository: HealthRepository,
    private val medicineId: String,
    private val now: Instant,
    private val localDay: String,
) : ViewModel() {

    private val _state = MutableStateFlow(MedicineScheduleUiState())
    val state: StateFlow<MedicineScheduleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val medicine = repository.medicine(medicineId)
                val slots = repository.doseLog(localDay).filter { it.medicineId == medicineId }
                val views = slots.map {
                    it.asView(now, shared = medicine?.shareWithGuardian == true)
                }
                _state.update {
                    it.copy(
                        loading = false,
                        medicine = medicine,
                        todaySlots = views,
                        adherence = adherenceFor(
                            slots = slots,
                            shared = medicine?.shareWithGuardian == true,
                            now = now,
                        ),
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    /**
     * The per-medicine share control.
     *
     * The difference between family support and family surveillance is exactly
     * this toggle. Support is a daughter in Pune learning that Amma has missed
     * two blood-pressure tablets in a row and ringing her. Surveillance is the
     * same message sent for a vitamin the pensioner takes when they remember,
     * to a son who reads it as a report card. The app cannot tell those apart
     * from the data — the miss looks identical — so the pensioner decides, per
     * medicine, and the default is off.
     */
    fun setShared(shared: Boolean) {
        val before = _state.value.medicine ?: return
        _state.update { it.copy(medicine = before.copy(shareWithGuardian = shared)) }
        viewModelScope.launch {
            try {
                val saved = repository.setMedicineShared(before.id, shared)
                _state.update { it.copy(medicine = saved) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(medicine = before, error = e.asHealthError()) }
            }
        }
    }

    fun onMarkDose(slotId: String, mark: DoseMark) {
        // Optimistic, as on the home screen: the tick lands on the tap and is
        // rolled back if the write fails. A tick that was never saved would
        // tell the family a dose was taken when it was not.
        val before = _state.value.todaySlots
        _state.update { current ->
            current.copy(
                todaySlots = current.todaySlots.map {
                    if (it.slot.id == slotId) {
                        it.copy(slot = it.slot.copy(mark = mark, markedAt = now))
                    } else {
                        it
                    }
                },
            )
        }
        viewModelScope.launch {
            try {
                repository.markDose(slotId, mark)
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(todaySlots = before, error = e.asHealthError()) }
            }
        }
    }

    private fun Exception.asHealthError(): HealthError =
        if (this is java.io.IOException) HealthError.Offline else HealthError.Failed
}