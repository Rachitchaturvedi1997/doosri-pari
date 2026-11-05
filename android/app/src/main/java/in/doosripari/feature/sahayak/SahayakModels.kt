package `in`.doosripari.feature.sahayak

import kotlinx.datetime.Instant

/**
 * Pension Sahayak — plan sections 4.5, 6.4 and 13.
 *
 * ## The citation is the product
 *
 * Everything in this file exists to make one thing type-shaped: an answer that
 * is attached to the official document it came from. [Citation] carries the
 * source document, the section, and the date a human last verified the source,
 * because those three facts are the difference between this app and the
 * well-meant wrong answer a family group chat already gave this pensioner.
 * A fluent paragraph with no source is not a lesser answer here — it is not an
 * answer at all, which is why [SahayakAnswer.NoOfficialAnswer] is a case of
 * [SahayakAnswer] rather than a string in an error field. See
 * [SahayakAnswer] for how the screen keeps that promise.
 *
 * ## The input boundary
 *
 * Plan 6.4's guardrail is "never ask for PPO, Aadhaar or bank numbers". This
 * package goes one step further and refuses to *accept* them: see
 * [QuestionValidator] at the bottom of this file. The reason is storage —
 * plan section 10 puts question text in `sahayak_questions` server-side, so a
 * pasted account number would land in a database table keyed to a human being
 * who typed it while upset about their pension. The right place to stop that is
 * the keyboard, not a privacy policy.
 *
 * ## Strings
 *
 * Kotlin literals rather than `R.string` resources, for the reason
 * `feature/today/TodayModels.kt` gives: `res/values/strings.xml` is another
 * slice's file, and plan 6.16 needs ten human-reviewed translations. Known
 * debt, flagged here, not silently shipped.
 */

/**
 * Plan 6.4's answer logic classifies every question. `Calculation` and
 * `Grievance` are not answered in prose at all — they route to a calculator or
 * the grievance helper, which is the plan's own rule ("opens a calculator,
 * pre-filled", "opens the grievance helper").
 */
enum class QuestionIntent(val label: String) {
    RuleQuestion("A rule question"),
    Calculation("A calculation"),
    Process("A process question"),
    Grievance("A grievance"),
    PersonalCase("About your own case"),
    OutOfScope("Outside the rules I have"),
}

/**
 * Where a claim came from, and when a person last checked it against the
 * original.
 *
 * [lastVerifiedOn] is **not** the document's own date and not the crawl date.
 * It is the date a reviewer opened the original and confirmed this text is what
 * the office actually published — plan 6.4's ingestion keeps the original URL
 * and status for exactly this display. It is a field rather than a footnote
 * because "the rule changed last month and our copy did not" is the failure
 * mode that would quietly retire this app.
 */
data class Citation(
    /** "CCS (Pension) Rules, 2021" / "DoPPW OM No. 1/2/2026-P&PW" */
    val sourceDocument: String,
    /** "Rule 44(2)" / "Annexure I" — where in the document the claim lives. */
    val section: String,
    /** Who published it. The app is never this: plan 6.4, "never present the app as the issuing authority". */
    val issuingOffice: String,
    val documentNumber: String? = null,
    /** Plan 6.4: "Always link to the original." Null only for a printed source. */
    val sourceUrl: String? = null,
    val status: SourceStatus = SourceStatus.Current,
    /**
     * Already a display string ("2 October 2026"), like the labels in
     * `feature/today/TodayModels.kt`. The formatted date is the reviewer's
     * locale question and it belongs to whoever renders the calendar, not to a
     * screen in this package guessing at "2 Oct" vs "October 2".
     */
    val lastVerifiedOn: String,
) {
    /** What the row says under the source name. Two facts, in this order. */
    val whereLabel: String
        get() = if (section.isBlank()) sourceDocument else "$sourceDocument · $section"
}

/** Plan 6.4's ingestion labels a superseded chunk "older rule" and down-ranks it. */
enum class SourceStatus { Current, Superseded }

/**
 * The one action an answer may carry, per the plan's wireframe: one primary
 * button under the answer, never a row of choices.
 */
data class AnswerAction(val label: String, val target: AnswerTarget)

enum class AnswerTarget { Calculator, GrievanceHelper, Process }

/**
 * An answer. Two cases, and the second one is the reason this type is sealed
 * rather than one nullable string:
 *
 * * [Cited] — every sentence in [detail] is backed by at least one entry of
 *   [citations]. The screen renders the citation block at answer text size,
 *   directly under the answer and before anything else tappable. A citation
 *   set that is empty is a programming error, not a layout choice: the
 *   constructor keeps the two together so "add the answer first, cite later"
 *   cannot compile.
 *
 * * [NoOfficialAnswer] — nothing citable was found. The plan is explicit:
 *   "below the confidence threshold, reply 'I could not find this in the rules
 *   I have' and offer a volunteer", and "no citable source" must never become a
 *   fluent uncited paragraph. So this case carries a *reason* and the next
 *   step, and the screen draws a labelled empty state — it does not fall
 *   through to some generic answer renderer that would invent text.
 */
sealed interface SahayakAnswer {
    val questionId: String

    data class Cited(
        override val questionId: String,
        /** The one-or-two sentence answer. */
        val summary: String,
        /** The explanation, each claim carrying its bracketed citation. */
        val detail: String,
        /** Non-empty by contract. An answer without a source is [NoOfficialAnswer]. */
        val citations: List<Citation>,
        val intent: QuestionIntent = QuestionIntent.RuleQuestion,
        /** The user's language, as the plan's "answer in the original language". */
        val languageLabel: String? = null,
        val action: AnswerAction? = null,
    ) : SahayakAnswer {
        init {
            require(citations.isNotEmpty()) {
                "A Sahayak answer with no citation is not an answer; use SahayakAnswer.NoOfficialAnswer"
            }
        }
    }

    data class NoOfficialAnswer(
        override val questionId: String,
        /** "I could not find this in the rules I have." Plan 6.4's own words. */
        val reason: String,
        val intent: QuestionIntent = QuestionIntent.OutOfScope,
    ) : SahayakAnswer
}

/** "Was this helpful?" — plan 6.4's feedback control, in three states. */
sealed interface FeedbackState {
    data object None : FeedbackState
    data class Sent(val helpful: Boolean) : FeedbackState
    data object Failed : FeedbackState
}

/**
 * "Ask a volunteer" — plan 6.4's human escalation. Verified volunteers are
 * T2+ retired pension or accounts officers who answer within 48 hours, so
 * [Sent] carries that promise as a label the screen shows verbatim rather than
 * a number the user has to interpret.
 */
sealed interface EscalationState {
    data object None : EscalationState
    data object Sending : EscalationState
    data class Sent(val referenceId: String, val promisedByLabel: String) : EscalationState
    data object Failed : EscalationState
}

/** A question as stored, for the "you asked this before" list on the ask screen. */
data class SahayakQuestion(
    val id: String,
    val text: String,
    val askedAt: Instant,
    /** Display string, as everywhere else in this app. */
    val askedAtLabel: String,
    val answered: Boolean,
)

enum class SahayakError { Offline, Failed }

/**
 * Plan 6.4's required closing line. Every answer screen ends with it, and it
 * is a constant rather than a literal in three composables because "every
 * answer says it" is a rule, and rules that are copy-pasted are rules that get
 * edited out one screen at a time.
 */
const val GENERAL_INFORMATION_NOTICE =
    "This is general information from the rules, not legal or financial advice. " +
        "For your own case, confirm with your pension disbursing bank or Pay and Accounts Office."

/**
 * The server port. One method that answers, one that records feedback, one
 * that escalates to a human, one for the ask screen's recent list.
 *
 * **Sahayak needs a connection.** Plan section 13's offline column lists Today,
 * the check-in queue, the document safe, the calculators, yoga, the spiritual
 * texts and reminders — retrieval and an answer are not in it, because an
 * answer generated from stale chunks with stale citations is worse than a
 * screen that says "not without a connection". The ViewModel maps a failure to
 * [SahayakError.Offline] and keeps the typed question on screen; it never
 * falls back to cached prose.
 *
 * Nothing here takes a PPO number, an Aadhaar number or a bank account. See
 * [QuestionValidator] and keep it that way.
 */
interface SahayakRepository {
    /** Thrown failures are mapped by the ViewModel: IOException → Offline, else Failed. */
    suspend fun ask(question: String, language: String?): SahayakAnswer

    /** Newest first, for the ask screen. */
    suspend fun recent(limit: Int = 5): List<SahayakQuestion>

    /** Best-effort; a failed feedback send must never block the answer. */
    suspend fun sendFeedback(questionId: String, helpful: Boolean)

    /** Plan 6.4: verified volunteers answer within 48 hours. */
    suspend fun escalate(question: String, questionId: String?): EscalationState.Sent
}

/**
 * Plan 6.4: "never ask for PPO, Aadhaar or bank numbers".
 *
 * This is the enforcement point, and it is at the input on purpose. The
 * question text is stored server-side (plan section 10), so a pensioner who
 * pastes "my PPO is 4567/A/2019 and my account is 123456789012" while worried
 * about their money is handing this app exactly the identifiers a fraud needs,
 * and this app is exactly where a fraud will look for them later.
 *
 * The patterns are deliberately broad: a false positive (a question about a
 * 12-digit number that is not an Aadhaar) costs the user a rephrase, and a
 * false negative costs them their bank details. Asymmetric costs, so the
 * detector errs towards blocking.
 *
 * This is a guard rail, not a guarantee — a determined user can spell the
 * number out in words. It is still the right place to stand: most leaks are
 * paste-shaped, not deliberate.
 */
object QuestionValidator {

    /** 12 digits in the Aadhaar grouping, with or without spaces or dashes. */
    private val AADHAAR = Regex("""\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b""")

    /** Indian account numbers run 9–18 digits. Long digit runs are refused wholesale. */
    private val LONG_DIGITS = Regex("""\b\d{9,18}\b""")

    /** "PPO No. 4567/A/2019", "ppo number", "GPO/CHE/…" — the pension identifier itself. */
    private val PPO = Regex("""(?i)\b(ppo|gppo|ppo\s*no\.?|pension\s*payment\s*order)\b""")

    fun problemWith(text: String): QuestionProblem? = when {
        text.isBlank() -> QuestionProblem.Empty
        PPO.containsMatchIn(text) -> QuestionProblem.ContainsPpoNumber
        AADHAAR.containsMatchIn(text) -> QuestionProblem.ContainsAadhaar
        LONG_DIGITS.containsMatchIn(text) -> QuestionProblem.ContainsBankAccount
        else -> null
    }
}

/**
 * What was wrong with the typed question. One case per way of being wrong, so
 * the message can tell the user *which* identifier to take out rather than
 * "invalid input" — a pensioner who cannot tell what they did wrong will
 * either give up or paste the same text into a worse app.
 */
sealed interface QuestionProblem {
    /** The ask button is disabled rather than firing this one. */
    data object Empty : QuestionProblem

    data object ContainsAadhaar : QuestionProblem
    data object ContainsPpoNumber : QuestionProblem
    data object ContainsBankAccount : QuestionProblem

    val message: String
        get() = when (this) {
            Empty -> "Write your question first."
            ContainsAadhaar ->
                "Please take out the Aadhaar number. We never need it, and it is safer not to type it."
            ContainsPpoNumber ->
                "Please take out the PPO number. We never need it, and it is safer not to type it."
            ContainsBankAccount ->
                "Please take out the bank account number. We never need it, and it is safer not to type it."
        }
}