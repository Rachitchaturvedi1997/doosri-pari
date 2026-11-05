package `in`.doosripari.feature.circles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriSheet
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * The post composer. Plan section 6.6's posts, and the landing place of Bolo's
 * `post` intent (section 5).
 *
 * ## The audience is chosen before anything is posted
 *
 * The Post button is disabled — and says why in words — until the user has
 * picked one of the three [PostVisibility] options by hand. No default is
 * pre-selected anywhere in this file. A pensioner posting "kal milte hain" to
 * a batch circle should not have inherited an audience from a toggle somebody
 * else set at onboarding.
 *
 * ## Bolo pre-fills; the user taps to post
 *
 * When the screen opens with `prefill` — the transcription of "Batch group mein
 * likho, kal milte hain" — the words are already in the box. Nothing else
 * happens. The plan's rule is that the voice layer never posts without a
 * visible confirmation, and the visible confirmation is the labelled Post
 * button below; there is no auto-send anywhere in this screen or its ViewModel.
 *
 * ## What the text field is
 *
 * A plain multi-line `OutlinedTextField`. `ui/design` has no text field
 * component yet, so this is Material 3's, tinted by nothing and labelled with
 * words; it will move into the design system when that component exists. What
 * matters is what the composer does *not* accept: no markup, no HTML, no
 * formatting toolbar. A post is plain text, and that is what makes the feed's
 * rendering rules (see `CircleFeedScreen`) safe.
 */
@Composable
fun PostComposerScreen(
    state: PostComposerUiState,
    onEvent: (PostComposerEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPosted: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val colors = DoosriTheme.colors
    var kindSheetOpen by remember { mutableStateOf(false) }
    var circleSheetOpen by remember { mutableStateOf(false) }

    DoosriScreen(
        title = "Write a post",
        modifier = modifier,
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
        }

        if (state.posted) {
            DoosriText.body(text = "Your post has been sent to the circle.")
            LaunchedEffect(Unit) { onPosted() }
            return@DoosriScreen
        }

        // The words. Everything else on this screen is about where they go.
        OutlinedTextField(
            value = state.text,
            onValueChange = { onEvent(PostComposerEvent.SetText(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { DoosriText.callout(text = "Your message") },
            minLines = 4,
        )

        DoosriSectionHeader(text = "What kind of post is this?")
        DoosriListRow(
            icon = Icons.Outlined.Edit,
            title = "Kind of post",
            value = state.kind.label,
            onClick = { kindSheetOpen = true },
        )

        if (state.targetCircleId == null) {
            // The Bolo path: the voice text named a circle in words but the
            // deep link does not carry an id, so the user picks one here.
            DoosriSectionHeader(text = "Which circle?")
            DoosriListRow(
                icon = Icons.Outlined.Groups,
                title = "Post to",
                value = state.destinationLabel ?: "Choose a circle",
                onClick = { circleSheetOpen = true },
            )
        } else {
            state.destinationLabel?.let { name ->
                DoosriText.callout(text = "Posting to $name.", color = colors.inkSecondary)
            }
        }

        DoosriSectionHeader(text = "Who can see this?")
        DoosriText.body(
            text = "Choose one. Nothing is posted until you do.",
            color = colors.inkSecondary,
        )
        for (visibility in PostVisibility.values()) {
            ChoiceRow(
                icon = visibility.icon,
                title = visibility.label,
                subtitle = visibility.explanation,
                selected = state.visibility == visibility,
                onSelect = { onEvent(PostComposerEvent.SetVisibility(visibility)) },
            )
        }

        state.whyNotReady?.let { why ->
            DoosriText.callout(text = why, color = colors.inkSecondary)
        }

        // The verb says what will happen and to where. This button is the
        // visible confirmation the plan requires before anything is posted.
        DoosriPrimaryButton(
            text = state.destinationLabel?.let { "Post to $it" } ?: "Post this message",
            onClick = { onEvent(PostComposerEvent.Post) },
            enabled = state.canPost,
        )
    }

    if (kindSheetOpen) {
        DoosriSheet(title = "What kind of post is this?", onDismiss = { kindSheetOpen = false }) {
            for (kind in PostKind.values()) {
                ChoiceRow(
                    icon = Icons.Outlined.Edit,
                    title = kind.label,
                    subtitle = "",
                    selected = state.kind == kind,
                    onSelect = {
                        onEvent(PostComposerEvent.SetKind(kind))
                        kindSheetOpen = false
                    },
                )
            }
        }
    }

    if (circleSheetOpen) {
        DoosriSheet(title = "Which circle?", onDismiss = { circleSheetOpen = false }) {
            if (state.myCircles.isEmpty()) {
                DoosriText.body(
                    text = "Your circles did not load. Go back and open the composer from a circle.",
                    color = colors.inkSecondary,
                )
            }
            for (circle in state.myCircles) {
                ChoiceRow(
                    icon = circle.type.icon,
                    title = circle.name,
                    subtitle = "${circle.memberCount} members",
                    selected = state.targetCircleId == circle.id,
                    onSelect = {
                        onEvent(PostComposerEvent.ChooseCircle(circle.id))
                        circleSheetOpen = false
                    },
                )
            }
        }
    }
}

private val PostVisibility.icon: ImageVector
    get() = when (this) {
        PostVisibility.Everyone -> Icons.Outlined.People
        PostVisibility.KnownOnly -> Icons.Outlined.Person
        PostVisibility.ModeratorsOnly -> Icons.Outlined.Lock
    }

/**
 * The injectable route, in the shape `CheckInRoute` established. [prefill] is
 * Bolo's transcription; see the file KDoc for what it does and does not do.
 */
@Composable
fun PostComposerRouteWith(
    repository: PostComposerRepository,
    circleId: Int?,
    viewModel: PostComposerViewModel? = null,
    modifier: Modifier = Modifier,
    prefill: String? = null,
    onPosted: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val vm: PostComposerViewModel = viewModel ?: viewModel(
        factory = remember { postComposerFactory(repository, circleId, prefill) },
    )
    val state by vm.state.collectAsState()

    PostComposerScreen(
        state = state,
        onEvent = vm::onEvent,
        modifier = modifier,
        onPosted = onPosted,
        onBack = onBack,
    )
}

private fun postComposerFactory(
    repository: PostComposerRepository,
    circleId: Int?,
    prefill: String?,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PostComposerViewModel(repository, circleId, prefill) as T
}

// ------------------------------------------------------------------ export --
// The navigation shell binds this exact name and signature. Bolo's `post`
// intent deep-links here with the spoken text in `prefill`, and the user still
// taps to post — the voice layer never posts by itself.

/**
 * Exported for the navigation shell. No circle id in the signature, so the
 * composer opens on the "Which circle?" chooser until the user picks one; the
 * repository is the not-wired stand-in until the network layer lands.
 */
@Composable
fun PostComposerRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    prefill: String? = null,
) {
    PostComposerRouteWith(
        repository = NotWiredPostComposerRepository(),
        circleId = null,
        prefill = prefill,
        modifier = modifier,
        onBack = onBack,
    )
}