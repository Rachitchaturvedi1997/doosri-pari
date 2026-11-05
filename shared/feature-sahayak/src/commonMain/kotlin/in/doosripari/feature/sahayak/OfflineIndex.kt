package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.OfflineFaq
import `in`.doosripari.core.model.SahayakAnswer
import `in`.doosripari.core.model.SahayakFeedback
import `in`.doosripari.core.model.SahayakIntent
import `in`.doosripari.core.model.TicketStatus

/**
 * The bundled FAQ, searched with no network. Plan section 6.4's offline
 * fallback.
 *
 * **This is the on-a-train path.** A pensioner between Kottayam and Thrissur
 * with one bar of signal asks a question and gets either a reviewed answer or
 * nothing, and "nothing" has to be an acceptable outcome rather than a spinner
 * that never resolves. So the index is built, scored and filtered in one pass
 * with no I/O and no clock, and a miss is a miss.
 *
 * ### The bundle is not the corpus
 *
 * [OfflineIndex] refuses an FAQ whose answer is not publishable, at
 * construction. core-model says why — "A ticket only promotes here once a
 * volunteer has answered AND the answer carries a source. Without that rule the
 * offline corpus fills with whatever a well-meaning volunteer believed" — and
 * the offline path is where that rule is most load-bearing, because there is no
 * second source to check against. An unsourced answer in the bundle is the one
 * thing that makes the whole feature untrustworthy, and it is the one thing a
 * user cannot detect for themselves.
 *
 * Rejected entries are counted rather than dropped silently, so a build that
 * bundles nothing says why.
 *
 * ### The same bar as the online path
 *
 * A match must clear [SahayakAnswer.MIN_CONFIDENCE] on the same
 * [Retriever.confidenceFor] the online retriever uses. That is a deliberate
 * constraint: the offline path is a *fallback*, and a fallback that answers
 * questions the online path would refuse to answer is not a fallback, it is a
 * second and worse product that ships to the people with the least signal.
 */
class OfflineIndex(
    faqs: List<OfflineFaq>,
) {

    /** What actually ships. See the class comment. */
    val entries: List<OfflineFaq>

    /** How many were dropped for not being publishable. */
    val rejectedCount: Int

    init {
        val usable = faqs.filter { it.answer.isPublishable }
        entries = usable.sortedByDescending { it.askCount }
        rejectedCount = faqs.size - usable.size
    }

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * The entries to put in the app bundle, most asked first.
     *
     * [OfflineFaq.askCount] decides the cut because the bundle has a size limit
     * and a question asked four hundred times is worth more offline space than
     * one asked once. The limit itself is a product decision this module does
     * not make — the caller passes it.
     */
    fun bundleFor(maxEntries: Int): List<OfflineFaq> = entries.take(maxEntries.coerceAtLeast(0))

    /**
     * Search the bundle.
     *
     * @param language the reader's language. Entries in it are preferred, not
     *   required: falling back to English is a lesser failure than an empty
     *   screen, and the answer carries its own [OfflineFaq.language] so the UI
     *   can label it rather than pretend otherwise.
     */
    fun search(
        text: String,
        language: AppLanguage? = null,
    ): OfflineSearchResult {
        val classification = IntentClassifier.classify(text)
        val contentTokens = Retriever.contentTokens(text)

        val matches = entries.mapNotNull { faq ->
            val searchable = searchableTokens(faq)
            val matched = contentTokens.filter { it in searchable }

            // The FAQ's own subject, compared with the question's. This is what
            // lets "mere arrears kitne hue" find an FAQ headed "What are DA
            // arrears?", where the wording shares nothing.
            val subjectAligned = faq.answer.intent == classification.intent &&
                classification.intent != SahayakIntent.Unknown

            val coverage = if (contentTokens.isEmpty()) {
                0.0
            } else {
                (matched.size + Retriever.SYNONYM_CREDIT * (contentTokens.size - matched.size))
                    .div(contentTokens.size)
                    .coerceIn(0.0, 1.0)
            }

            val confidence = Retriever.confidenceFor(coverage, subjectAligned)
            if (confidence < SahayakAnswer.MIN_CONFIDENCE) return@mapNotNull null

            OfflineMatch(
                faq = faq,
                confidence = confidence,
                coverage = coverage,
                matchedTokens = matched,
                subjectAligned = subjectAligned,
            )
        }

        // A language match breaks a tie and a near-tie. It is a tie-break, not a
        // filter, for the reason in [search]'s KDoc.
        val ranked = matches
            .sortedWith(
                compareByDescending<OfflineMatch> {
                    if (language != null && it.faq.language == language) 1 else 0
                }.thenByDescending { it.confidence },
            )

        return OfflineSearchResult(
            question = text,
            classification = classification,
            matches = ranked,
        )
    }

    /**
     * Everything about an FAQ that can be matched on.
     *
     * The endonym is in the set so a question typed in Devanagari can reach an
     * FAQ whose question is in Devanagari, and so a question about "English"
     * in a UI that offers language choices finds the English entry.
     */
    private fun searchableTokens(faq: OfflineFaq): Set<String> =
        SahayakText.indexSet("${faq.question} ${faq.answer.text} ${faq.language.endonym}")

    companion object {
        /** An index with nothing in it, for a build whose corpus is still empty. */
        val EMPTY: OfflineIndex = OfflineIndex(emptyList())

        /**
         * Whether a volunteer ticket's answer may be promoted into the bundle.
         *
         * All three conditions, and each one is a different failure:
         *
         *  - the ticket was **answered** — an open ticket has no answer to
         *    promote, and promoting the question would put a placeholder in
         *    front of users.
         *  - the feedback is not **not helpful** — a volunteer answering and the
         *    user disagreeing means one of them is wrong, and the bundle is not
         *    the place to settle it.
         *  - the answer **is publishable** — it has sources and clears the
         *    confidence bar. See the class comment for why this is the one that
         *    matters.
         *
         * Null feedback is treated as approval: a volunteer who reviewed a
         *    ticket is the review, and the app has no other reviewer.
         */
        fun canPromote(
            status: TicketStatus,
            answer: SahayakAnswer?,
            feedback: SahayakFeedback? = null,
        ): Boolean = when {
            status != TicketStatus.Answered -> false
            answer == null || !answer.isPublishable -> false
            feedback == SahayakFeedback.NotHelpful -> false
            else -> true
        }
    }
}

/** One FAQ, and how well it answers. */
data class OfflineMatch(
    val faq: OfflineFaq,
    /**
     * The same number an online answer's confidence would be.
     *
     * Never above [SahayakAnswer.MIN_CONFIDENCE] in practice, because matches
     * below it are not returned — kept as the score rather than a boolean
     * because two FAQs both above the bar still need ordering.
     */
    val confidence: Float,
    val coverage: Double,
    val matchedTokens: List<String>,
    val subjectAligned: Boolean,
) {
    /** The answer to show, and its own disclaimer travels with it. */
    val answer: SahayakAnswer get() = faq.answer
}

/**
 * An offline search, including the question it could not answer.
 *
 * [classification] is kept so the UI can say "we read that as a question about
 * commutation" even when nothing matched — which is the moment the user most
 * needs to know Sahayak understood them before it failed them.
 */
data class OfflineSearchResult(
    val question: String,
    val classification: IntentClassification,
    val matches: List<OfflineMatch>,
) {
    val best: OfflineMatch? get() = matches.firstOrNull()

    val isEmpty: Boolean get() = matches.isEmpty()
}
