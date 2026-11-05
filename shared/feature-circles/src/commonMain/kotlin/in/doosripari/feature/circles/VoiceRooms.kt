package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.Circle
import `in`.doosripari.core.model.RoomSession
import `in`.doosripari.core.model.UserProfile
import `in`.doosripari.core.model.VoiceRoom

/**
 * Chai pe Charcha. Plan section 6.7.
 *
 * ## A room is not a circle
 *
 * This is the reason the file exists, so it is worth being blunt about the
 * consequence. A [VoiceRoom] has a `circleId` — it borrows a circle, and it
 * borrows its *name and its gate*. It does not borrow its membership. Section
 * 6.7's rooms are a scheduled thing anybody can drop into; somebody who
 * listens to "Subah ki khabar" at eight in the morning has not thereby joined a
 * circle, has not become visible in that circle's feed, and cannot be reached
 * by it.
 *
 * Getting that wrong is not a UI bug, it is a consent bug. A circle's whole
 * value is that it is small and everybody in it is known. If walking into a
 * room is a back door into the circle, then membership in a trust circle
 * carries no weight and the join rules — which is the entire subject of
 * `JoinRules.kt` — are decoration.
 *
 * So [RoomState] holds the [Circle] as a read-only snapshot, and no function in
 * this file can write to it. There is no parameter through which a membership
 * change could be expressed, and a test asserts the circle is unchanged after
 * a join and after a leave.
 */
data class RoomState(
    val room: VoiceRoom,
    /**
     * The circle this room is named after, carried so the header can show it
     * and so a moderator can be resolved. **Read-only** — see above.
     */
    val circle: Circle,
    val session: RoomSession,
    val seats: List<RoomSeat> = emptyList(),
    val consent: RecordingConsent = RecordingConsent(),
    /** The user's own seat, or null when they are not in the room. */
    val self: RoomSeat? = null,
    val mutedByHost: Set<Int> = emptySet(),
) {
    val listeners: Int get() = seats.count { it.role == RoomRole.Listener }
    val speakers: Int get() = seats.count { it.role == RoomRole.Speaker }

    fun seatOf(userId: Int): RoomSeat? = seats.firstOrNull { it.userId == userId }

    /**
     * The next person the host could put on the mic, if any.
     *
     * A full room with a raised hand is the one situation section 6.7's "up to
     * 8 speakers" creates, and the host needs a name to put on screen rather
     * than a "no more speakers" message.
     */
    fun firstRaisedHand(): RoomSeat? = seats.firstOrNull { it.handRaised }

    /** Ids actually on a mic right now. The recording gate needs this exact set. */
    fun speakingNow(): List<Int> = seats
        .filter { it.role.canSpeak && it.userId !in mutedByHost }
        .map { it.userId }
}

/** What somebody is doing in the room. Section 6.7: a host, speakers, listeners. */
enum class RoomRole(val code: String) {
    Host("host"),
    Speaker("speaker"),
    Listener("listener");

    val canSpeak: Boolean get() = this != Listener

    companion object {
        fun fromCode(code: String?): RoomRole =
            entries.firstOrNull { it.code == code } ?: Listener
    }
}

/** One person's place in the room. */
data class RoomSeat(
    val userId: Int,
    val displayName: String,
    val role: RoomRole,
    /** Section 6.7's "raise hand". A flag, not a request — it clears on unmute. */
    val handRaised: Boolean = false,
    /**
     * The host took this mic rather than the person being offered it.
     *
     * Carried on the seat so that "removed by the host" is not the same thing
     * as "left", which matters for the three-strikes rule in section 6.7: a
     * person who is repeatedly removed has been reported, and the count is
     * worth more than the seat.
     */
    val removedByHost: Boolean = false,
)

/**
 * The result of walking into or out of a room.
 *
 * [circleMembershipChanged] is always false. It is on the type anyway, so the
 * invariant is something a caller and a test can check rather than something
 * only a comment claims.
 */
data class RoomJoin(
    val state: RoomState,
    val circleMembershipChanged: Boolean = false,
)

/** Why a room could not be joined. Typed, so the UI can say which. */
enum class RoomBlock(val code: String) {
    /** The room is not live. Section 6.7's rooms are scheduled. */
    NotLive("not_live"),

    /** The circle has not admitted this person, and the room does not bypass it. */
    CircleApprovalPending("circle_approval_pending"),
    CircleNotJoined("circle_not_joined"),

    /**
     * Section 6.7: "up to 8 speakers". A ninth is told to listen rather than
     * refused, because the sentence above says so and the caller can then raise
     * a hand instead.
     */
    NoSpeakerPlaces("no_speaker_places"),

    /** Section 6.7's strike rule — three upheld reports, thirty days. */
    HostingSuspended("hosting_suspended"),
}

sealed interface RoomAccess {
    data object Allowed : RoomAccess
    data class Denied(val block: RoomBlock) : RoomAccess
}

val RoomBlock.message: String
    get() = when (this) {
        RoomBlock.NotLive -> "This room has not started yet."
        RoomBlock.CircleApprovalPending ->
            "Wait until your request to join the circle is accepted."

        RoomBlock.CircleNotJoined -> "Join the circle first, then come to the room."
        RoomBlock.NoSpeakerPlaces ->
            "All the speaker places are taken. You can listen, and raise your hand."

        RoomBlock.HostingSuspended -> "This person cannot host a room just now."
    }

/** Either the user is in, or there is a typed reason they are not. */
sealed interface RoomJoinOrBlock {
    data class Joined(val join: RoomJoin) : RoomJoinOrBlock
    data class Blocked(val block: RoomBlock) : RoomJoinOrBlock
}

/**
 * The room's transitions.
 *
 * Every function takes and returns a whole [RoomState], and none of them can
 * reach the circle. That is not a convention, it is the shape of the API: there
 * is no parameter through which a membership change could be expressed.
 */
object VoiceRooms {

    /** Section 6.7: "a host, up to 8 speakers and up to 200 listeners". */
    const val MAX_SPEAKERS: Int = 8
    const val MAX_LISTENERS: Int = 200

    /**
     * May this person be in the room at all?
     *
     * A room that borrows a circle borrows its *gate* as well as its name. An
     * outsider does not get in, because the reason a circle is trusted is that
     * everybody in it is known — and if the room is the weakest part of the
     * circle, the join rules mean nothing.
     *
     * This is the only place a room and a circle interact, and it *reads* the
     * circle. It never writes to it.
     */
    fun access(circle: Circle, room: VoiceRoom, user: UserProfile): RoomAccess = when {
        !room.isLive -> RoomAccess.Denied(RoomBlock.NotLive)
        circle.isPending -> RoomAccess.Denied(RoomBlock.CircleApprovalPending)
        !circle.isJoined -> RoomAccess.Denied(RoomBlock.CircleNotJoined)
        else -> RoomAccess.Allowed
    }

    /**
     * Walk in.
     *
     * Joining a room is not joining a circle — see the file header. The circle
     * is copied through untouched and [RoomJoin.circleMembershipChanged] is
     * false by construction, so nothing downstream can come to depend on a
     * room subscription having enrolled somebody anywhere.
     *
     * Asking for a [RoomRole.Speaker] place when all eight are taken is a
     * [RoomBlock.NoSpeakerPlaces] rather than a quiet downgrade to Listener:
     * the caller may want to raise a hand instead, and a silent downgrade
     * leaves the person waiting for a mic button that will never light up.
     */
    fun join(
        state: RoomState,
        user: UserProfile,
        displayName: String,
        role: RoomRole,
    ): RoomJoinOrBlock {
        // Already in is not an error. A reconnect after a dropped call must not
        // tell somebody they are somewhere they already are.
        state.seatOf(user.id)?.let { return RoomJoinOrBlock.Joined(RoomJoin(state)) }

        when (val access = access(state.circle, state.room, user)) {
            is RoomAccess.Denied -> return RoomJoinOrBlock.Blocked(access.block)
            RoomAccess.Allowed -> Unit
        }

        val full = when (role) {
            RoomRole.Speaker -> state.speakers >= MAX_SPEAKERS
            RoomRole.Listener -> state.listeners >= MAX_LISTENERS
            // A host is not capped: the host is whoever the server says is
            // hosting, and section 6.7's "a host" is singular by construction.
            RoomRole.Host -> false
        }
        if (full) return RoomJoinOrBlock.Blocked(RoomBlock.NoSpeakerPlaces)

        val seat = RoomSeat(userId = user.id, displayName = displayName, role = role)
        return RoomJoinOrBlock.Joined(
            RoomJoin(state.copy(seats = state.seats + seat, self = seat))
        )
    }

    /**
     * Leave the room.
     *
     * Also not leaving the circle: the seat goes, the circle is copied through
     * exactly as it arrived. This half of the rule is easier to get wrong than
     * the join, because "leave" and "leave circle" sit on the same screen and
     * one handler that clears a flag can quietly do both.
     */
    fun leave(state: RoomState, userId: Int): RoomJoin = RoomJoin(
        state = state.copy(
            seats = state.seats.filterNot { it.userId == userId },
            self = state.self?.takeIf { it.userId != userId },
            mutedByHost = state.mutedByHost - userId,
        ),
    )

    /**
     * Section 6.7's "raise hand".
     *
     * A flag, not a request to speak. Listeners have no mic to raise it for, so
     * the call is a no-op for them rather than a rejection.
     */
    fun raiseHand(state: RoomState, userId: Int): RoomState {
        val seat = state.seatOf(userId)?.takeIf { it.role.canSpeak } ?: return state
        return state.copy(seats = state.seats.replaceSeat(seat.copy(handRaised = true)))
    }

    /** Section 6.7: the host can mute. Muting clears the raised hand with it. */
    fun mute(state: RoomState, hostId: Int, targetId: Int): RoomState {
        if (!canModerate(state, hostId, targetId)) return state
        val seat = state.seatOf(targetId) ?: return state
        return state.copy(
            seats = state.seats.replaceSeat(seat.copy(handRaised = false)),
            mutedByHost = state.mutedByHost + targetId,
        )
    }

    fun unmute(state: RoomState, hostId: Int, targetId: Int): RoomState {
        if (!canModerate(state, hostId, targetId)) return state
        return state.copy(mutedByHost = state.mutedByHost - targetId)
    }

    /**
     * Section 6.7: the host can remove.
     *
     * The removed seat is *returned*, not just dropped. Somebody taken off a
     * mic has been accused of something, and a screen that forgets who it just
     * removed is a screen that will remove the same person four times in an
     * evening. Handing the seat back lets the caller count it towards
     * [ReportStrikes.HOSTING_BAN_AFTER].
     *
     * The host cannot remove themselves — that is leaving, and it is [leave].
     */
    fun remove(state: RoomState, hostId: Int, targetId: Int): RoomModeration {
        if (!canModerate(state, hostId, targetId)) return RoomModeration.NotPermitted
        val seat = state.seatOf(targetId) ?: return RoomModeration.NotPresent
        return RoomModeration.Removed(
            join = RoomJoin(
                state = state.copy(
                    seats = state.seats.filterNot { it.userId == targetId },
                    self = state.self?.takeIf { it.userId != targetId },
                    mutedByHost = state.mutedByHost - targetId,
                ),
            ),
            removed = seat.copy(removedByHost = true),
        )
    }

    /**
     * Section 6.7: "3 valid reports means no hosting for 30 days".
     *
     * Reads [ReportStrikes] rather than repeating the thresholds, so the room
     * rule and the moderation ladder cannot drift apart.
     */
    fun mayHost(upheldReports: Int): Boolean = !ReportStrikes.hostingBannedFor(upheldReports)

    /** Only the host moderates. Section 6.7 gives nobody else the power. */
    fun canModerate(state: RoomState, hostId: Int, targetId: Int): Boolean {
        // Not yourself: removing yourself is leaving, and it is [leave].
        if (hostId == targetId) return false
        return state.seatOf(hostId)?.role == RoomRole.Host
    }

    private fun List<RoomSeat>.replaceSeat(seat: RoomSeat): List<RoomSeat> =
        map { if (it.userId == seat.userId) seat else it }
}

/** What [VoiceRooms.remove] did. */
sealed interface RoomModeration {
    /** Not the host, or the host tried to remove themselves. */
    data object NotPermitted : RoomModeration

    /** Nobody by that name is in the room. */
    data object NotPresent : RoomModeration

    data class Removed(val join: RoomJoin, val removed: RoomSeat) : RoomModeration
}

/**
 * Who has agreed to be recorded, and whether the room is announcing it.
 *
 * [consented] and [declined] are id sets rather than flags on the seat because
 * a person can change their mind mid-session, and because the *set* is what the
 * gate needs to answer. A speaker who is silent at the moment recording starts
 * has agreed to nothing: silence is never read as consent, which is the whole
 * reason this is a set and not a counter.
 */
data class RecordingConsent(
    /** Section 6.7's visible sign. Without it, recording does not start. */
    val announced: Boolean = false,
    val consented: Set<Int> = emptySet(),
    val declined: Set<Int> = emptySet(),
    val recording: Boolean = false,
) {
    /**
     * Who would actually end up in the file.
     *
     * Listeners are not in this list and never consent to anything, which is
     * why the gate is passed the speaking set rather than deriving it from the
     * seats.
     */
    fun whoWouldBeRecorded(speakingNow: List<Int>): List<Int> =
        speakingNow.filterNot { it in declined }
}

/** Why a recording may not be kept. */
enum class RecordingBlock(val code: String) {
    /** The room has recording switched off. Section 6.7: off by default. */
    RoomForbidsRecording("room_forbids_recording"),

    /** The visible sign is part of the rule, not a nicety. */
    NotAnnounced("not_announced"),

    /** Somebody was on a mic and never agreed. */
    ConsentMissing("consent_missing"),
}

sealed interface RecordingVerdict {
    data object Allowed : RecordingVerdict

    /**
     * [missingConsentFor] is the list of ids to go and ask, not a count. "2
     * people have not agreed" gives a host nothing to do; two names and two
     * buttons is the entire feature.
     */
    data class Blocked(
        val block: RecordingBlock,
        val missingConsentFor: List<Int> = emptyList(),
    ) : RecordingVerdict

    /**
     * A recording that already exists has to be *deleted*, not kept.
     *
     * A distinct verdict rather than a [RecordingBlock] because the action is
     * the opposite: the host is not being stopped, they are being told the
     * file they have cannot be published. Deleting it later does not undo the
     * distribution, which is the point.
     */
    data object DeleteInstead : RecordingVerdict
}

/**
 * The recording gate. Section 6.7: off by default, a visible sign, and consent
 * from every speaker.
 *
 * `core-model`'s [RoomSession.mayKeepRecording] asks a weaker question — it is
 * true as soon as *one* person consented, and true whenever nothing has been
 * recorded yet. That is a useful sanity check on a row and a dangerous thing to
 * trust as the policy, so the real rule lives here: unanimity over the people
 * currently speaking, plus an announcement, plus the room's own setting.
 */
object RecordingGate {

    /**
     * May this session's recording be kept?
     *
     * Order is deliberate. [RecordingBlock.RoomForbidsRecording] comes first so
     * that a room with recording off gets a permanent answer rather than being
     * sent to ask eight people for consent it will never be allowed to use.
     */
    fun mayKeep(
        session: RoomSession,
        room: VoiceRoom,
        consent: RecordingConsent,
        speakingNow: List<Int>,
    ): RecordingVerdict {
        if (!room.recordingAllowed) {
            return RecordingVerdict.Blocked(RecordingBlock.RoomForbidsRecording)
        }
        if (session.isRecorded) return RecordingVerdict.DeleteInstead
        if (!consent.announced) return RecordingVerdict.Blocked(RecordingBlock.NotAnnounced)

        val captured = consent.whoWouldBeRecorded(speakingNow)
        val missing = captured.filterNot { it in consent.consented }
        if (missing.isNotEmpty()) {
            return RecordingVerdict.Blocked(RecordingBlock.ConsentMissing, missing)
        }
        return RecordingVerdict.Allowed
    }

    /**
     * The weaker row-level check, exposed so the two are never confused.
     *
     * [RoomSession.mayKeepRecording] being true does not mean the recording may
     * be kept; it means the row is not in an impossible state. Callers that
     * need the policy call [mayKeep].
     */
    fun rowIsSane(session: RoomSession): Boolean = session.mayKeepRecording
}
