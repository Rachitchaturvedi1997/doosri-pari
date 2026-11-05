package `in`.doosripari.feature.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.doosripari.R
import `in`.doosripari.data.CheckInSnapshot
import `in`.doosripari.ui.components.CheckInButton
import `in`.doosripari.ui.components.SosButton
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Plan section 4.5, the Today screen, for the check-in slice.
 *
 * The screen is deliberately short. The plan's users are 60+, often on a
 * budget handset, and the one thing this screen has to do is get a check-in
 * recorded. Everything below the button is secondary and scrolls away.
 */
// TopAppBar is still marked experimental in Material 3. The plan wants a large
// title in the toolbar, which is what it is for; the opt-in is here rather than
// at the module so it is obvious which call it is about.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    state: CheckInUiState,
    onCheckIn: () -> Unit,
    onSos: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** How the app should greet them — "Sharma ji". Null while signed out. */
    addressAs: String? = null,
) {
    val colors = DoosriTheme.colors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = addressAs?.let {
                            stringResource(R.string.today_greeting, it)
                        } ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                },
                actions = {
                    SosButton(
                        label = stringResource(R.string.sos_button),
                        onClick = onSos,
                        modifier = Modifier.padding(end = DoosriDimens.screenMargin),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.ink,
                    actionIconContentColor = colors.accent,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = DoosriDimens.screenMargin,
                end = DoosriDimens.screenMargin,
                top = DoosriDimens.lg,
                bottom = DoosriDimens.huge,
            ),
            verticalArrangement = Arrangement.spacedBy(DoosriDimens.lg),
        ) {
            item {
                val error = state.error
                if (error != null) {
                    ErrorBanner(
                        error = error,
                        onRetry = onRetry,
                    )
                }
            }

            item {
                CheckInButton(
                    label = stringResource(R.string.check_in_button),
                    onClick = onCheckIn,
                    sending = state.sending,
                    doneLabel = state.today
                        ?.checkedAt
                        ?.toLocalDateTime(TimeZone.currentSystemDefault())
                        ?.let { stringResource(R.string.check_in_done, it.hour12Label()) },
                )
            }

            state.preferences?.takeIf { it.isPaused }?.let { paused ->
                item {
                    Text(
                        text = "Check-ins are paused. Nobody will be alerted while you are away.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                    )
                }
            }

            if (state.history.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.history_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = DoosriDimens.sm),
                    )
                }
                item { HorizontalDivider(color = colors.divider) }
                items(state.history, key = { it.day }) { day ->
                    HistoryRow(day)
                }
            } else if (!state.loading) {
                item {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: CheckInError, onRetry: () -> Unit) {
    val colors = DoosriTheme.colors
    val message = when (error) {
        CheckInError.Offline -> stringResource(R.string.check_in_offline)
        CheckInError.Failed -> stringResource(R.string.check_in_failed)
    }
    Snackbar(
        containerColor = colors.surface,
        contentColor = colors.ink,
        action = {
            TextButton(onClick = onRetry) {
                Text(
                    text = stringResource(R.string.retry),
                    color = colors.accent,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One day in the two-week list. A day somebody else resolved is shown in the
 * safety colour: the user should be able to see at a glance that somebody came
 * looking for them, not have to tap through to find out.
 */
@Composable
private fun HistoryRow(day: CheckInSnapshot) {
    val colors = DoosriTheme.colors
    val done = day.isDone
    val escalated = day.isEscalated
    val (icon, tint) = when {
        done -> Icons.Filled.CheckCircle to colors.ok
        escalated -> Icons.Outlined.Schedule to colors.safety
        else -> Icons.Outlined.Schedule to colors.inkSecondary
    }
    val description = when {
        done -> "Checked in"
        escalated -> "Alerted"
        else -> "No check-in"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One row, one meaning. Announced as a single phrase rather than
            // as a date, an icon and a word read separately.
            .clearAndSetSemantics { contentDescription = "${day.day}, $description" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = day.day,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
    }
}

/** "8:12 am" in the user's own locale, which is what they will read aloud. */
private fun LocalDateTime.hour12Label(): String {
    val h12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour < 12) "am" else "pm"
    return "%d:%02d %s".format(h12, minute, suffix)
}
