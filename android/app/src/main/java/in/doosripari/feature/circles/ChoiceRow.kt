package `in`.doosripari.feature.circles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * One option in a "choose one" list: an icon, a title, a line of explanation,
 * and a state word ("Chosen" / "Not chosen").
 *
 * The design system has no radio or checkbox component yet, and this app cannot
 * afford a bare tick: the rule of plan section 4.3 and 6.16 is that every state
 * is carried by a word as well as a shape, never by colour alone. So the state
 * is a tick **shape** change plus the word "Chosen", and the whole row is one
 * tap target at the 56dp floor.
 *
 * Used for the composer's audience (post visibility), the Anubhav board's
 * listing kind, and any other pick-one list. When `ui/design` grows a choice
 * component, this folds into it.
 */
@Composable
fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DoosriTheme.colors
    val stateWord = when {
        !enabled -> "Not available"
        selected -> "Chosen"
        else -> "Not chosen"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DoosriDimens.minControlHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $subtitle. $stateWord"
            }
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.RadioButton, onClick = onSelect)
                } else {
                    Modifier
                }
            )
            .padding(vertical = DoosriDimens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
    ) {
        Icon(
            imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(28.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(DoosriDimens.xs),
        ) {
            DoosriText.body(text = title, color = colors.ink)
            DoosriText.callout(text = subtitle, color = colors.inkSecondary)
        }
        DoosriText.callout(text = stateWord, color = colors.inkSecondary)
    }
}