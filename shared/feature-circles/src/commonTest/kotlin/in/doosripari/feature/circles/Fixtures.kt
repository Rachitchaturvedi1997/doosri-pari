package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.Circle
import `in`.doosripari.core.model.CircleType
import `in`.doosripari.core.model.JoinRule
import `in`.doosripari.core.model.Post
import `in`.doosripari.core.model.PostKind
import `in`.doosripari.core.model.RoomSession
import `in`.doosripari.core.model.Tier
import `in`.doosripari.core.model.UserProfile
import `in`.doosripari.core.model.VoiceRoom
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Builders for the circle tests.
 *
 * Every one has a default for everything except the thing being varied, so a
 * test says only what it is about. Plan section 20 asks for real tests over
 * real timings; a fixture that forces each test to spell out a whole
 * [UserProfile] is a fixture that gets worked around.
 */
internal object Fixtures {

    /** A fixed instant, so nothing in a test depends on the wall clock. */
    val NOW: Instant = Instant.parse("2026-10-14T09:00:00Z")

    /** [NOW] moved forwards by [by] minutes, for ordering tests. */
    fun minutesFromNow(by: Long): Instant = NOW.plus(by.minutes)

    fun user(
        id: Int = 7,
        tier: Tier = Tier.T2,
        phone: String? = "+919876543210",
        departmentId: Int? = null,
    ) = UserProfile(
        id = id,
        phoneE164 = phone,
        name = "Sharma",
        addressAs = "Sharma ji",
        tier = tier,
        departmentId = departmentId,
    )

    fun circle(
        id: Int = 1,
        joinRule: JoinRule = JoinRule.Open,
        type: CircleType = CircleType.City,
        departmentId: Int? = null,
        isJoined: Boolean = false,
        isPending: Boolean = false,
    ) = Circle(
        id = id,
        type = type,
        name = "Pensioners of Lucknow",
        joinRule = joinRule,
        departmentId = departmentId,
        isJoined = isJoined,
        isPending = isPending,
    )

    /**
     * A post. [minutesAgo] is the only knob most tests need — feed order is
     * about time, so the factory is expressed in time rather than in instants.
     */
    fun post(
        id: Int,
        minutesAgo: Long = 0,
        authorId: Int = 100,
        kind: PostKind = PostKind.Text,
        text: String? = "Namaste",
        media: List<String> = emptyList(),
        hidden: Boolean = false,
    ) = Post(
        id = id,
        circleId = 1,
        authorId = authorId,
        authorName = "Kaur",
        kind = kind,
        text = text,
        media = media,
        createdAt = minutesFromNow(-minutesAgo),
        hidden = hidden,
    )

    fun room(id: Int = 1, circleId: Int = 1, recordingAllowed: Boolean = false) = VoiceRoom(
        id = id,
        circleId = circleId,
        title = "Subah ki khabar",
        hostId = 42,
        isLive = true,
        recordingAllowed = recordingAllowed,
    )

    fun session(id: Int = 1, roomId: Int = 1, isRecorded: Boolean = false, consented: Int = 0) =
        RoomSession(
            id = id,
            roomId = roomId,
            startedAt = NOW,
            isRecorded = isRecorded,
            consentedForRecording = consented,
        )
}
