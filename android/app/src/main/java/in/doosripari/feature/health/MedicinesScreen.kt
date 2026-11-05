package `in`.doosripari.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriConfirmAlert
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSosButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * Plan section 6.10, the medicines list.
 *
 * Inline English strings; a `strings.xml` entry and a human translation are
 * required before the second language ships (plan section 6.16).
 */
@Composable
fun MedicinesScreen(
    state: MedicinesUiState,
    onOpenSchedule: (String) -> Unit,
    onAddMedicine: () -> Unit,
    onToggleShared: (String, Boolean) -> Unit,
    onDeletePressed: (Medicine) -> Unit,
    onEnableHealthData: () -> Unit,
    onRetry: () -> Unit,
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    if (!state.isHealthDataOn) {
        HealthDataOffState(onEnable = onEnableHealthData, onSos = onSos, title = "Medicines")
        return
    }

    DoosriScreen(
        title = "Medicines",
        modifier = modifier,
        actions = { DoosriSosButton(onClick = onSos) },
    ) {
        if (onBack != null) {
            HealthBackRow(label = "Back to health", onBack = onBack)
        }

        state.error?.let {
            DoosriText.callout(text = "Could not load your medicines.", color = DoosriTheme.colors.ink)
            DoosriSecondaryButton(text = "Try again", onClick = onRetry)
        }

        if (state.needsRefill.isNotEmpty()) {
            DoosriCard {
                DoosriText.headline(text = "Running out")
                state.needsRefill.forEach { medicine ->
                    DoosriText.body(
                        text = "${medicine.name}: ${medicine.stockDaysLeft} days of stock left.",
                        color = DoosriTheme.colors.inkSecondary,
                    )
                }
            }
        }

        DoosriPrimaryButton(text = "Add a medicine", onClick = onAddMedicine)

        if (state.medicines.isEmpty()) {
            DoosriEmptyState(
                message = "Your medicines will appear here.",
                actionLabel = "Add the first one",
                onAction = onAddMedicine,
            )
        } else {
            state.medicines.forEach { medicine ->
                MedicineCard(
                    medicine = medicine,
                    onOpenSchedule = { onOpenSchedule(medicine.id) },
                    onToggleShared = { shared -> onToggleShared(medicine.id, shared) },
                    onDeletePressed = { onDeletePressed(medicine) },
                )
            }
        }

        state.deletedName?.let { name ->
            DoosriText.callout(text = "$name was removed.", color = DoosriTheme.colors.inkSecondary)
        }
    }
}

@Composable
private fun MedicineCard(
    medicine: Medicine,
    onOpenSchedule: () -> Unit,
    onToggleShared: (Boolean) -> Unit,
    onDeletePressed: () -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriCard {
        DoosriListRow(
            icon = Icons.Outlined.Medication,
            title = medicine.name,
            value = "${medicine.dosesPerDay}× a day",
            onClick = onOpenSchedule,
        )

        val food = when (medicine.withFood) {
            FoodTiming.BeforeFood -> "before food"
            FoodTiming.AfterFood -> "after food"
            FoodTiming.WithFood -> "with food"
            FoodTiming.Anytime -> "any time"
        }
        DoosriText.callout(
            text = medicine.times.joinToString(", ") + " · $food",
            color = colors.inkSecondary,
        )

        if (medicine.needsRefillSoon) {
            DoosriText.callout(
                text = "${medicine.stockDaysLeft} days of stock left — time to refill.",
                color = colors.ink,
            )
        }

        HorizontalDivider(color = colors.divider)

        // ------------------------------------------------------------------
        // The per-medicine share control.
        //
        // The difference between family support and family surveillance is
        // exactly this toggle. Support is a daughter being told that Amma has
        // missed two blood-pressure tablets in a row and ringing her.
        // Surveillance is the same message about a vitamin taken when
        // remembered, read as a report card. From the outside the two look
        // identical — the parent's app either reports the misses or it does
        // not — so the choice is the pensioner's, per medicine, and it is off
        // until they make it.
        // ------------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
        ) {
            DoosriText.body(
                text = if (medicine.shareWithGuardian) {
                    "Missed doses are shared with your family"
                } else {
                    "Missed doses are kept private"
                },
                modifier = Modifier.weight(1f),
                color = colors.ink,
            )
            Switch(
                checked = medicine.shareWithGuardian,
                onCheckedChange = onToggleShared,
            )
        }

        DoosriSecondaryButton(text = "Remove this medicine", onClick = onDeletePressed)
    }
}

// ------------------------------------------------------------------ the route --

private sealed interface MedicinesOpen {
    data class Schedule(val medicineId: String) : MedicinesOpen
    data class Editor(val medicineId: String?, val prefillName: String?) : MedicinesOpen
}

/**
 * The medicines list, and the schedule/editor screens it opens.
 *
 * `prefillName` is the "Bolo" reminder intent (plan section 5): the voice
 * layer lands here with the medicine name in the person's own words and the
 * editor opens on top of the list, ready to confirm. The voice layer never
 * saves without that visible confirmation.
 */
@Composable
fun MedicinesRoute(
    repository: HealthRepository,
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    prefillName: String? = null,
    viewModel: MedicinesViewModel? = null,
) {
    var open by remember {
        mutableStateOf<MedicinesOpen?>(
            if (prefillName != null) {
                MedicinesOpen.Editor(medicineId = null, prefillName = prefillName)
            } else {
                null
            },
        )
    }

    when (val target = open) {
        is MedicinesOpen.Schedule -> MedicineScheduleRoute(
            repository = repository,
            medicineId = target.medicineId,
            onEdit = {
                open = MedicinesOpen.Editor(medicineId = target.medicineId, prefillName = null)
            },
            onSos = onSos,
            modifier = modifier,
            onBack = { open = null },
        )

        is MedicinesOpen.Editor -> MedicineEditorRoute(
            repository = repository,
            medicineId = target.medicineId,
            prefillName = target.prefillName,
            onSaved = { open = null },
            onSos = onSos,
            modifier = modifier,
            onBack = { open = null },
        )

        null -> {
            val vm: MedicinesViewModel = viewModel ?: viewModel(
                factory = remember { healthViewModelFactory { MedicinesViewModel(repository) } },
            )
            val state by vm.state.collectAsState()

            MedicinesScreen(
                state = state,
                onOpenSchedule = { open = MedicinesOpen.Schedule(it) },
                onAddMedicine = {
                    open = MedicinesOpen.Editor(medicineId = null, prefillName = null)
                },
                onToggleShared = vm::setShared,
                onDeletePressed = vm::onDeletePressed,
                onEnableHealthData = vm::grantHealthConsent,
                onRetry = vm::refresh,
                onSos = onSos,
                modifier = modifier,
                onBack = onBack,
            )

            // Destructive, so it is an alert with verbs, never OK/Cancel.
            // "Keep it" is the button a frightened user should be able to
            // reach without reading the question at all.
            state.pendingDelete?.let { medicine ->
                DoosriConfirmAlert(
                    title = "Remove ${medicine.name}?",
                    message = "The medicine and its dose history are removed from this " +
                        "phone. Nothing is sent anywhere.",
                    confirmLabel = "Remove ${medicine.name}",
                    dismissLabel = "Keep it",
                    onConfirm = vm::confirmDelete,
                    onDismiss = vm::dismissDelete,
                )
            }
        }
    }
}