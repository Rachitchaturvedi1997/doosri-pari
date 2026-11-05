package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.ActionKind
import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.DocStatus
import `in`.doosripari.core.model.OfflineFaq
import `in`.doosripari.core.model.SahayakAction
import `in`.doosripari.core.model.SahayakAnswer
import `in`.doosripari.core.model.SahayakChunk
import `in`.doosripari.core.model.SahayakDoc
import `in`.doosripari.core.model.SahayakIntent
import `in`.doosripari.core.model.SahayakSource
import `in`.doosripari.core.model.ServiceType
import kotlinx.datetime.Instant

/**
 * A small corpus for the tests, in the shape the real one has.
 *
 * It is deliberately awkward in the four ways the real corpus is awkward:
 *
 *  - one document is [DocStatus.Superseded] and one is [DocStatus.Draft], so the
 *    two statuses that decide what may be quoted are both present and neither is
 *    a special case in the code.
 *  - one chunk is tagged [ServiceType.Defence] and one is tagged for every
 *    service, so "filtered by the user's service type" has something to filter.
 *  - one chunk is *about* Aadhaar, because the guardrail that forbids asking
 *    for an Aadhaar number must not forbid quoting a rule that mentions one.
 *  - one passage points at the calculator instead of stating a figure, which is
 *    how a corpus should handle anything the toolkit computes — and which keeps
 *    the fixture from contradicting `feature-toolkit`.
 */
internal object TestCorpus {

    private fun at(iso: String): Instant = Instant.parse("${iso}T00:00:00Z")

    val pensionRules = SahayakDoc(
        id = 1,
        title = "CCS (Pension) Rules, 2021",
        issuer = "Department of Pension and Pensioners' Welfare",
        docNo = "G.S.R. 320(E)",
        url = "https://example.gov.in/ccs-pension-rules-2021",
        issuedOn = at("2021-06-30"),
        effectiveOn = at("2021-01-01"),
        status = DocStatus.Current,
    )

    val commutationRules = SahayakDoc(
        id = 2,
        title = "CCS (Commutation of Pension) Rules, 2011",
        issuer = "Department of Pension and Pensioners' Welfare",
        docNo = "G.S.R. 519(E)",
        url = "https://example.gov.in/ccs-commutation-rules",
        issuedOn = at("2011-09-01"),
        effectiveOn = at("2011-10-01"),
        status = DocStatus.Current,
    )

    /** Superseded, and still the right answer for a question about 2014. */
    val daOrder2014 = SahayakDoc(
        id = 3,
        title = "Dearness Allowance revision, April 2014",
        issuer = "Ministry of Finance, Department of Expenditure",
        docNo = "No. 1409/2014/CCS.A.III",
        issuedOn = at("2014-04-08"),
        effectiveOn = at("2014-04-01"),
        status = DocStatus.Superseded,
        supersededById = 4,
    )

    val daOrder2018 = SahayakDoc(
        id = 4,
        title = "Dearness Allowance revision, January 2018",
        issuer = "Ministry of Finance, Department of Expenditure",
        docNo = "No. 1672/2017/CCS.A.III",
        issuedOn = at("2017-12-27"),
        effectiveOn = at("2018-01-01"),
        status = DocStatus.Current,
        supersededById = null,
    )

    val lifeCertificateFaqs = SahayakDoc(
        id = 5,
        title = "Jeevan Pramaan — frequently asked questions",
        issuer = "Ministry of Electronics and Information Technology",
        url = "https://example.gov.in/jeevan-pramaan",
        issuedOn = at("2022-03-01"),
        status = DocStatus.Current,
    )

    /** Staged and not reviewed. Plan 6.4: nothing goes live unreviewed. */
    val draftCghs = SahayakDoc(
        id = 6,
        title = "CGHS contribution rates, 2026 (draft)",
        issuer = "Ministry of Health and Family Welfare",
        issuedOn = at("2026-01-01"),
        status = DocStatus.Draft,
    )

    val echsOrder = SahayakDoc(
        id = 7,
        title = "ECHS Order, 2015",
        issuer = "Ministry of Defence",
        docNo = "No. B/49711/AG/ECHS",
        issuedOn = at("2015-03-01"),
        status = DocStatus.Current,
    )

    val docs: List<SahayakDoc> = listOf(
        pensionRules,
        commutationRules,
        daOrder2014,
        daOrder2018,
        lifeCertificateFaqs,
        draftCghs,
        echsOrder,
    )

    val familyPension = SahayakChunk(
        id = 101,
        docId = 1,
        headingPath = "Rule 44(2) — Family pension on death",
        text = "Where a pensioner dies, the family pension payable to the spouse " +
            "shall be fifty per cent of the last pay drawn, and shall not be less " +
            "than the amount of the minimum pension.",
        page = 12,
    )

    val gratuity = SahayakChunk(
        id = 102,
        docId = 1,
        headingPath = "Rule 45 — Gratuity on retirement",
        // Points at the calculation rather than stating a figure, so the corpus
        // and feature-toolkit cannot disagree about a number in two places.
        text = "Gratuity on retirement is payable to an employee who has put in the " +
            "qualifying period of service. The amount is computed under the CCS " +
            "(Retirement Benefits) Rules and settled in the manner directed there.",
        page = 13,
    )

    val commutationCeiling = SahayakChunk(
        id = 103,
        docId = 2,
        headingPath = "Rule 3 — Ceiling on commutation",
        text = "The portion of the pension that may be commuted shall not exceed " +
            "one-third of the basic pension in the case of a pensioner who retired " +
            "before the first day of January, 2000, and shall not exceed one-half " +
            "in the case of any other pensioner, subject to the commutation table.",
        page = 4,
    )

    val daArrears2014 = SahayakChunk(
        id = 104,
        docId = 3,
        headingPath = "Paragraph 3 — Revision with effect from 1 April 2014",
        text = "Dearness allowance shall be revised with effect from the first day " +
            "of April, 2014, and the arrears shall be paid as a difference between " +
            "the rate in force and the rate already drawn.",
        page = 2,
    )

    val daArrears2018 = SahayakChunk(
        id = 105,
        docId = 4,
        headingPath = "Paragraph 2 — Revision with effect from 1 January 2018",
        text = "Dearness allowance shall be revised with effect from the first day " +
            "of January, 2018 at one hundred and fifty per cent of the basic pay, " +
            "and the arrears shall be paid as a difference between the rate in " +
            "force and the rate already drawn.",
        page = 1,
    )

    val lifeCertificate = SahayakChunk(
        id = 106,
        docId = 5,
        headingPath = "FAQ 3 — How do I register for a digital life certificate?",
        // Contains "Aadhaar". The composer must be able to quote this.
        text = "A pensioner may register on the Jeevan Pramaan portal using an Aadhaar " +
            "number and a mobile number linked to the pensioner's bank account. The " +
            "digital life certificate is generated on the spot and is available for " +
            "download from the same portal.",
        page = 3,
    )

    val draftCghsRates = SahayakChunk(
        id = 107,
        docId = 6,
        headingPath = "Draft — Revised contribution rates",
        text = "The contribution rates for CGHS card holders are revised with effect " +
            "from the first day of January 2026.",
    )

    val echsTreatment = SahayakChunk(
        id = 108,
        docId = 7,
        headingPath = "Paragraph 4 — Treatment at empanelled hospitals",
        text = "A serving or retired defence pensioner holding an ECHS card is " +
            "entitled to treatment at empanelled hospitals within India.",
        page = 6,
        serviceTypes = listOf(ServiceType.Defence),
    )

    val chunks: List<SahayakChunk> = listOf(
        familyPension,
        gratuity,
        commutationCeiling,
        daArrears2014,
        daArrears2018,
        lifeCertificate,
        draftCghsRates,
        echsTreatment,
    )

    /**
     * A published answer, for the tests that need one and do not care where the
     * passages came from.
     */
    fun publishedAnswer(
        text: String = "Family pension on death is fifty per cent of the last pay drawn.",
        intent: SahayakIntent = SahayakIntent.FamilyPension,
        confidence: Float = 0.9f,
    ): SahayakAnswer = SahayakAnswer(
        text = text,
        sources = listOf(
            SahayakSource(
                docId = pensionRules.id,
                title = pensionRules.title,
                issuer = pensionRules.issuer,
                docNo = pensionRules.docNo,
                pinpoint = "Rule 44(2)",
            ),
        ),
        intent = intent,
        confidence = confidence,
    )

    /**
     * A refusal, as the composer produces one. Deliberately not a real
     * `SahayakAnswer` with a blank text: the refusal the composer writes is a
     * real answer object with no sources, and a test that fakes one differently
     * would not be testing the thing.
     */
    fun refusalAnswer(intent: SahayakIntent = SahayakIntent.Unknown): SahayakAnswer = SahayakAnswer(
        text = "I could not find this in the rules I have.",
        sources = emptyList(),
        intent = intent,
        confidence = 0f,
    )

    private val commutationFaq = OfflineFaq(
        id = 1,
        question = "How much of my pension can I commute as a lump sum?",
        answer = publishedAnswer(
            text = "A pre-2000 pensioner may commute a third of the basic pension; a " +
                "later pensioner may commute a half. The commutation table then " +
                "converts that into a lump sum.",
            intent = SahayakIntent.Commutation,
        ),
        askCount = 412,
    )

    private val lifeCertificateFaq = OfflineFaq(
        id = 2,
        question = "How do I make a digital life certificate on Jeevan Pramaan?",
        answer = publishedAnswer(
            text = "Register on the Jeevan Pramaan portal with your Aadhaar number " +
                "and a mobile number, and the certificate is generated immediately.",
            intent = SahayakIntent.PensionSlip,
        ),
        language = AppLanguage.Hindi,
        askCount = 388,
    )

    /**
     * A well-meaning volunteer, no sources attached.
     *
     * This entry is the reason [OfflineIndex] filters at construction: it is
     * exactly what the corpus would fill up with, and a pensioner on a train
     * has no way to tell it from a cited answer.
     */
    private val unsourcedFaq = OfflineFaq(
        id = 3,
        question = "Is my DA arrears going to be paid this year?",
        answer = SahayakAnswer(
            text = "Yes, the arrears will be released shortly and the amount will be " +
                "large.",
            sources = emptyList(),
            intent = SahayakIntent.Arrears,
            confidence = 0.95f,
        ),
        askCount = 999,
    )

    private val familyFaq = OfflineFaq(
        id = 4,
        question = "What is the family pension for my wife after I die?",
        answer = publishedAnswer(),
        askCount = 250,
    )

    /** The bundle as it should ship, with the unsourced entry already excluded. */
    fun faqs(): List<OfflineFaq> = listOf(commutationFaq, unsourcedFaq, lifeCertificateFaq, familyFaq)

    fun index(): OfflineIndex = OfflineIndex(faqs())

    /** A query with the classification already read, for tests that skip that step. */
    fun query(
        text: String,
        serviceType: ServiceType? = null,
        topK: Int = RetrievalQuery.DEFAULT_TOP_K,
    ): RetrievalQuery = RetrievalQuery(
        text = text,
        classification = IntentClassifier.classify(text),
        serviceType = serviceType,
        topK = topK,
    )

    fun search(
        text: String,
        serviceType: ServiceType? = null,
        topK: Int = RetrievalQuery.DEFAULT_TOP_K,
    ): RetrievalResult = Retriever.search(query(text, serviceType, topK), chunks, docs)

    fun volunteerAction(): SahayakAction = SahayakAction(
        kind = ActionKind.AskVolunteer,
        target = "volunteer/new",
        label = "Ask a volunteer",
    )
}
