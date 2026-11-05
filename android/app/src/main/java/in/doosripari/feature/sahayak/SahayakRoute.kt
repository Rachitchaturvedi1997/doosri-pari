package `in`.doosripari.feature.sahayak

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Pension Sahayak — the stateful entry point.
 *
 * The navigation shell binds `SahayakRoute(modifier: Modifier = Modifier,
 * onBack: () -> Unit = {})`; everything after those two is an optional
 * override so a test can pass a fake repository or a fake ViewModel and the
 * shell can pass nothing. OnBack is the host's pop — this feature is pushed
 * from the Main tab (plan section 5), and the question↔answer move inside it is
 * state, not navigation, so answering a question never pops the screen out
 * from under a pensioner who wants to rephrase it.
 *
 * Plan section 5's "Bolo" voice routing deep-links here with the question
 * pre-filled: pass it as [initialQuestion] and the question screen opens with
 * the words already in the box. The question screen itself is
 * [SahayakQuestionScreen]; it takes its text from `SahayakUiState.question`.
 */
@Composable
fun SahayakRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    initialQuestion: String? = null,
    repository: SahayakRepository? = null,
    viewModel: SahayakViewModel? = null,
    onOpenCalculator: () -> Unit = {},
    onOpenGrievance: () -> Unit = {},
    onOpenProcess: () -> Unit = {},
    onOpenSource: (Citation) -> Unit = {},
    /** Plan 3.2: read this screen aloud. The TTS engine is not wired yet. */
    onReadAloud: () -> Unit = {},
) {
    val vm = viewModel ?: remember(repository, initialQuestion) {
        // No production SahayakRepository exists in `data/` yet. Rather than
        // crash the shell, the route falls back to a port that fails — so the
        // screen says "this needs an internet connection" and keeps the typed
        // question, instead of inventing an answer. Pass the real repository
        // here the moment one lands; nothing else changes.
        SahayakViewModel(
            repository = repository ?: NotConnectedSahayak(),
            language = null,
            initialQuestion = initialQuestion ?: "",
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.showingAnswer) {
        SahayakAnswerScreen(
            state = state,
            onBack = vm::backToQuestion,
            onRetry = vm::retry,
            onReadAloud = onReadAloud,
            onOpenSource = onOpenSource,
            onPrimaryAction = { target ->
                when (target) {
                    AnswerTarget.Calculator -> onOpenCalculator()
                    AnswerTarget.GrievanceHelper -> onOpenGrievance()
                    AnswerTarget.Process -> onOpenProcess()
                }
            },
            onFeedback = vm::sendFeedback,
            onEscalate = vm::escalate,
            modifier = modifier,
        )
    } else {
        SahayakQuestionScreen(
            state = state,
            onQuestionChange = vm::onQuestionChange,
            onAsk = vm::ask,
            onUseExample = vm::useExample,
            onReadAloud = onReadAloud,
            modifier = modifier,
        )
    }
}

/**
 * A [SahayakRepository] for a build whose network layer is not written yet.
 *
 * It fails the way a dead connection fails — an [java.io.IOException] — so the
 * ViewModel shows [SahayakError.Offline] and the answer screen says in words
 * that this needs a connection. It does **not** return sample answers: a fake
 * answer with a fake citation on this particular screen is worse than no app,
 * because the citation is the product.
 */
private class NotConnectedSahayak : SahayakRepository {

    override suspend fun ask(question: String, language: String?): SahayakAnswer =
        throw java.io.IOException("Sahayak is not connected yet")

    override suspend fun recent(limit: Int): List<SahayakQuestion> = emptyList()

    override suspend fun sendFeedback(questionId: String, helpful: Boolean): Boolean =
        throw java.io.IOException("Sahayak is not connected yet")

    override suspend fun escalate(question: String, questionId: String?): EscalationState.Sent =
        throw java.io.IOException("Sahayak is not connected yet")
}