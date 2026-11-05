package `in`.doosripari.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * Plan section 4.4: 96 tall, green, "Main theek hoon · I'm fine". Once pressed
 * it reads "Checked in at 8:12" with a tick.
 *
 * The button is the largest control in the app by a wide margin, on purpose.
 * It is the one thing a user under stress has to find and press correctly, and
 * a target that is easy to miss means a check-in that never gets recorded.
 *
 * It fills its width rather than being a fixed 96x96 square so the label fits
 * in Hindi, Marathi and Tamil without truncating — the English string is the
 * shortest of the ten.
 */
@Composable
fun CheckInButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Non-null when the day is already done; shows the time instead of the label. */
    doneLabel: String? = null,
    sending: Boolean = false,
) {
    val done = doneLabel != null
    val colors = DoosriTheme.colors

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(DoosriDimens.checkInButtonHeight)
            // TalkBack should read this as one control that is now checked in,
            // not as an icon plus two separate labels.
            .semantics {
                contentDescription = label
                stateDescription = doneLabel ?: label
            },
        enabled = !done && !sending,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.ok,
            contentColor = colors.background,
            disabledContainerColor = colors.ok,
            disabledContentColor = colors.background,
        ),
        contentPadding = PaddingValues(
            horizontal = DoosriDimens.lg,
        ),
    ) {
        when {
            sending -> CircularProgressIndicator(
                color = colors.background,
                modifier = Modifier.size(40.dp),
            )

            done -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = doneLabel,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * The SOS button, plan section 4.4: in the top toolbar, red, and labelled "SOS"
 * — never an icon alone.
 *
 * `danger` colouring is not decoration here. It is the only control in the app
 * allowed to use the safety colour, and it stays red whether or not it is the
 * thing the user is currently looking at.
 */
@Composable
fun SosButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.height(DoosriDimens.minControlHeight),
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.safety,
            contentColor = colors.background,
        ),
        contentPadding = PaddingValues(
            horizontal = DoosriDimens.lg,
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Plan section 4.4: surface colour, 14 radius, 20 padding, one title, at most three lines, one action. */
@Composable
fun DoosriCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val colors = DoosriTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DoosriDimens.cardRadius),
        color = colors.surface,
    ) {
        Column(
            modifier = Modifier.padding(DoosriDimens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
        ) {
            if (trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    trailing()
                }
            } else {
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
            content?.invoke()
        }
    }
}
