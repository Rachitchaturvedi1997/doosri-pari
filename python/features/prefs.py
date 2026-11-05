# =============================================================================
# Feature · Preferences, consents, the notification inbox and the heartbeat.
# Plan sections 6.15, 6.16 and 14.
#
#   GET   /me/prefs                    everything on the Settings screen
#   PATCH /me/prefs                    change some of it
#   PUT   /me/consent/{name}           give or withdraw one consent, with the time
#   GET   /inbox                       what the app told this person (the bell)
#   POST  /inbox/read                  mark some or all read
#   GET   /inbox/unread-count
#   POST  /heartbeat                   "the phone is alive" (for 'phone may be off')
#   GET   /cities   /cities/nearest    the city reference (geo.py)
#
# Two rules from the plan are enforced here rather than left to the client:
#   * Only the Safety channel cannot be turned off (6.15). A request to switch it
#     off is refused, not silently ignored, so Settings cannot show a false "off".
#   * A consent is a timestamped fact. Withdrawing one is recorded with the time,
#     and the things that depend on it (location in an alert, health sharing)
#     read it on their next send, not on the next deploy (plan 14).
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime
from typing import Literal
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, select, update

from python import geo
from python.deps import CurrentUser, DbSession
from python.models import CheckInSettings, DeviceHeartbeat, PushInbox, UserPrefs, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.prefs")
router = APIRouter(tags=["prefs"])

CHANNELS = ("safety", "medicines", "pension", "circles", "seva", "practice")
CONSENTS = ("location", "health", "guardians", "analytics")
FINDABLE = ("everyone", "verified", "nobody")
TEXT_SCALES = (1.0, 1.15, 1.3)


# ------------------------------------------------------------------ helpers --
def prefs_for(db, user_id: int) -> UserPrefs:
    row = db.get(UserPrefs, user_id)
    if row is None:
        row = UserPrefs(user_id=user_id)
        db.add(row)
        db.flush()
    return row


def prefs_view(p: UserPrefs, user) -> dict:  # noqa: ANN001
    channels = {c: bool((p.channels or {}).get(c, True)) for c in CHANNELS}
    channels["safety"] = True
    return {
        "channels": channels,
        "digest_time": p.digest_time,
        "quiet_hours": {"start": p.quiet_start, "end": p.quiet_end},
        "simple_mode": p.simple_mode,
        "show_tithi": p.show_tithi,
        "today_hidden": list(p.today_hidden or []),
        "findable_by": p.findable_by,
        "ice_after_sos": p.ice_after_sos,
        "pension_credit_day": p.pension_credit_day,
        "consents": {
            c: {
                "granted": bool((p.consents or {}).get(c, {}).get("granted", False)),
                "at": (p.consents or {}).get(c, {}).get("at"),
            }
            for c in CONSENTS
        },
        "language": user.lang,
        "text_scale": user.text_scale,
        "discoverable": user.discoverable,
        "safety_cannot_be_off": True,
    }


# ------------------------------------------------------------------ schemas --
class PrefsIn(BaseModel):
    channels: dict[str, bool] | None = None
    digest_time: str | None = Field(default=None, pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    quiet_start: int | None = Field(default=None, ge=0, le=23)
    quiet_end: int | None = Field(default=None, ge=0, le=23)
    simple_mode: bool | None = None
    show_tithi: bool | None = None
    findable_by: str | None = None
    ice_after_sos: bool | None = None
    pension_credit_day: int | None = Field(default=None, ge=1, le=31)
    text_scale: float | None = None
    language: str | None = None


class ConsentIn(BaseModel):
    granted: bool


class ReadIn(BaseModel):
    ids: list[int] | None = None
    all: bool = False


class HeartbeatIn(BaseModel):
    battery_pct: int | None = Field(default=None, ge=0, le=100)
    app_version: str | None = Field(default=None, max_length=32)


# ---------------------------------------------------------------- endpoints --
@router.get("/me/prefs")
def get_prefs(user: CurrentUser, db: DbSession):
    p = prefs_for(db, user.id)
    db.commit()
    return prefs_view(p, user)


@router.patch("/me/prefs")
def patch_prefs(body: PrefsIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    p = prefs_for(db, user.id)
    data = body.model_dump(exclude_unset=True, exclude_none=True)
    if not data:
        raise HTTPException(400, "nothing to change")

    if "channels" in data:
        unknown = set(data["channels"]) - set(CHANNELS)
        if unknown:
            raise HTTPException(400, f"unknown channel: {', '.join(sorted(unknown))}")
        if data["channels"].get("safety") is False:
            # Refused loudly. A Settings screen that shows Safety as off while
            # alerts still arrive is a lie, and the reverse is a missed check-in.
            raise HTTPException(400, "Safety notifications cannot be turned off")
        merged = {c: True for c in CHANNELS}
        merged.update(p.channels or {})
        merged.update(data["channels"])
        merged["safety"] = True
        p.channels = merged
    if "findable_by" in data:
        if data["findable_by"] not in FINDABLE:
            raise HTTPException(400, f"findable_by must be one of {', '.join(FINDABLE)}")
        p.findable_by = data["findable_by"]
        user.discoverable = data["findable_by"] != "nobody"
    if "text_scale" in data:
        if data["text_scale"] not in TEXT_SCALES:
            raise HTTPException(400, "text_scale must be 1.0, 1.15 or 1.3")
        user.text_scale = data["text_scale"]
        p.text_scale_set = True
    if "language" in data:
        from python.models import LANGUAGES  # noqa: PLC0415

        if data["language"] not in LANGUAGES:
            raise HTTPException(400, f"language must be one of {', '.join(LANGUAGES)}")
        user.lang = data["language"]

    for field in ("digest_time", "quiet_start", "quiet_end", "simple_mode", "show_tithi",
                  "ice_after_sos", "pension_credit_day"):
        if field in data:
            setattr(p, field, data[field])
    p.updated_at = now()
    db.commit()
    return prefs_view(p, user)


@router.put("/me/consent/{name}")
def set_consent(name: str, body: ConsentIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Give or withdraw one consent (plan 14). Recorded with the time.

    Withdrawing `location` also switches off "share location when I miss a
    check-in" at once, because that setting is the consent in action; leaving it
    on after a withdrawal is the exact defect plan 14 warns about.
    """
    limit_write(request, user.id)
    if name not in CONSENTS:
        raise HTTPException(404, f"no such consent; choose from {', '.join(CONSENTS)}")
    p = prefs_for(db, user.id)
    consents = dict(p.consents or {})
    consents[name] = {"granted": body.granted, "at": now()}
    p.consents = consents

    effects = []
    if name == "location" and not body.granted:
        s = db.get(CheckInSettings, user.id)
        if s is not None and s.share_location:
            s.share_location = False
            effects.append("check-in location sharing switched off")
    if name == "guardians" and not body.granted:
        from python.models import Guardian  # noqa: PLC0415

        for g in db.scalars(select(Guardian).where(Guardian.user_id == user.id)).all():
            perms = dict(g.perms or {})
            for k in ("location", "medicines", "health", "document_safe"):
                perms[k] = False
            g.perms = perms
        effects.append("optional guardian permissions switched off (alerts and SOS stay)")
    db.commit()
    return {"name": name, "granted": body.granted, "at": consents[name]["at"], "effects": effects}


# -------------------------------------------------------------------- inbox --
def inbox_view(r: PushInbox) -> dict:
    return {
        "id": r.id,
        "kind": r.kind,
        "title": r.title,
        "body": r.body,
        "payload": r.payload or {},
        "created_at": r.created_at,
        "read": r.read_at is not None,
    }


@router.get("/inbox")
def list_inbox(user: CurrentUser, db: DbSession, limit: int = 50, before: float | None = None):
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    q = select(PushInbox).where(PushInbox.user_id == user.id, PushInbox.title != "skipped")
    if before is not None:
        q = q.where(PushInbox.created_at < before)
    rows = db.scalars(q.order_by(PushInbox.created_at.desc()).limit(limit)).all()
    return {"items": [inbox_view(r) for r in rows], "next_before": rows[-1].created_at if len(rows) == limit else None}


@router.get("/inbox/unread-count")
def unread_count(user: CurrentUser, db: DbSession):
    n = db.scalar(
        select(func.count(PushInbox.id)).where(
            PushInbox.user_id == user.id, PushInbox.read_at.is_(None), PushInbox.title != "skipped"
        )
    ) or 0
    return {"count": n}


@router.post("/inbox/read")
def mark_read(body: ReadIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    if not body.all and not body.ids:
        raise HTTPException(400, "give ids or all=true")
    q = update(PushInbox).where(PushInbox.user_id == user.id, PushInbox.read_at.is_(None))
    if not body.all:
        q = q.where(PushInbox.id.in_(body.ids or []))
    n = db.execute(q.values(read_at=now())).rowcount or 0
    db.commit()
    return {"marked": n}


# ---------------------------------------------------------------- heartbeat --
@router.post("/heartbeat")
def heartbeat(body: HeartbeatIn, user: CurrentUser, db: DbSession):
    """
    The app says it is alive. No location, nothing else: it exists so an alert can
    say "their phone may be switched off" (plan 6.3) when this has been quiet for
    six hours. Deliberately not rate limited per write - it is a cheap upsert and
    a phone that cannot report it is alive is the case we most need to see.
    """
    row = db.get(DeviceHeartbeat, user.id)
    if row is None:
        row = DeviceHeartbeat(user_id=user.id)
        db.add(row)
    row.at = now()
    row.battery_pct = body.battery_pct
    row.app_version = body.app_version
    db.commit()
    return {"ok": True, "at": row.at}


# --------------------------------------------------------------------- cities --
@router.get("/cities")
def cities(q: str | None = None, limit: int = 20):
    if q:
        found = geo.search(q, limit=min(max(limit, 1), 50))
    else:
        found = list(geo.CITIES[: min(max(limit, 1), 50)])
    return {"cities": [geo.city_view(c) for c in found]}


@router.get("/cities/nearest")
def nearest_city(lat: float, lng: float):
    if not -90 <= lat <= 90 or not -180 <= lng <= 180:
        raise HTTPException(400, "coordinates out of range")
    c = geo.nearest(lat, lng)
    return {"city": geo.city_view(c) if c else None}
