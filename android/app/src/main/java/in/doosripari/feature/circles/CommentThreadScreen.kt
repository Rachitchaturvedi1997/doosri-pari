package `in`.doosripari.feature.circles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
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
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.design.DoosriText
import `in`.doosripari.ui.theme.DoosriTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * A post and its comments. Plan section 6.6, under section 15.
 *
 * ## What the screen does with the text
 *
 * Same rules as the feed, stated again here because this is the screen that
 * shows the **full** text: plain `Text`, no markup, no linkification, and a
 * held-for-review item rendered as "a moderator is checking this" rather than
 * as silence. The feed clips a post to three lines; this screen does not clip,
 * because a comment thread is the moderation view too — a user reporting a scam
 * and a moderator judging it both need to read all of it, and half a threat is
 * not a threat somebody can act on.
 *
 * **Every comment carries a report control, by tap.** Section 15 requires a
 * report on every comment, and section 3.4 rules out long-press: a pensioner
 * with a tremor will not find it. The row is a visible [ReportControl] under
 * each comment, including under the comments the reported post's author wrote
 * in their own defence.
 *
 * The box at the bottom sends only when the labelled "Add comment" button is
 * tapped — never on the keyboard's enter key, which on a 200% font is exactly
 * the accidental tap that would post a half-written line to strangers.
 */
@Composable
fun CommentThreadScreen(
    state: CommentThreadUiState,
    onEvent: (CommentThreadEvent) -> Unit,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "Conversation",
        modifier = modifier,
    ) {
        val problem = state.problem
        if (problem != null) {
            DoosriText.body(text = problem, color = colors.inkSecondary)
        }

        val post = state.post
        if (post == null) {
            if (!state.loading) {
                DoosriEmptyState(message = "This post is no longer here.")
            }
            return@DoosriScreen
        }

        DoosriCard {
            DoosriText.headline(text = post.authorName)
            DoosriText.callout(
                text = "${post.authorStanding} · ${post.createdAt.agoLabel(now)}",
                color = colors.inkSecondary,
            )
            if (post.heldForReview) {
                DoosriText.body(
                    text = "A moderator is checking this post. It is not hidden from you.",
                    color = colors.inkSecondary,
                )
            } else {
                // The full text, unclipped. See the file KDoc.
                post.text?.let { text -> DoosriText.body(text = text) }
                post.transcript?.let { transcript ->
                    DoosriText.callout(text = "Voice note, written out:", color = colors.inkSecondary)
                    DoosriText.body(text = transcript)
                }
                post.linkHostnames.forEach { host ->
                    DoosriText.callout(
                        text = "Link to $host — checked before it opens.",
                        color = colors.inkSecondary,
                    )
                }
            }
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

        DoosriSectionHeader(text = "Comments")
        if (state.comments.isEmpty()) {
            DoosriText.body(
                text = "No comments yet. Yours can be the first.",
                color = colors.inkSecondary,
            )
        }
        for (comment in state.comments) {
            DoosriCard {
                DoosriText.headline(text = comment.authorName)
                DoosriText.callout(
                    text = comment.createdAt.agoLabel(now),
                    color = colors.inkSecondary,
                )
                if (comment.heldForReview) {
                    DoosriText.body(
                        text = "A moderator is checking this comment. It is not hidden from you.",
                        color = colors.inkSecondary,
                    )
                } else {
                    DoosriText.body(text = comment.text)
                }
            }
            if (!comment.isMine) {
                ReportControl(
                    target = ReportTarget(
                        kind = ReportTargetKind.Comment,
                        id = comment.id,
                        description = "the comment by ${comment.authorName}",
                    ),
                    onSend = { /* the Route shows the acknowledgement */ },
                )
            }
        }

        DoosriSectionHeader(text = "Write a comment")
        OutlinedTextField(
            value = state.draft,
            onValueChange = { onEvent(CommentThreadEvent.SetDraft(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { DoosriText.callout(text = "Your comment") },
            minLines = 2,
        )
        if (!state.canSend && !state.sending) {
            DoosriText.callout(text = "Write something first.", color = colors.inkSecondary)
        }
        // The screen's one primary. The verb is the visible confirmation.
        DoosriPrimaryButton(
            text = "Add comment",
            onClick = { onEvent(CommentThreadEvent.Send) },
            enabled = state.canSend,
        )
    }
}

/**
 * The injectable route, in the shape `CheckInRoute` established. The clock is
 * captured once so every "3 days ago" on the screen means the same thing.
 */
@Composable
fun CommentThreadRoute(
    repository: CommentThreadRepository,
    postId: Int,
    viewModel: CommentThreadViewModel? = null,
    modifier: Modifier = Modifier,
) {
    val vm: CommentThreadViewModel = viewModel
        ?: viewModel(factory = remember { commentThreadFactory(repository, postId) })
    val state by vm.state.collectAsState()
    val now = remember { Clock.System.now() }

    CommentThreadScreen(
        state = state,
        onEvent = vm::onEvent,
        now = now,
        modifier = modifier,
    )
}

private fun commentThreadFactory(
    repository: CommentThreadRepository,
    postId: Int,
) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CommentThreadViewModel(repository, postId) as T
}