package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.CircleType
import `in`.doosripari.core.model.JoinRule
import `in`.doosripari.core.model.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chai pe Charcha, plan section 6.7.
 *
 * The first half of this file is the invariant the whole module rests on: a
 * room is not a circle, and nothing that happens in a room may change circle
 * membership. The second half is the recording gate, which is the only place in
 * the app where a person's voice is captured and kept without their having
 * asked for it.
 */
class VoiceRoomsTest {

    private val member = Fixtures.user(id = 7, tier = Tier.T2)
    private val outsider = Fixtures.user(id = 8, tier = Tier.T2)

    private fun roomState(
        seats: List<RoomSeat> = emptyList(),
        consent: RecordingConsent = RecordingConsent(),
        sessionIsRecorded: Boolean = false,
        consented: Int = 0,
        circleJoined: Boolean = true,
        circlePending: Boolean = false,
        isLive: Boolean = true,
        recordingAllowed: Boolean = false,
    ) = RoomState(
        room = Fixtures.room(recordingAllowed = recordingAllowed).copy(isLive = isLive),
        circle = Fixtures.circle(
            joinRule = JoinRule.Verified,
            isJoined = circleJoined,
            isPending = circlePending,
        ),
        session = Fixtures.session(isRecorded = sessionIsRecorded, consented = consented),
        seats = seats,
        consent = consent,
    )

    // ------------------------------------------------- a room is not a circle --

    @Test
    fun `joining a room does not join the circle`() {
        // The invariant. A circle's value is that it is small and everybody in
        // it is known; if walking into a room enrolled somebody in it, the join
        // rules in JoinRules.kt would be decoration and every trust promise in
        // section 6.6 would be worth nothing.
        val before = roomState()
        val after = VoiceRooms.join(before, member, "Sharma ji", RoomRole.Listener)

        val joined = assertIs<RoomJoinOrBlock.Joined>(after)
        assertFalse(joined.join.circleMembershipChanged)
        assertEquals(before.circle, joined.join.state.circle)
        assertEquals(1, joined.join.state.seats.size)
        // And the circle is still exactly the circle it was: not joined,
        // not pending, and nobody added to it.
        assertFalse(joined.join.state.circle.isJoined)
        assertFalse(joined.join.state.circle.isPending)
        assertEquals(0, joined.join.state.circle.memberCount)
    }

    @Test
    fun `leaving a room does not leave the circle`() {
        // The half that is easier to get wrong, because "leave" and "leave
        // circle" sit on the same screen and one handler that clears a flag can
        // quietly do both.
        val seated = assertIs<RoomJoinOrBlock.Joined>(
            VoiceRooms.join(roomState(), member, "Sharma ji", RoomRole.Speaker)
        ).join.state
        assertEquals(1, seated.seats.size)

        val after = VoiceRooms.leave(seated, member.id)

        assertFalse(after.circleMembershipChanged)
        assertTrue(after.state.seats.isEmpty())
        assertNull(after.state.self)
        assertEquals(seated.circle, after.state.circle)
        assertTrue(after.state.circle.isJoined)
    }

    @Test
    fun `speaking in a room does not join the circle either`() {
        // A caller that promotes somebody from listener to speaker must not be
        // able to smuggle a membership change in through the back door.
        val seated = assertIs<RoomJoinOrBlock.Joined>(
            VoiceRooms.join(roomState(), member, "Sharma ji", RoomRole.Listener)
        ).join.state

        val handRaised = VoiceRooms.raiseHand(seated, member.id)

        assertEquals(seated.circle, handRaised.circle)
        assertFalse(handRaised.circle.isJoined)
        // And a listener with no mic cannot raise a hand at all, rather than
        // being given a button that does nothing.
        val listenerState = roomState(
            seats = listOf(RoomSeat(userId = 9, displayName = "Kaur", role = RoomRole.Listener)),
        )
        assertEquals(listenerState, VoiceRooms.raiseHand(listenerState, 9))
    }

    @Test
    fun `the room borrows the circle's gate, so an outsider is turned away`() {
        val before = roomState()
        val result = VoiceRooms.join(before, outsider, "Kaur", RoomRole.Listener)

        val blocked = assertIs<RoomJoinOrBlock.Blocked>(result)
        assertEquals(RoomBlock.CircleNotJoined, blocked.block)
        assertTrue(blocked.block.message.isNotBlank())
        // Nothing about the circle moved, which is the whole point.
        assertTrue(before.seats.isEmpty())
        assertFalse(before.circle.isJoined)
    }

    @Test
    fun `somebody whose circle request is pending is told to wait`() {
        val result = VoiceRooms.join(
            roomState(circleJoined = false, circlePending = true),
            member,
            "Sharma ji",
            RoomRole.Listener,
        )
        assertEquals(
            RoomBlock.CircleApprovalPending,
            assertIs<RoomJoinOrBlock.Blocked>(result).block,
        )
    }

    @Test
    fun `a room that has not started cannot be joined`() {
        val result = VoiceRooms.join(
            roomState(isLive = false), member, "Sharma ji", RoomRole.Listener,
        )
        assertEquals(RoomBlock.NotLive, assertIs<RoomJoinOrBlock.Blocked>(result).block)
    }

    @Test
    fun `walking back into a room you are already in is not an error`() {
        // A reconnect after a dropped call must not tell somebody they are
        // somewhere they already are.
        val seated = assertIs<RoomJoinOrBlock.Joined>(
            VoiceRooms.join(roomState(), member, "Sharma ji", RoomRole.Speaker)
        ).join.state

        val again = assertIs<RoomJoinOrBlock.Joined>(
            VoiceRooms.join(seated, member, "Sharma ji", RoomRole.Speaker)
        )

        assertEquals(1, again.join.state.seats.size)
        assertEquals(seated.circle, again.join.state.circle)
    }

    // -------------------------------------------------------------- capacity --

    @Test
    fun `a ninth speaker is told the places are taken rather than quietly demoted`() {
        // A silent downgrade to listener leaves somebody waiting for a mic
        // button that will never light up.
        val seats = (1..8).map { RoomSeat(userId = it, displayName = "S$it", role = RoomRole.Speaker) }
        val full = roomState(seats = seats)

        val result = VoiceRooms.join(full, member, "Sharma ji", RoomRole.Speaker)

        assertEquals(
            RoomBlock.NoSpeakerPlaces,
            assertIs<RoomJoinOrBlock.Blocked>(result).block,
        )
        assertEquals(8, full.speakers)
        // And the sentence tells them they can still listen.
        assertTrue(RoomBlock.NoSpeakerPlaces.message.contains("listen"))
    }

    @Test
    fun `a listener can still get in when the speaker places are full`() {
        val seats = (1..8).map { RoomSeat(userId = it, displayName = "S$it", role = RoomRole.Speaker) }
        val result = VoiceRooms.join(roomState(seats = seats), member, "Sharma ji", RoomRole.Listener)

        assertIs<RoomJoinOrBlock.Joined>(result)
    }

    @Test
    fun `a listener is refused only when two hundred are already in`() {
        val seats = (1..VoiceRooms.MAX_LISTENERS).map {
            RoomSeat(userId = it, displayName = "L$it", role = RoomRole.Listener)
        }
        val result = VoiceRooms.join(
            roomState(seats = seats), member, "Sharma ji", RoomRole.Listener,
        )
        assertEquals(
            RoomBlock.NoSpeakerPlaces,
            assertIs<RoomJoinOrBlock.Blocked>(result).block,
        )
    }

    // ------------------------------------------------------------ moderation --

    @Test
    fun `only the host mutes, and muting clears the raised hand`() {
        val host = RoomSeat(userId = 42, displayName = "Host", role = RoomRole.Host)
        val speaker = RoomSeat(userId = 7, displayName = "Sharma ji", role = RoomRole.Speaker)
        val state = VoiceRooms.raiseHand(roomState(seats = listOf(host, speaker)), 7)

        assertTrue(state.seatOf(7)!!.handRaised)
        val muted = VoiceRooms.mute(state, hostId = 42, targetId = 7)

        assertTrue(7 in muted.mutedByHost)
        assertFalse(muted.seatOf(7)!!.handRaised)
        assertEquals(state.circle, muted.circle)
    }

    @Test
    fun `a listener cannot mute anybody`() {
        val listener = RoomSeat(userId = 8, displayName = "Kaur", role = RoomRole.Listener)
        val state = roomState(seats = listOf(listener))

        assertFalse(VoiceRooms.canModerate(state, hostId = 8, targetId = 7))
        assertEquals(state, VoiceRooms.mute(state, hostId = 8, targetId = 7))
    }

    @Test
    fun `removing somebody hands the seat back so it can be counted`() {
        // Somebody taken off a mic has been accused of something, and a screen
        // that forgets who it just removed will remove the same person four
        // times in an evening.
        val host = RoomSeat(userId = 42, displayName = "Host", role = RoomRole.Host)
        val speaker = RoomSeat(userId = 7, displayName = "Sharma ji", role = RoomRole.Speaker)
        val state = roomState(seats = listOf(host, speaker))

        val removed = assertIs<RoomModeration.Removed>(VoiceRooms.remove(state, 42, 7))

        assertEquals(7, removed.removed.userId)
        assertTrue(removed.removed.removedByHost)
        assertEquals(listOf(42), removed.join.state.seats.map { it.userId })
        assertFalse(removed.join.circleMembershipChanged)
    }

    @Test
    fun `the host removing themselves is leaving, and is refused as a removal`() {
        val host = RoomSeat(userId = 42, displayName = "Host", role = RoomRole.Host)
        assertEquals(
            RoomModeration.NotPermitted,
            VoiceRooms.remove(roomState(seats = listOf(host)), 42, 42),
        )
    }

    @Test
    fun `three upheld reports stop somebody hosting`() {
        // Section 6.7, read from the same table the strike ladder uses.
        assertTrue(VoiceRooms.mayHost(0))
        assertTrue(VoiceRooms.mayHost(2))
        assertFalse(VoiceRooms.mayHost(3))
        assertFalse(VoiceRooms.mayHost(4))
    }

    // ------------------------------------------------------------- recording --

    @Test
    fun `a room with recording off is refused before anybody is asked`() {
        // A host with recording switched off should not be sent round eight
        // people collecting consent for a file they will never be allowed to
        // keep.
        val state = roomState(recordingAllowed = false)
        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = true, consented = setOf(7, 42)),
            speakingNow = listOf(7, 42),
        )

        assertEquals(
            RecordingBlock.RoomForbidsRecording,
            assertIs<RecordingVerdict.Blocked>(verdict).block,
        )
    }

    @Test
    fun `recording is refused until the sign is showing`() {
        // Section 6.7: "a visible sign and consent from every speaker". Both,
        // and the sign is checked first because it is the one everybody sees.
        val state = roomState(recordingAllowed = true)
        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = false, consented = setOf(7, 42)),
            speakingNow = listOf(7, 42),
        )

        assertEquals(RecordingBlock.NotAnnounced, assertIs<RecordingVerdict.Blocked>(verdict).block)
    }

    @Test
    fun `one speaker who never agreed blocks the recording and is named`() {
        // "2 people have not agreed" gives a host nothing to do. Two ids and
        // two buttons is the entire feature.
        val state = roomState(recordingAllowed = true)
        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = true, consented = setOf(7, 42)),
            speakingNow = listOf(7, 42, 55),
        )

        val blocked = assertIs<RecordingVerdict.Blocked>(verdict)
        assertEquals(RecordingBlock.ConsentMissing, blocked.block)
        assertEquals(listOf(55), blocked.missingConsentFor)
    }

    @Test
    fun `every speaker on a mic has to have agreed, not just one of them`() {
        // `core-model`'s `mayKeepRecording` is satisfied by a single consent.
        // That is a sanity check on a row, not a policy, and treating it as one
        // would record a person who said no.
        val state = roomState(recordingAllowed = true)
        assertTrue(state.session.mayKeepRecording)

        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = true, consented = setOf(42)),
            speakingNow = listOf(42, 7),
        )
        assertEquals(
            listOf(7),
            assertIs<RecordingVerdict.Blocked>(verdict).missingConsentFor,
        )
    }

    @Test
    fun `somebody who said no is never recorded, so their agreement is not needed`() {
        val state = roomState(recordingAllowed = true)
        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = true, consented = setOf(7, 42), declined = setOf(55)),
            speakingNow = listOf(7, 42, 55),
        )

        assertEquals(RecordingVerdict.Allowed, verdict)
    }

    @Test
    fun `a recording that already exists has to be deleted, not kept`() {
        val state = roomState(recordingAllowed = true, sessionIsRecorded = true)
        val verdict = RecordingGate.mayKeep(
            state.session,
            state.room,
            RecordingConsent(announced = true, consented = setOf(7, 42)),
            speakingNow = listOf(7, 42),
        )

        assertEquals(RecordingVerdict.DeleteInstead, verdict)
    }

    @Test
    fun `listeners are never recorded and never have to consent`() {
        val listener = RoomSeat(userId = 9, displayName = "Kaur", role = RoomRole.Listener)
        val host = RoomSeat(userId = 42, displayName = "Host", role = RoomRole.Host)
        val state = roomState(seats = listOf(host, listener), recordingAllowed = true)

        assertEquals(listOf(42), state.speakingNow())
        assertEquals(
            RecordingVerdict.Allowed,
            RecordingGate.mayKeep(
                state.session,
                state.room,
                RecordingConsent(announced = true, consented = setOf(42)),
                speakingNow = state.speakingNow(),
            ),
        )
    }

    @Test
    fun `a muted speaker drops out of the recording set`() {
        val host = RoomSeat(userId = 42, displayName = "Host", role = RoomRole.Host)
        val speaker = RoomSeat(userId = 7, displayName = "Sharma ji", role = RoomRole.Speaker)
        val state = roomState(seats = listOf(host, speaker), recordingAllowed = true)

        val muted = VoiceRooms.mute(state, hostId = 42, targetId = 7)

        assertEquals(listOf(42), muted.speakingNow())
        assertEquals(
            RecordingVerdict.Allowed,
            RecordingGate.mayKeep(
                muted.session,
                muted.room,
                RecordingConsent(announced = true, consented = setOf(42)),
                speakingNow = muted.speakingNow(),
            ),
        )
    }

    // ----------------------------------------------------------------- misc --

    @Test
    fun `an unknown role code falls back to listening`() {
        assertEquals(RoomRole.Listener, RoomRole.fromCode("a_role_from_the_future"))
        assertEquals(RoomRole.Listener, RoomRole.fromCode(null))
        assertEquals(RoomRole.Host, RoomRole.fromCode("host"))
    }

    @Test
    fun `every room block has a sentence and a wire code`() {
        for (block in RoomBlock.entries) {
            assertTrue(block.message.isNotBlank(), "$block has no message")
            assertTrue(block.code.isNotBlank())
        }
    }

    @Test
    fun `a voice only circle is still a circle and is still not joined by a room`() {
        // Section 6.6 lists VoiceOnly as a circle type, which is exactly why
        // this needed saying: a voice-only circle is the one place a reader is
        // most likely to believe "I am in it now".
        val circle = Fixtures.circle(type = CircleType.VoiceOnly, isJoined = false)
        val state = roomState().copy(circle = circle)

        val after = VoiceRooms.join(state, outsider, "Kaur", RoomRole.Listener)

        assertEquals(
            RoomBlock.CircleNotJoined,
            assertIs<RoomJoinOrBlock.Blocked>(after).block,
        )
        assertFalse(state.circle.isJoined)
    }
}
