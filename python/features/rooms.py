# =============================================================================
# Feature · Chai pe Charcha, the host's side — plan section 6.7.
#
#   POST /rooms                     make a room (T2 hosts; circle moderators too)
#   GET  /rooms/scheduled           what is coming up in my circles
#   POST /rooms/{id}/start          the host opens today's sitting
#   POST /rooms/{id}/end
#   POST /rooms/{id}/hand           a listener raises a hand
#   POST /rooms/{id}/promote | demote | mute | remove      host controls
#   GET  /rooms/{id}/hands          who is waiting to speak
#   POST /rooms/{id}/recording      (refused until an egress service exists)
#
# features/circles.py already has /rooms/live and /rooms/{id}/join. The media
# transport is LiveKit (python/integrations/livekit.py); every control here is a
# call to it, so the server never carries audio - it decides who may publish.
#
# The plan's rules, kept:
#   * a host, up to 8 speakers, up to 200 listeners; raise hand to speak;
#   * recording is OFF by default and this module cannot turn it on - a sign and
#     every speaker's consent are prerequisites, and there is no egress service
#     wired, so the honest answer to "record this" is "not yet";
#   * 3 valid (actioned) reports against someone's rooms in 30 days means no
#     hosting for 30 days.
# =============================================================================
from __future__ import annotations

import logging
import threading
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from python.deps import CurrentUser, DbSession
from python.features.circles import MAX_LISTENERS, MAX_SPEAKERS, room_view
from python.integrations import livekit
from python.models import (
    TIER_RANK,
    Circle,
    CircleMember,
    Report,
    RoomSession,
    User,
    VoiceRoom,
    now,
)
from python.ratelimit import limit_write
from python.realtime import publish

log = logging.getLogger("doosri.rooms")
router = APIRouter(tags=["rooms"])

HOST_BAN_REPORTS = 3
HOST_BAN_DAYS = 30
_DAYS = ["MO", "TU", "WE", "TH", "FR", "SA", "SU"]

# Raised hands live in memory: they are a queue for a room that lasts an hour,
# and a hand that outlives its sitting is a bug, not a record.
_hands: dict[int, dict[int, float]] = {}
_lock = threading.Lock()


class RoomIn(BaseModel):
    circle_id: int
    title: str = Field(min_length=3, max_length=160)
    schedule_rrule: str | None = Field(default=None, max_length=120)
    timezone: str = "Asia/Kolkata"


class TargetIn(BaseModel):
    user_id: int


# --------------------------------------------------------------- scheduling --
def parse_rrule(rule: str) -> dict:
    parts = dict(p.split("=", 1) for p in rule.strip().split(";") if "=" in p)
    if parts.get("FREQ") not in ("DAILY", "WEEKLY"):
        raise ValueError("FREQ must be DAILY or WEEKLY")
    days = [d for d in parts.get("BYDAY", "").split(",") if d]
    if any(d not in _DAYS for d in days):
        raise ValueError("BYDAY must be MO,TU,WE,TH,FR,SA,SU")
    hour, minute = int(parts.get("BYHOUR", 8)), int(parts.get("BYMINUTE", 0))
    if not (0 <= hour <= 23 and 0 <= minute <= 59):
        raise ValueError("BYHOUR/BYMINUTE out of range")
    return {"freq": parts["FREQ"], "days": days, "hour": hour, "minute": minute}


def next_occurrence(rule: str, after: float, tzname: str = "Asia/Kolkata") -> float | None:
    """The next time the rule fires strictly after `after`, as epoch seconds."""
    try:
        spec = parse_rrule(rule)
        tz = ZoneInfo(tzname)
    except Exception:
        return None
    start = datetime.fromtimestamp(after, tz)
    for offset in range(0, 15):
        day = (start + timedelta(days=offset)).replace(hour=spec["hour"], minute=spec["minute"], second=0, microsecond=0)
        if spec["freq"] == "WEEKLY" and spec["days"] and _DAYS[day.weekday()] not in spec["days"]:
            continue
        if day.timestamp() > after:
            return day.timestamp()
    return None


# ------------------------------------------------------------------ helpers --
def _room(db, room_id: int) -> VoiceRoom:
    r = db.get(VoiceRoom, room_id)
    if r is None:
        raise HTTPException(404, "room not found")
    return r


def _host_only(room: VoiceRoom, user: User) -> None:
    if room.host_id != user.id:
        raise HTTPException(403, "only the host can do that")


def _live_session(db, room: VoiceRoom) -> RoomSession | None:
    return db.scalar(
        select(RoomSession).where(RoomSession.room_id == room.id, RoomSession.ended_at.is_(None))
        .order_by(RoomSession.started_at.desc()).limit(1)
    )


def host_is_banned(db, user_id: int) -> bool:
    """Plan 6.7: three valid reports against your rooms means no hosting for 30 days."""
    since = now() - HOST_BAN_DAYS * 86400
    n = db.scalar(
        select(func.count(Report.id))
        .join(VoiceRoom, VoiceRoom.id == Report.target_id)
        .where(Report.target_type == "voice_room", Report.status == "actioned",
               Report.actioned_at >= since, VoiceRoom.host_id == user_id)
    ) or 0
    return n >= HOST_BAN_REPORTS


def _identity(user_id: int) -> str:
    return f"u{user_id}"


# ---------------------------------------------------------------- endpoints --
@router.post("/rooms")
def create_room(body: RoomIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    circle = db.get(Circle, body.circle_id)
    if circle is None or circle.is_archived:
        raise HTTPException(404, "circle not found")
    m = db.scalar(select(CircleMember).where(CircleMember.circle_id == circle.id, CircleMember.user_id == user.id))
    moderator = m is not None and m.pending_at is None and m.role in ("moderator", "owner")
    if not (user.tier_rank >= TIER_RANK["T2"] or moderator):
        raise HTTPException(403, "hosting a room needs a T2 account or being a moderator of this circle")
    if host_is_banned(db, user.id):
        raise HTTPException(403, "hosting is paused for now after reports on your rooms")
    nxt = None
    if body.schedule_rrule:
        try:
            parse_rrule(body.schedule_rrule)
        except ValueError as exc:
            raise HTTPException(400, f"schedule not understood: {exc}") from None
        nxt = next_occurrence(body.schedule_rrule, now(), body.timezone)
    room = VoiceRoom(circle_id=circle.id, title=body.title.strip(), host_id=user.id,
                     schedule_rrule=body.schedule_rrule, next_session_at=nxt, recording=False)
    db.add(room)
    db.commit()
    return room_view(room)


@router.get("/rooms/scheduled")
def scheduled(user: CurrentUser, db: DbSession):
    mine = {c for (c,) in db.execute(select(CircleMember.circle_id).where(CircleMember.user_id == user.id, CircleMember.pending_at.is_(None))).all()}
    if not mine:
        return {"rooms": []}
    rows = db.scalars(
        select(VoiceRoom).where(VoiceRoom.circle_id.in_(mine), VoiceRoom.next_session_at.isnot(None))
        .order_by(VoiceRoom.next_session_at).limit(30)
    ).all()
    return {"rooms": [room_view(r) for r in rows]}


@router.post("/rooms/{room_id}/start")
def start_room(room_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _host_only(room, user)
    if host_is_banned(db, user.id):
        raise HTTPException(403, "hosting is paused for now after reports on your rooms")
    session = _live_session(db, room)
    if session is None:
        session = RoomSession(room_id=room.id, started_at=now())
        db.add(session)
        db.flush()
    room.livekit_room = f"dp-{room.id}-{session.id}"
    if room.schedule_rrule:
        room.next_session_at = next_occurrence(room.schedule_rrule, now() + 60)
    db.commit()
    token = livekit.join_token(room=room.livekit_room, identity=_identity(user.id),
                               name=user.address_as or user.name or "Host", speaker=True, host=True)
    return {"room": room_view(room, session), "session_id": session.id, "token": token,
            "url": livekit_url(), "recording": False}


def livekit_url() -> str | None:
    from python import config  # noqa: PLC0415

    return config.LIVEKIT_URL or None


@router.post("/rooms/{room_id}/end")
def end_room(room_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _host_only(room, user)
    session = _live_session(db, room)
    if session is None:
        return {"ended": False}
    session.ended_at = now()
    if room.livekit_room:
        livekit.end_room(room.livekit_room)
    with _lock:
        _hands.pop(room.id, None)
    db.commit()
    return {"ended": True, "peak_listeners": session.peak_listeners}


@router.post("/rooms/{room_id}/hand")
def raise_hand(room_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    if _live_session(db, room) is None:
        raise HTTPException(409, "this room is not running right now")
    with _lock:
        hands = _hands.setdefault(room.id, {})
        if user.id in hands:
            hands.pop(user.id)          # tap again to lower it
            raised = False
        else:
            hands[user.id] = now()
            raised = True
    publish(room.host_id, {"type": "hand", "room_id": room.id, "user_id": user.id, "raised": raised})
    return {"raised": raised}


@router.get("/rooms/{room_id}/hands")
def hands(room_id: int, user: CurrentUser, db: DbSession):
    room = _room(db, room_id)
    _host_only(room, user)
    with _lock:
        waiting = sorted(_hands.get(room.id, {}).items(), key=lambda kv: kv[1])
    people = {u.id: u for u in db.scalars(select(User).where(User.id.in_([uid for uid, _ in waiting]))).all()} if waiting else {}
    return {"hands": [{"user_id": uid, "name": (people[uid].address_as or people[uid].name) if uid in people else None, "at": at}
                      for uid, at in waiting]}


def _control(room: VoiceRoom, user: User, db) -> None:
    _host_only(room, user)
    if _live_session(db, room) is None or not room.livekit_room:
        raise HTTPException(409, "this room is not running right now")


@router.post("/rooms/{room_id}/promote")
def promote(room_id: int, body: TargetIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _control(room, user, db)
    parts = livekit.list_participants(room.livekit_room)
    if parts is not None:
        speakers = sum(1 for p in parts if (p.get("permission") or {}).get("canPublish"))
        if speakers >= MAX_SPEAKERS:
            raise HTTPException(409, f"a room holds {MAX_SPEAKERS} speakers; ask one to step down first")
    ok = livekit.update_permission(room.livekit_room, _identity(body.user_id), can_publish=True)
    with _lock:
        _hands.get(room.id, {}).pop(body.user_id, None)
    publish(body.user_id, {"type": "promoted", "room_id": room.id})
    return {"promoted": ok}


@router.post("/rooms/{room_id}/demote")
def demote(room_id: int, body: TargetIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _control(room, user, db)
    ok = livekit.update_permission(room.livekit_room, _identity(body.user_id), can_publish=False)
    publish(body.user_id, {"type": "demoted", "room_id": room.id})
    return {"demoted": ok}


@router.post("/rooms/{room_id}/mute")
def mute(room_id: int, body: TargetIn, request: Request, user: CurrentUser, db: DbSession):
    """Mute a speaker. Done as a demotion: a muted-but-publishing seat can unmute itself, a demoted one cannot."""
    limit_write(request, user.id)
    room = _room(db, room_id)
    _control(room, user, db)
    ok = livekit.update_permission(room.livekit_room, _identity(body.user_id), can_publish=False)
    return {"muted": ok}


@router.post("/rooms/{room_id}/remove")
def remove(room_id: int, body: TargetIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _control(room, user, db)
    if body.user_id == room.host_id:
        raise HTTPException(400, "the host cannot be removed")
    ok = livekit.remove_participant(room.livekit_room, _identity(body.user_id))
    return {"removed": ok}


@router.post("/rooms/{room_id}/recording")
def recording(room_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    room = _room(db, room_id)
    _host_only(room, user)
    raise HTTPException(
        501,
        "Recording is not available yet. When it is, the room will show a visible sign and "
        "every speaker will be asked for consent first.",
    )
