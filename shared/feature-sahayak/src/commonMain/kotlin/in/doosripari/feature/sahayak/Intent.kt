package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.SahayakIntent

/**
 * What kind of question this is, as opposed to what it is about.
 *
 * Plan section 6.4 step 2 gives six kinds and they are not the same axis as
 * core-model's [SahayakIntent]:
 *
 * ```
 * rule_question | calculation | process | grievance | personal_case | out_of_scope
 * ```
 *
 * [SahayakIntent] answers *which corpus* to search — arrears, commutation, tax.
 * This answers *what happens next* — show an answer, open a calculator, open
 * the grievance helper, find a person. Both are needed, they are orthogonal,
 * and collapsing them into one enum would make "how much commutation do I get"
 * impossible to classify: it is a [SahayakIntent.Commutation] question and a
 * [SahayakQuestionKind.Calculation] one at the same time.
 *
 * core-model has no type for this, and adding one there is not this module's
 * call, so it is declared here. See the module report.
 */
enum class SahayakQuestionKind(val code: String) {
    /** "What does rule 44 say?" — answer with the rule and its citation. */
    RuleQuestion("rule_question"),

    /**
     * "How much commutation do I get?" — plan 6.4: opens a calculator,
     * pre-filled. Not an answer from the corpus: the corpus says what the rule
     * *is*, the calculator says what *this pensioner* gets, and only the
     * toolkit module can do the second honestly.
     */
    Calculation("calculation"),

    /** "How do I apply for a life certificate?" — a document and a link. */
    Process("process"),

    /** Plan 6.4: opens the grievance helper. */
    Grievance("grievance"),

    /**
     * "My arrears for 2021 still haven't come." — about this person's own
     * case, which is the one Sahayak must not answer from a rule.
     */
    PersonalCase("personal_case"),

    /**
     * Plan 6.4's out-of-scope. The honest answer to "should I buy gold" is a
     * refusal, and a refusal needs a class as much as an answer needs one.
     */
    OutOfScope("out_of_scope");

    companion object {
        /** Falls back to [OutOfScope] rather than throwing: a word we do not
         *  recognise is out of scope until somebody looks at it. */
        fun fromCode(code: String?): SahayakQuestionKind =
            entries.firstOrNull { it.code == code } ?: OutOfScope
    }
}

/**
 * Whether the question is about a period that has passed.
 *
 * This exists for one decision, and it is the oldest one in the module. Plan
 * 6.4's answer logic step 3 says "current rules preferred", and core-model's
 * [in.doosripari.core.model.DocStatus] says a [in.doosripari.core.model.DocStatus.Superseded]
 * chunk is still the right answer for an older question. So a question about
 * 2019 may be answered from a 2019 rule, and a question about today may not.
 *
 * **The failure direction is the safe one.** [Past] only ever *permits* a
 * superseded chunk to be quoted; it never removes a current one. So a period
 * read wrongly — which is the likeliest error here, since the classifier is
 * rules and not understanding — can produce an old citation, never a missing
 * answer. A rule that could hide the current rule would do the opposite.
 */
enum class PeriodReference(val code: String) {
    /** Nothing in the question points at a past period. The default. */
    Current("current"),

    /**
     * A year, "last year", "when I retired", "under the old rules".
     *
     * A bare four-digit year counts as [Past]. That is the right reading for
     * this corpus: questions carry years because they are about a revision that
     * happened in one ("arrears for 2021"), and the module has no clock with
     * which to decide which year is "this year" without being handed today.
     */
    Past("past"),

    /** The question said nothing about time at all. */
    Unstated("unstated");

    companion object {
        fun fromCode(code: String?): PeriodReference =
            entries.firstOrNull { it.code == code } ?: Unstated
    }
}

/**
 * Shared text handling, so the classifier, the retriever and the offline index
 * all break a sentence the same way.
 *
 * Tokenising once and matching on tokens rather than on substrings is what makes
 * a two-word phrase such as "family pension" mean itself without also matching
 * "my family pension form is wrong" three separate times over. It also gives
 * word boundaries for free, which is why "da" does not fire inside "dadasaheb".
 *
 * Devanagari is kept whole: matras are combining marks in category
 * [java.lang.Character.NON_SPACING_MARK], and a character class that stops at
 * [java.lang.Character.LETTER] would split `मेरा` into `मे` and `रा` and match
 * neither the transliterated term nor the Devanagari one.
 */
object SahayakText {

    /**
     * Lower-cased, punctuation-stripped word list.
     *
     * Nothing here is a lower-casing of Devanagari, which has no case; that is
     * correct rather than a gap, since the terms are matched in romanised form
     * in either case.
     */
    fun tokens(text: String): List<String> =
        SPLIT.split(text.lowercase()).filter { it.isNotEmpty() }

    /** The tokens joined by single spaces, for display. */
    fun normalise(text: String): String = tokens(text).joinToString(" ")

    /**
     * How much of a token is kept for matching.
     *
     * Six characters, and this is the single most consequential constant in the
     * module. It exists because a pensioner writes "commute", "commuted",
     * "commuting" and "commutation" for one idea, and a bag of exact tokens
     * treats those as four words and finds none of them. Six characters is where
     * all four agree ("commut") and where the domain's other families still
     * agree: arrears/arrear, dearness/dearne, gratuity/gratu, retire/retire.
     *
     * It also merges words that have no business merging — "ration" with
     * "rationale", say. That is a real false positive and the honest cost of
     * not having BM25's stemmer, which the plan specifies and this module does
     * not have. The errors it produces are near misses inside the right
     * chapter, and a near miss in the right chapter is what a volunteer checks;
     * a refusal sends somebody to a counter.
     */
    const val INDEX_PREFIX_CHARS: Int = 6

    /** Tokens truncated to [INDEX_PREFIX_CHARS] — the unit of matching. */
    fun indexTokens(text: String): List<String> =
        tokens(text).map { it.take(INDEX_PREFIX_CHARS) }

    /** [indexTokens] as a set, for membership tests against a whole passage. */
    fun indexSet(text: String): Set<String> = indexTokens(text).toSet()

    private val SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")
}

/**
 * A phrase that indicates a subject, and how much it is worth.
 *
 * Weights exist because the plan's synonym table groups words of very unequal
 * usefulness. "commutation" is the word; "chhoot" is a guess at how a Hindi
 * speaker might write it, and a question containing only the guess should lose
 * to one containing the word. The default is 1.0 so most terms need no number.
 */
data class Term(
    val phrase: String,
    val weight: Double = 1.0,
) {
    init {
        require(weight > 0) { "term '$phrase' cannot have weight $weight" }
    }

    /** The phrase broken into tokens. Empty for a phrase of only punctuation. */
    val tokens: List<String> = SahayakText.tokens(phrase)
}

/**
 * A term that fired, and how often.
 *
 * The occurrence count is reported and deliberately does **not** multiply the
 * score. If it did, a pensioner who typed "da da da" three times would outrank
 * a careful question containing three different subject words, and the
 * classifier's job is to be the same answer twice in a row.
 */
data class TermMatch(
    val term: Term,
    val occurrences: Int,
) {
    val phrase: String get() = term.phrase
    val weight: Double get() = term.weight
}

/**
 * One subject's score, with the terms that produced it.
 *
 * Both numbers are kept because they answer different questions. [score] is
 * what the ranking uses — a subject with many strong terms beats one with a
 * single weak term. [coverage] is what the answer's confidence is built from —
 * how much of the subject's vocabulary the question actually used, which is
 * what "am I sure?" means.
 */
data class IntentCandidate(
    val intent: SahayakIntent,
    /** Sum of the weights of the distinct matched terms. Zero means no match. */
    val score: Double,
    /** [score] over the profile's total weight. 0.0 to 1.0. */
    val coverage: Double,
    val matchedTerms: List<TermMatch>,
) {
    val matched: Boolean get() = matchedTerms.isNotEmpty()
}

/**
 * A question, read.
 *
 * Deliberately a data class with no answer in it. Classification is a separate
 * concern from retrieval and from composing, so that a retrieval bug can be
 * reproduced from a string without an index, and so the same classification can
 * be shown to a user — "we read that as a question about your DA arrears" — for
 * confirmation before a wrong reading sends them down the wrong path.
 */
data class IntentClassification(
    /** The subject. [SahayakIntent.Unknown] when nothing matched. */
    val intent: SahayakIntent,
    /** What happens next, per plan 6.4 step 2. */
    val kind: SahayakQuestionKind,
    /** Every subject that scored, best first. */
    val candidates: List<IntentCandidate>,
    /** The winning subject's terms, for display. */
    val matchedTerms: List<TermMatch>,
    /** Terms that decided [kind], for display. Same reason as [matchedTerms]. */
    val kindTerms: List<TermMatch>,
    val period: PeriodReference,
    /**
     * True when a second subject came close.
     *
     * A close second is not an error — "how much DA arrears do I get" is
     * genuinely two subjects — but it is worth knowing, because the tie is
     * broken by weight order rather than by understanding, and the composer
     * widens its search when this is set.
     */
    val isAmbiguous: Boolean,
    /** The runner-up's score, when there is one. */
    val runnerUpScore: Double,
    /**
     * True when the question matched the table of things Sahayak must not
     * answer, as opposed to merely matching no subject at all.
     *
     * The distinction decides which refusal a pensioner gets. "Should I put my
     * pension money into a fund" is refused with "this is not a pension
     * question", which is correct. A pension question this file's vocabulary
     * happens to miss gets the same [SahayakQuestionKind.OutOfScope] but is
     * *not* out of scope, and must be answered with "I could not find this in
     * the rules I have" instead — otherwise a gap in this file is reported to
     * the user as a gap in their question.
     */
    val isExplicitlyOutOfScope: Boolean,
) {
    /** [coverage] of the winner, as the answer's starting confidence. */
    val coverage: Double get() = candidates.firstOrNull()?.coverage ?: 0.0

    /** The one-line reading, shown to the user for confirmation. */
    val summary: String
        get() = when {
            isExplicitlyOutOfScope ->
                "This is not a pension question, so Sahayak will not answer it."
            else -> buildString {
                append("Read as a ")
                append(kind.code.replace('_', ' '))
                append(" about ")
                append(intent.code.replace('_', ' '))
                if (period == PeriodReference.Past) append(" (about a past period)")
                append('.')
            }
        }
}

/**
 * Rule-based intent classification. Plan section 6.4, step 2.
 *
 * **No model, on purpose.** Every decision here is a table lookup and an
 * arithmetic sum, so the answer to "why did Sahayak think that was about
 * gratuity?" is a list of words that were present in the question — which
 * [IntentClassification.matchedTerms] carries to the screen. A classifier that
 * cannot be interrogated cannot be shown to be wrong, and the cost of being
 * wrong here is a pensioner sent to the wrong office with the wrong documents.
 *
 * The tables are also the plan's own synonym table, written out:
 * DA ↔ DR ↔ dearness relief; commutation ↔ lump sum; family pension ↔ widow
 * pension ↔ parivarik pension. Those rows are in the plan because the same
 * question arrives six ways, and a synonym table is the cheapest possible
 * answer to that.
 *
 * **The Indian-language problem is real and is only partly solved here.** The
 * terms include romanised Hindi — `kitna`, `kaise`, `shikayat`, `parivarik`
 * — which is what the plan calls "Hindi transliterations". A user typing in
 * Devanagari gets the romanised terms for free, because most of the words
 * survive transliteration of the *question* only if the classifier also holds
 * the Devanagari spelling, and that table belongs with a native speaker rather
 * than with this file. The consequence is stated in the module report: Devanagari
 * input will classify less well than transliterated input, which is the wrong
 * way round for this app's users, and is why [SahayakIntent.Unknown] routes to
 * a person instead of to a guess.
 */
object IntentClassifier {

    /**
     * The weight gap that counts as ambiguous.
     *
     * Relative to the winner, so it scales with the question: a runner-up
     * within 60% of the leader is a genuine two-subject question, and one at 20%
     * of it is noise.
     *
     * Sixty per cent rather than something tighter, because the two-subject
     * questions in this domain are not balanced. "Dearness relief arrears" gives
     * the DA profile 2.2 and the arrears profile 1.5 — the right reading is both,
     * and a rule that calls that unambiguous would be hiding the tie rather than
     * reporting it. The flag exists so the retriever widens the subject
     * vocabulary it will align against, and under-reporting it produces a
     * narrower search.
     */
    const val AMBIGUITY_RATIO: Double = 0.6

    private val ARREARS = listOf(
        Term("arrears", weight = 1.5),
        Term("arrear"),
        Term("arrears claim"),
        Term("bakaya"),
        Term("bakaya da"),
        Term("pichla da"),
        Term("purana da"),
        Term("baki da"),
        Term("pichle da"),
        Term("da pichle saal"),
        Term("missed revision"),
        Term("revision miss"),
        Term("arrears kitna"),
        Term("kitna hoga arrears"),
    )

    private val DEARNESS_ALLOWANCE = listOf(
        // "da" is the whole point and also the whole risk: it is a Hindi
        // particle meaning "that", so it will fire on ordinary sentences. It is
        // given the lowest weight in the table for that reason — a question
        // that says "da" once and nothing else does not become a DA question.
        Term("da", weight = 0.4),
        Term("dr", weight = 0.4),
        Term("dearness"),
        Term("dearness allowance"),
        Term("dearness relief", weight = 1.2),
        Term("da rate"),
        Term("da badhega"),
        Term("da badhe"),
        Term("dr kitna"),
        Term("da kitna"),
        Term("dearness kitna"),
        Term("da adha"),
    )

    private val COMMUTATION = listOf(
        Term("commutation", weight = 1.5),
        Term("commute"),
        Term("commuted"),
        Term("commutation table", weight = 1.5),
        Term("lump sum", weight = 1.4),
        Term("lump-sum", weight = 1.4),
        Term("one time payment"),
        Term("ekai muly", weight = 1.2),
        Term("ekai jame"),
        // Gratuity and leave encashment are commutation questions to a
        // pensioner — all three are "money instead of a monthly pension" — and
        // core-model's SahayakIntent has one value for them. The toolkit
        // calculators then separate them. Documented here because lumping three
        // retirement benefits into one intent is a decision, not an accident.
        Term("gratuity", weight = 1.3),
        Term("leave encashment", weight = 1.3),
        Term("encashment"),
        Term("chhoot", weight = 1.2),
        Term("chhuti", weight = 0.9),
    )

    private val PENSION_SLIP = listOf(
        Term("pension slip", weight = 1.5),
        Term("slip", weight = 1.0),
        Term("payslip", weight = 1.3),
        Term("pay slip", weight = 1.3),
        Term("salary slip", weight = 1.2),
        Term("e-dar", weight = 1.4),
        Term("edar", weight = 1.2),
        Term("e dar", weight = 1.2),
        Term("slip me kaya"),
        Term("salary certificate"),
        // The life certificate has no SahayakIntent of its own and is not a
        // slip, but the plan's corpus table lists Jeevan Pramaan as a phase-1
        // source and it is one of the two most-asked things a pensioner does
        // with this app. It lives under the slip subject because both are
        // "prove you are still alive to the office", and leaving it out of the
        // table meant "how do I renew my life certificate" classified as
        // nothing at all.
        Term("life certificate", weight = 1.5),
        Term("digital life certificate", weight = 1.5),
        Term("jeevan pramaan", weight = 1.5),
        Term("jivan pramaan", weight = 1.5),
        Term("life certificate kaise", weight = 1.5),
        Term("alive hona", weight = 1.0),
    )

    private val HEALTH_COVER = listOf(
        Term("cghs", weight = 1.5),
        Term("echs", weight = 1.5),
        Term("health card", weight = 1.4),
        Term("health cover", weight = 1.4),
        Term("mediclaim"),
        Term("medical card"),
        Term("hospital", weight = 1.0),
        Term("wellness centre", weight = 1.3),
        Term("wellness center", weight = 1.3),
        Term("ayushman", weight = 1.4),
        Term("ab ayushman", weight = 1.4),
        Term("sparsh", weight = 1.3),
        Term("reimbursement", weight = 1.2),
        Term("dispensary", weight = 1.2),
        Term("health claim"),
        Term("medical claim"),
    )

    private val FAMILY_PENSION = listOf(
        Term("family pension", weight = 1.5),
        Term("widow pension", weight = 1.4),
        Term("widow", weight = 1.1),
        Term("parivarik pension", weight = 1.4),
        Term("parivarik", weight = 1.1),
        Term("family member", weight = 1.1),
        Term("spouse"),
        Term("wife"),
        Term("husband"),
        Term("after my death", weight = 1.3),
        Term("meri maut ke baad", weight = 1.3),
        Term("nominee", weight = 1.2),
        Term("nomination", weight = 1.2),
        Term("dependent pension"),
    )

    private val TAX = listOf(
        Term("income tax", weight = 1.5),
        Term("tax", weight = 0.8),
        Term("80u", weight = 1.5),
        Term("80 u", weight = 1.5),
        Term("section 80u", weight = 1.5),
        Term("itr"),
        Term("tax return", weight = 1.3),
        Term("file return", weight = 1.2),
        Term("tax certificate", weight = 1.3),
        Term("tax nikalna"),
        Term("income tax act", weight = 1.3),
    )

    private val NEW_PENSION_SCHEME = listOf(
        Term("nps", weight = 1.5),
        Term("ups", weight = 1.5),
        Term("unified pension", weight = 1.5),
        Term("new pension scheme", weight = 1.5),
        Term("nps scheme", weight = 1.5),
        Term("aps", weight = 1.2),
        Term("old pension scheme", weight = 1.4),
        Term("nps account", weight = 1.4),
        Term("nps vatsa", weight = 1.4),
        Term("pension scheme", weight = 0.9),
        Term("new rules", weight = 0.8),
    )

    private val ELIGIBILITY = listOf(
        Term("eligibility", weight = 1.4),
        Term("eligible", weight = 1.3),
        Term("qualifying service", weight = 1.5),
        Term("qualifying", weight = 1.2),
        Term("who is eligible", weight = 1.5),
        Term("kaun eligible", weight = 1.5),
        Term("not eligible", weight = 1.4),
        Term("retirement age", weight = 1.3),
        Term("superannuation", weight = 1.2),
        Term("kitne saal", weight = 1.0),
        Term("how many years", weight = 1.0),
    )

    /**
     * The subject tables, in the order they are searched.
     *
     * Order is not precedence — [classify] ranks by score — but it decides which
     * subject keeps a tie, and a tie is exactly what "my DA arrears have not
     * come" produces. Arrears before dearness allowance, because a question
     * naming both is about the arrears.
     */
    private val SUBJECTS: List<Pair<SahayakIntent, List<Term>>> = listOf(
        SahayakIntent.Arrears to ARREARS,
        SahayakIntent.DearnessAllowance to DEARNESS_ALLOWANCE,
        SahayakIntent.Commutation to COMMUTATION,
        SahayakIntent.FamilyPension to FAMILY_PENSION,
        SahayakIntent.HealthCover to HEALTH_COVER,
        SahayakIntent.Tax to TAX,
        SahayakIntent.PensionSlip to PENSION_SLIP,
        SahayakIntent.NewPensionScheme to NEW_PENSION_SCHEME,
        SahayakIntent.Eligibility to ELIGIBILITY,
    )

    // The plan's six kinds. See [SahayakQuestionKind] for why they are not
    // [SahayakIntent].

    private val GRIEVANCE = listOf(
        Term("grievance", weight = 1.5),
        Term("complaint", weight = 1.4),
        Term("complain", weight = 1.2),
        Term("shikayat", weight = 1.5),
        Term("shikayata", weight = 1.4),
        Term("nhi mila", weight = 1.3),
        Term("nahi mila", weight = 1.3),
        Term("nahi mil raha", weight = 1.4),
        Term("not received", weight = 1.4),
        Term("still pending", weight = 1.3),
        Term("pending since", weight = 1.3),
        Term("no response", weight = 1.2),
        Term("dalil", weight = 1.2),
        Term("rejected my", weight = 1.3),
        Term("refused my", weight = 1.3),
    )

    /**
     * First-person markers, on their own.
     *
     * Not a term table with weights, because these are a *condition* rather
     * than evidence: see [PERSONAL_DATUM] for why one is not enough.
     */
    private val FIRST_PERSON: Set<String> = setOf(
        "i", "my", "me", "mine", "myself",
        "mera", "meri", "mere", "maine", "mujhe", "mujhko",
        "apna", "apni", "apne", "main", "hum", "hamara",
    )

    /**
     * A reference to this pensioner's own record, event or file.
     *
     * A possessive pronoun on its own does **not** make a question a personal
     * case, and the distinction is the difference between opening a calculator
     * and handing somebody off to a volunteer. "How much can I commute from my
     * pension" is first-person and is a question about the ceiling, which the
     * toolkit answers exactly; routing it to a person would be the module
     * refusing to do work it can do. What makes a personal case is naming the
     * record — my slip, my claim, the year I retired.
     *
     * The spouse is deliberately **absent**, which took one test to see. "What
     * is the family pension for my wife after I die?" is the single most
     * canonical family-pension question there is, and with `my wife` listed
     * here it was refused to a volunteer — over a word that names the
     * beneficiary, not the claimant. The spouse is the *subject* of the
     * family-pension rule and [FAMILY_PENSION] already holds it; a question
     * naming nobody else still reaches [SahayakQuestionKind.PersonalCase]
     * through the no-subject branch in [decideKind], which is where "my
     * wife?" on its own lands.
     */
    private val PERSONAL_DATUM = listOf(
        Term("my slip", weight = 1.4),
        Term("my case", weight = 1.4),
        Term("my claim", weight = 1.4),
        Term("my file", weight = 1.3),
        Term("my record", weight = 1.3),
        Term("my last", weight = 1.2),
        Term("my arrears", weight = 1.3),
        Term("my pensioner number", weight = 1.4),
        Term("i retired", weight = 1.4),
        Term("i joined", weight = 1.3),
        Term("i died", weight = 1.4),
        Term("mere slip", weight = 1.4),
        Term("apna case", weight = 1.3),
    )

    /**
     * The subjects plan 6.4 forbids: investments, and anything else that is
     * advice rather than a rule.
     *
     * Explicit because the alternative is accidental. Without this table, a
     * question the vocabulary happens not to cover is classified the same way as
     * a question about gold futures — and the refusal it gets says "this is not
     * a pension question", which is a lie told to a pensioner who asked a real
     * pension question. So the two cases are separated: an explicit refusal says
     * out of scope, and an unrecognised question falls through to "I could not
     * find this in the rules I have", which is true.
     */
    private val OUT_OF_SCOPE = listOf(
        Term("invest", weight = 1.3),
        Term("investment", weight = 1.3),
        Term("gold", weight = 1.0),
        Term("share market", weight = 1.5),
        Term("stock market", weight = 1.5),
        Term("mutual fund", weight = 1.5),
        Term("crypto", weight = 1.4),
        Term("bitcoin", weight = 1.4),
        Term("lottery", weight = 1.2),
        Term("loan", weight = 1.0),
        Term("emi", weight = 1.0),
        Term("insurance", weight = 1.0),
        Term("should i buy", weight = 1.5),
        Term("kya kharid", weight = 1.4),
        Term("lagao", weight = 1.1),
    )

    private val CALCULATION = listOf(
        Term("kitna", weight = 1.2),
        Term("kitne", weight = 1.2),
        Term("kitna banega", weight = 1.5),
        Term("kitna hoga", weight = 1.5),
        Term("kitna milega", weight = 1.5),
        Term("kitna bhega"),
        Term("kitna aayega"),
        Term("calculate", weight = 1.4),
        Term("calculator", weight = 1.4),
        Term("maan lein", weight = 1.2),
        Term("maan lijiye", weight = 1.2),
        Term("how much", weight = 1.2),
        Term("how many", weight = 0.9),
        Term("estimate", weight = 1.2),
        Term("anumaan", weight = 1.2),
        Term("hisab", weight = 1.1),
        Term("ganana"),
    )

    private val PROCESS = listOf(
        Term("how do i", weight = 1.4),
        Term("kaise", weight = 1.3),
        Term("kaise kare", weight = 1.4),
        Term("kaise karu", weight = 1.4),
        Term("process", weight = 1.2),
        Term("step", weight = 1.0),
        Term("steps", weight = 1.0),
        Term("procedure", weight = 1.2),
        Term("apply", weight = 1.2),
        Term("application", weight = 1.2),
        Term("form", weight = 1.0),
        Term("where", weight = 1.1),
        Term("kahan", weight = 1.2),
        Term("which office", weight = 1.4),
        Term("kaun office", weight = 1.4),
        Term("documents", weight = 1.1),
        Term("document needed", weight = 1.3),
        Term("how to", weight = 1.2),
    )

    private val PAST_MARKERS = listOf(
        Term("last year", weight = 1.5),
        Term("pichle saal", weight = 1.5),
        // The same phrase in Devanagari. [SahayakText] keeps matras attached,
        // so this matches `पिछले साल` typed directly rather than only after
        // transliteration.
        Term("पिछले साल", weight = 1.5),
        Term("back then", weight = 1.4),
        Term("us samay", weight = 1.4),
        Term("uske time", weight = 1.4),
        Term("tab", weight = 0.8),
        Term("when i retired", weight = 1.5),
        Term("jab maine", weight = 1.3),
        Term("old rule", weight = 1.5),
        Term("older rule", weight = 1.5),
        Term("purana rule", weight = 1.5),
        Term("in 2019", weight = 1.2),
        Term("pehle", weight = 0.8),
    )

    /**
     * A bare four-digit year, caught separately from the phrases above.
     *
     * See [PeriodReference.Past] for why a year in a question is a past period
     * in all but the rarest case.
     */
    private val YEAR = Regex("^(19|20)\\d{2}$")

    private val CURRENT_MARKERS = listOf(
        Term("now", weight = 1.2),
        Term("abhi", weight = 1.2),
        Term("aaj", weight = 1.2),
        Term("current", weight = 1.1),
        Term("latest", weight = 1.2),
        Term("naya", weight = 0.9),
        Term("present", weight = 0.9),
    )

    /**
     * Read a question.
     *
     * @param text the question as typed or transcribed. Never blank-checked:
     *   a blank question classifies as [SahayakIntent.Unknown] and
     *   [SahayakQuestionKind.OutOfScope], which the composer turns into an
     *   offer to talk to a person — the right answer to an empty box, and
     *   better than a `require` that crashes the ask screen.
     */
    fun classify(text: String): IntentClassification {
        val tokens = SahayakText.tokens(text)

        val candidates = SUBJECTS
            .map { (intent, terms) -> score(intent, terms, tokens) }
            .filter { it.matched }
            .sortedByDescending { it.score }

        val top = candidates.firstOrNull()

        val kindMatch = decideKind(tokens, top != null)
        val period = decidePeriod(tokens)

        return IntentClassification(
            intent = top?.intent ?: SahayakIntent.Unknown,
            kind = kindMatch.first,
            candidates = candidates,
            matchedTerms = top?.matchedTerms ?: emptyList(),
            kindTerms = kindMatch.second,
            period = period,
            isAmbiguous = top != null && (candidates.getOrNull(1)?.score ?: 0.0) >=
                top.score * AMBIGUITY_RATIO,
            runnerUpScore = candidates.getOrNull(1)?.score ?: 0.0,
            isExplicitlyOutOfScope = kindMatch.first == SahayakQuestionKind.OutOfScope &&
                kindMatch.second.isNotEmpty(),
        )
    }

    /**
     * Score one subject's table against the question's tokens.
     *
     * A sliding window of the term's own length, so a two-word term matches
     * only where its words are adjacent. Not exposed: it is a detail of
     * [score], and a caller wanting a subject's score should get the terms too,
     * which is what this returns.
     */
    private fun score(
        intent: SahayakIntent,
        terms: List<Term>,
        tokens: List<String>,
    ): IntentCandidate {
        val matches = terms.mapNotNull { term -> count(term, tokens)?.let { TermMatch(term, it) } }
        val raw = matches.sumOf { it.weight }
        val total = terms.sumOf { it.weight }

        return IntentCandidate(
            intent = intent,
            score = raw,
            coverage = if (total > 0) (raw / total).coerceIn(0.0, 1.0) else 0.0,
            matchedTerms = matches,
        )
    }

    /**
     * How many times a phrase appears, or null when it does not appear at all.
     *
     * Null rather than zero so a term that did not fire is absent from
     * [IntentCandidate.matchedTerms] — the list is what the user is shown, and
     * a list of every term in the table with zeros in it explains nothing.
     */
    private fun count(term: Term, tokens: List<String>): Int? {
        if (term.tokens.isEmpty() || term.tokens.size > tokens.size) return null

        var found = 0
        for (start in 0..(tokens.size - term.tokens.size)) {
            var hit = true
            for (offset in term.tokens.indices) {
                if (tokens[start + offset] != term.tokens[offset]) {
                    hit = false
                    break
                }
            }
            if (hit) found++
        }
        return if (found > 0) found else null
    }

    /**
     * The kind, and the terms that decided it.
     *
     * Precedence is fixed and deliberate, and it is *not* the order the kinds
     * appear in the plan:
     *
     * ```
     * out_of_scope   the subject table forbids it
     *   ↓
     * grievance      a problem with what already happened
     *   ↓
     * calculation    a number only the toolkit can produce
     *   ↓
     * personal_case  about this person's own record
     *   ↓
     * process        how to do something
     *   ↓
     * rule_question  what the rule says
     * ```
     *
     * Three of those orderings are argued rather than listed:
     *
     * Out of scope is first and is a *veto*. Plan 6.4 step 5 forbids investment
     * advice, and a veto is the only shape in which "forbids" is true: a
     * question can name a subject, a personal record and a calculation all at
     * once and still be refused, because "should I put my arrears into a fund"
     * does.
     *
     * A calculation outranks a personal case, which inverts the obvious order
     * and is the single most consequential decision in this function. "How much
     * can I commute from my pension" is first-person, names no record, and wants
     * a figure the toolkit computes exactly; refusing it to a volunteer would
     * make the app worse at the one thing it is good at. The calculator is
     * pre-filled from the user's own record anyway, so the personal part is
     * handled — by the toolkit, not by a person.
     *
     * A grievance outranks a calculation, because "how much are my arrears, they
     * still have not come" is not a request for arithmetic, it is a request for
     * the money.
     *
     * Rule question is last because it is the fallback: a question that names a
     * subject and nothing else is a question about the rule.
     */
    private fun decideKind(
        tokens: List<String>,
        hasSubject: Boolean,
    ): Pair<SahayakQuestionKind, List<TermMatch>> {
        val outOfScope = bestOf(OUT_OF_SCOPE, tokens)
        if (outOfScope.score > 0) return SahayakQuestionKind.OutOfScope to outOfScope.matches

        val grievance = bestOf(GRIEVANCE, tokens)
        val calculation = bestOf(CALCULATION, tokens)
        val datum = bestOf(PERSONAL_DATUM, tokens)
        val process = bestOf(PROCESS, tokens)
        val firstPerson = tokens.any { it in FIRST_PERSON }

        if (!hasSubject && datum.score == 0.0 && firstPerson) {
            // No subject, but a reference to the user's own record: "my claim
            // was rejected" names no subject the tables know and is still a
            // personal case.
            return SahayakQuestionKind.PersonalCase to datum.matches
        }

        val kind = when {
            grievance.score > 0 -> SahayakQuestionKind.Grievance
            calculation.score > 0 -> SahayakQuestionKind.Calculation
            firstPerson && datum.score > 0 -> SahayakQuestionKind.PersonalCase
            process.score > 0 -> SahayakQuestionKind.Process
            hasSubject -> SahayakQuestionKind.RuleQuestion
            else -> SahayakQuestionKind.OutOfScope
        }

        val terms = when (kind) {
            SahayakQuestionKind.Grievance -> grievance.matches
            SahayakQuestionKind.Calculation -> calculation.matches
            SahayakQuestionKind.PersonalCase -> datum.matches
            SahayakQuestionKind.Process -> process.matches
            SahayakQuestionKind.OutOfScope -> outOfScope.matches
            SahayakQuestionKind.RuleQuestion -> emptyList()
        }
        return kind to terms
    }

    private data class ScoredTerms(
        val score: Double,
        val matches: List<TermMatch>,
    )

    private fun bestOf(terms: List<Term>, tokens: List<String>): ScoredTerms {
        val matches = terms.mapNotNull { term -> count(term, tokens)?.let { TermMatch(term, it) } }
        return ScoredTerms(matches.sumOf { it.weight }, matches)
    }

    /**
     * Whether the question is about a period that has passed.
     *
     * A current marker beats a past one: "what is the DA rate now, and what was
     * it in 2016" is a current question with a historical part, and the safe
     * reading is the one that keeps superseded rules quotable. The cost of being
     * wrong that way is an older citation the user can see and discount; the
     * cost the other way is no answer at all.
     */
    private fun decidePeriod(tokens: List<String>): PeriodReference {
        if (tokens.isEmpty()) return PeriodReference.Unstated

        val past = bestOf(PAST_MARKERS, tokens)
        val current = bestOf(CURRENT_MARKERS, tokens)
        if (past.score > 0 && current.score > 0) {
            return if (current.score >= past.score) PeriodReference.Current else PeriodReference.Past
        }
        if (current.score > 0) return PeriodReference.Current
        if (past.score > 0) return PeriodReference.Past

        return if (tokens.any { YEAR.matches(it) }) PeriodReference.Past else PeriodReference.Unstated
    }

    /**
     * A subject's whole vocabulary in index form — the plan's synonym list,
     * flattened and truncated to [SahayakText.INDEX_PREFIX_CHARS].
     *
     * Exposed for [Retriever], which needs the *other* direction of the same
     * table: the classifier expands a question ("da" → dearness allowance) and
     * the retriever expands a *document* ("dearness relief" → answerable by
     * "da"). Two directions of one table, and keeping them in one object is
     * what stops them drifting apart.
     *
     * Truncated on the way out rather than by the caller, because every caller
     * matches this against a truncated passage and a caller that forgets would
     * silently get zero subject alignment on every chunk — the kind of bug that
     * looks like a corpus problem.
     *
     * The table for a subject this file does not know is empty rather than
     * throwing, because [SahayakIntent.Unknown] and [SahayakIntent.fromCode] can
     * both produce values with no profile.
     */
    fun synonymIndexTokens(intent: SahayakIntent): Set<String> =
        SUBJECTS.firstOrNull { it.first == intent }
            ?.second
            .orEmpty()
            .flatMapTo(LinkedHashSet()) { term -> term.tokens.map { it.take(SahayakText.INDEX_PREFIX_CHARS) } }
}
