package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.Circle
import `in`.doosripari.core.model.CircleType
import `in`.doosripari.core.model.JoinRule
import `in`.doosripari.core.model.Tier
import `in`.doosripari.core.model.UserProfile

/**
 * Why a join button is disabled — plan section 6.6.
 *
 * A boolean is the wrong return type here and the reason is specific to this
 * audience. A greyed-out button is a dead end: a 65-year-old who taps it learns
 * nothing about what to do next, tries it once more, and concludes the app is
 * broken. Section 3 of the plan ("nothing hidden") and section 4.4 (one
 * primary action per screen, always labelled) point the same way — the screen
 * has to be able to *say* "verify your phone number first".
 *
 * So [JoinDecision] is a sealed type with a [JoinDecision.Deny] arm carrying a
 * typed [JoinBlock]. Every value of that enum is reachable, every one has a
 * sentence, and a test walks the whole ladder.
 *
 * [JoinDecision.canJoin] always equals `Circle.canJoin(user)` from
 * `core-model`. The two must not disagree: `core-model` is what the server and
 * the circle list use, and a client that says "join" where the model says
 * "cannot" produces a 403 the user has no way to act on.
 */
sealed interface JoinDecision {

    /** Whether the user may join, or request to join, right now. */
    val canJoin: Boolean

    /**
     * A sentence for the user.
     *
     * This is a *fallback*, not the localisation mechanism. The apps translate
     * from [Deny.block] and [Deny.requiredTier] and reach for this when a
     * translation is missing — English is better than a blank space, and far
     * better than showing nothing at all.
     */
    val message: String

    /**
     * True when joining sends a request a member must approve rather than
     * joining outright — plan section 6.6's "Request, office-bearers approve".
     * The button says "Request to join", which is a different promise from
     * "Join" and must not borrow its label.
     */
    val isRequest: Boolean

    data class Allow(val asRequest: Boolean = false) : JoinDecision {
        override val canJoin: Boolean get() = true
        override val isRequest: Boolean get() = asRequest
        override val message: String
            get() = if (asRequest) {
                "Ask a member to let you in. They will see your request."
            } else {
                "Join this circle"
            }
    }

    data class Deny(val block: JoinBlock, val requiredTier: Tier? = null) : JoinDecision {
        override val canJoin: Boolean get() = false
        override val isRequest: Boolean get() = false
        override val message: String get() = block.message
    }
}

/**
 * Every way a join can be refused.
 *
 * `code` is the wire name and [fromCode] falls back to [TierTooLow] rather than
 * throwing: a circle type the server has not told us about must still render
 * something the user can read.
 */
enum class JoinBlock(val code: String) {
    /** Already in. Not a failure — the UI shows "Leave" instead of a button. */
    AlreadyMember("already_member"),

    /** A request is outstanding. The UI shows "Request sent" and waits. */
    ApprovalPending("approval_pending"),

    /**
     * A tier short, with a phone number already on file — so a document is the
     * next step and the message says that.
     */
    TierTooLow("tier_too_low"),

    /**
     * No verified phone number. Deliberately *not* folded into [TierTooLow].
     *
     * A T0 user with no phone on file does not need a document; they need a
     * one-minute OTP. Telling them to "upgrade your verification" sends them
     * looking for a control that does not exist, and section 6.1 lists the OTP
     * as the first rung for exactly this reason.
     */
    PhoneUnverified("phone_unverified"),

    /**
     * A department circle, and the user has not recorded a department.
     * Different fix from [DifferentDepartment] and a different screen —
     * section 6.1's onboarding collects it, so the action is "go and add it".
     */
    NoDepartmentOnRecord("no_department_on_record"),

    /** Right service, wrong department. The user cannot fix this at all. */
    DifferentDepartment("different_department"),

    /** The circle wants a member's invitation. Only a member can send one. */
    NeedsMemberInvitation("needs_member_invitation"),

    /** The circle's own record is incomplete; a moderator has to finish it. */
    CircleNotConfigured("circle_not_configured");

    companion object {
        fun fromCode(code: String?): JoinBlock =
            entries.firstOrNull { it.code == code } ?: TierTooLow
    }
}

/**
 * The sentence for each refusal.
 *
 * Kept beside the rule that produces it rather than in the UI, so the wording
 * and the reason cannot drift apart. Every arm names a next action; none of
 * them says "not allowed", which is the phrase that gives a pensioner nothing
 * to do and is the reason this type exists.
 */
val JoinBlock.message: String
    get() = when (this) {
        JoinBlock.AlreadyMember ->
            "You are already in this circle."
        JoinBlock.ApprovalPending ->
            "Your request is with the members. Their answer will appear here."
        JoinBlock.TierTooLow ->
            "This circle is for verified members. Verify your identity to join."
        JoinBlock.PhoneUnverified ->
            "Verify your phone number to join this circle. It takes a minute."
        JoinBlock.NoDepartmentOnRecord ->
            "This circle is for one department. Add your department in your profile first."
        JoinBlock.DifferentDepartment ->
            "This circle is for a different department."
        JoinBlock.NeedsMemberInvitation ->
            "This is a private circle. Ask a member to invite you."
        JoinBlock.CircleNotConfigured ->
            "This circle is not finished yet. A moderator needs to set it up."
    }

/**
 * Whether a user may join, and if not, exactly what is in the way.
 *
 * Pure, so both platforms and the tests get the same answer from the same three
 * inputs and the button and its explanation can never disagree.
 */
object JoinRules {

    /**
     * Decide.
     *
     * The checks run most-specific first, because the most specific reason is
     * the one with an action attached. A user who is already in a tier-gated
     * circle is told they are already in, not that they need a document:
     * sending somebody to fix something that is not broken is how a screen
     * loses a user's trust for good.
     */
    fun evaluate(circle: Circle, user: UserProfile): JoinDecision {
        if (circle.isJoined) return JoinDecision.Deny(JoinBlock.AlreadyMember)
        if (circle.isPending) return JoinDecision.Deny(JoinBlock.ApprovalPending)

        return when (circle.joinRule) {
            JoinRule.Open -> JoinDecision.Allow()

            JoinRule.Verified, JoinRule.DocumentVerified -> {
                // `core-model` gives both of these a non-null required tier, and
                // the non-null is what makes this a comparison rather than a
                // set membership test.
                val needed = circle.joinRule.requiredTier!!
                when {
                    user.tier.rank >= needed.rank -> JoinDecision.Allow()
                    needsPhoneOtpFirst(user, needed) ->
                        JoinDecision.Deny(JoinBlock.PhoneUnverified, needed)

                    else -> JoinDecision.Deny(JoinBlock.TierTooLow, needed)
                }
            }

            JoinRule.SameDepartment -> when {
                circle.departmentId == null -> JoinDecision.Deny(JoinBlock.CircleNotConfigured)
                user.departmentId == null -> JoinDecision.Deny(JoinBlock.NoDepartmentOnRecord)
                user.departmentId != circle.departmentId ->
                    JoinDecision.Deny(JoinBlock.DifferentDepartment)

                else -> JoinDecision.Allow()
            }

            JoinRule.Approval -> JoinDecision.Allow(asRequest = true)
        }
    }

    /**
     * Does the shortest route to [needed] run through a phone OTP?
     *
     * Section 6.1 makes T1 reachable two ways: an OTP, or a vouch from an
     * existing member. A vouch needs somebody else to agree, so for a user
     * with no phone on file the OTP is the only thing available *today* — and
     * today is the only time frame a person acts on. Once a phone exists the
     * remaining gap is a document, and [JoinBlock.TierTooLow] is the honest
     * message.
     *
     * Capped at T1 for the same reason: nobody can phone-verify their way to
     * T2, and offering an OTP for a T2 circle would be a promise the app
     * cannot keep.
     */
    private fun needsPhoneOtpFirst(user: UserProfile, needed: Tier): Boolean =
        user.phoneE164.isNullOrBlank() && needed.rank <= Tier.T1.rank

    /**
     * Whether the user may post in a circle they are already in.
     *
     * Plan section 6.6's batch row says posting needs T1, and section 6.1
     * defines T0 as "can check in and be escalated to, can host nothing".
     * Both point the same way, so the rule is applied to *every* circle rather
     * than only batch ones: a first post from somebody who joined an hour ago
     * is what turns a trusted circle into a spam channel, and no one's
     * security should depend on the circle happening to be of one type.
     */
    fun evaluatePosting(circle: Circle, user: UserProfile): PostDecision {
        if (circle.isPending) return PostDecision.Deny(PostBlock.ApprovalPending)
        if (!circle.isJoined) return PostDecision.Deny(PostBlock.NotAMember)
        if (user.tier.rank < POSTING_TIER.rank) {
            return PostDecision.Deny(PostBlock.TierTooLowToPost, POSTING_TIER)
        }
        return PostDecision.Allowed
    }

    /** Plan section 6.6: posting in a batch circle needs T1. */
    val POSTING_TIER: Tier = Tier.T1

    /**
     * The order the "circles you might like" rail is shown in.
     *
     * Batch first, because the people a pensioner most wants to hear from are
     * the ones they served with, and section 6.6's own table leads with it.
     */
    fun suggestedOrder(): List<CircleType> = listOf(
        CircleType.Batch,
        CircleType.Department,
        CircleType.City,
        CircleType.Interest,
        CircleType.VoiceOnly,
    )
}

/**
 * Why a composed post cannot be posted yet. The same reasoning as [JoinBlock]:
 * typed, and every value names what to do.
 */
sealed interface PostDecision {
    data object Allowed : PostDecision

    data class Deny(val block: PostBlock, val requiredTier: Tier? = null) : PostDecision {
        val message: String get() = block.message
    }
}

enum class PostBlock(val code: String) {
    NotAMember("not_a_member"),
    ApprovalPending("approval_pending"),
    TierTooLowToPost("tier_too_low_to_post");

    companion object {
        fun fromCode(code: String?): PostBlock =
            entries.firstOrNull { it.code == code } ?: NotAMember
    }
}

val PostBlock.message: String
    get() = when (this) {
        PostBlock.NotAMember -> "Join this circle before you post."
        PostBlock.ApprovalPending -> "Wait until a member accepts your request."
        PostBlock.TierTooLowToPost ->
            "Verify your identity before you post. (${JoinRules.POSTING_TIER.code} needed.)"
    }
