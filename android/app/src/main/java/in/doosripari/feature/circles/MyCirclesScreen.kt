package `in`.doosripari.feature.circles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Interests
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriSosButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * The Saathi tab's root: "my circles". Plan section 6.6.
 *
 * Rows, not cards. A list of circles the user already belongs to is a list of
 * places to go, and the list row (plan section 4.4) is the component for that:
 * icon, title, one value, whole row tappable. The value carries the only thing
 * that changes between rows — how many members, or "waiting for approval" when
 * an association's office-bearers have not answered yet (section 6.6's
 * association joining rule).
 *
 * **This screen shows no user-generated text at all.** Circle names come from
 * the feed's neighbours but the names are chosen by whoever started the circle;
 * the screen keeps them to one line and never renders anything else a member
 * wrote. Everything a stranger wrote lives one tap deeper, in the feed, where
 * the handling rules are written down.
 */
@Composable
fun MyCirclesScreen(
    state: MyCirclesUiState,
    onEvent: (MyCirclesEvent) -> Unit,
    onOpenCircle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onSuggest: () -> Unit = {},
    onSos: () -> Unit = {},
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "My circles",
        modifier = modifier,
        // SOS is in the toolbar of every main screen (plan section 5), and this
        // is one of the four tabs' roots. Pushed screens leave it to the tab.
        actions = { DoosriSosButton(onClick = onSos) },
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
            DoosriSecondaryButton(text = "Try again", onClick = { onEvent(MyCirclesEvent.Refresh) })
        }

        if (state.isEmptyAfterLoad) {
            DoosriEmptyState(
                message = "Your circles will appear here. Start with the ones suggested from your batch and city.",
                actionLabel = "See suggestions",
                onAction = onSuggest,
            )
            return@DoosriScreen
        }

        if (state.joined.isNotEmpty()) {
            DoosriSectionHeader(text = "You are a member")
            for (circle in state.joined) {
                CircleRow(circle = circle, onOpenCircle = onOpenCircle)
            }
        }

        if (state.pending.isNotEmpty()) {
            DoosriSectionHeader(text = "Waiting for approval")
            DoosriText.body(
                text = "An office-bearer has to answer these. Nothing is sent to them again.",
                color = colors.inkSecondary,
            )
            for (circle in state.pending) {
                CircleRow(circle = circle, onOpenCircle = onOpenCircle)
            }
        }
    }
}

@Composable
private fun CircleRow(
    circle: SaathiCircle,
    onOpenCircle: (Int) -> Unit,
) {
    DoosriListRow(
        icon = circle.type.icon,
        title = circle.name,
        value = if (circle.isPending) {
            "Waiting for approval"
        } else {
            "${circle.memberCount} members"
        },
        onClick = { onOpenCircle(circle.id) },
    )
}

/** One Material Symbol per circle type. Every one of them sits beside text. */
internal val CircleType.icon: ImageVector
    get() = when (this) {
        CircleType.Batch -> Icons.Outlined.School
        CircleType.Department -> Icons.Outlined.Apartment
        CircleType.City -> Icons.Outlined.LocationCity
        CircleType.Association -> Icons.Outlined.Groups
        CircleType.Interest -> Icons.Outlined.Interests
        CircleType.Private -> Icons.Outlined.Lock
    }

/** The icon for a voice room. Used by the room list and the room screen. */
internal val VoiceRoomIcon: ImageVector = Icons.Outlined.Campaign

@Composable
fun MyCirclesRoute(
    repository: MyCirclesRepository,
    viewModel: MyCirclesViewModel? = null,
    modifier: Modifier = Modifier,
    onOpenCircle: (Int) -> Unit = {},
    onSuggest: () -> Unit = {},
    onSos: () -> Unit = {},
) {
    val vm: MyCirclesViewModel = viewModel
        ?: viewModel(factory = remember { myCirclesFactory(repository) })
    val state by vm.state.collectAsState()

    MyCirclesScreen(
        state = state,
        onEvent = vm::onEvent,
        onOpenCircle = onOpenCircle,
        modifier = modifier,
        onSuggest = onSuggest,
        onSos = onSos,
    )
}

private fun myCirclesFactory(
    repository: MyCirclesRepository,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        MyCirclesViewModel(repository) as T
}