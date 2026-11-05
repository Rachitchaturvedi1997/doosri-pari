package `in`.doosripari.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/** Icon size inside a button. The plan puts a Material Symbol beside every label. */
private val IconSize = 28.dp

/**
 * Plan section 4.4: full width, 64 tall, accent fill, semibold label, icon on
 * the left. One per screen — a screen with two of these has not decided what it
 * wants the user to do.
 */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    container: Color = DoosriTheme.colors.accent,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(DoosriDimens.primaryButtonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = DoosriTheme.colors.background,
        ),
        contentPadding = PaddingValues(horizontal = DoosriDimens.xl),
    ) {
        ButtonLabel(label, icon)
    }
}

/** Plan section 4.4: 56 tall, outlined, label always visible. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(DoosriDimens.minControlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(DoosriDimens.buttonRadius),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = DoosriTheme.colors.accent,
        ),
    ) {
        ButtonLabel(label, icon)
    }
}

/**
 * Icon then label, per plan section 4.4.
 *
 * The icon carries no content description: the label beside it is the name, and
 * a description as well would make TalkBack read the same thing twice. The
 * label itself keeps its semantics, so the whole button still announces as one
 * control.
 */
@Composable
private fun ButtonLabel(label: String, icon: ImageVector?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
