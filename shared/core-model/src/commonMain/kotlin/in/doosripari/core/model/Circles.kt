package `in`.doosripari.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Saathi circles — the batch, the department, the city, the interest group.
 * Plan section 6.6.
 *
 * The design problem this app has to solve is not networking, it is trust. A
 * retired government employee will not post into a room of strangers, and will
 * not read a room full of strangers. So circles are joinable by *who you are*
 * rather than by invitation, the join rules are part of the type rather than a
 * runtime check, and the interesting field is [Circle.joinRule].
 */

/** What kind of circle this is. Drives which join rules are offered. */
enum class CircleType(val code: String) {
    /** Everybody from the same batch, e.g. "Railways 2019–21". */
    Batch("batch"),
    Department("department"),
    City("city"),
    Interest("interest"),
    /** A voice room that is not also a feed. */
    VoiceOnly("voice_only");

    companion object {
        fun fromCode(code: String?): CircleType =
            entries.firstOrNull { it.code == code } ?: Interest
    }
}

/**
 * How somebody gets in. Plan section 6.6: circles are not open by default.
 *
 * The ladder runs from "verified enough" to "already a member". Anything a
 * pensioner cannot satisfy on their own is a bad first experience, so
 * [Approval] exists but is only ever offered to somebody who has already been
 * vouched for.
 */
enum class JoinRule(val code: String) {
    /** Open to any signed-up member. */
    Open("open"),

    /** T1 and above. */
    Verified("verified"),

    /** T2 and above — a document has been checked. */
    DocumentVerified("document_verified"),

    /** Same department, same service type. */
    SameDepartment("same_department"),

    /** An existing member has to invite. */
    Approval("approval");

    /** The tier a user needs, or null when the rule is not tier-based. */
    val requiredTier: Tier?
        get() = when (this) {
            Open -> Tier.T0
            Verified -> Tier.T1
            DocumentVerified -> Tier.T2
            SameDepartment, Approval -> null
        }

    companion object {
        fun fromCode(code: String?): JoinRule =
            entries.firstOrNull { it.code == code } ?: Verified
    }
}

data class Circle(
    val id: Int,
    val type: CircleType,
    val name: String,
    val departmentId: Int? = null,
    val cityId: Int? = null,
    val joinRule: JoinRule = JoinRule.Verified,
    val createdBy: Int? = null,
    val memberCount: Int = 0,
    /** Set when this user is already in. Drives the button's label. */
    val isJoined: Boolean = false,
    /** Set when a join is pending approval. */
    val isPending: Boolean = false,
) {
    /**
     * Whether the current user can join right now.
     *
     * Computed from the user's own tier rather than asked of the server, so
     * the button says why it is disabled instead of just being greyed out.
     */
    fun canJoin(user: UserProfile): Boolean = when {
        isJoined || isPending -> false
        joinRule.requiredTier != null -> user.tier.rank >= joinRule.requiredTier!!.rank
        joinRule == JoinRule.SameDepartment ->
            user.departmentId != null && user.departmentId == departmentId
        else -> true
    }
}

enum class CircleRole(val code: String) {
    Member("member"),
    Moderator("moderator"),
    Owner("owner");

    val canModerate: Boolean get() = this != Member
}

data class CircleMember(
    val userId: Int,
    val displayName: String,
    val role: CircleRole = CircleRole.Member,
    val joinedAt: Instant? = null,
    val muted: Boolean = false,
)

/**
 * A post in a circle. Plan section 6.6.
 *
 * [transcript] exists because a great deal of what this audience wants to talk
 * about is easier to say than to type — and because plan section 6.6 requires
 * a voice note to be transcribed before it can be reported on.
 */
data class Post(
    val id: Int,
    val circleId: Int,
    val authorId: Int,
    val authorName: String,
    val kind: PostKind,
    val text: String? = null,
    val media: List<String> = emptyList(),
    /** Set when [kind] is [PostKind.Voice] and transcription has completed. */
    val transcript: String? = null,
    val poll: Poll? = null,
    val createdAt: Instant,
    val hidden: Boolean = false,
    val reportCount: Int = 0,
    val commentCount: Int = 0,
    val likedByMe: Boolean = false,
) {
    /**
     * Whether moderation has pulled this out of the feed.
     *
     * A hidden post is not deleted — it is held, and a moderator can see it.
     * For a 65-year-old audience a wrong removal is far more damaging than a
     * wrong retention, so the default is visible and the report is what
     * triggers a look.
     */
    val isVisible: Boolean get() = !hidden
}

enum class PostKind(val code: String) {
    Text("text"),
    Image("image"),
    Voice("voice"),
    Poll("poll"),
    /** A forwarded news item, with its source kept. */
    News("news");

    companion object {
        fun fromCode(code: String?): PostKind =
            entries.firstOrNull { it.code == code } ?: Text
    }
}

data class Poll(
    val question: String,
    val options: List<PollOption>,
    val closesAt: Instant? = null,
    val totalVotes: Int = 0,
    val myVoteOptionId: Int? = null,
)

data class PollOption(val id: Int, val text: String, val votes: Int = 0) {
    /**
     * Share of the vote, for the result bar.
     *
     * The total is a property of the poll, not of the option, so it is passed
     * in rather than stored twice. Null when nobody has voted — a bar at zero
     * width looks broken, and a poll that has not been answered yet should read
     * as unanswered rather than as 0%.
     */
    fun shareOf(totalVotes: Int): Float? =
        if (totalVotes > 0) votes.toFloat() / totalVotes else null
}

data class Comment(
    val id: Int,
    val postId: Int,
    val authorId: Int,
    val authorName: String,
    val text: String,
    val createdAt: Instant,
    val reportCount: Int = 0,
)

/** Somebody reporting something. Plan section 10, `reports`. */
data class Report(
    val id: Int,
    val targetType: ReportTarget,
    val targetId: Int,
    val reporterId: Int,
    val reason: ReportReason,
    val status: ReportStatus = ReportStatus.Open,
    val createdAt: Instant? = null,
)

enum class ReportTarget(val code: String) {
    Post("post"),
    Comment("comment"),
    User("user"),
    Listing("listing"),
    VoiceRoom("voice_room");

    companion object {
        fun fromCode(code: String?): ReportTarget? =
            entries.firstOrNull { it.code == code }
    }
}

/**
 * Why somebody reported something. The options are the ones a moderator can
 * actually act on — "something else" is absent because an unactionable report
 * teaches people that reporting does nothing.
 */
enum class ReportReason(val code: String, val requiresImmediateAction: Boolean) {
    Abuse("abuse", true),
    HateSpeech("hate_speech", true),
    Scam("scam", true),
    Spam("spam", false),
    Misinformation("misinformation", false),
    PersonalInfo("personal_info", true);

    companion object {
        fun fromCode(code: String?): ReportReason? =
            entries.firstOrNull { it.code == code }
    }
}

enum class ReportStatus(val code: String) {
    Open("open"),
    Actioned("actioned"),
    Dismissed("dismissed");

    companion object {
        fun fromCode(code: String?): ReportStatus =
            entries.firstOrNull { it.code == code } ?: Open
    }
}

// ----------------------------------------------------------------- events --

data class CircleEvent(
    val id: Int,
    val circleId: Int,
    val title: String,
    val startsAt: Instant,
    /** Free-form, because a meeting place in Lucknow has no lat/lng worth storing. */
    val place: String? = null,
    val placePoint: GeoPoint? = null,
    val cost: Double = 0.0,
    val capacity: Int? = null,
    val going: Int = 0,
    val myRsvp: RsvpStatus? = null,
) {
    val isFull: Boolean get() = capacity != null && going >= capacity
    val isFree: Boolean get() = cost <= 0.0
}

enum class RsvpStatus(val code: String) {
    Going("going"),
    Maybe("maybe"),
    NotGoing("not_going");

    companion object {
        fun fromCode(code: String?): RsvpStatus? =
            entries.firstOrNull { it.code == code }
    }
}

// ------------------------------------------------------------- voice rooms --

/**
 * Chai pe Charcha — a live voice room. Plan section 6.7.
 *
 * A room is NOT a circle. It borrows one. The distinction matters for consent:
 * joining a room does not join the circle, and leaving does not leave either.
 * Recording is [recording] and defaults off, and the plan requires explicit
 * consent from every participant before a recording is kept — the [RoomSession]
 * carries that count for exactly this reason.
 */
data class VoiceRoom(
    val id: Int,
    val circleId: Int,
    val title: String,
    val hostId: Int,
    /** A recurrence rule, e.g. "FREQ=WEEKLY;BYDAY=TU". */
    val scheduleRule: String? = null,
    val nextSessionAt: Instant? = null,
    val isLive: Boolean = false,
    val listenerCount: Int = 0,
    val recordingAllowed: Boolean = false,
)

data class RoomSession(
    val id: Int,
    val roomId: Int,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val peakListeners: Int = 0,
    /** How many people explicitly agreed to be recorded. */
    val consentedForRecording: Int = 0,
    val isRecorded: Boolean = false,
) {
    val isEnded: Boolean get() = endedAt != null

    /**
     * A recording may only be kept when every participant agreed.
     *
     * Not a warning — a gate. A room that recorded one person who said no is a
     * privacy breach that cannot be undone by deleting the file later, because
     * it was already distributed.
     */
    val mayKeepRecording: Boolean get() = !isRecorded || consentedForRecording > 0
}
