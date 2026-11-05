package `in`.doosripari.feature.circles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.feature.seva.Rupees
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * The events list. Plan section 6.6's events, one card each.
 *
 * The card carries the three things section 6.6 puts on an event — date,
 * place and cost — and the user's own answer, as words ("You are going"),
 * because somebody who already answered should not have to open the detail to
 * remember which way they answered before inviting their daughter along.
 *
 * The cost comes from `feature.seva.Rupees`, which is the one place in the app
 * that turns paise into rupee text. Nothing here formats money itself: a
 * second hand-rolled rupee formatter is a second place for the rounding bug
 * `Rupees` exists to prevent.
 */
@Composable
fun CircleEventsScreen(
    state: CircleEventsUiState,
    onEvent: (CircleEventsEvent) -> Unit,
    onOpenEvent: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "Events",
        modifier = modifier,
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
            DoosriSecondaryButton(text = "Try again", onClick = { onEvent(CircleEventsEvent.Refresh) })
        }

        if (state.isEmptyAfterLoad) {
            DoosriEmptyState(
                message = "Nothing planned yet. An event a circle adds will appear here.",
            )
            return@DoosriScreen
        }

        for (event in state.events) {
            EventCard(event = event, onOpenEvent = onOpenEvent)
        }
    }
}

@Composable
private fun EventCard(
    event: CircleEventCard,
    onOpenEvent: (Int) -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriCard(onClick = { onOpenEvent(event.id) }) {
        DoosriText.headline(text = event.title)
        DoosriText.body(text = event.startsAt.dayAndTimeLabel())
        DoosriText.callout(
            text = listOfNotNull(
                event.place,
                event.cityName,
                if (event.isFree) "Free" else Rupees.display(event.costPaise),
            ).joinToString(" · "),
            color = colors.inkSecondary,
        )

        val going = "${event.going} going"
        val answer = event.myRsvp?.label
        DoosriText.callout(
            text = listOfNotNull(going, answer).joinToString(" · "),
            color = colors.inkSecondary,
        )
    }
}

@Composable
fun CircleEventsRoute(
    repository: CircleEventsRepository,
    viewModel: CircleEventsViewModel? = null,
    modifier: Modifier = Modifier,
    onOpenEvent: (Int) -> Unit = {},
) {
    val vm: CircleEventsViewModel = viewModel
        ?: viewModel(factory = remember { circleEventsFactory(repository) })
    val state by vm.state.collectAsState()

    CircleEventsScreen(
        state = state,
        onEvent = vm::onEvent,
        onOpenEvent = onOpenEvent,
        modifier = modifier,
    )
}

private fun circleEventsFactory(
    repository: CircleEventsRepository,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CircleEventsViewModel(repository) as T
}