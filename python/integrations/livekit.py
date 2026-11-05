# =============================================================================
# LiveKit — the SFU behind Chai pe Charcha. Plan sections 6.7 and 9.
#
# Access tokens are plain HS256 JWTs (API key as issuer, API secret as the key),
# so no SDK is needed to mint one. Room control (removing a participant, muting a
# track, ending a room) uses LiveKit's Twirp HTTP API with an admin token.
#
# The grant model follows the plan:
#   * a listener can subscribe but not publish;
#   * a speaker can publish audio only (no video, no screen share);
#   * the host is a room admin and can mute and remove;
#   * recording is never enabled from here — python/features/circles.py owns the
#     consent policy, and this module has no code path that starts an egress.
# =============================================================================
from __future__ import annotations

import logging
import time

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.livekit")

TOKEN_TTL_S = 4 * 3600


def available() -> bool:
    return bool(config.LIVEKIT_URL and config.LIVEKIT_API_KEY and config.LIVEKIT_API_SECRET)


def _sign(claims: dict) -> str | None:
    try:
        import jwt  # noqa: PLC0415

        return jwt.encode(claims, config.LIVEKIT_API_SECRET, algorithm="HS256")
    except Exception as exc:
        log.warning("could not sign livekit token: %s", type(exc).__name__)
        return None


def join_token(
    *, room: str, identity: str, name: str, speaker: bool = False, host: bool = False
) -> str | None:
    """A token that lets one person into one room with the right rights."""
    if not available():
        return None
    now = int(time.time())
    return _sign(
        {
            "iss": config.LIVEKIT_API_KEY,
            "sub": identity,
            "name": name[:80],
            "nbf": now - 10,
            "exp": now + TOKEN_TTL_S,
            "video": {
                "room": room,
                "roomJoin": True,
                "canSubscribe": True,
                "canPublish": bool(speaker or host),
                "canPublishSources": ["microphone"] if (speaker or host) else [],
                "canPublishData": bool(speaker or host),
                "roomAdmin": bool(host),
            },
        }
    )


def _admin_token(room: str) -> str | None:
    now = int(time.time())
    return _sign(
        {
            "iss": config.LIVEKIT_API_KEY,
            "nbf": now - 10,
            "exp": now + 300,
            "video": {"roomAdmin": True, "room": room},
        }
    )


def _api(method: str, room: str, body: dict) -> bool:
    if not available():
        return False
    token = _admin_token(room)
    if not token:
        return False
    base = config.LIVEKIT_URL.replace("wss://", "https://").replace("ws://", "http://").rstrip("/")
    r = _http.request(
        "POST",
        f"{base}/twirp/livekit.RoomService/{method}",
        headers={"Authorization": f"Bearer {token}"},
        json=body,
        timeout=10.0,
        retries=1,
    )
    return r.ok


def remove_participant(room: str, identity: str) -> bool:
    return _api("RemoveParticipant", room, {"room": room, "identity": identity})


def end_room(room: str) -> bool:
    return _api("DeleteRoom", room, {"room": room})


def mute_participant(room: str, identity: str, track_sid: str) -> bool:
    return _api(
        "MutePublishedTrack", room, {"room": room, "identity": identity, "track_sid": track_sid, "muted": True}
    )


def update_permission(room: str, identity: str, *, can_publish: bool) -> bool:
    """Bring a listener up to speak (hand raised) or send a speaker back down."""
    return _api(
        "UpdateParticipant",
        room,
        {
            "room": room,
            "identity": identity,
            "permission": {
                "can_subscribe": True,
                "can_publish": can_publish,
                "can_publish_data": can_publish,
                "can_publish_sources": [2] if can_publish else [],  # 2 = MICROPHONE
            },
        },
    )


def list_participants(room: str) -> list[dict] | None:
    if not available():
        return None
    token = _admin_token(room)
    if not token:
        return None
    base = config.LIVEKIT_URL.replace("wss://", "https://").replace("ws://", "http://").rstrip("/")
    r = _http.request(
        "POST",
        f"{base}/twirp/livekit.RoomService/ListParticipants",
        headers={"Authorization": f"Bearer {token}"},
        json={"room": room},
        timeout=10.0,
    )
    if r.ok and isinstance(r.body, dict):
        return r.body.get("participants", [])
    return None
