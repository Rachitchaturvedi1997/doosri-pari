package `in`.doosripari.feature.circles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================
 * THE MEDIA SEAM. NOTHING BELOW THIS LINE SPEAKS AUDIO.
 * ============================================================
 *
 * Chai pe Charcha (plan section 6.7) is Moholla's Baithak room moved to an SFU
 * for scale: a host, up to 8 speakers, up to 200 listeners, loudness
 * normalisation, noise suppression, and a foreground service on Android (the
 * plan reuses Moholla's PlaybackHub) so the room keeps sounding while the app is
 * in the pocket.
 *
 * **What production supplies here:** the SFU client for the chosen vendor —
 * LiveKit, Agora, 100ms, or a self-hosted mediasoup/LiveKit stack; the plan says
 * "moved to an SFU" and leaves the vendor to the build. Production also supplies
 * the audio device handling, the Android foreground service, the mic
 * permission flow, and the recording gate of section 6.7 (recording is off by
 * default, and when it is on there is a visible sign and consent from every
 * speaker).
 *
 * **What this seam promises the screen:** join, leave, and an honest connection
 * status. That is deliberately everything. The screen's job is the roster and
 * the controls; the transport's job is the audio.
 *
 * ## The rule the seam exists to keep
 *
 * **The screen must never present a room as live when it is not.** "Live" on
 * this screen comes from the server ([VoiceRoomCard.isLive] and a non-null
 * `sessionId`), and "you are in the room" comes only from
 * [TransportStatus.Connected] on this seam. The two are independent facts and
 * the screen shows both in words. If the transport is not connected, the screen
 * says so in words — it does not draw a live badge, a speaking indicator, or a
 * waveform to fill the gap. A pensioner who is told a bhajan room is live, who
 * hears nothing, and who cannot tell whether the fault is their phone or the
 * app will simply give up on the app.
 */
interface VoiceRoomTransport {

    /** Where the connection is right now. Words, not a boolean. */
    val status: StateFlow<TransportStatus>

    /**
     * Connect to the session the server has actually started.
     *
     * [sessionId] is the server's non-null session — the screen only calls this
     * when the room card carried one, so there is no way to "join" a room that
     * exists only as a schedule entry.
     */
    suspend fun join(sessionId: Int)

    /** Hang up. Must be safe to call twice: a double tap on Leave is not an error. */
    suspend fun leave()
}

/**
 * The connection, as words a person would use. The screen renders [label]
 * verbatim; there is no enum name anywhere on screen (plan section 3: nothing
 * hidden, and no engineering vocabulary in front of the user).
 */
enum class TransportStatus(val label: String) {
    /** Not in a room. The normal state of the list screen. */
    Idle("Not connected"),

    /** The tap happened; the handshake has not finished. */
    Joining("Joining the room…"),

    /** In the room and carrying audio. */
    Connected("You are in the room"),

    /** Left cleanly. */
    Left("You have left the room"),

    /**
     * The handshake failed or dropped.
     *
     * Named as something the user can act on ("could not connect"), never as a
     * code. A room that silently stops carrying audio is indistinguishable from
     * a room where nobody is speaking, and this audience will sit in silence
     * waiting rather than assume the app broke.
     */
    Failed("The room could not connect. Try again."),
}

/**
 * The stand-in used until the SFU vendor lands.
 *
 * It never claims to be connected — [status] stays [TransportStatus.Idle] and
 * `join` reports [TransportStatus.Failed] in words. Shipping a stub that faked
 * Connected would be the exact lie the rule above forbids, and it would be
 * discovered by a user, alone, in a room they think is broken.
 *
 * Whoever wires the real transport replaces this one at the Route; nothing else
 * in the feature changes.
 */
class NotWiredVoiceRoomTransport : VoiceRoomTransport {

    private val _status = MutableStateFlow(TransportStatus.Idle)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    override suspend fun join(sessionId: Int) {
        _status.value = TransportStatus.Failed
    }

    override suspend fun leave() {
        _status.value = TransportStatus.Left
    }
}