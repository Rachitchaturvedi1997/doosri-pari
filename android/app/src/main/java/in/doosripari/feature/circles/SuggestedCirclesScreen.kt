package `in`.doosripari.feature.circles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriSosButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * The suggested-circles screen, plan section 6.1 step 8 and section 6.6, under
 * section 15's discipline.
 *
 * ## The feed of a stranger is not what is dangerous here — the *circle* is
 *
 * This screen hands a pensioner the single most dangerous object in the app: a
 * group of people who say they are colleagues, presented by an engine the user
 * cannot audit. A department plus a cadre plus two posting years names a few
 * hundred people in the country (see the essay on [SuggestionReason]), so a
 * circle that materialises without an explanation is indistinguishable from
 * somebody who has worked out who this user is. The screen therefore leads with
 * the reason, in the user's own words, and the join control comes second.
 *
 * The two groups are separate sections, not one list with badges:
 *  - **Explained** — the reason checks against the user's own life ("You
 *    retired from Indian Railways in 2019"). These may be joined here.
 *  - **"We are not sure why these are here"** — [SuggestionReason.NoReason].
 *    No one-tap join. The only button is "Open this circle first", because
 *    joining something we cannot explain is not a decision the user has enough
 *    information to make; and quietly offering a join button next to the words
 *    "we are not sure" is exactly the habit section 1 says makes this audience
 *    easy to scam.
 *
 * The screen's one primary button is "Join N circles" at the bottom: the plan's
 * onboarding pre-ticked suggestions and this is the moment the user either
 * confirms that batch or walks away. "Join now" on a card is a secondary so the
 * screen keeps one clear main action.
 */
@Composable
fun SuggestedCirclesScreen(
    state: SuggestedCirclesUiState,
    onEvent: (SuggestedCirclesEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCircle: (Int) -> Unit = {},
    onSos: () -> Unit = {},
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "Suggested circles",
        modifier = modifier,
        actions = { DoosriSosButton(onClick = onSos) },
    ) {
        DoosriText.body(
            text = "These circles come from what you told us. Each one says why it is here.",
            color = colors.inkSecondary,
        )

        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
            DoosriSecondaryButton(text = "Try again", onClick = { onEvent(SuggestedCirclesEvent.DismissProblem) })
        }

        if (state.isEmptyAfterLoad) {
            // No action button: there is nothing to add here — the suggestions
            // come from onboarding, and an empty-state button that could not
            // actually fetch anything would be a dead promise (plan 4.4).
            DoosriEmptyState(
                message = "Nothing was suggested yet. Your batch and city circles will appear here.",
            )
            return@DoosriScreen
        }

        if (state.explained.isNotEmpty()) {
            DoosriSectionHeader(text = "Suggested for you")
            for (suggestion in state.explained) {
                SuggestionCard(
                    suggestion = suggestion,
                    chosen = suggestion.circle.id in state.chosen,
                    joining = state.joining == suggestion.circle.id,
                    joined = suggestion.circle.isJoined,
                    onEvent = onEvent,
                )
            }
        }

        if (state.unexplained.isNotEmpty()) {
            // Its own section, with its own heading that says what is wrong
            // rather than dressing it up. See SuggestionReason.NoReason.
            DoosriSectionHeader(text = "We are not sure why these are here")
            DoosriText.body(
                text = "Nothing you told us explains these. Open one to read it before joining.",
                color = colors.inkSecondary,
            )
            for (suggestion in state.unexplained) {
                UnexplainedCard(suggestion = suggestion, onOpenCircle = onOpenCircle)
            }
        }

        if (state.explained.isNotEmpty()) {
            DoosriSecondaryButton(
                text = "Choose every explained circle",
                onClick = { onEvent(SuggestedCirclesEvent.AcceptAll) },
            )
        }

        // The screen's one primary. The count is in the label so a tap is never
        // a surprise — section 3's "nothing hidden" applies to counts too.
        DoosriPrimaryButton(
            text = if (state.chosenCount == 1) {
                "Join 1 circle"
            } else {
                "Join ${state.chosenCount} circles"
            },
            onClick = { onEvent(SuggestedCirclesEvent.Finish) },
            enabled = state.chosenCount > 0 && state.joining == null,
        )
    }
}

/**
 * One explained suggestion: the reason first, the join choice second.
 *
 * Two controls appear inside one card, which bends the card's one-action rule
 * (plan section 4.4) on purpose: the tick row is *state* — which circles the
 * bottom button will join — and "Join now" is the one action. Merging them
 * would mean tapping a card either joins a group of strangers or does not, and
 * neither reading is safe here.
 */
@Composable
private fun SuggestionCard(
    suggestion: CircleSuggestion,
    chosen: Boolean,
    joining: Boolean,
    joined: Boolean,
    onEvent: (SuggestedCirclesEvent) -> Unit,
) {
    val colors = DoosriTheme.colors
    val circle = suggestion.circle

    DoosriCard {
        DoosriText.headline(text = circle.name)

        // The reason, in the user's own vocabulary. This line is the content of
        // the screen; it is never collapsed or hidden behind an info icon.
        DoosriText.body(text = suggestion.reason.whyYouSeeThis())

        val known = suggestion.knownMembers
        val meta = buildString {
            append("${circle.memberCount} members")
            if (known.isNotEmpty()) {
                append(" · ")
                append(known.take(2).joinToString(", "))
                if (known.size > 2) append(" and others you know")
                append(" are here")
            }
        }
        DoosriText.callout(text = meta, color = colors.inkSecondary)

        ChooseRow(
            chosen = chosen,
            label = if (circle.isJoined) "You have joined this circle" else "Join this circle",
            onToggle = { onEvent(SuggestedCirclesEvent.ToggleChosen(circle.id)) },
            enabled = !circle.isJoined,
        )

        when {
            circle.isJoined ->
                DoosriText.callout(text = "You are a member.", color = colors.inkSecondary)
            circle.isPending ->
                DoosriText.callout(text = "Waiting for approval.", color = colors.inkSecondary)
            joining ->
                DoosriText.callout(text = "Joining…", color = colors.inkSecondary)
            else ->
                DoosriSecondaryButton(
                    text = "Join now",
                    onClick = { onEvent(SuggestedCirclesEvent.JoinNow(circle.id)) },
                )
        }
    }
}

/**
 * A suggestion with no reason we can give.
 *
 * No tick row and no join button: the only control is "Open this circle first",
 * which navigates to the circle where the user can read what it is. The words
 * "we are not sure" stay visible above the card.
 */
@Composable
private fun UnexplainedCard(
    suggestion: CircleSuggestion,
    onOpenCircle: (Int) -> Unit,
) {
    val circle = suggestion.circle

    DoosriCard {
        DoosriText.headline(text = circle.name)
        DoosriText.body(text = suggestion.reason.whyYouSeeThis())
        DoosriSecondaryButton(
            text = "Open this circle first",
            onClick = { onOpenCircle(circle.id) },
        )
    }
}

/**
 * The tick row. The state is carried by a tick **and** a word, never by colour:
 * "Join this circle · Chosen" and "Join this circle · Not chosen" both survive
 * colour blindness and a bright screen (plan section 6.16).
 */
@Composable
private fun ChooseRow(
    chosen: Boolean,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = DoosriTheme.colors
    val stateWord = when {
        !enabled -> "Already joined"
        chosen -> "Chosen"
        else -> "Not chosen"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $stateWord"
            }
            .then(
                if (enabled) {
                    Modifier.clickable(
                        role = Role.Checkbox,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = DoosriDimens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
    ) {
        Icon(
            imageVector = if (chosen) Icons.Filled.CheckCircle else Icons.Outlined.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(28.dp),
        )
        DoosriText.body(
            text = label,
            modifier = Modifier.weight(1f),
            color = colors.ink,
        )
        DoosriText.callout(text = stateWord, color = colors.inkSecondary)
    }
}

/**
 * Route with optional overrides, in the shape `CheckInRoute` established: the
 * repository and the ViewModel can both be replaced by a test.
 *
 * Unlike `CheckInRoute` there is no production repository to default to — the
 * network layer for circles is not written yet — so the repository is a
 * required parameter rather than a silently-faked one. Inventing a default
 * here would mean inventing circles to show a pensioner.
 */
@Composable
fun SuggestedCirclesRoute(
    repository: SuggestedCirclesRepository,
    viewModel: SuggestedCirclesViewModel? = null,
    modifier: Modifier = Modifier,
    onOpenCircle: (Int) -> Unit = {},
    onDone: () -> Unit = {},
    onSos: () -> Unit = {},
) {
    val vm: SuggestedCirclesViewModel = viewModel
        ?: viewModel(factory = remember { suggestedCirclesFactory(repository) })
    val state by vm.state.collectAsState()

    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    SuggestedCirclesScreen(
        state = state,
        onEvent = vm::onEvent,
        modifier = modifier,
        onOpenCircle = onOpenCircle,
        onSos = onSos,
    )
}

private fun suggestedCirclesFactory(
    repository: SuggestedCirclesRepository,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SuggestedCirclesViewModel(repository) as T
}