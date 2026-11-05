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
 * Plan section 6.10, the dose log: one day of scheduled doses, each shown as
 * taken, skipped, snoozed, due — or missed.
 *
 * The rows come from the *schedule*, not from what somebody recorded, so
 * "8:00, nothing happened" always has a row. See `HealthRepository.kt` for why
 * the absence of a tap is the event, and `DoseLogScreen.kt` for how MISSED is
 * rendered.
 */
data class DoseLogUiState(
    val loading: Boolean = true,
    val localDay: String,
    val consents: HealthConsents = HealthConsents(),
    val doses: List<DoseSlotView> = emptyList(),
    /** One summary per medicine that has doses today, keyed by medicine id. */
    val adherenceByMedicine: Map<String, AdherenceSummary> = emptyMap(),
    val error: HealthError? = null,
) {
    val isHealthDataOn: Boolean get() = consents.healthData

    val missedCount: Int get() = doses.count { it.status == DoseStatus.Missed }

    /**
     * Medicines where the guardian line has been crossed. Empty unless the
     * pensioner shared that medicine — see [adherenceFor].
     */
    val guardianToldFor: List<String>
        get() = adherenceByMedicine.filterValues { it.guardianWillBeTold }.keys.toList()
}

class DoseLogViewModel(
    private val repository: HealthRepository,
    private val now: Instant,
    private val localDay: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DoseLogUiState(localDay = localDay))
    val state: StateFlow<DoseLogUiState> = _state.asStateFlow()

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
                        it.copy(loading = false, consents = consents, doses = emptyList())
                    }
                    return@launch
                }

                val medicines = repository.medicines()
                val sharedById = medicines.associate { it.id to it.shareWithGuardian }
                val slots = repository.doseLog(localDay)

                val adherence = slots
                    .groupBy { it.medicineId }
                    .mapValues { (medicineId, medicineSlots) ->
                        adherenceFor(
                            slots = medicineSlots,
                            shared = sharedById[medicineId] == true,
                            now = now,
                        )
                    }

                _state.update {
                    it.copy(
                        loading = false,
                        consents = consents,
                        doses = slots.map { slot ->
                            slot.asView(
                                now = now,
                                shared = sharedById[slot.medicineId] == true,
                                missedTwiceRunning =
                                    (adherence[slot.medicineId]?.missedInARow ?: 0) >=
                                        GUARDIAN_TELL_THRESHOLD,
                            )
                        },
                        adherenceByMedicine = adherence,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    /**
     * Record what the person did. Never called with "missed" — MISSED is
     * derived from silence, not chosen. See `HealthRepository.markDose`.
     */
    fun onMark(slotId: String, mark: DoseMark) {
        val before = _state.value.doses
        _state.update { current ->
            current.copy(
                doses = current.doses.map {
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
                _state.update { it.copy(doses = before, error = e.asHealthError()) }
            }
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