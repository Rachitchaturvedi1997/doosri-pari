package `in`.doosripari.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriSosButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Plan section 6.10, the Swasthya hub, and the shared chrome of the health
 * package.
 *
 * Strings here are inline English: `res/values/strings.xml` is owned outside
 * this package while the app is being built in parallel, so each of these
 * needs a resource entry and a human translation before the second language
 * ships (plan section 6.16). The same flag is on the strings in
 * `ui/design/Components.kt`.
 *
 * A note on colour, which holds on every screen in this package: `safety` red
 * and `ok` green belong to SOS, a missed check-in and "I'm fine" and appear
 * nowhere here (plan section 4.3). A dose is Taken, Missed or Skipped in
 * *words* — with an icon whose shape also carries the state — because the one
 * colour pair this app uses for good and bad is the one pair a colour-blind
 * user cannot rely on, and because a red that also means "medicine not taken"
 * is a red that no longer means "help me".
 */

// ------------------------------------------------------- shared package chrome --

/**
 * The visible back affordance for screens this package pushes itself.
 *
 * DoosriScreen deliberately draws no back arrow — that belongs to the
 * navigation host (see its KDoc). Inside this package's own stacking routes
 * there is no host to draw one, so the row lives at the top of the content
 * instead. Arrow plus the word "Back": an icon alone is not a label.
 */
@Composable
internal fun HealthBackRow(label: String, onBack: () -> Unit) {
    val colors = DoosriTheme.colors
    TextButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(28.dp),
            )
            DoosriText.callout(text = label, color = colors.accent)
        }
    }
}

/**
 * The "you have not turned this on" state.
 *
 * Health data is a separate, named consent (plan section 14). When it is off
 * every health list in this app is empty *by design*, and a blank list would
 * read as "you have no medicines" — which is a different sentence with
 * different consequences from "you have not asked us to keep any". So the
 * empty health screen says which one it is, in words, and offers the switch.
 */
@Composable
internal fun HealthDataOffState(onEnable: () -> Unit, onSos: () -> Unit, title: String) {
    DoosriScreen(title = title, actions = { DoosriSosButton(onClick = onSos) }) {
        DoosriCard {
            DoosriText.headline(text = "Health data is off")
            DoosriText.body(
                text = "You have not turned health data on, so nothing is being kept here — " +
                    "no medicines, no readings, no reminders. Your medicines are not missing " +
                    "from this list; there is no list yet. Nothing is sent to your family " +
                    "either. You can turn this on here or in Settings, and turn it off again " +
                    "at any time.",
                color = DoosriTheme.colors.inkSecondary,
            )
            DoosriPrimaryButton(text = "Turn on health data", onClick = onEnable)
        }
    }
}

/** The dose state, in words. The word is the state; the icon only repeats it. */
internal fun DoseStatus.label(): String = when (this) {
    DoseStatus.Pending -> "Due"
    DoseStatus.Taken -> "Taken"
    DoseStatus.Skipped -> "Skipped"
    DoseStatus.Later -> "Snoozed 15 min"
    DoseStatus.Missed -> "Missed"
}

internal fun DoseStatus.icon(): ImageVector = when (this) {
    DoseStatus.Pending, DoseStatus.Later -> Icons.Outlined.Schedule
    DoseStatus.Taken -> Icons.Outlined.CheckCircle
    DoseStatus.Skipped -> Icons.Outlined.Close
    DoseStatus.Missed -> Icons.Outlined.Warning
}

/** `safety` and `ok` are reserved; every dose state renders in ink or accent. */
@Composable
internal fun DoseStatus.tint(): Color = when (this) {
    DoseStatus.Taken -> DoosriTheme.colors.accent
    DoseStatus.Missed -> DoosriTheme.colors.ink
    else -> DoosriTheme.colors.inkSecondary
}

// ------------------------------------------------------------------- the hub --

@Composable
fun HealthHomeScreen(
    state: HealthHomeUiState,
    onOpenMedicines: () -> Unit,
    onOpenDoseLog: () -> Unit,
    onOpenVitals: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenFacilities: () -> Unit,
    onEnableHealthData: () -> Unit,
    onRetry: () -> Unit,
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val colors = DoosriTheme.colors

    if (!state.isHealthDataOn) {
        HealthDataOffState(onEnable = onEnableHealthData, onSos = onSos, title = "Swasthya · Health")
        return
    }

    DoosriScreen(
        title = "Swasthya · Health",
        modifier = modifier,
        actions = { DoosriSosButton(onClick = onSos) },
    ) {
        if (onBack != null) {
            HealthBackRow(label = "Back", onBack = onBack)
        }

        state.error?.let {
            DoosriText.callout(
                text = "Something went wrong loading your health data.",
                color = colors.ink,
            )
            DoosriSecondaryButton(text = "Try again", onClick = onRetry)
        }

        DoosriCard {
            DoosriText.headline(text = "Health data")
            DoosriText.body(
                text = "This app keeps your medicines and readings and shows them back to you. " +
                    "It does not diagnose anything and never suggests a change to your dose.",
                color = colors.inkSecondary,
            )
            DoosriText.callout(
                text = "Recording is on. You can stop it in Settings — stopping really stops it.",
                color = colors.inkSecondary,
            )
        }

        if (state.doses.isNotEmpty()) {
            DoosriSectionHeader(text = "Today's doses")
            state.doses.forEach { view ->
                DoseRow(view)
            }
            DoosriSecondaryButton(text = "Open the dose log", onClick = onOpenDoseLog)
        }

        DoosriSectionHeader(text = "Your records")
        DoosriListRow(
            icon = Icons.Outlined.Medication,
            title = "Medicines",
            value = "${state.doses.size} due today",
            onClick = onOpenMedicines,
        )
        HorizontalDivider(color = colors.divider)
        DoosriListRow(
            icon = Icons.Outlined.Schedule,
            title = "Dose log",
            value = "${state.missedCount} missed today",
            onClick = onOpenDoseLog,
        )
        HorizontalDivider(color = colors.divider)
        DoosriListRow(
            icon = Icons.Outlined.Event,
            title = "Reminders",
            value = "${state.upcomingReminders.size} coming",
            onClick = onOpenReminders,
        )

        DoosriSectionHeader(text = "Last readings")
        if (state.lastVitals.isEmpty()) {
            DoosriText.body(
                text = "No readings yet. Record one and it will show here.",
                color = colors.inkSecondary,
            )
        } else {
            state.lastVitals.forEach { entry ->
                DoosriListRow(
                    icon = Icons.Outlined.Favorite,
                    title = entry.kind.label,
                    value = entry.displayValue(),
                    onClick = onOpenVitals,
                )
                HorizontalDivider(color = colors.divider)
            }
        }
        DoosriSecondaryButton(text = "Open the vitals log", onClick = onOpenVitals)

        DoosriSectionHeader(text = "Empanelled hospitals near you")
        state.facilities.forEach { facility ->
            DoosriListRow(
                icon = Icons.Outlined.LocalHospital,
                title = facility.name,
                value = facility.lastUpdated,
                onClick = onOpenFacilities,
            )
            HorizontalDivider(color = colors.divider)
        }
        DoosriSecondaryButton(text = "Open the facility finder", onClick = onOpenFacilities)
    }
}

@Composable
private fun DoseRow(view: DoseSlotView) {
    val colors = DoosriTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription =
                    "${view.slot.medicineName}, ${view.slot.doseLabel}, ${view.status.label()}"
            }
            .padding(vertical = DoosriDimens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
    ) {
        Icon(
            imageVector = view.status.icon(),
            contentDescription = null,
            tint = view.status.tint(),
            modifier = Modifier.size(28.dp),
        )
        DoosriText.body(
            text = view.slot.medicineName,
            modifier = Modifier.weight(1f),
            color = colors.ink,
        )
        DoosriText.callout(text = view.status.label(), color = colors.inkSecondary)
    }
}

/**
 * Wires the hub to the repository. The ViewModel is a plain class with its
 * dependencies in the constructor, so a test passes a fake repository here or
 * passes a prepared ViewModel and skips this wiring entirely.
 */
@Composable
fun HealthHomeRoute(
    repository: HealthRepository,
    onOpenMedicines: () -> Unit,
    onOpenDoseLog: () -> Unit,
    onOpenVitals: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenFacilities: () -> Unit,
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: HealthHomeViewModel? = null,
) {
    val vm: HealthHomeViewModel = viewModel ?: viewModel(
        factory = remember {
            healthViewModelFactory {
                HealthHomeViewModel(
                    repository = repository,
                    now = kotlinx.datetime.Clock.System.now(),
                    localDay = kotlinx.datetime.Clock.System.now()
                        .toLocalDayString(),
                )
            }
        },
    )
    val state by vm.state.collectAsState()

    HealthHomeScreen(
        state = state,
        onOpenMedicines = onOpenMedicines,
        onOpenDoseLog = onOpenDoseLog,
        onOpenVitals = onOpenVitals,
        onOpenReminders = onOpenReminders,
        onOpenFacilities = onOpenFacilities,
        onEnableHealthData = vm::grantHealthConsent,
        onRetry = vm::refresh,
        onSos = onSos,
        modifier = modifier,
        onBack = onBack,
    )
}

/** "YYYY-MM-DD" in the phone's own time zone — a dose day is a local day. */
internal fun kotlinx.datetime.Instant.toLocalDayString(): String {
    val date = this.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    return "%04d-%02d-%02d".format(date.year, date.monthNumber, date.dayOfMonth)
}

// -------------------------------------------------- the contract wrapper --

private sealed interface HealthDestination {
    data object Home : HealthDestination
    data object Medicines : HealthDestination
    data object DoseLog : HealthDestination
    data object Vitals : HealthDestination
    data object Reminders : HealthDestination
    data object Facilities : HealthDestination
}

/**
 * The health area as the navigation shell sees it (naming contract).
 *
 * Health is one door with several rooms behind it, so this wrapper keeps a
 * small stack of its own rather than pushing a flat list at the shell. Each
 * room is also reachable through its own `XRoute` for tests and deep links.
 */
@Composable
fun HealthRoute(modifier: Modifier = Modifier, onBack: () -> Unit = {}) {
    val repository = remember { DemoHealthRepository() }
    var destination by remember { mutableStateOf<HealthDestination>(HealthDestination.Home) }

    when (destination) {
        HealthDestination.Home -> HealthHomeRoute(
            repository = repository,
            onOpenMedicines = { destination = HealthDestination.Medicines },
            onOpenDoseLog = { destination = HealthDestination.DoseLog },
            onOpenVitals = { destination = HealthDestination.Vitals },
            onOpenReminders = { destination = HealthDestination.Reminders },
            onOpenFacilities = { destination = HealthDestination.Facilities },
            // SOS opens the hold-and-countdown screen of plan section 6.3,
            // which lives in the check-in slice, not in this package. Wired to
            // a no-op here rather than left as a dead button, but it must not
            // look finished — the same note MainActivity carries on its own
            // SOS wiring.
            onSos = { },
            modifier = modifier,
            onBack = onBack,
        )

        HealthDestination.Medicines -> MedicinesRoute(
            repository = repository,
            onSos = { },
            onBack = { destination = HealthDestination.Home },
            modifier = modifier,
        )

        HealthDestination.DoseLog -> DoseLogRoute(
            repository = repository,
            onSos = { },
            onBack = { destination = HealthDestination.Home },
            modifier = modifier,
        )

        HealthDestination.Vitals -> VitalsRoute(
            repository = repository,
            onSos = { },
            onBack = { destination = HealthDestination.Home },
            modifier = modifier,
        )

        HealthDestination.Reminders -> HealthRemindersRoute(
            repository = repository,
            onSos = { },
            onBack = { destination = HealthDestination.Home },
            modifier = modifier,
        )

        HealthDestination.Facilities -> FacilitiesRoute(
            repository = repository,
            onSos = { },
            onBack = { destination = HealthDestination.Home },
            modifier = modifier,
        )
    }
}