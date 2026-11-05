package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plan section 6.10, the medicines list.
 *
 * The list is the pensioner's own record. It shows what is scheduled, what is
 * running out, and — the one control that matters — which of these medicines
 * the family may be told about. Nothing here reads a prescription or suggests
 * a change; see [HealthRepository] for why that line exists at all.
 */
data class MedicinesUiState(
    val loading: Boolean = true,
    val consents: HealthConsents = HealthConsents(),
    val medicines: List<Medicine> = emptyList(),
    /**
     * The row the confirm alert is about. Held in state rather than in the
     * composable so the alert survives a re-render and so a test can drive the
     * whole delete flow — press remove, confirm — without a dialog to click.
     */
    val pendingDelete: Medicine? = null,
    val deletedName: String? = null,
    val error: HealthError? = null,
) {
    val isHealthDataOn: Boolean get() = consents.healthData

    /** Plan section 6.10: a refill alert at five days of stock. */
    val needsRefill: List<Medicine> get() = medicines.filter { it.needsRefillSoon }
}

class MedicinesViewModel(
    private val repository: HealthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MedicinesUiState())
    val state: StateFlow<MedicinesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val consents = repository.consents()
                if (!consents.healthData) {
                    // Nothing is loaded behind a closed consent. See the same
                    // rule in HealthHomeViewModel.
                    _state.update {
                        it.copy(loading = false, consents = consents, medicines = emptyList())
                    }
                    return@launch
                }
                val medicines = repository.medicines()
                _state.update {
                    it.copy(loading = false, consents = consents, medicines = medicines, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    /**
     * The per-medicine share toggle.
     *
     * This is the whole of the family link, and it is optimistic in both
     * directions: a person turning sharing *off* must see it off instantly,
     * because the question they are asking is "am I still being watched?" and a
     * spinner in that moment is an answer of its own. Rolled back on failure,
     * and the rollback is visible — a toggle that silently failed is the one
     * bug this control must never ship with.
     */
    fun setShared(id: String, shared: Boolean) {
        val before = _state.value.medicines
        _state.update { current ->
            current.copy(
                medicines = current.medicines.map {
                    if (it.id == id) it.copy(shareWithGuardian = shared) else it
                },
            )
        }
        viewModelScope.launch {
            try {
                val saved = repository.setMedicineShared(id, shared)
                _state.update { current ->
                    current.copy(
                        medicines = current.medicines.map {
                            if (it.id == saved.id) saved else it
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(medicines = before, error = e.asHealthError()) }
            }
        }
    }

    /** The screen asks for a confirm first; this opens it. */
    fun onDeletePressed(medicine: Medicine) {
        _state.update { it.copy(pendingDelete = medicine) }
    }

    fun dismissDelete() {
        _state.update { it.copy(pendingDelete = null) }
    }

    /** Only reached through DoosriConfirmAlert, with verb buttons. */
    fun confirmDelete() {
        val target = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            try {
                repository.deleteMedicine(target.id)
                _state.update {
                    it.copy(
                        medicines = it.medicines.filterNot { m -> m.id == target.id },
                        deletedName = target.name,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.asHealthError()) }
            }
        }
    }

    fun acknowledgeDeleted() {
        _state.update { it.copy(deletedName = null) }
    }

    /**
     * Turning health data on. Named, separate, and withdrawable in Settings
     * (plan section 14) — this is not the guardian switch, and the two are
     * never set together.
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
}