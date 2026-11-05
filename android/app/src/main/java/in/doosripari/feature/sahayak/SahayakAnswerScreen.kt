package `in`.doosripari.feature.sahayak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSecondaryButton
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * Pension Sahayak — the answer screen. Plan sections 4.5 and 6.4.
 *
 * ## Why the citation is impossible to miss
 *
 * The plan's wireframe (4.5) puts the sources immediately under the answer and
 * above the feedback row, and this file follows that order literally — but
 * order alone is a thing a later edit re-sorts "for balance". So the citation
 * block is defended in four separate ways:
 *
 *  1. **Placement.** [SourcesBlock] renders directly after the answer card and
 *     before any tappable feedback control. There is no comment-heavy footnote
 *     region in this file where a citation can quietly migrate to; the only
 *     "bottom" content is the general-information notice the plan itself puts
 *     last.
 *  2. **Size.** The source name and the "last checked on" date are rendered at
 *     body size (20sp), the same size as the answer's own reading text. A
 *     citation at caption size is small print, and small print is where a
 *     pensioner's trust in this app would go to die — the citation *is* the
 *     product (see the note at the top of [SahayakModels]).
 *  3. **Type.** [SahayakAnswer.Cited] cannot exist without a non-empty citation
 *     list (its `init` requires one), so there is no state in which the answer
 *     renders and the source block is empty. "Add the citation later" is a
 *     compile error, not a TODO.
 *  4. **Content.** Each source row shows three facts, not one: document and
 *     section, the issuing office, and the date a reviewer last checked the
 *     original — plus an "Older rule" label on anything superseded (plan 6.4).
 *     A bare document name is a link, not a citation.
 *
 * A screen that shows a fluent answer and hides where it came from is exactly
 * the app this one exists to replace. If a change makes the sources less
 * visible, the change is wrong.
 */
@Composable
fun SahayakAnswerScreen(
    state: SahayakUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onReadAloud: () -> Unit,
    onOpenSource: (Citation) -> Unit,
    onPrimaryAction: (AnswerTarget) -> Unit,
    onFeedback: (Boolean) -> Unit,
    onEscalate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoosriTheme.colors

    DoosriScreen(
        title = "Pension Sahayak",
        modifier = modifier,
        actions = {
            TextButton(onClick = onReadAloud) {
                Icon(
                    Icons.Rounded.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "Read aloud",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    modifier = Modifier.padding(start = DoosriDimens.xs),
                )
            }
        },
    ) {
        // The question is echoed at the top, exactly as the plan's wireframe
        // shows it, so the answer is never read against a forgotten question.
        Text(
            text = "“${state.askedQuestion}”",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
        )

        when {
            state.asking -> LoadingCard()

            state.error != null -> ErrorCard(
                error = state.error,
                onRetry = onRetry,
                onBack = onBack,
            )

            state.answer is SahayakAnswer.Cited -> {
                val answer = state.answer as SahayakAnswer.Cited
                AnswerCard(answer)

                answer.action?.let { action ->
                    DoosriPrimaryButton(
                        text = action.label,
                        onClick = { onPrimaryAction(action.target) },
                    )
                }

                // Position is load-bearing. See the header comment.
                SourcesBlock(
                    citations = answer.citations,
                    onOpenSource = onOpenSource,
                )

                FeedbackControl(
                    feedback = state.feedback,
                    escalation = state.escalation,
                    onFeedback = onFeedback,
                    onEscalate = onEscalate,
                )
            }

            state.answer is SahayakAnswer.NoOfficialAnswer -> NoOfficialAnswerCard(
                answer = state.answer as SahayakAnswer.NoOfficialAnswer,
                escalation = state.escalation,
                onEscalate = onEscalate,
                onBack = onBack,
            )
        }

        // Plan 6.4: "Every answer ends with" this line. It is rendered by this
        // one call, for every answer and every "no answer", so the rule cannot
        // be met on one screen and quietly dropped on the next.
        GeneralInformationNotice()
    }
}

// ----------------------------------------------------------------- the answer --

@Composable
private fun AnswerCard(answer: SahayakAnswer.Cited) {
    val colors = DoosriTheme.colors

    DoosriCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = answer.summary,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
        )
        Text(
            text = answer.detail,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
    }
}

/**
 * The sources block. One card per source so each is a complete, self-contained
 * citation — a pensioner shows this screen to a clerk at the bank, and a
 * half-citation on a shared list row is not something a clerk will accept.
 */
@Composable
private fun SourcesBlock(
    citations: List<Citation>,
    onOpenSource: (Citation) -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriSectionHeader("Sources")

    citations.forEach { citation ->
        DoosriCard(modifier = Modifier.fillMaxWidth()) {
            DoosriListRow(
                icon = Icons.Outlined.MenuBook,
                title = citation.whereLabel,
                value = if (citation.status == SourceStatus.Superseded) "Older rule" else null,
            )

            CitationLine(
                icon = Icons.Outlined.Info,
                text = "Issued by ${citation.issuingOffice}" +
                    (citation.documentNumber?.let { " · $it" } ?: ""),
            )

            // The verification date at body size, in the same block as the
            // answer. Not a caption, not behind the chevron: "when did a person
            // last check this against the original" is a fact the reader is
            // owed at the same volume as everything else on the screen.
            CitationLine(
                icon = Icons.Outlined.Event,
                text = "Last checked against the original on ${citation.lastVerifiedOn}",
            )

            val url = citation.sourceUrl
            if (url != null) {
                DoosriSecondaryButton(
                    text = "Open the original",
                    onClick = { onOpenSource(citation) },
                    icon = Icons.Outlined.OpenInNew,
                )
            }
        }
    }
}

@Composable
private fun CitationLine(icon: ImageVector, text: String) {
    val colors = DoosriTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkSecondary,
        )
    }
}

// ------------------------------------------------------------ no real answer --

/**
 * "No official answer to this."
 *
 * Plan 6.4's guardrail: below the confidence threshold, say so and offer a
 * volunteer. What must **not** happen is the model producing a fluent paragraph
 * with no source — that is the failure this whole feature exists to prevent, so
 * it is prevented structurally: this branch renders a labelled empty state and
 * a next step, and there is no path through this screen that renders prose from
 * [SahayakAnswer.NoOfficialAnswer]. The type has only a `reason`; it has no
 * `detail` field to fill in.
 */
@Composable
private fun NoOfficialAnswerCard(
    answer: SahayakAnswer.NoOfficialAnswer,
    escalation: EscalationState,
    onEscalate: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = colors.marigold,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = "No official answer to this",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
            )
        }
        Text(
            text = answer.reason,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
        Text(
            text = "I only answer from the official rules and orders. " +
                "Rather than guess, I would rather pass your question to someone who can look at your case.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkSecondary,
        )
    }

    DoosriPrimaryButton(
        text = "Ask a verified volunteer",
        onClick = onEscalate,
        icon = Icons.Outlined.SupportAgent,
        enabled = escalation !is EscalationState.Sending && escalation !is EscalationState.Sent,
    )
    DoosriSecondaryButton(
        text = "Ask in a different way",
        onClick = onBack,
    )

    EscalationStatusLine(escalation)
}

// -------------------------------------------------------------- the controls --

/**
 * "Was this helpful? Yes / No / Ask a volunteer" — plan 6.4's feedback and
 * escalation controls, as three labelled buttons in one row-block. They are
 * secondary buttons rather than chips: at 60+ and one thumb, a chip is a
 * mis-tap waiting to happen, and "Ask a volunteer" is the plan's whole safety
 * net for a wrong answer.
 */
@Composable
private fun FeedbackControl(
    feedback: FeedbackState,
    escalation: EscalationState,
    onFeedback: (Boolean) -> Unit,
    onEscalate: () -> Unit,
) {
    val colors = DoosriTheme.colors

    DoosriSectionHeader("Was this helpful?")

    when (feedback) {
        FeedbackState.None -> {
            DoosriSecondaryButton(text = "Yes, this helped", onClick = { onFeedback(true) })
            DoosriSecondaryButton(text = "No, this did not help", onClick = { onFeedback(false) })
            DoosriSecondaryButton(
                text = "Ask a volunteer",
                onClick = onEscalate,
                icon = Icons.Outlined.SupportAgent,
                enabled = escalation !is EscalationState.Sending && escalation !is EscalationState.Sent,
            )
        }

        is FeedbackState.Sent -> Text(
            text = if (feedback.helpful) {
                "Thank you. We will show more answers like this."
            } else {
                "Thank you for saying so. A volunteer reviews answers that people find unhelpful, or you can ask one directly below."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )

        FeedbackState.Failed -> Text(
            text = "That note did not send. The answer is still here.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
    }

    EscalationStatusLine(escalation)
}

/**
 * The escalation state, in words. Plan 6.4 promises a human answer within 48
 * hours, so that is the sentence: "within 48 hours", not "ASAP", not a spinner.
 */
@Composable
private fun EscalationStatusLine(escalation: EscalationState) {
    val colors = DoosriTheme.colors

    when (escalation) {
        EscalationState.None, EscalationState.Sending -> Unit

        is EscalationState.Sent -> Text(
            text = "Sent to a verified volunteer. They answer ${escalation.promisedByLabel}. " +
                "Reference: ${escalation.referenceId}.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )

        EscalationState.Failed -> Text(
            text = "That did not reach us. Please try again when you have a connection.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )
    }
}

// ------------------------------------------------------------ loading, error --

@Composable
private fun LoadingCard() {
    val colors = DoosriTheme.colors
    DoosriCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.md),
        ) {
            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
            Text(
                text = "Looking through the official rules…",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
        }
        Text(
            text = "This takes a few seconds. Your question is kept either way.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun ErrorCard(
    error: SahayakError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = DoosriTheme.colors
    val message = when (error) {
        // Sahayak is not in plan 13's offline list: the retrieval and the
        // answer need a connection, and an answer from stale chunks with stale
        // citations is worse than saying so. The question is kept on screen.
        SahayakError.Offline ->
            "This needs an internet connection. I cannot check the rules from this phone alone."
        SahayakError.Failed ->
            "Something went wrong and I could not answer. Your question is kept."
    }

    DoosriCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
    }
    DoosriPrimaryButton(text = "Try again", onClick = onRetry)
    DoosriSecondaryButton(text = "Change my question", onClick = onBack)
}

// ---------------------------------------------------------------- the notice --

@Composable
private fun GeneralInformationNotice() {
    val colors = DoosriTheme.colors
    HorizontalDivider(color = colors.divider)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.inkSecondary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = GENERAL_INFORMATION_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
        )
    }
}