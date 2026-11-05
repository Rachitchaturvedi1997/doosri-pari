# =============================================================================
# Feature · Teerth yatra planner — plan section 7.2 item 10.
#
#   GET    /yatra/places                    senior-friendly pilgrimage information
#   GET    /yatra/places/{slug}
#   POST   /yatra/plans                     a trip, with a checklist written for it
#   GET    /yatra/plans
#   PATCH  /yatra/plans/{id}                tick things off, change the dates
#   POST   /yatra/plans/{id}/start          pause check-ins and tell family, if chosen
#   DELETE /yatra/plans/{id}
#
# What the planner never does: book anything (it links to the official booking
# site the place row names), or claim a place is accessible beyond what its
# sourced row says - accessibility is shown as data with its source and the words
# "check before you travel". Trip sharing sends a family member the place and the
# dates and nothing else; location during the trip stays governed by the
# person's SOS/check-in location consent, never by this feature.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import or_, select

from python import config
from python.deps import CurrentUser, DbSession
from python.dispatch import accepted_guardians, notify_guardians
from python.features.checkin import settings_for
from python.models import SPIRITUAL_PATHS, CheckInPause, YatraPlace, YatraPlan, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.yatra")
router = APIRouter(tags=["yatra"])

MAX_PLANS = 20


class PlanIn(BaseModel):
    place_id: int | None = None
    title: str = Field(min_length=2, max_length=160)
    starts_on: float | None = None
    ends_on: float | None = None
    pause_checkin: bool = False
    share_with_family: bool = False


class ChecklistItem(BaseModel):
    text: str = Field(min_length=1, max_length=160)
    done: bool = False
    group: str = Field(default="other", max_length=24)


class PlanPatch(BaseModel):
    title: str | None = Field(default=None, min_length=2, max_length=160)
    starts_on: float | None = None
    ends_on: float | None = None
    pause_checkin: bool | None = None
    share_with_family: bool | None = None
    checklist: list[ChecklistItem] | None = Field(default=None, max_length=60)
    status: str | None = None


def default_checklist(days: int, place: YatraPlace | None) -> list[dict]:
    """
    What to carry, written for older travellers (plan 7.2): medicines, papers,
    contacts, comfort. The medicine line is counted in days because "enough
    tablets" is the item people get wrong.
    """
    n = max(days, 1)
    items = [
        ("medicines", f"Medicines for {n + 3} days ({n} days plus 3 spare), in their original strips"),
        ("medicines", "A written list of your medicines and doses, and your doctor's number"),
        ("medicines", "BP and sugar monitor, if you use one"),
        ("documents", "A photo ID and a copy of your pension ID"),
        ("documents", "Health card (CGHS / ECHS / insurance) and its helpline number"),
        ("documents", "Tickets and booking confirmations, on paper as well as on the phone"),
        ("contacts", "Family contacts written on paper, in case the phone is lost or dead"),
        ("contacts", "Turn on Daily Check-in for the trip, or pause it and tell your family"),
        ("comfort", "Comfortable walking shoes and a walking stick or support, if you use one"),
        ("comfort", "A water bottle, light snacks, a cap or umbrella"),
        ("comfort", "Phone charger and a power bank"),
    ]
    if place is not None:
        acc = place.accessibility or {}
        if acc.get("steps") in ("many", "some"):
            items.append(("comfort", "There are steps at this place. Ask about a palki, doli or lift before you go"))
        if acc.get("wheelchair"):
            items.append(("comfort", "Wheelchair access is reported here. Confirm with the temple office before you travel"))
        if place.best_season:
            items.append(("planning", f"Best season reported: {place.best_season}. Check the weather before you go"))
        if place.booking_url:
            items.append(("planning", "Book only on the official site linked in this plan"))
    return [{"text": t, "done": False, "group": g} for g, t in items]


def _place_view(p: YatraPlace) -> dict:
    return {
        "id": p.id, "slug": p.slug, "name": p.name, "state_code": p.state_code, "path": p.path,
        "accessibility": p.accessibility or {}, "best_season": p.best_season, "summary": p.summary,
        "official_url": p.official_url, "booking_url": p.booking_url, "source": p.source_ref,
        "accessibility_note": "Reported information. Please confirm with the place before you travel.",
    }


def _plan_view(pl: YatraPlan, place: YatraPlace | None) -> dict:
    return {
        "id": pl.id, "title": pl.title, "place": _place_view(place) if place else None,
        "starts_on": pl.starts_on, "ends_on": pl.ends_on, "checklist": pl.checklist or [],
        "pause_checkin": pl.pause_checkin, "share_with_family": pl.share_with_family,
        "status": pl.status, "progress": _progress(pl),
    }


def _progress(pl: YatraPlan) -> dict:
    items = pl.checklist or []
    return {"done": sum(1 for i in items if i.get("done")), "total": len(items)}


@router.get("/yatra/places")
def places(user: CurrentUser, db: DbSession, path: str | None = None, state: str | None = None, q: str | None = None):
    if path is not None and path not in SPIRITUAL_PATHS:
        raise HTTPException(400, f"path must be one of {', '.join(SPIRITUAL_PATHS)}")
    stmt = select(YatraPlace).where(YatraPlace.is_active.is_(True))
    if path:
        stmt = stmt.where(or_(YatraPlace.path == path, YatraPlace.path.is_(None)))
    if state:
        stmt = stmt.where(YatraPlace.state_code == state.upper())
    if q:
        stmt = stmt.where(YatraPlace.name.contains(q))
    rows = db.scalars(stmt.order_by(YatraPlace.name).limit(100)).all()
    return {"places": [_place_view(p) for p in rows]}


@router.get("/yatra/places/{slug}")
def place(slug: str, user: CurrentUser, db: DbSession):
    p = db.scalar(select(YatraPlace).where(YatraPlace.slug == slug, YatraPlace.is_active.is_(True)))
    if p is None:
        raise HTTPException(404, "place not found")
    return _place_view(p)


@router.post("/yatra/plans")
def create_plan(body: PlanIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    p = db.get(YatraPlace, body.place_id) if body.place_id else None
    if body.place_id and p is None:
        raise HTTPException(404, "place not found")
    if body.starts_on and body.ends_on and body.ends_on < body.starts_on:
        raise HTTPException(400, "the trip cannot end before it starts")
    count = len(db.scalars(select(YatraPlan.id).where(YatraPlan.user_id == user.id, YatraPlan.status != "cancelled")).all())
    if count >= MAX_PLANS:
        raise HTTPException(409, "you have twenty trips planned; remove one first")
    days = int(((body.ends_on - body.starts_on) / 86400) + 1) if (body.starts_on and body.ends_on) else 3
    pl = YatraPlan(
        user_id=user.id, place_id=body.place_id, title=body.title.strip(), starts_on=body.starts_on,
        ends_on=body.ends_on, pause_checkin=body.pause_checkin, share_with_family=body.share_with_family,
        checklist=default_checklist(days, p),
    )
    db.add(pl)
    db.commit()
    return _plan_view(pl, p)


@router.get("/yatra/plans")
def plans(user: CurrentUser, db: DbSession):
    rows = db.scalars(select(YatraPlan).where(YatraPlan.user_id == user.id, YatraPlan.status != "cancelled")
                      .order_by(YatraPlan.starts_on.nullslast())).all()
    places_ = {x.id: x for x in db.scalars(select(YatraPlace).where(YatraPlace.id.in_([r.place_id for r in rows if r.place_id]))).all()} if rows else {}
    return {"plans": [_plan_view(r, places_.get(r.place_id)) for r in rows]}


def _own(db, user, plan_id: int) -> YatraPlan:
    pl = db.get(YatraPlan, plan_id)
    if pl is None or pl.user_id != user.id:
        raise HTTPException(404, "trip not found")
    return pl


@router.patch("/yatra/plans/{plan_id}")
def patch_plan(plan_id: int, body: PlanPatch, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    pl = _own(db, user, plan_id)
    data = body.model_dump(exclude_unset=True)
    if "status" in data and data["status"] not in ("planned", "started", "done", "cancelled"):
        raise HTTPException(400, "status must be planned, started, done or cancelled")
    for k, v in data.items():
        if k == "checklist":
            pl.checklist = [dict(i) for i in (v or [])]
        elif v is not None:
            setattr(pl, k, v)
    db.commit()
    place_ = db.get(YatraPlace, pl.place_id) if pl.place_id else None
    return _plan_view(pl, place_)


@router.post("/yatra/plans/{plan_id}/start")
def start_plan(plan_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    Setting off. If the person chose it: pause the daily check-in for the length
    of the trip (never more than the 30-day maximum) and tell the guardians, and
    tell them where and when. Nothing about live location is shared here.
    """
    limit_write(request, user.id)
    pl = _own(db, user, plan_id)
    if pl.status == "cancelled":
        raise HTTPException(409, "this trip was cancelled")
    place_ = db.get(YatraPlace, pl.place_id) if pl.place_id else None
    out: dict = {"paused_until": None, "family_told": 0}

    if pl.pause_checkin:
        tz = ZoneInfo(user.time_zone or "UTC")
        end = pl.ends_on or (now() + 3 * 86400)
        until = datetime.fromtimestamp(end, tz).replace(hour=23, minute=59) + timedelta(hours=8)
        cap = datetime.fromtimestamp(now(), tz) + timedelta(days=config.PAUSE_MAX_DAYS)
        until = min(until, cap)
        s = settings_for(db, user)
        s.paused_until = until.timestamp()
        db.add(CheckInPause(user_id=user.id, starts_at=now(), ends_at=until.timestamp(), reason="yatra", guardians_notified=True))
        out["paused_until"] = s.paused_until

    if pl.share_with_family and accepted_guardians(db, user.id, "alerts"):
        # Reuse the pause wording; the trip dates are in the title the family reads.
        out["family_told"] = notify_guardians(
            db, user, kind=f"yatra{pl.id}", title_key="paused_title", body_key="paused_body",
            days=max(int(((pl.ends_on or now()) - now()) / 86400) + 1, 1),
        )
    pl.status = "started"
    db.commit()
    return {**_plan_view(pl, place_), **out}


@router.delete("/yatra/plans/{plan_id}")
def delete_plan(plan_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    pl = _own(db, user, plan_id)
    pl.status = "cancelled"
    db.commit()
    return {"cancelled": plan_id}
