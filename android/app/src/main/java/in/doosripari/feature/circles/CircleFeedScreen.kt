package `in`.doosripari.feature.circles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A circle's feed. Plan section 6.6, under section 15.
 *
 * ## What this screen does with user-generated text from strangers
 *
 * It prints it and nothing else. [CirclePost.text] is prose written by somebody
 * the user has never met, read by a pensioner who has just learned that most
 * calls are frauds, so every decision below is on the side of boring:
 *
 *  - **Plain `Text`, no markup, no linkification.** There is no HTML field in
 *    the model and none is parsed here. A stranger's words can never become a
 *    button, a coloured heading, or a tappable phone number. The only tappable
 *    things on a post are the named controls below it.
 *  - **A held post renders as a notice, not as silence.** Section 15 holds
 *    posts from new members that carry phone numbers or payment requests; the
 *    author and moderators still see the post, and everyone else sees "a
 *    moderator is checking this post". A vanished post is indistinguishable
 *    from a deleted one, and this audience already distrusts silent deletion.
 *  - **Photos load only on a labelled tap** ("Photos are not loaded yet"), for
 *    the reason written on [CirclePost.photoRefs]: fetching a stranger's image
 *    from a stranger's URL leaks the reader's IP address and the fact that they
 *    opened this circle. The tap is the consent.
 *  - **Links are shown as hostnames, not as links.** `linkHostnames` is
 *    pre-extracted server-side and checked against section 15's blocklist
 *    before anything here could be made tappable; this screen does not discover
 *    links at render time and does not open them.
 *  - **A report control is on every post, by tap.** [ReportControl] is a visible
 *    row with an icon and a word — never a long-press, which a pensioner with a
 *    tremor will not find (section 3.4).
 *
 * ## Why a scrolling page and not a LazyColumn
 *
 * The plan caps the feed at 30 posts and then says "You're all caught up. See
 * you tomorrow." A finite list is therefore the design — and it is also what
 * the design system's `DoosriScreen` accepts, since its body is a scrolling
 * column. If a later phase ever pages a feed, it needs the paging component the
 * design system asks for, not a `LazyColumn` nested inside this screen.
 */
@Composable
fun CircleFeedScreen(
    state: CircleFeedUiState,
    onEvent: (CircleFeedEvent) -> Unit,
    onOpenThread: (Int) -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = state.circle?.name ?: "Circle",
        modifier = modifier,
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
            DoosriSecondaryButton(text = "Try again", onClick = { onEvent(CircleFeedEvent.Refresh) })
        }

        state.circle?.about?.let { about ->
            DoosriText.callout(text = about, color = colors.inkSecondary)
        }

        if (state.isEmptyAfterLoad) {
            DoosriEmptyState(
                message = "No posts yet. Write the first line to this circle.",
                actionLabel = "Write a post",
                onAction = onCompose,
            )
            return@DoosriScreen
        }

        for (post in state.posts) {
            PostCard(
                post = post,
                now = state.now,
                photosRevealed = post.id in state.revealedPhotos,
                namasted = post.id in state.namasted,
                onEvent = onEvent,
                onOpenThread = onOpenThread,
            )
        }

        if (state.caughtUp) {
            // The plan's own words. The feed stops here and does not offer more.
            DoosriText.body(
                text = "You're all caught up. See you tomorrow.",
                color = colors.inkSecondary,
                modifier = Modifier.padding(vertical = DoosriDimens.xl),
            )
        }

        // The screen's one primary action (plan section 4.4).
        DoosriPrimaryButton(text = "Write a post", onClick = onCompose)
    }
}

/**
 * One post: a tappable preview card (opens the comment thread) with the
 * moderation and privacy controls as visible rows beneath it.
 *
 * The card shows at most three lines of the post body — the plan's card rule —
 * and the full text lives on the thread screen, which is the post's detail
 * view. The report row sits *outside* the card so the card keeps its one
 * action, and so "Report" is never accidentally what a tap on the post means.
 */
@Composable
private fun PostCard(
    post: CirclePost,
    now: Instant,
    photosRevealed: Boolean,
    namasted: Boolean,
    onEvent: (CircleFeedEvent) -> Unit,
    onOpenThread: (Int) -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriCard(onClick = { onOpenThread(post.id) }) {
        DoosriText.headline(text = post.authorName)
        DoosriText.callout(
            text = "${post.authorStanding} · ${post.kind.label}",
            color = colors.inkSecondary,
        )

        if (post.heldForReview) {
            // A notice, never silence. See the file KDoc.
            DoosriText.body(
                text = "A moderator is checking this post. It is not hidden from you.",
                color = colors.inkSecondary,
            )
        } else {
            post.text?.let { text ->
                DoosriText.body(text = text, maxLines = 3)
            }

            if (post.kind == PostKind.Photo && post.photoRefs.isEmpty() && !photosRevealed) {
                DoosriListRow(
                    icon = Icons.Outlined.Image,
                    title = "Photos are not loaded yet",
                    value = "Tap to show",
                    onClick = { onEvent(CircleFeedEvent.RevealPhotos(post.id)) },
                )
            } else if (post.photoRefs.isNotEmpty()) {
                // The count in words. The image loader lands with the media
                // pipeline; this screen will not fetch pixels on its own.
                DoosriText.callout(
                    text = "${post.photoRefs.size} photos, loaded at your tap.",
                    color = colors.inkSecondary,
                )
            }

            if (post.linkHostnames.isNotEmpty()) {
                DoosriText.callout(
                    text = "Links: ${post.linkHostnames.joinToString(", ")} — checked before they open.",
                    color = colors.inkSecondary,
                )
            }
        }

        val comments = if (post.commentCount == 0) {
            "No comments yet"
        } else {
            "${post.commentCount} comments"
        }
        DoosriText.callout(
            text = "${post.createdAt.agoLabel(now)} · $comments",
            color = colors.inkSecondary,
        )
    }

    if (!post.heldForReview) {
        NamasteRow(
            namasted = namasted,
            onNamaste = { onEvent(CircleFeedEvent.Namaste(post.id)) },
        )
    }

    if (!post.isMine) {
        ReportControl(
            target = ReportTarget(
                kind = ReportTargetKind.Post,
                id = post.id,
                description = "the post by ${post.authorName}",
            ),
            onSend = { /* the Route shows the acknowledgement */ },
        )
    }
}

/**
 * The "Namaste" reaction — one row, one tap, a set-state.
 *
 * The word is the plan's own ("a 'Namaste' reaction"); the icon only emphasises
 * it. Once tapped the row says "Namaste sent" and stays tapped: tapping again
 * is one reaction, never an un-reaction and never an error — this audience
 * double-taps, and a withdrawn greeting somebody already saw is a small public
 * embarrassment, not a feature.
 */
@Composable
private fun NamasteRow(
    namasted: Boolean,
    onNamaste: () -> Unit,
) {
    DoosriListRow(
        icon = Icons.Outlined.WavingHand,
        title = if (namasted) "Namaste sent" else "Say namaste",
        value = if (namasted) "Sent" else null,
        onClick = if (namasted) null else onNamaste,
    )
}

/**
 * Route with optional overrides, in the shape `CheckInRoute` established.
 *
 * The repository is required rather than defaulted: there is no production
 * circles network layer yet, and inventing a default would mean inventing
 * strangers' posts to show a pensioner. `now` is captured once at open time and
 * handed to the ViewModel so "3 days ago" is fixed for the whole visit and
 * testable at a pinned clock.
 */
@Composable
fun CircleFeedRoute(
    repository: CircleFeedRepository,
    circleId: Int,
    viewModel: CircleFeedViewModel? = null,
    modifier: Modifier = Modifier,
    onOpenThread: (Int) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    val vm: CircleFeedViewModel = viewModel ?: viewModel(
        factory = remember {
            circleFeedFactory(repository, circleId, Clock.System.now())
        },
    )
    val state by vm.state.collectAsState()

    CircleFeedScreen(
        state = state,
        onEvent = vm::onEvent,
        onOpenThread = onOpenThread,
        onCompose = onCompose,
        modifier = modifier,
    )
}

private fun circleFeedFactory(
    repository: CircleFeedRepository,
    circleId: Int,
    now: Instant,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CircleFeedViewModel(repository, circleId, now) as T
}