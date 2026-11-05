# =============================================================================
# Feature · Daily check-in and SOS — plan section 6.3.
#
#   GET  /checkin/settings          the user's window and permissions
#   PUT  /checkin/settings
#   GET  /checkin/today             today's row and where it has got to
#   POST /checkin                   record a check-in
#   GET  /checkin/history
#   POST /checkin/pause             travel or hospital, 1–30 days
#   POST /guardian/ack/{id}         a guardian says "I've reached them"
#
# The stage machine is not implemented here. It lives in
# shared/feature-checkin as pure Kotlin so the phone and the server cannot
# disagree; python/escalation.py is the server-side mirror and this module is
# only the HTTP surface over it. Plan section 6.3 is explicit that escalation
# runs on the server, because neither Android nor iOS guarantees exact
# background timing.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from python import config
from python.deps import CurrentUser, get_db
from python.dispatch import make_dispatcher, notify_guardians
from python.escalation import Escalator, local_day, parse_window_start
from python.models import (
    METHOD_IN_PERSON,
    METHOD_IVR,
    METHOD_PASSIVE,
    METHOD_TAPPED,
    TERMINAL_STAGES,
    CheckIn,
    CheckInSettings,
    Guardian,
    User,
)

log = logging.getLogger("doosri.checkin")
router = APIRouter(tags=["checkin"])

VALID_METHODS = (METHOD_TAPPED, METHOD_PASSIVE, METHOD_IVR, METHOD_IN_PERSON)


# ------------------------------------------------------------------ schemas --
class SettingsIn(BaseModel):
    window_start: str = Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    window_mins: int = Field(ge=1, le=24 * 60, default=180)
    passive: bool = False
    share_location: bool = False


class CheckInIn(BaseModel):
    method: str = Field(default=METHOD_TAPPED)
    # Only stored when the user has share_location on.
    lat: float | None = None
    lng: float | None = None
    accuracy: float | None = None


class PauseIn(BaseModel):
    days: int = Field(ge=config.PAUSE_MIN_DAYS, le=config.PAUSE_MAX_DAYS)


# ------------------------------------------------------------------ helpers --
def settings_for(db: Session, user: User) -> CheckInSettings:
    s = user.settings
    if s is None:
        s = CheckInSettings(user_id=user.id)
        db.add(s)
        db.flush()
    return s


def view(row: CheckIn) -> dict:
    return {
        "day": row.day,
        "stage": row.stage,
        "checked_at": row.checked_at,
        "method": row.method,
        "resolved_at": row.resolved_at,
        "events": row.events or [],
    }


def settings_view(s: CheckInSettings) -> dict:
    return {
        "window_start": s.window_start,
        "window_mins": s.window_mins,
        "passive": s.passive,
        "share_location": s.share_location,
        "paused_until": s.paused_until,
    }


# ---------------------------------------------------------------- endpoints --
@router.get("/checkin/settings")
def get_settings(user: CurrentUser, db: Session = Depends(get_db)):
    return settings_view(settings_for(db, user))


@router.put("/checkin/settings")
def put_settings(body: SettingsIn, user: CurrentUser, db: Session = Depends(get_db)):
    s = settings_for(db, user)
    s.window_start = body.window_start
    s.window_mins = body.window_mins
    s.passive = body.passive
    s.share_location = body.share_location
    db.commit()
    return settings_view(s)


@router.get("/checkin/today")
def checkin_today(user: CurrentUser, db: Session = Depends(get_db)):
    """Today's row, created on first read so the app always has something to show."""
    esc = Escalator(db, user, dispatch=make_dispatcher(db, user))
    return view(esc.open_today(local_day(user)))


@router.post("/checkin")
def record_checkin(body: CheckInIn, user: CurrentUser, db: Session = Depends(get_db)):
    """
    A check-in from a tap, a passive signal, or the IVR.

    A late check-in — one that lands after guardians have already been told —
    still lands, and still notifies. Plan section 6.3.
    """
    if body.method not in VALID_METHODS:
        raise HTTPException(400, f"method must be one of {', '.join(VALID_METHODS)}")
    if body.lat is not None and not -90 <= body.lat <= 90:
        raise HTTPException(400, "lat out of range")
    if body.lng is not None and not -180 <= body.lng <= 180:
        raise HTTPException(400, "lng out of range")

    esc = Escalator(db, user, dispatch=make_dispatcher(db, user))
    row = esc.open_today(local_day(user))
    row = esc.check_in(
        row, method=body.method, lat=body.lat, lng=body.lng, accuracy=body.accuracy
    )
    db.commit()
    return view(row)


@router.post("/checkin/pause")
def pause_checkin(body: PauseIn, user: CurrentUser, db: Session = Depends(get_db)):
    """Travel or hospital. Guardians are told by the notification job."""
    s = settings_for(db, user)
    until = datetime.now(timezone.utc) + timedelta(days=body.days)
    s.paused_until = until.timestamp()
    db.commit()
    # Plan 6.3: guardians are told about a pause, or a fortnight in hospital
    # reads as silence. Best effort; a vendor failure must not undo the pause.
    told = 0
    try:
        told = notify_guardians(
            db, user, kind=f"paused{int(s.paused_until)}",
            title_key="paused_title", body_key="paused_body", days=body.days,
        )
    except Exception:
        log.exception("could not tell guardians about pause for user %s", user.id)
    return {"paused_until": s.paused_until, "days": body.days, "guardians_told": told}


@router.get("/checkin/history")
def checkin_history(user: CurrentUser, db: Session = Depends(get_db), days: int = 30):
    if not 1 <= days <= 365:
        raise HTTPException(400, "days must be 1..365")
    rows = db.scalars(
        select(CheckIn)
        .where(CheckIn.user_id == user.id)
        .order_by(CheckIn.day.desc())
        .limit(days)
    ).all()
    return [view(r) for r in rows]


@router.post("/guardian/ack/{checkin_id}")
def guardian_ack(checkin_id: int, user: CurrentUser, db: Session = Depends(get_db)):
    """
    A guardian tapping "I've reached them" closes the alert.

    Two guards: the caller must actually be an accepted guardian of this user,
    and the alert must still be open.
    """
    row = db.get(CheckIn, checkin_id)
    if row is None:
        raise HTTPException(404, "check-in not found")
    if row.stage in TERMINAL_STAGES:
        raise HTTPException(409, f"already {row.stage}")

    if user.id != row.user_id:
        is_guardian = db.scalar(
            select(Guardian).where(
                Guardian.user_id == row.user_id,
                Guardian.guardian_user_id == user.id,
                Guardian.accepted_at.isnot(None),
            )
        )
        if is_guardian is None:
            raise HTTPException(403, "not an accepted guardian of this user")

    owner = db.get(User, row.user_id)
    row = Escalator(db, owner, dispatch=make_dispatcher(db, owner)).resolve(
        row, resolved_by=user.id, note="reached by guardian"
    )
    db.commit()
    return view(row)
