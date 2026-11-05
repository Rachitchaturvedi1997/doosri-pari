package `in`.doosripari.feature.sahayak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.doosripari.ui.design.DoosriCard
import `in`.doosripari.ui.design.DoosriEmptyState
import `in`.doosripari.ui.design.DoosriListRow
import `in`.doosripari.ui.design.DoosriPrimaryButton
import `in`.doosripari.ui.design.DoosriScreen
import `in`.doosripari.ui.design.DoosriSectionHeader
import `in`.doosripari.ui.theme.DoosriDimens
import `in`.doosripari.ui.theme.DoosriTheme

/**
 * Pension Sahayak — the question screen. Plan sections 4.5, 6.4 and 6.16.
 *
 * One screen, one job: get a question typed or tapped and asked. The box is
 * large because the users type slowly and often with one thumb; the examples
 * are tappable rows because a pensioner who cannot phrase the question is the
 * common case, not the edge case, and every icon here has its words beside it.
 *
 * The answer side lives in [SahayakAnswerScreen]. This file deliberately holds
 * no answer rendering at all: an answer must be able to appear in exactly one
 * place, with its citation block, or the "citation is the product" rule has a
 * second place to be forgotten.
 */
@Composable
fun SahayakQuestionScreen(
    state: SahayakUiState,
    onQuestionChange: (String) -> Unit,
    onAsk: () -> Unit,
    onUseExample: (String) -> Unit,
    onReadAloud: () -> Unit,
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
        Text(
            text = "Ask in your own words — Hindi, English, or however you say it.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
        )

        OutlinedTextField(
            value = state.question,
            onValueChange = onQuestionChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your question") },
            minLines = 3,
            isError = state.problem != null,
            supportingText = {
                val problem = state.problem
                if (problem != null) {
                    // `ink`, not `safety`: plan 4.3 reserves the red for SOS and a
                    // missed check-in, and a typo in a question is not somebody in
                    // danger. The words carry the warning; the field's error
                    // outline carries the state.
                    Text(text = problem.message, color = colors.ink)
                } else {
                    Text(text = "For example: “Mera DA arrears kitna banega?”")
                }
            },
        )

        // The guard line sits with the input, not in a policy screen. See the
        // comment on [QuestionValidator]: a question is stored server-side, and
        // the pensioner most likely to paste a PPO number is the one who is
        // frightened about their pension. Saying "we never need it" here is the
        // whole of the defence at the moment it matters.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoosriDimens.sm),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = "Please do not type your PPO number, Aadhaar number or bank account number. We never need them.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
        }

        DoosriPrimaryButton(
            text = "Ask Sahayak",
            onClick = onAsk,
            icon = Icons.Rounded.Send,
            enabled = state.question.isNotBlank() && !state.asking,
        )

        DoosriSectionHeader("Try one of these")
        DoosriCard {
            EXAMPLE_QUESTIONS.forEach { example ->
                DoosriListRow(
                    icon = Icons.Outlined.QuestionAnswer,
                    title = example,
                    onClick = { onUseExample(example) },
                )
            }
        }

        if (state.recent.isNotEmpty()) {
            DoosriSectionHeader("You asked before")
            DoosriCard {
                state.recent.forEach { asked ->
                    DoosriListRow(
                        icon = Icons.Outlined.MenuBook,
                        title = asked.text,
                        value = if (asked.answered) "Answered" else "No answer yet",
                        onClick = { onUseExample(asked.text) },
                    )
                }
            }
        } else {
            DoosriEmptyState(
                message = "Your questions will appear here. Nothing is shared with your family unless you ask us to.",
            )
        }

        Text(
            text = GENERAL_INFORMATION_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = colors.inkSecondary,
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * The three examples cover the plan's own intents — a rule question, a
 * calculation and a process — so the first tap teaches what this screen is
 * for. They are plain questions, not prompts: the user should recognise their
 * own words in them.
 */
private val EXAMPLE_QUESTIONS = listOf(
    "Mera DA arrears kitna banega?",
    "Family pension kitni milti hai?",
    "Jeevan Pramaan kab tak jama karna hai?",
)
