package `in`.doosripari.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Plan section 6.10, the CGHS/ECHS finder: official lists refreshed monthly,
 * each showing "last updated".
 *
 * The date is shown next to every result and the search results never hide it.
 * A person who drives across town to a dispensary that closed last month has
 * been failed by an app that kept the freshness of its data to itself.
 */
data class FacilitiesUiState(
    val loading: Boolean = true,
    val query: String = "",
    /** Null means everything. */
    val kind: FacilityKind? = null,
    val facilities: List<Facility> = emptyList(),
    val error: HealthError? = null,
) {
    val isEmpty: Boolean get() = !loading && facilities.isEmpty()
}

class FacilitiesViewModel(
    private val repository: HealthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FacilitiesUiState())
    val state: StateFlow<FacilitiesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val current = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val facilities = repository.facilities(
                    kind = current.kind,
                    query = current.query.ifBlank { null },
                )
                _state.update {
                    it.copy(loading = false, facilities = facilities, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        refresh()
    }

    fun onSelectKind(kind: FacilityKind?) {
        _state.update { it.copy(kind = kind) }
        refresh()
    }

    private fun Exception.asHealthError(): HealthError =
        if (this is java.io.IOException) HealthError.Offline else HealthError.Failed
}