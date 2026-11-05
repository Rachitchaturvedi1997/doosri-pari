package `in`.doosripari.core.model

import kotlinx.datetime.Instant

/**
 * Pension Sahayak — a pensioner asks a question in their own words and gets an
 * answer with the rule it came from printed underneath. Plan section 6.4.
 *
 * Three rules govern everything in this file, and they are the reason the
 * feature exists at all:
 *
 *  1. **An uncited answer is not shown.** [SahayakAnswer.sources] is not a
 *     decoration; an answer with no source is a rumour with a font.
 *  2. **The disclaimer is data, not a view string.** [SahayakAnswer.disclaimer]
 *     travels with the answer, so it cannot be dropped by a redesign, and so a
 *     cached answer still carries it.
 *  3. **The question is hashed, never stored.** [SahayakQuestion.userHash] is a
 *     one-way digest. Plan section 14 does not want a log of what a 68-year-old
 *     asked about their own arrears.
 */

/** What the user was trying to find out. Drives which corpus is searched. */
enum class SahayakIntent(val code: String) {
    /** "Mera DA arrears kitna banega?" — the most common question by a mile. */
    Arrears("arrears"),
    /** Dearness allowance or revision. */
    DearnessAllowance("dearness_allowance"),
    /** Commutation, gratuity, leave encashment. */
    Commutation("commutation"),
    /** Life certificate, pensioners' portal, e-dar, slip. */
    PensionSlip("pension_slip"),
    /** CGHS, ECHS, SPARSH, Ayushman. */
    HealthCover("health_cover"),
    /** Family pension, nomination, family details. */
    FamilyPension("family_pension"),
    /** Income tax on pension, Section 80U. */
    Tax("tax"),
    /** NPS, UPS, new pension rules. */
    NewPensionScheme("nps"),
    /** Which rules apply, to whom. */
    Eligibility("eligibility"),
    /** Something we genuinely do not have. Answered with a person, not a guess. */
    Unknown("unknown");

    companion object {
        fun fromCode(code: String?): SahayakIntent =
            entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/**
 * One official document, chunked for retrieval.
 *
 * [status] is the whole safety mechanism. A chunk is only ever quoted to a user
 * when its document is `Current`; a `Superseded` one is kept because the rules
 * of 2019 still explain an arrears figure from 2019, and `Draft` exists so an
 * admin can stage an update without it leaking.
 */
data class SahayakDoc(
    val id: Int,
    val title: String,
    val issuer: String,
    val docNo: String? = null,
    val url: String? = null,
    val issuedOn: Instant? = null,
    val effectiveOn: Instant? = null,
    val status: DocStatus = DocStatus.Draft,
    /** The doc that replaced this one, so an old answer can be traced forward. */
    val supersededById: Int? = null,
)

enum class DocStatus(val code: String) {
    /** Being staged. Never shown. */
    Draft("draft"),

    /** In force. The only status a user is ever shown. */
    Current("current"),

    /** Replaced, but still the right answer for an older question. */
    Superseded("superseded");

    companion object {
        fun fromCode(code: String?): DocStatus =
            entries.firstOrNull { it.code == code } ?: Draft
    }
}

/** One retrievable passage. A "chunk" of a document, 200–600 tokens. */
data class SahayakChunk(
    val id: Int,
    val docId: Int,
    /** "Chapter IV > Rule 12" — shown so a user can find the page themselves. */
    val headingPath: String,
    val text: String,
    val page: Int? = null,
    /** Which services this passage applies to. Empty means all of them. */
    val serviceTypes: List<ServiceType> = emptyList(),
)

/** The citation printed under an answer. A user can open the source. */
data class SahayakSource(
    val docId: Int,
    val title: String,
    val issuer: String,
    val docNo: String? = null,
    val url: String? = null,
    /** Rule number or section heading within the document. */
    val pinpoint: String? = null,
    val page: Int? = null,
    /** The exact quoted passage, so the claim can be checked against it. */
    val quote: String? = null,
)

/**
 * A question as asked, and the answer as given.
 *
 * [confidence] is not decoration either. The `sahayak_index` job runs an
 * evaluation after every ingest and publishes only if the scores hold, so a
 * low-confidence answer should never reach [isPublishable] in the first place.
 * The field is kept so the client's own rendering can refuse to show one.
 */
data class SahayakAnswer(
    val text: String,
    val sources: List<SahayakSource>,
    val intent: SahayakIntent,
    val confidence: Float = 0f,
    /** Plan section 14: required on every answer, carried as data. */
    val disclaimer: String = DEFAULT_DISCLAIMER,
    /** The one thing to do next, if there is one. At most one, per plan 4.5. */
    val primaryAction: SahayakAction? = null,
    /**
     * True only when the answer is good enough to publish. The client checks
     * this rather than checking [confidence] itself, so the rule lives in one
     * place and the bar can be raised without shipping an app build.
     */
    val isPublishable: Boolean = sources.isNotEmpty() && confidence >= MIN_CONFIDENCE,
) {
    init {
        require(text.isNotBlank()) { "an answer with no text is not an answer" }
    }

    companion object {
        const val MIN_CONFIDENCE = 0.55f

        /**
         * Plan section 14, word for word. Every Sahayak answer ends with this,
         * including a cached one and including a one-line one.
         */
        const val DEFAULT_DISCLAIMER =
            "General information, not advice. For a decision about your own " +
                "pension, please confirm with DoPPW or your Accounts Office."
    }
}

/** The single next step an answer can offer. Plan section 4.5 shows one. */
data class SahayakAction(
    val kind: ActionKind,
    /** Deep link target — a toolkit calculator, a document, a person. */
    val target: String,
    val label: String,
)

enum class ActionKind(val code: String) {
    OpenCalculator("open_calculator"),
    OpenDocument("open_document"),
    FindOffice("find_office"),
    AskVolunteer("ask_volunteer"),
    CheckStatus("check_status"),
}

/** A question, and what the user did about it. Plan section 10. */
data class SahayakQuestion(
    val id: Int,
    /**
     * One-way digest of the user id, never the id itself. Plan section 14:
     * the question log exists to improve answers, not to build a file on
     * somebody's financial worries.
     */
    val userHash: String? = null,
    val language: AppLanguage = AppLanguage.English,
    val question: String,
    val intent: SahayakIntent = SahayakIntent.Unknown,
    val answeredAt: Instant? = null,
    val answer: SahayakAnswer? = null,
)

/** "Was this helpful?" — and if not, what to do instead. Plan section 6.4. */
enum class SahayakFeedback(val code: String) {
    Helpful("helpful"),
    NotHelpful("not_helpful"),
    /** Needs a person. Creates a volunteer ticket. */
    AskVolunteer("ask_volunteer");

    companion object {
        fun fromCode(code: String?): SahayakFeedback? =
            entries.firstOrNull { it.code == code }
    }
}

/** A question a volunteer picked up. Plan section 10, `volunteer_tickets`. */
data class VolunteerTicket(
    val id: Int,
    val questionId: Int,
    val assignedTo: Int? = null,
    val answer: String? = null,
    val status: TicketStatus = TicketStatus.Open,
    /** Set when a good answer is promoted into the offline FAQ. */
    val promotedFaqId: Int? = null,
    val createdAt: Instant? = null,
) {
    val isAwaitingUser: Boolean get() = status == TicketStatus.Answered
}

enum class TicketStatus(val code: String) {
    Open("open"),
    Assigned("assigned"),
    Answered("answered"),
    Closed("closed");

    companion object {
        fun fromCode(code: String?): TicketStatus =
            entries.firstOrNull { it.code == code } ?: Open
    }
}

/**
 * The offline fallback: the top questions and their answers, bundled with the
 * app so Sahayak is not an empty box on a train.
 *
 * A ticket only promotes here once a volunteer has answered AND the answer
 * carries a source. Without that rule the offline corpus fills with whatever
 * a well-meaning volunteer believed.
 */
data class OfflineFaq(
    val id: Int,
    val question: String,
    val answer: SahayakAnswer,
    val language: AppLanguage = AppLanguage.English,
    /** How often it has been asked; decides the bundle's contents. */
    val askCount: Int = 0,
)
