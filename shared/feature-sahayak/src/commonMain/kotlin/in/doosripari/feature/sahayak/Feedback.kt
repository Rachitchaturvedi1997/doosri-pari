package `in`.doosripari.feature.sahayak

import `in`.doosripari.core.model.AppLanguage
import `in`.doosripari.core.model.SahayakAnswer
import `in`.doosripari.core.model.SahayakFeedback
import `in`.doosripari.core.model.SahayakQuestion
import `in`.doosripari.core.model.TicketStatus
import `in`.doosripari.core.model.VolunteerTicket
import kotlinx.datetime.Instant

/**
 * What happened when a pensioner said whether the answer helped, and what it
 * should cause. Plan section 6.4's third button; plan section 10's
 * `volunteer_tickets`.
 *
 * The three buttons on the plan's card are **Yes / No / Ask a volunteer**, and
 * the third is not a flavour of the second — it is the only one that can be
 * pressed by somebody who has just been told the truth, which is that Sahayak
 * could not answer them. Somebody who taps "No" on a refusal has not complained;
 * they have restated the problem. Somebody who taps "Ask a volunteer" on one
 * has said it clearly, and that is the case worth routing.
 *
 * ### Two rules that are not obvious
 *
 * **"Helpful" on a refusal still raises a ticket.** The button asks whether the
 * answer was helpful, and on a refusal the thing that was helpful was the
 * message saying nothing was found. The question is still unanswered, and the
 * corpus still needs a reviewed answer for it. Letting "helpful" close a
 * refusal silently would let a whole category of unanswered question look
 * resolved — and this app's quality targets are measured on exactly that.
 *
 * **A second tap does not open a second ticket.** The same pensioner tapping
 * twice, or a retry on a flaky connection, must not put two tickets in a
 * volunteer queue that is worked by retired officers answering within 48 hours.
 * [record] returns the existing ticket instead.
 *
 * Both are consequences of the same thing: the volunteer queue is a scarce
 * resource staffed by people who are themselves pensioners, and a queue padded
 * with duplicates is a queue that takes longer.
 */
object FeedbackRecorder {

    /**
     * Why a ticket was or was not raised.
     *
     * Constants rather than prose, so a test can assert the *reason* and a
     * triage list can group by it, and so the [FeedbackRecord.reason] string
     * shown to a volunteer is not the thing under test.
     */
    object Reason {
        /** The user asked for a person. Always honoured. */
        const val ASKED_FOR_A_PERSON: String = "asked_for_a_person"

        /** "No" on an answer that was published. */
        const val ANSWER_WRONG: String = "answer_wrong"

        /** "Yes" on a refusal — the question is still unanswered. */
        const val REFUSAL_HELPFUL: String = "refusal_marked_helpful"

        /** A ticket is already open for this question. */
        const val ALREADY_OPEN: String = "already_open"

        /** Published and marked helpful. Nothing to do. */
        const val NO_ACTION: String = "no_action"
    }

    /**
     * Record the tap and decide what it causes.
     *
     * @param question the logged question. Built by [buildQuestion] so the
     *   caller never has to assemble a [SahayakQuestion] with a raw user id in
     *   it.
     * @param at when it happened. An argument, not a clock read, for the reason
     *   the rest of this project takes no clock: the tests pin it.
     * @param nextTicketId the id the server will give the ticket. An argument
     *   because ids come from `volunteer_tickets` (plan 10) and a placeholder
     *   guessed here would collide with a real one.
     * @param openTickets what is already queued. Needed only to suppress
     *   duplicates; an empty list means "assume nothing is open", which is
     *   right the first time a question is answered and wrong every time after
     *   — so callers should pass the real queue.
     */
    fun record(
        question: SahayakQuestion,
        feedback: SahayakFeedback,
        at: Instant,
        nextTicketId: Int? = null,
        openTickets: List<VolunteerTicket> = emptyList(),
    ): FeedbackRecord {
        val existing = openTickets.firstOrNull {
            it.questionId == question.id && it.status != TicketStatus.Closed
        }

        // Duplicates are checked first, so a double tap is reported as one
        // whatever the user pressed. The reason changes, though: a second "No"
        // on an already-open ticket is a *different* signal from a second "Ask a
        // volunteer" — it says the waiting is going badly.
        if (existing != null) {
            return FeedbackRecord(
                question = question,
                feedback = feedback,
                ticket = existing,
                didRaiseTicket = false,
                reason = Reason.ALREADY_OPEN,
            )
        }

        val unanswered = question.answer?.isPublishable != true

        if (feedback == SahayakFeedback.Helpful && !unanswered) {
            return FeedbackRecord(
                question = question,
                feedback = feedback,
                ticket = null,
                didRaiseTicket = false,
                reason = Reason.NO_ACTION,
            )
        }

        val reason = when (feedback) {
            SahayakFeedback.AskVolunteer -> Reason.ASKED_FOR_A_PERSON
            SahayakFeedback.NotHelpful ->
                if (unanswered) Reason.REFUSAL_HELPFUL else Reason.ANSWER_WRONG
            SahayakFeedback.Helpful -> Reason.REFUSAL_HELPFUL
        }

        if (nextTicketId == null) {
            // Without an id there is nothing to hand a volunteer, and a ticket
            // with a guessed id is a ticket pointing at the wrong question.
            return FeedbackRecord(
                question = question,
                feedback = feedback,
                ticket = null,
                didRaiseTicket = false,
                reason = reason,
            )
        }

        return FeedbackRecord(
            question = question,
            feedback = feedback,
            ticket = VolunteerTicket(
                id = nextTicketId,
                questionId = question.id,
                status = TicketStatus.Open,
                createdAt = at,
            ),
            didRaiseTicket = true,
            reason = reason,
        )
    }

    /**
     * The question log entry, built so plan section 14 cannot be violated by
     * accident.
     *
     * The user is a [SahayakQuestion.userHash] and nothing else. The parameter
     * is named for what it is and there is no overload taking an id, so the
     * mistake has to be made deliberately and visibly rather than by passing the
     * wrong string to a function that wanted any string.
     *
     * @param answer the answer given, including its disclaimer and its sources.
     *   Stored whole, so a question log entry can be re-read later without
     *   needing the corpus that produced it.
     */
    fun buildQuestion(
        id: Int,
        text: String,
        userHash: String?,
        language: AppLanguage,
        classification: IntentClassification,
        answer: SahayakAnswer?,
        answeredAt: Instant?,
    ): SahayakQuestion = SahayakQuestion(
        id = id,
        userHash = userHash,
        language = language,
        question = text,
        // The reading travels with the question. A corpus that later proves the
        // classifier wrong can be re-scored from the log without the raw text
        // being classified again under different rules.
        intent = classification.intent,
        answeredAt = answeredAt,
        answer = answer,
    )
}

/**
 * One tap, and its consequence.
 *
 * [ticket] is non-null when a ticket should exist — either newly raised
 * ([didRaiseTicket]) or the one that already was. It is the only thing a
 * caller needs to act on, and it is null exactly when nothing is to be done.
 */
data class FeedbackRecord(
    val question: SahayakQuestion,
    val feedback: SahayakFeedback,
    /** The ticket to queue, or the one already queued. Null for no action. */
    val ticket: VolunteerTicket?,
    /** True only when this tap is what created [ticket]. */
    val didRaiseTicket: Boolean,
    /** A [FeedbackRecorder.Reason] constant, for triage and for tests. */
    val reason: String,
) {
    val needsVolunteer: Boolean get() = ticket != null
}
