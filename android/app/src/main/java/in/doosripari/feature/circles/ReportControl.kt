package `in`.doosripari.feature.circles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoneyOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import `in`.doosripari.ui.design.DoosriConfirmAlert
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriSheet

/**
 * The report control, as one reusable piece: a tap-reachable row, a cause sheet,
 * and a confirmation before anything is sent.
 *
 * ## Why this exists as one component
 *
 * Plan section 15 wants a report on **every** post, comment, room, listing and
 * profile. The rule this component enforces is in the task and in section 3.4:
 * **reachable by TAP, not by a long-press.** This app's users are 60+, many with
 * a tremor; a long-press is a gesture they will not find and cannot reliably
 * perform, and a report control hidden behind one is a report control that does
 * not exist. So the row is a visible [DoosriListRow] — icon with a text label
 * beside it, 64dp tall, whole row tappable — not a gesture and not an icon.
 *
 * ## The two steps are deliberate
 *
 *  1. Tapping the row opens the cause sheet. The causes are words a moderator
 *     can act on (see [ReportCause]); there is no free-text box to write into,
 *     because a frightened user facing a scam should not have to compose prose.
 *  2. Choosing a cause opens [DoosriConfirmAlert] with **verbs** on the buttons
 *     — "Send report" and "Keep" — and the message names the exact thing being
 *     reported. Section 4.4 reserves the alert for irreversible actions and
 *     section 3's rule is that nothing is sent without a visible confirmation;
 *     a report is sent to moderators and is not retractable from this screen.
 *
 * "Keep" is the safe button and it sits where the finger rests, because a user
 * who panics mid-flow must be able to leave without reading.
 *
 * [onSend] is the only way a report leaves this component. The caller decides
 * what happens next (a toast, a row that says "Reported"); this component only
 * guarantees that the report was chosen and confirmed by the user themselves.
 */
@Composable
fun ReportControl(
    target: ReportTarget,
    onSend: (ReportCause) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember(target) { mutableStateOf(false) }
    var pendingCause by remember(target) { mutableStateOf<ReportCause?>(null) }

    DoosriListRow(
        icon = Icons.Outlined.Flag,
        title = "Report this ${target.kind.whatYouSee}",
        onClick = { sheetOpen = true },
        modifier = modifier,
    )

    if (sheetOpen) {
        DoosriSheet(
            title = "Why are you reporting this?",
            onDismiss = { sheetOpen = false },
        ) {
            for (cause in ReportCause.values()) {
                DoosriListRow(
                    icon = cause.icon,
                    title = cause.label,
                    value = if (cause.urgent) "Looked at within 2 hours" else "Looked at within 24 hours",
                    onClick = {
                        pendingCause = cause
                        sheetOpen = false
                    },
                )
            }
        }
    }

    val cause = pendingCause
    if (cause != null) {
        // Section 15's response times are shown before sending, so the user
        // knows what they are choosing, not after.
        val responseNote = if (cause.urgent) {
            "A moderator looks at this kind of report within 2 hours."
        } else {
            "A moderator looks at this kind of report within 24 hours."
        }
        DoosriConfirmAlert(
            title = "Send this report?",
            message = "${cause.label} — ${target.description}. $responseNote",
            confirmLabel = "Send report",
            dismissLabel = "Keep",
            onConfirm = {
                onSend(cause)
                pendingCause = null
            },
            onDismiss = { pendingCause = null },
        )
    }
}

/**
 * One icon per cause. Every one of them is drawn beside its text label — this
 * app has no icon-only controls (plan section 4.3), so the icon is emphasis,
 * never the name of the thing.
 */
private val ReportCause.icon: ImageVector
    get() = when (this) {
        ReportCause.Abuse, ReportCause.HateSpeech -> Icons.Outlined.Warning
        ReportCause.Scam -> Icons.Outlined.MoneyOff
        ReportCause.PersonalInfo -> Icons.Outlined.Lock
        ReportCause.Spam -> Icons.Outlined.Block
        ReportCause.WrongInformation -> Icons.Outlined.Info
    }