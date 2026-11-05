package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.ActionKind
import `in`.doosripari.core.model.SahayakAction
import `in`.doosripari.core.model.SahayakAnswer
import `in`.doosripari.core.model.SahayakIntent
import `in`.doosripari.core.model.SahayakSource

/**
 * Turns retrieved passages into an answer, or into an offer to talk to a person.
 * Plan section 6.4, "Answer logic".
 *
 * ### The one thing this file is for
 *
 * **It cannot produce a publishable answer with no sources.** Not "should not" —
 * cannot. The refusal is not a branch a later change could forget: an answer is
 * built only from passages that cleared
 * [in.doosripari.core.model.SahayakAnswer.MIN_CONFIDENCE], and an empty list of
 * those produces a [SahayakAnswer] whose `sources` is empty, which makes
 * [SahayakAnswer.isPublishable] false inside core-model rather than in a test
 * somewhere. The composer never passes `isPublishable` at all; it passes
 * `confidence` and lets the model's own rule decide, so the bar lives in one
 * place and can be raised without touching this file.
 *
 * Everything below is a detail of *why* that refusal happens, so that a
 * volunteer reading the ticket knows whether the corpus was missing, the
 * question was unreadable, or the question was not Sahayak's to answer.
 *
 * ### What is refused, and what is merely answered badly
 *
 * Three refusals are not about confidence at all:
 *
 *  - [RefusalReason.OutOfScope] — the question is not a pension question.
 *    Plan 6.4 step 5 is explicit that Sahayak does not give investment advice,
 *    and the cheapest way to guarantee that is to refuse the whole category.
 *  - [RefusalReason.PersonalCase] — "my arrears for 2021 still have not come".
 *    The rules can say what should happen; only an office can say what did. The
 *    corpus is not the place to guess about one person's file.
 *  - [RefusalReason.Grievance] — same, and plan 6.4 wants the grievance helper
 *    opened, which this module has no route for. See [primaryActionFor].
 *
 * ### The guardrails
 *
 * Plan 6.4 step 5 also forbids asking for PPO, Aadhaar or bank numbers. That
 * check is [containsForbiddenRequest], and it runs over the composed prose
 * **and not over the quotations**. A rule that legitimately mentions Aadhaar is
 * still worth quoting; a sentence our own answer wrote asking a pensioner for
 * their Aadhaar number is not. Keeping the two apart matters, because a single
 * undifferentiated check would refuse to cite half the corpus.
 *
 * This module has no model in it, so the prose is a template and the check
 * cannot yet fail. It is here anyway, and the seam is named: a model-backed
 * composer replaces [answerText] and nothing else — the same
 * [containsForbiddenRequest] call, the same source filter, the same confidence,
 * the same refusals.
 */
object Composer {

    /**
     * How many sources one answer carries.
     *
     * Plan 6.4 retrieves six and shows source cards for the claims. Three is
     * what a pensioner will actually open: past three, the citations stop being
     * read and become a row of document names, which is the same as no
     * citations at all with more ink. The rest stay in
     * [RetrievalResult.hits] for the UI's "other passages" list.
     */
    const val MAX_SOURCES: Int = 3

    /**
     * How much of a passage is quoted.
     *
     * Long enough to show the rule, short enough to read on a phone with a
     * large font — which is the actual device. Truncated on a word boundary,
     * because a quotation with a half-word and an ellipsis in it is not a
     * quotation.
     */
    const val QUOTE_CHARS: Int = 280

    /** Why there is no answer. Carried to the volunteer ticket and the log. */
    enum class RefusalReason {
        /** The corpus had nothing for this question at all. */
        NoSources,

        /**
         * Passages were found and none was good enough to publish on.
         *
         * Distinct from [NoSources] because the corpus may well be fine and the
         * question was written in a way the retriever could not follow — which
         * is a question for the synonyms table, not for the corpus.
         */
        BelowConfidence,

        /** Not a pension question. Plan 6.4: no investment advice. */
        OutOfScope,

        /** About this one pensioner's own file. See the class comment. */
        PersonalCase,

        /** A complaint. Plan 6.4 wants the grievance helper, not an answer. */
        Grievance,

        /**
         * The composed prose asked for a PPO, Aadhaar or bank number.
         *
         * Kept as a refusal rather than a silent strip: a composer that reaches
         * this has already produced text that should never have been generated,
         * and hiding that makes the bug unfindable.
         */
        ForbiddenRequest,
    }

    /**
     * The answer, or the refusal.
     *
     * @param used the passages to cite. Defaults to everything in
     *   [retrievalResult] that clears the confidence bar, already limited to
     *   [MAX_SOURCES]. It is a parameter so a caller can answer a narrower
     *   question than the one it searched for — a chat screen showing one
     *   passage at a time is the obvious case.
     */
    fun compose(
        retrievalResult: RetrievalResult,
        used: List<ScoredChunk> = usableHits(retrievalResult),
    ): SahayakAnswer {
        val query = retrievalResult.query

        val classificationRefusal = refuseIfNotOurQuestion(query.classification)
        if (classificationRefusal != null) return classificationRefusal

        if (used.isEmpty()) {
            return refuse(
                query,
                if (retrievalResult.hits.isEmpty()) {
                    RefusalReason.NoSources
                } else {
                    RefusalReason.BelowConfidence
                },
            )
        }

        val text = answerText(query, used)

        // The guardrail. Over the prose only — see the class comment.
        if (containsForbiddenRequest(text)) {
            return refuse(query, RefusalReason.ForbiddenRequest)
        }

        return SahayakAnswer(
            text = text,
            // Built from `used`, which is non-empty, so this is where
            // [SahayakAnswer.isPublishable] becomes true — and it becomes true
            // because a source exists, which is the point.
            sources = used.map { citation(it) },
            intent = query.classification.intent,
            // The best passage's confidence, which is
            // [SahayakAnswer.confidence] and nothing else. An answer is never
            // more confident than the best thing it is standing on.
            confidence = used.first().confidence,
            // core-model's constant, attached on every path including every
            // refusal. Plan section 14: an answer that forgets its disclaimer
            // is the failure this app is most often criticised for, so the
            // disclaimer is a constructor argument here rather than something
            // the view remembers to print.
            disclaimer = SahayakAnswer.DEFAULT_DISCLAIMER,
            primaryAction = primaryActionFor(query, used.first()),
            // isPublishable is deliberately not passed. core-model computes it
            // from sources and confidence, and a second copy of that rule in
            // this file would be a second thing to forget.
        )
    }

    /**
     * The passages worth citing: those at or above the bar, best first, capped.
     *
     * The bar is read from [SahayakAnswer.MIN_CONFIDENCE] rather than written
     * out, so raising it in core-model changes this file's behaviour with no
     * edit here.
     */
    fun usableHits(retrievalResult: RetrievalResult): List<ScoredChunk> =
        retrievalResult.hits
            .filter { it.confidence >= SahayakAnswer.MIN_CONFIDENCE }
            .take(MAX_SOURCES)

    /**
     * Refusals that follow from what the question *is*, not from what was
     * found. Null means the question is one Sahayak should try to answer.
     */
    private fun refuseIfNotOurQuestion(
        classification: IntentClassification,
    ): SahayakAnswer? = when (classification.kind) {
        // Out of scope only refuses when the forbidden-subject table actually
        // matched. See [IntentClassification.isExplicitlyOutOfScope]: a pension
        // question this module's vocabulary misses must be told "I could not
        // find it", not "that is not a pension question".
        SahayakQuestionKind.OutOfScope ->
            if (classification.isExplicitlyOutOfScope) {
                refusal(
                    REFUSAL_TEXTS.getValue(RefusalReason.OutOfScope),
                    intent = classification.intent,
                )
            } else {
                null
            }
        SahayakQuestionKind.PersonalCase ->
            refusal(REFUSAL_TEXTS.getValue(RefusalReason.PersonalCase), intent = classification.intent)
        SahayakQuestionKind.Grievance ->
            refusal(REFUSAL_TEXTS.getValue(RefusalReason.Grievance), intent = classification.intent)
        SahayakQuestionKind.RuleQuestion,
        SahayakQuestionKind.Calculation,
        SahayakQuestionKind.Process,
        -> null
    }

    private fun refuse(
        query: RetrievalQuery,
        reason: RefusalReason,
    ): SahayakAnswer = refusal(REFUSAL_TEXTS.getValue(reason), intent = query.classification.intent)

    /**
     * A refusal. No sources, no confidence, and an action that reaches a person.
     *
     * The empty [SahayakAnswer.sources] is not an oversight — it is the refusal.
     * [SahayakAnswer.isPublishable] is therefore false by core-model's own
     * rule, and this method cannot construct a publishable answer even by
     * accident, because there is no way to set a flag from here.
     */
    private fun refusal(text: String, intent: SahayakIntent): SahayakAnswer = SahayakAnswer(
        text = text,
        sources = emptyList(),
        intent = intent,
        confidence = 0f,
        disclaimer = SahayakAnswer.DEFAULT_DISCLAIMER,
        primaryAction = volunteerAction(),
    )

    /**
     * The refusal sentences.
     *
     * Written as sentences rather than as codes because these are the words a
     * 68-year-old reads when the app has failed them, and "I could not find
     * this in the rules I have" is a sentence and `NO_SOURCES` is not. Each
     * names a next step, because an answer with no source and no route is worse
     * than a refusal that admits it.
     */
    private val REFUSAL_TEXTS: Map<RefusalReason, String> = mapOf(
        RefusalReason.NoSources ->
            "I could not find this in the rules I have. I would rather say that " +
                "than give you an answer I cannot show you the source for.",
        RefusalReason.BelowConfidence ->
            "I found passages that might be about this, but none of them is close " +
                "enough to be sure. A volunteer can check the rule and write back.",
        RefusalReason.OutOfScope ->
            "Sahayak only answers questions about pension rules. This is not one of " +
                "them, and I cannot help with it.",
        RefusalReason.PersonalCase ->
            "I can explain the rule, but I cannot say what happened in your case — " +
                "that is your Accounts Office's record, not mine. A volunteer can " +
                "help you work out what to ask them.",
        RefusalReason.Grievance ->
            "This looks like something has gone wrong with a claim. I can tell you " +
                "what the rule says should have happened, but a volunteer needs to " +
                "take it further.",
        RefusalReason.ForbiddenRequest ->
            "I am not able to publish this answer. Please do not share Aadhaar, " +
                "PPO or bank numbers here — Sahayak will never ask for them.",
    )

    /**
     * The one action a refusal offers.
     *
     * Plan 6.4: "below the confidence threshold, reply 'I could not find this in
     * the rules I have' and offer a volunteer." A refusal that does not end in
     * somebody is a dead end, and this app's users are largely offline.
     */
    fun volunteerAction(): SahayakAction = SahayakAction(
        kind = ActionKind.AskVolunteer,
        target = VOLUNTEER_TARGET,
        label = "Ask a volunteer",
    )

    /**
     * The one thing to do next, per plan 4.5's "at most one".
     *
     * A calculation opens a calculator rather than an answer, which is the
     * whole point of [SahayakQuestionKind.Calculation]: the corpus says the rule
     * and the toolkit computes the figure, and only one of them can be right
     * about this pensioner.
     *
     * Note one gap: plan 6.4 wants a grievance question to "open the grievance
     * helper", and [ActionKind] has no member for a helper — the nearest is
     * [ActionKind.FindOffice]. Rather than invent a kind here, a grievance is
     * routed to a volunteer, which is slower but honest. See the module report.
     */
    fun primaryActionFor(query: RetrievalQuery, best: ScoredChunk): SahayakAction? = when (
        query.classification.kind
    ) {
        SahayakQuestionKind.Calculation -> calculatorFor(query.classification.intent)
        SahayakQuestionKind.Process, SahayakQuestionKind.RuleQuestion ->
            SahayakAction(
                kind = ActionKind.OpenDocument,
                target = "doc:${best.doc.id}",
                label = "Open ${best.doc.title}",
            )
        // Out of scope, personal case and grievance all reach a person, and the
        // personal case and grievance ones have already been refused by the time
        // this is called. Kept explicit rather than defaulted so that a new kind
        // cannot silently inherit a document link.
        SahayakQuestionKind.Grievance, SahayakQuestionKind.PersonalCase, SahayakQuestionKind.OutOfScope ->
            volunteerAction()
    }

    /**
     * The toolkit route for a subject, or null when there is no calculator for
     * it.
     *
     * Null is a real answer here. A tax question has no calculator in this app,
     * and opening a blank one would be worse than opening the rule.
     */
    fun calculatorFor(intent: SahayakIntent): SahayakAction? {
        val route = when (intent) {
            // A DA question and an arrears question are the same calculator
            // viewed at two points: one shows what the rate in force pays now,
            // the other what a missed revision was worth.
            SahayakIntent.Arrears, SahayakIntent.DearnessAllowance -> "toolkit/arrears"
            SahayakIntent.Commutation -> "toolkit/commutation"
            SahayakIntent.PensionSlip -> "toolkit/payroll"
            else -> null
        } ?: return null

        return SahayakAction(
            kind = ActionKind.OpenCalculator,
            target = route,
            label = "Work out your figure",
        )
    }

    /**
     * The prose, with its citations inline.
     *
     * Template, not generation — see the class comment. The shape is the
     * plan's: say what the question was read as, then one cited line per
     * passage, then the disclaimer that [SahayakAnswer.disclaimer] already
     * carries.
     *
     * An older rule is labelled where it is cited rather than in a footnote,
     * because a citation the user cannot interpret is not a citation: "older
     * rule" next to the passage is what tells them this is what applied in 2016
     * and not what applies now.
     */
    fun answerText(query: RetrievalQuery, hits: List<ScoredChunk>): String = buildString {
        append(LEAD_IN)
        append(query.classification.summary)
        append('\n')

        for (hit in hits) {
            append('\n')
            if (hit.isOlderRule) {
                append("(older rule) ")
            }
            append('·')
            append(' ')
            append(hit.chunk.headingPath.ifBlank { hit.doc.title })
            append(' ')
            append(citationMarker(hit))
            append('\n')
            append(quoteOf(hit))
        }

        append('\n')
        append(SahayakAnswer.DEFAULT_DISCLAIMER)
    }

    /**
     * The plan's citation form: `[CCS (Pension) Rules 2021, Rule 44(2)]`.
     *
     * The pinpoint is preferred over the document number because it is what
     * takes a reader to the passage; the number is a fallback for the minority
     * of documents whose chunks carry no heading path.
     */
    fun citationMarker(hit: ScoredChunk): String {
        val where = hit.chunk.headingPath.ifBlank {
            hit.doc.docNo ?: hit.doc.issuer
        }
        return "[${hit.doc.title}, $where]"
    }

    /** The citation a source card opens from. */
    fun citation(hit: ScoredChunk): SahayakSource = SahayakSource(
        docId = hit.doc.id,
        title = hit.doc.title,
        issuer = hit.doc.issuer,
        docNo = hit.doc.docNo,
        url = hit.doc.url,
        pinpoint = hit.chunk.headingPath.ifBlank { null },
        page = hit.chunk.page,
        quote = quoteOf(hit),
    )

    /** The passage, trimmed to [QUOTE_CHARS] on a word boundary. */
    fun quoteOf(hit: ScoredChunk): String {
        val text = hit.chunk.text.trim().replace(WHITESPACE, " ")
        if (text.length <= QUOTE_CHARS) return text
        val cut = text.take(QUOTE_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > QUOTE_CHARS / 2) cut.take(lastSpace) else cut) + "…"
    }

    /**
     * Whether a piece of prose asks for something it must never ask for.
     *
     * Plan 6.4 step 5: "never ask for PPO, Aadhaar or bank numbers". The
     * patterns are the document names, the abbreviations, and the words that
     * precede a number — because the dangerous sentence is not "Aadhaar" but
     * "share your Aadhaar number", and a check on the noun alone would let the
     * verb through.
     *
     * Substring matching, not word matching, on purpose: "aadhaar" appears
     * inside "aadhaarpan", which is a different thing and is a false positive
     * worth having rather than a false negative worth having. This errs towards
     * refusing, which is the direction that is safe for a privacy rule.
     */
    fun containsForbiddenRequest(text: String): Boolean {
        val lower = text.lowercase()
        return FORBIDDEN.any { it in lower }
    }

    /**
     * What not to ask for.
     *
     * `pan card` is here and `pancard` is not, because both are written; the
     * list is the union of the two spellings and the two abbreviations people
     * actually type.
     */
    private val FORBIDDEN: List<String> = listOf(
        "aadhaar", "aadhar", "adhar",
        "ppo number", "ppo no", "pension payment order",
        "pan card", "pancard", "pan number",
        "bank account number", "account number", "ifsc",
        "bank details", "banking details",
        "passbook", "pass book",
    )

    /**
     * Opens the answer without a promise.
     *
     * "Here is what the rules say" rather than "The answer is", because the
     * difference between the two is the difference between a rule quoted
     * correctly and advice given wrongly, and the second is what gets an old
     * person to act on something that does not apply to them.
     */
    private const val LEAD_IN = "Here is what the rules say. "

    private const val VOLUNTEER_TARGET = "volunteer/new"

    private val WHITESPACE = Regex("\\s+")
}
