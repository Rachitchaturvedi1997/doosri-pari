package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.DocStatus
import `in`.doosripari.core.model.SahayakChunk
import `in`.doosripari.core.model.SahayakDoc
import `in`.doosripari.core.model.ServiceType

/**
 * A search, and the reading of the question that made it.
 *
 * The classification is carried rather than recomputed, for one reason: the
 * retriever and the composer both need to know what the question was *read as*,
 * and if they each re-derived it they could disagree — a chunk admitted by one
 * because the question is about the past and rejected by the other because it
 * is not. One reading, passed on.
 */
data class RetrievalQuery(
    val text: String,
    val classification: IntentClassification,
    /**
     * The asker's service, when the app knows it. Plan 6.4 step 3: "filtered by
     * the user's service type".
     *
     * Null when the user has not said, and that means *no filter*. Filtering an
     * unknown service to central civilian rules would leave a defence pensioner
     * — who did not say so yet — with an empty screen and no explanation, which
     * is the one outcome worse than showing them the wrong corpus.
     */
    val serviceType: ServiceType? = null,
    /** How many chunks come back. Plan 6.4 step 3: hybrid top 12, re-ranked to 6. */
    val topK: Int = DEFAULT_TOP_K,
) {
    /**
     * True when a superseded rule may be quoted, which is plan 6.4's
     * "superseded chunks are down-ranked and labelled 'older rule'" in the only
     * form that is safe: a question about a past period may be answered from
     * the rule that was in force then.
     *
     * [PeriodReference.Unstated] is treated as *not* past, deliberately. The
     * module cannot read a period it was not told, and the plan's direction is
     * "current rules preferred" — so an unstated period gets current rules only.
     * The cost is that "what was the rule in 2016" answered without the words
     * "in 2016" gets today's rule; [PeriodReference] is where a year in the
     * text is picked up.
     */
    val mayQuoteSuperseded: Boolean get() = classification.period == PeriodReference.Past

    companion object {
        /** Plan 6.4 step 3. Six, because that is what the plan re-ranks to. */
        const val DEFAULT_TOP_K: Int = 6
    }
}

/**
 * Why a chunk did not make it.
 *
 * A retriever that silently drops a superseded rule looks identical to one that
 * never had it, and the difference is the whole reason this file is more than
 * `filter { }.sortedBy { }`. Every exclusion is recorded, so a "Sahayak said it
 * could not find the rule" bug has an answer that names the rule.
 */
enum class ExclusionReason {
    /** A [DocStatus.Draft] document. Plan 6.4: nothing goes live unreviewed. */
    Draft,

    /**
     * The chunk is for other service types and the asker's is not one of them.
     */
    ServiceType,

    /**
     * No [SahayakDoc] for this chunk, so it has no status, no title and no
     * issuer — and a citation is built out of exactly those. An uncitable chunk
     * is not quotable, whatever it says.
     */
    NoDocument,

    /**
     * A superseded document, for a question the classifier did not read as
     * about a past period.
     */
    SupersededNotQuotable,

    /**
     * The chunk did not say enough of what was asked.
     *
     * The floor exists because coverage cannot express it: a one-word question
     * whose single word appears in a 400-word chunk has coverage 1.0, and
     * "the word 'pension' appears in the CCS Pension Rules" is not an answer to
     * any question anyone asked.
     */
    TooLittleOverlap,
}

/** A chunk that was considered and set aside, kept for the trace. */
data class ExcludedChunk(
    val chunk: SahayakChunk,
    val reason: ExclusionReason,
    /**
     * The score it would have had. Recorded so that a corpus-wide
     * near-miss is visible: every chunk coming back excluded for
     * [ExclusionReason.TooLittleOverlap] means the query was wrong, not the
     * corpus.
     */
    val wouldHaveScored: Double,
)

/**
 * One chunk, scored, with the scoring left in.
 *
 * Every number here is public because the plan asks for it — 6.4's quality
 * targets (citation accuracy, confident wrong answers under 2%) are measured
 * against answers, and an answer whose score cannot be read is an answer that
 * cannot be diagnosed. [SahayakAnswer.confidence] is a number in the model and
 * this is where it comes from.
 */
data class ScoredChunk(
    val chunk: SahayakChunk,
    val doc: SahayakDoc,
    /** The combined score, 0.0 to 1.0. Ranked on this. */
    val score: Double,
    /**
     * How much of the question this chunk answers, 0.0 to 1.0.
     *
     * A question word the chunk does not contain still counts, at
     * [Retriever.SYNONYM_CREDIT], when a synonym of it is present — so
     * "da" against a chunk reading "dearness relief" is 0.5, not 0. That
     * half-credit is the plan's synonyms table doing its job, and it is a
     * guess: it cannot tell that the chunk is about the *wrong* dearness
     * relief, which is why the minimum-match floor is separate.
     */
    val coverage: Double,
    /** Question words this chunk actually contains. */
    val matchedTokens: List<String>,
    /**
     * True when the chunk shares wording with the subject the question was read
     * as, whether or not the question's own words appear. This is the "is this
     * passage about the right thing" signal, and it is the one that can rescue a
     * one-word question.
     */
    val subjectAligned: Boolean,
    /**
     * The discount applied because the document is superseded. Non-null means
     * the passage is quotable only because the question is about a past period,
     * and the UI must label it "older rule" — plan 6.4, ingestion step 4.
     */
    val supersededDiscount: Double?,
) {
    val status: DocStatus get() = doc.status

    /** Plan 6.4: a superseded chunk shown for a past question is labelled. */
    val isOlderRule: Boolean get() = supersededDiscount != null

    /**
     * The confidence an answer built on this chunk alone would carry.
     *
     * [SahayakAnswer.confidence] is a [Float] and this is where it is decided.
     * It is deliberately not the score: a chunk that is a perfect but tiny
     * overlap for a two-word question should not outrank a chunk that answers
     * the whole question, and taking the best hit's score would say it did.
     */
    val confidence: Float get() = Retriever.confidenceFor(coverage, subjectAligned)

    /** The score a superseded passage was discounted from. */
    val unpenalisedScore: Double get() = score / (supersededDiscount ?: 1.0)
}

/**
 * The result of a search: what can be quoted, and what could not.
 *
 * Both halves are returned. The composer needs only [hits], and the trace is
 * for everything else — a volunteer asking "why did Sahayak not use the 2016
 * rule" is asking about [excluded], and the answer is in there.
 */
data class RetrievalResult(
    val query: RetrievalQuery,
    /** Quotable, best first, at most [RetrievalQuery.topK]. */
    val hits: List<ScoredChunk>,
    val excluded: List<ExcludedChunk>,
) {
    val isEmpty: Boolean get() = hits.isEmpty()

    val best: ScoredChunk? get() = hits.firstOrNull()

    /** The documents behind [hits], de-duplicated and in rank order. */
    val citedDocs: List<SahayakDoc> get() = hits.map { it.doc }.distinctBy { it.id }
}

/**
 * Keyword-overlap retrieval over a chunk list. Plan section 6.4, step 3.
 *
 * **This is not the plan's retriever.** The plan specifies "BM25 plus pgvector
 * embeddings", a synonym table and Hindi transliterations, re-ranking 12
 * candidates down to 6. This scores word overlap with the classifier's synonym
 * table, and everything else about it is a deliberate stand-in:
 *
 *  - the candidate pool is the whole chunk list, because a BM25 index and an
 *    embedding store are not available in this module and no build-file change
 *    is permitted. The *output* honours [RetrievalQuery.topK], so the seam
 *    where an index replaces the pool is one function.
 *  - there is no vector step, so a paraphrase with no shared words will not be
 *    found. This is the honest cost: it is why [IntentClassifier] leans on a
 *    large term table, and why a query matching nothing falls through to a
 *    person rather than to a near neighbour.
 *
 * What *is* implemented to the letter is the part with a safety consequence:
 * the service-type filter, and the rule that a superseded chunk is quotable
 * only for a question about a past period.
 *
 * ### The scoring
 *
 * ```
 * coverage        = (matched question words + SYNONYM_CREDIT × synonym-only words)
 *                   ÷ question content words
 * subjectAligned  = the chunk shares wording with the question's subject
 * score           = COVERAGE_WEIGHT × coverage + SUBJECT_WEIGHT × subjectAligned
 * ```
 *
 * Both halves are in [ScoredChunk] and neither is folded away, because a score
 * of 0.71 is not actionable — whether it came from a long question partly
 * matched, or a short one matched exactly, are different failures.
 */
object Retriever {

    /** Share of the score that comes from how much of the question was covered. */
    const val COVERAGE_WEIGHT: Double = 0.6

    /**
     * Share that comes from being on the right subject.
     *
     * The larger of the two, on purpose. A passage that answers a different
     * question in the right chapter is the dangerous failure, and coverage
     * cannot see it.
     */
    const val SUBJECT_WEIGHT: Double = 0.4

    /**
     * What a question word the chunk does not contain is worth when a synonym
     * of it is.
     *
     * Half, not full, and never more: the synonym table knows that "da" and
     * "dearness relief" mean the same thing, and it does not know whether this
     * passage is the one being asked about.
     */
    const val SYNONYM_CREDIT: Double = 0.5

    /**
     * Question words a chunk must contain to be quotable at all.
     *
     * The floor, not the score. See [ExclusionReason.TooLittleOverlap].
     */
    const val MIN_MATCHED_TOKENS: Int = 2

    /**
     * Search.
     *
     * @param docs every document the corpus holds, any order. A chunk whose
     *   document is missing is excluded rather than cited: an answer with a
     *   citation to a document nobody can open is worse than no answer, because
     *   it looks checked.
     */
    fun search(
        query: RetrievalQuery,
        chunks: List<SahayakChunk>,
        docs: List<SahayakDoc>,
    ): RetrievalResult {
        val byId = docs.associateBy { it.id }

        // The subject's own vocabulary, used to align chunks to the reading of
        // the question. An ambiguous reading widens it, which is the whole
        // point of [IntentClassification.isAmbiguous]: "how much DA arrears do
        // I get" is read as both, and both vocabularies should reach the index.
        val alignment = LinkedHashSet<String>()
        for (candidate in query.classification.candidates.take(MAX_ALIGNMENT_SUBJECTS)) {
            alignment += IntentClassifier.synonymIndexTokens(candidate.intent)
        }

        val contentTokens = contentTokens(query.text)
        val hits = ArrayList<ScoredChunk>()
        val excluded = ArrayList<ExcludedChunk>()

        for (chunk in chunks) {
            val doc = byId[chunk.docId]
            if (doc == null) {
                // No document means no status, no title and no issuer, and a
                // citation is made of those three. Uncitable, so not quotable.
                excluded += ExcludedChunk(chunk, ExclusionReason.NoDocument, 0.0)
                continue
            }

            val passage = searchableTokens(chunk)
            val matched = contentTokens.filter { it in passage }

            // Two sets, not one. A document's title and number say what the
            // *document* is, not what this passage says, so they may lift a
            // chunk's subject alignment without being allowed to manufacture
            // coverage. Counting them as content means every chunk in a
            // two-hundred-page rulebook matches every question about that
            // rulebook — a family-pension question retrieved the gratuity
            // passage purely because both sit under "CCS (Pension) Rules,
            // 2021". A user's question names a subject; the corpus's answer to
            // it has to be in the passage.
            val contextual = passage + docContextTokens(doc)
            val subjectAligned = alignment.any { it in contextual }

            val coverage = if (contentTokens.isEmpty()) {
                0.0
            } else {
                // A question word the chunk lacks is credited at half when some
                // synonym of it is present, so "da" against "dearness relief"
                // is half a hit rather than none.
                (matched.size + SYNONYM_CREDIT * (contentTokens.size - matched.size))
                    .div(contentTokens.size)
                    .coerceIn(0.0, 1.0)
            }

            val score = (
                COVERAGE_WEIGHT * coverage +
                    SUBJECT_WEIGHT * if (subjectAligned) 1.0 else 0.0
                ).coerceIn(0.0, 1.0)

            val exclusion = exclude(
                chunk = chunk,
                doc = doc,
                serviceType = query.serviceType,
                mayQuoteSuperseded = query.mayQuoteSuperseded,
                matched = matched,
                subjectAligned = subjectAligned,
            )

            if (exclusion != null) {
                excluded += ExcludedChunk(chunk, exclusion, score)
            } else {
                // Computed before the score is built rather than applied to it
                // afterwards, because the ranking is on this number and a
                // discount that is recorded but not applied is a comment
                // describing behaviour the code does not have.
                val discount = if (doc.status == DocStatus.Superseded) {
                    SUPERSEDED_DISCOUNT
                } else {
                    null
                }

                hits += ScoredChunk(
                    chunk = chunk,
                    doc = doc,
                    score = score * (discount ?: 1.0),
                    coverage = coverage,
                    matchedTokens = matched,
                    subjectAligned = subjectAligned,
                    // Superseded passages are not excluded, only discounted —
                    // and the discount is carried out to the UI so it can label
                    // them "older rule", which plan 6.4 requires.
                    supersededDiscount = discount,
                )
            }
        }

        // Rank on the discounted score, so a current passage always outranks an
        // equally relevant older one. Ties keep corpus order, which is the
        // ingest order, which is the reviewed order — deliberate, since it means
        // a tie is broken by a human having looked at both.
        val ranked = hits
            .sortedByDescending { it.score }
            .take(query.topK)

        return RetrievalResult(query = query, hits = ranked, excluded = excluded)
    }

    /**
     * The number an answer is graded on.
     *
     * Public because it is the definition of publishability in practice:
     * [in.doosripari.core.model.SahayakAnswer.MIN_CONFIDENCE] is the bar, and
     * this is the only thing compared against it.
     */
    fun confidenceFor(coverage: Double, subjectAligned: Boolean): Float {
        val raw = COVERAGE_WEIGHT * coverage + SUBJECT_WEIGHT * if (subjectAligned) 1.0 else 0.0
        return raw.coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The first exclusion that applies, or null if the chunk may be quoted.
     *
     * Status is checked before service type because a draft must never be
     * considered for anything, not even for a pensioner it happens to fit.
     */
    private fun exclude(
        chunk: SahayakChunk,
        doc: SahayakDoc,
        serviceType: ServiceType?,
        mayQuoteSuperseded: Boolean,
        matched: List<String>,
        subjectAligned: Boolean,
    ): ExclusionReason? = when {
        doc.status == DocStatus.Draft -> ExclusionReason.Draft
        chunk.serviceTypes.isNotEmpty() &&
            serviceType != null &&
            serviceType !in chunk.serviceTypes -> ExclusionReason.ServiceType
        doc.status == DocStatus.Superseded && !mayQuoteSuperseded ->
            ExclusionReason.SupersededNotQuotable
        // One word is enough only with subject alignment, which is the case a
        // synonym table exists to rescue: "DA?" should find the dearness
        // allowance rule even though "da" is not written in it.
        matched.size < MIN_MATCHED_TOKENS && !(matched.size == 1 && subjectAligned) ->
            ExclusionReason.TooLittleOverlap
        else -> null
    }

    /**
     * What the passage itself says: its heading path and its text.
     *
     * The heading path is in because plan 6.4 chunks "one rule or sub-rule per
     * chunk" with a heading path, and a question about rule 44 should find the
     * chunk headed "Rule 44" even if the passage never says the number. The
     * document's own words are in [docContextTokens] instead — see [search].
     */
    private fun searchableTokens(chunk: SahayakChunk): Set<String> =
        SahayakText.indexSet("${chunk.headingPath} ${chunk.text}")

    /**
     * What the document around the passage says: its title and its number.
     *
     * Matched for alignment and never for coverage. A document number is never
     * in anybody's question, so counting it would only ever add a free hit to
     * one chunk in a few thousand; the title is the one that matters, and it
     * matters as a *weak* signal, which is exactly what a 0.4 alignment boost
     * is.
     */
    private fun docContextTokens(doc: SahayakDoc): Set<String> = buildString {
        append(doc.title).append(' ')
        doc.docNo?.let { append(it) }
    }.let(SahayakText::indexSet)

    /**
     * The question's words worth searching on, as
     * [SahayakText.INDEX_PREFIX_CHARS]-character prefixes.
     *
     * Function words are dropped, and that is what makes a one-word question a
     * one-word question: "my DA arrears" is one content word, not three, so it
     * needs subject alignment to be quotable rather than riding in on the word
     * "arrears". The list is deliberately bilingual, because a Hindi question
     * has Hindi function words and dropping only the English ones would leave
     * `mere`, `ka`, `hai` inflating its coverage.
     *
     * The values are prefixes, not words, and that shows in
     * [ScoredChunk.matchedTokens] — which is the price of making "commuted" find
     * "commutation" and the reason a matched token reads `commut`. See
     * [SahayakText.INDEX_PREFIX_CHARS].
     */
    fun contentTokens(text: String): List<String> =
        SahayakText.tokens(text)
            .filterNot { it in STOP_WORDS }
            .map { it.take(SahayakText.INDEX_PREFIX_CHARS) }

    private const val SUPERSEDED_DISCOUNT = 0.6

    /** How many subjects' vocabularies count as "aligned". See [search]. */
    private const val MAX_ALIGNMENT_SUBJECTS = 2

    /**
     * Words that say nothing about what was asked.
     *
     * Includes the first-person pronouns that [IntentClassifier] counts as
     * evidence of a personal case: for classification they are the signal, for
     * retrieval they are noise, and one table cannot be both.
     */
    private val STOP_WORDS: Set<String> = setOf(
        // English
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "been",
        "of", "for", "to", "in", "on", "at", "by", "with", "from", "as", "into",
        "do", "does", "did", "can", "could", "will", "would", "should", "may", "might",
        "i", "my", "me", "mine", "we", "our", "you", "your", "it", "its", "this", "that",
        "these", "those", "there", "here", "am", "have", "has", "had", "not", "no",
        "how", "what", "why", "when", "where", "which", "who", "whom", "please", "tell",
        "much", "many", "more", "most", "some", "any", "all", "get", "got", "give",
        // Hindi, transliterated
        "ka", "ki", "ke", "ko", "se", "hai", "hain", "ho", "hoon", "main", "hum", "aap",
        "mera", "meri", "mere", "maine", "mujhe", "mujhko", "apna", "apni", "apne",
        "kya", "kyun", "kab", "kahan", "kaun", "kaise", "kitna", "kitne", "kya hua",
        "aur", "phir", "bhi", "hi", "to", "tak", "liye", "waala", "wali", "wala", "gaya",
        "gayi", "kiya", "hota", "hoti", "raha", "rahi", "ab", "vaise", "jaldi", "sahi",
    )
}
