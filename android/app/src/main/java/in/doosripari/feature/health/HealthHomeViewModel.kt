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
 * The Swasthya hub: plan section 6.10.
 *
 * One screen with five things on it, because the plan's users will not find a
 * second and third level of health menu: today's doses, the last reading of
 * each kind of vital, what is coming, where the nearest empanelled hospital
 * is, and the switch that says whether any of it is being recorded at all.
 */
data class HealthHomeUiState(
    val loading: Boolean = true,
    val consents: HealthConsents = HealthConsents(),
    val todayDoses: List<DoseSlot> = emptyList(),
    /**
     * [todayDoses] already resolved against the clock, with the guardian flags
     * folded in. Resolving once here rather than in each row is deliberate: if
     * two rows each call `statusAt`, a future edit could give them two
     * different instants and the day would disagree with itself on screen.
     */
    val doses: List<DoseSlotView> = emptyList(),
    val lastVitals: List<VitalEntry> = emptyList(),
    val upcomingReminders: List<HealthReminder> = emptyList(),
    val facilities: List<Facility> = emptyList(),
    val error: HealthError? = null,
) {
    /**
     * False until the person turns health data on.
     *
     * When this is false every list below it is empty *by design*, and the
     * screen explains that rather than showing a blank list. A blank list of
     * medicines reads as "you have no medicines"; the truth is "you have not
     * asked us to keep any". That difference is the whole of the DPDP consent,
     * and a user who cannot tell which one they are looking at has not really
     * consented to anything.
     */
    val isHealthDataOn: Boolean get() = consents.healthData

    val missedCount: Int get() = doses.count { it.status == DoseStatus.Missed }

    val takenCount: Int get() = doses.count { it.status == DoseStatus.Taken }

    /** True when somebody in the family has been told, so the UI can say so. */
    val guardianHasBeenTold: Boolean
        get() = doses.any { it.medicineSharesWithGuardian && it.missedTwiceRunning }

    val dueCount: Int get() = doses.count { it.status == DoseStatus.Pending }
}

/** A dose plus everything a screen needs to draw it, decided once, in one place. */
data class DoseSlotView(
    val slot: DoseSlot,
    val status: DoseStatus,
    val medicineSharesWithGuardian: Boolean,
    val missedTwiceRunning: Boolean,
)

/**
 * `now` is a constructor parameter rather than a call to the clock, so that a
 * test can pin the instant and assert that a dose nobody touched reads MISSED.
 * It also means a screen sitting open across a dose time re-renders correctly
 * when `refresh()` runs, rather than showing a stale "pending" until the next
 * navigation.
 */
private fun DoseSlot.viewAt(now: Instant): DoseSlotView = DoseSlotView(
    slot = this,
    status = statusAt(now),
    medicineSharesWithGuardian = false,
    missedTwiceRunning = false,
)

class HealthHomeViewModel(
    private val repository: HealthRepository,
    private val now: Instant,
    private val localDay: String,
) : ViewModel() {

    private val _state = MutableStateFlow(HealthHomeUiState())
    val state: StateFlow<HealthHomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val consents = repository.consents()
                if (!consents.healthData) {
                    // Nothing is loaded behind a closed consent. Not "loaded and
                    // found to be empty" — not loaded, so that a future change
                    // to this code cannot quietly start reading records the
                    // person has not agreed to keep.
                    _state.update {
                        it.copy(
                            loading = false,
                            consents = consents,
                            todayDoses = emptyList(),
                            doses = emptyList(),
                            lastVitals = emptyList(),
                            error = null,
                        )
                    }
                    return@launch
                }

                val medicines = repository.medicines()
                val sharedIds = medicines.filter { it.shareWithGuardian }.map { it.id }.toSet()
                val doses = repository.doseLog(localDay)
                val vitals = VitalKind.entries.mapNotNull { kind ->
                    repository.vitals(kind, days = 1).firstOrNull()
                }
                val reminders = repository.reminders().filter { !it.done }
                val facilities = repository.facilities(kind = null, query = null)

                _state.update {
                    it.copy(
                        loading = false,
                        consents = consents,
                        todayDoses = doses,
                        doses = doses.map { slot ->
                            slot.viewAt(now).copy(
                                medicineSharesWithGuardian = slot.medicineId in sharedIds,
                                missedTwiceRunning = false,
                            )
                        },
                        lastVitals = vitals,
                        upcomingReminders = reminders.take(3),
                        facilities = facilities.take(3),
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.asHealthError()) }
            }
        }
    }

    /**
     * Turning health data on. Named, separate, and reversible from Settings
     * (plan section 14: separate consents with withdrawal) — this is not the
     * same as the guardian switch, and the two are never set together.
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

    fun onDosePressed(slotId: String, mark: DoseMark) {
        // Optimistic: a pensioner pressing "Taken" at 8:02 in front of a
        // kitchen full of people should see it tick immediately. Rolled back if
        // the write fails, because a tick that was never saved is worse than a
        // delay — it would tell the family a dose was taken when it was not.
        val before = _state.value.todayDoses
        _state.update { current ->
            current.copy(
                todayDoses = current.todayDoses.map { slot ->
                    if (slot.id == slotId) {
                        slot.copy(mark = mark, markedAt = now)
                    } else {
                        slot
                    }
                },
            )
        }
        viewModelScope.launch {
            try {
                val saved = repository.markDose(slotId, mark)
                _state.update { current ->
                    current.copy(
                        todayDoses = current.todayDoses.map { slot ->
                            if (slot.id == saved.id) saved else slot
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(todayDoses = before, error = e.asHealthError()) }
            }
        }
    }

    private fun Exception.asHealthError(): HealthError =
        if (this is java.io.IOException) HealthError.Offline else HealthError.Failed
}
