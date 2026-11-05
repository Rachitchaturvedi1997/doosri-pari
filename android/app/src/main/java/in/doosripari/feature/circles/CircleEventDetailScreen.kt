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
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * One event, and the RSVP. Plan section 6.6.
 *
 * ## The RSVP buttons are a set, and the second tap is silent
 *
 * The three verbs come from [RsvpChoice]; the current answer is a sentence
 * above them ("You are going", "You might come", "You are not coming", or "You
 * have not answered yet"). The chosen verb's button **stays enabled** — that is
 * the point: this audience double-taps, and the requirement is that tapping
 * twice is one RSVP and never an error dialog. `CircleEventDetailViewModel.rsvp`
 * absorbs the second tap before any network call. What the second tap must
 * never do is withdraw the answer somebody's daughter has already booked a
 * taxi for.
 *
 * Tapping a *different* verb changes the answer — the last deliberate choice
 * wins — and the words flip immediately (optimistic), rolling back with an
 * apology only if the network refuses.
 *
 * ## What is on the card
 *
 * Date, place, cost. The place is organiser-typed free text — a venue name, the
 * society hall, "near the post office" — and the model carries no address field
 * for it to accidentally grow into (see `CircleEventCard.place`). The cost is
 * paise held as a long, printed through `feature.seva.Rupees`, the app's one
 * money formatter.
 */
@Composable
fun CircleEventDetailScreen(
    state: CircleEventDetailUiState,
    onEvent: (CircleEventDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "Event",
        modifier = modifier,
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
            DoosriSecondaryButton(text = "Try again", onClick = { onEvent(CircleEventDetailEvent.Refresh) })
        }

        val event = state.event
        if (event == null) {
            if (!state.loading) {
                DoosriEmptyState(message = "This event is no longer here.")
            }
            return@DoosriScreen
        }

        DoosriCard {
            DoosriText.headline(text = event.title)
            DoosriText.body(text = event.startsAt.dayAndTimeLabel())
            DoosriText.callout(
                text = listOfNotNull(event.place, event.cityName).joinToString(" · "),
                color = colors.inkSecondary,
            )
            DoosriText.callout(
                text = if (event.isFree) "Free" else Rupees.display(event.costPaise),
                color = colors.inkSecondary,
            )
            val places = event.capacity?.let { "${event.going} of $it places taken" }
                ?: "${event.going} going"
            DoosriText.callout(text = places, color = colors.inkSecondary)
            DoosriText.callout(
                text = "We will remind you a day before and 2 hours before.",
                color = colors.inkSecondary,
            )
        }

        DoosriText.body(
            text = event.myRsvp?.label ?: "You have not answered yet.",
            color = colors.inkSecondary,
        )

        val goingBlocked = event.isFull && event.myRsvp?.affirmative != true
        if (goingBlocked) {
            DoosriText.body(
                text = "This event is full. You can still say you might come.",
                color = colors.inkSecondary,
            )
        }

        // The screen's one primary (plan section 4.4). It stays enabled after
        // it is answered — see the file KDoc on why the second tap is silent.
        DoosriPrimaryButton(
            text = RsvpChoice.Going.verb,
            onClick = { onEvent(CircleEventDetailEvent.Rsvp(RsvpChoice.Going)) },
            enabled = !goingBlocked && state.sendingRsvp == null,
        )
        DoosriSecondaryButton(
            text = RsvpChoice.Maybe.verb,
            onClick = { onEvent(CircleEventDetailEvent.Rsvp(RsvpChoice.Maybe)) },
            enabled = state.sendingRsvp == null,
        )
        DoosriSecondaryButton(
            text = RsvpChoice.Cannot.verb,
            onClick = { onEvent(CircleEventDetailEvent.Rsvp(RsvpChoice.Cannot)) },
            enabled = state.sendingRsvp == null,
        )

        // The state in words, never as an enum on screen (plan section 3).
        state.sendingRsvp?.let { sending ->
            DoosriText.callout(
                text = "Telling them: ${sending.label}…",
                color = colors.inkSecondary,
            )
        }
    }
}

@Composable
fun CircleEventDetailRoute(
    repository: CircleEventDetailRepository,
    eventId: Int,
    viewModel: CircleEventDetailViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val vm: CircleEventDetailViewModel = viewModel
        ?: viewModel(factory = remember { circleEventDetailFactory(repository, eventId) })
    val state by vm.state.collectAsState()

    CircleEventDetailScreen(
        state = state,
        onEvent = vm::onEvent,
        modifier = modifier,
    )
}

private fun circleEventDetailFactory(
    repository: CircleEventDetailRepository,
    eventId: Int,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CircleEventDetailViewModel(repository, eventId) as T
}