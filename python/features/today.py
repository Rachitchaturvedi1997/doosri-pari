# =============================================================================
# Feature · Today (Aaj) — plan sections 4.5, 6.2 and 13.
#
#   GET  /today/feed            everything the screen needs, in one request
#   POST /today/opened          a card was tapped (feeds the ordering rule)
#   POST /today/hidden          "Edit Today": hide or show a card
#   POST /today/credit_answer   "Did your pension credit?" yes / no
#
# THE BOUNDARY (plan 10, 14). This feed carries a DR RATE - a published table
# that is the same fact for every pensioner on a service - and the DAY OF THE
# MONTH the user says their pension credits. It never carries a rupee amount that
# belongs to the user. There is no such column on the server and no code path that
# could put one in this response; the clients keep the figures on the phone. If a
# future change wants a user's pension in this file, that change is the bug.
#
# The wire shape is the one the apps already decode (android TodayModels.kt,
# ios TodayModels.swift): snake_case keys, epoch-second times, and strings that
# are already formatted for the reader ("7:00 pm", "1 November") so a Hindi user
# reads "1 नवंबर" without the client knowing the month names of ten languages.
#
# One card failing must not blank the screen. Every section is built in its own
# try/except and a failed one comes back null/empty with the reason logged, so a
# broken weather service never hides the check-in button's neighbours.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import select

from python import geo, matching
from python.deps import CurrentUser, DbSession
from python.escalation import local_day
from python.features.prefs import prefs_for
from python.models import (
    Booking,
    Circle,
    CircleMember,
    DrRate,
    Festival,
    KendraProgramme,
    Listing,
    NewsItem,
    Post,
    Rating,
    Reminder,
    RoomSession,
    SahayakDoc,
    ScamAlert,
    User,
    VoiceRoom,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.today")
router = APIRouter(tags=["today"])

SECTIONS = (
    "check_in", "pension", "reminders", "practice", "circles",
    "live_room", "seva", "scam", "weather", "news",
)
OPEN_WINDOW_DAYS = 14

_WEEKDAYS = {
    "en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"],
    "hi": ["सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार"],
}
_MONTHS = {
    "en": ["January", "February", "March", "April", "May", "June", "July", "August",
           "September", "October", "November", "December"],
    "hi": ["जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त",
           "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"],
}
_REMINDER_KINDS = {"life_certificate", "tax", "cghs_renewal", "medical",
                   "commutation_restoration", "additional_pension", "other"}


# ------------------------------------------------------------------ helpers --
def _tz(user: User) -> ZoneInfo:
    try:
        return ZoneInfo(user.time_zone or "UTC")
    except Exception:
        return ZoneInfo("UTC")


def date_label(user: User, at: float) -> str:
    d = datetime.fromtimestamp(at, _tz(user))
    lang = user.lang if user.lang in _WEEKDAYS else "en"
    return f"{_WEEKDAYS[lang][d.weekday()]}, {d.day} {_MONTHS[lang][d.month - 1]}"


def when_label(user: User, at: float) -> str:
    """'7:00 pm' for today, '1 November' for another day - in the user's language."""
    tz = _tz(user)
    d = datetime.fromtimestamp(at, tz)
    today = datetime.fromtimestamp(now(), tz).date()
    lang = user.lang if user.lang in _MONTHS else "en"
    if d.date() == today:
        return d.strftime("%I:%M %p").lstrip("0").lower()
    return f"{d.day} {_MONTHS[lang][d.month - 1]}"


def _name(u: User | None) -> str:
    return (u.address_as or u.name or "A member") if u else "A member"


def _first_line(text: str | None, width: int = 80) -> str:
    t = " ".join((text or "").split())
    return t if len(t) <= width else t[: width - 1].rstrip() + "…"


# ----------------------------------------------------------------- sections --
def _dr(db, user: User) -> dict | None:
    at = now()
    for service in (user.service_type, "central_civil"):
        if not service:
            continue
        row = db.scalars(
            select(DrRate)
            .where(DrRate.service_type == service, DrRate.effective_from <= at)
            .order_by(DrRate.effective_from.desc())
            .limit(1)
        ).first()
        if row is None:
            continue
        doc = db.get(SahayakDoc, row.source_doc_id) if row.source_doc_id else None
        if doc is not None:
            note = ", ".join(x for x in (doc.issuer, f"OM {doc.doc_no}" if doc.doc_no else None, doc.title) if x)
        else:
            note = "Set by the Doosri Pari team. Confirm with your own pension order."
        if service != user.service_type:
            note += f" (rate shown is for {service.replace('_', ' ')})"
        return {"rate_percent": row.rate_percent, "effective_from": row.effective_from, "source_note": note[:300]}
    return None


def _reminders(db, user: User) -> list[dict]:
    at = now()
    rows = db.scalars(
        select(Reminder)
        .where(Reminder.user_id == user.id, Reminder.done_at.is_(None),
               Reminder.kind != "check_in", Reminder.kind != "digest",
               Reminder.next_at <= at + 7 * 86400)
        .order_by(Reminder.next_at)
        .limit(8)
    ).all()
    out = []
    for r in rows:
        sub = (r.schedule or {}).get("subkind")
        if r.kind == "life_certificate":
            kind = "life_certificate"
        elif sub in _REMINDER_KINDS:
            kind = sub
        elif sub:
            kind = "other"          # a grievance follow-up, for instance
        elif r.kind in ("medicine", "appointment"):
            kind = "medical"
        else:
            kind = "other"
        due = (r.schedule or {}).get("at") or r.next_at
        out.append({
            "id": str(r.id),
            "title": r.title or r.kind.replace("_", " ").title(),
            "when_label": when_label(user, float(due)),
            "kind": kind,
        })
    return out


def _practice(db, user: User) -> dict | None:
    from python.features import kendra  # noqa: PLC0415 - avoids an import cycle at load

    profile = kendra.profile_for(db, user)
    check = kendra.latest_mobility(db, user)
    gate = "fall_risk" if (check is not None and check.is_at_fall_risk) else None
    programmes = db.scalars(
        select(KendraProgramme).where(KendraProgramme.is_active.is_(True)).order_by(KendraProgramme.id)
    ).all()
    tz = _tz(user)
    local = datetime.fromtimestamp(now(), tz)
    evening = local.hour >= 17
    eligible = []
    for p in programmes:
        if p.kind not in ("chair_yoga", "mobility", "pranayama", "meditation"):
            continue
        if kendra.screen_programme(db, profile, p, gate)["blocked"]:
            continue
        eligible.append(p)
    if not eligible:
        return None
    if evening:
        wind = [p for p in eligible if "evening" in p.slug or "wind" in p.slug]
        if wind:
            eligible = wind
    pick = eligible[local.timetuple().tm_yday % len(eligible)]
    return {
        "id": pick.slug,
        "title": pick.title,
        "minutes": pick.total_minutes,
        "subtitle": _first_line(pick.description, 70) or "A gentle practice for today.",
    }


def _circles(db, user: User) -> list[dict]:
    rows = db.execute(
        select(Post, Circle)
        .join(CircleMember, CircleMember.circle_id == Post.circle_id)
        .join(Circle, Circle.id == Post.circle_id)
        .where(CircleMember.user_id == user.id, CircleMember.muted.is_(False),
               CircleMember.pending_at.is_(None), Post.hidden.is_(False),
               Post.author_id != user.id, Post.created_at > now() - 3 * 86400)
        .order_by(Post.created_at.desc())
        .limit(3)
    ).all()
    authors = {u.id: u for u in db.scalars(select(User).where(User.id.in_({p.author_id for p, _ in rows}))).all()} if rows else {}
    return [
        {
            "id": str(p.id),
            "circle": c.name,
            "line": f"{_name(authors.get(p.author_id))}: {_first_line(p.text or p.transcript or 'shared a photo', 70)}",
            "kind": "post",
        }
        for p, c in rows
    ]


def _news(db, user: User) -> tuple[dict | None, list[dict]]:
    rows = db.scalars(
        select(NewsItem)
        .where(NewsItem.is_active.is_(True), NewsItem.language.in_((user.lang or "en", "en")))
        .order_by(NewsItem.published_at.desc())
        .limit(5)
    ).all()
    items = [
        {"id": str(n.id), "headline": n.title, "source": n.publisher, "published_at": n.published_at}
        for n in rows
    ]
    headline = items[0] if items else None
    return headline, items[1:4] if headline else []


def _weather(user: User) -> dict | None:
    from python.integrations import weather  # noqa: PLC0415

    lat, lng, city = user.lat, user.lng, None
    if lat is None or lng is None:
        c = geo.get(user.city_id)
        if c is None:
            return None
        lat, lng, city = c.lat, c.lng, c
    else:
        city = geo.get(user.city_id) or geo.nearest(lat, lng)
    w = weather.current(lat, lng)
    if not w or w.get("temp_c") is None:
        return None
    return {
        "city": city.name if city else "Your area",
        "summary": w["summary"],
        "min_c": int(round(w["low_c"] if w.get("low_c") is not None else w["temp_c"])),
        "max_c": int(round(w["high_c"] if w.get("high_c") is not None else w["temp_c"])),
        "air_quality": (f"{w['aqi_band']} ({int(w['aqi'])})" if w.get("aqi") is not None else None),
        "observed_at": now(),
    }


def _scam(db, user: User) -> dict | None:
    a = db.scalars(
        select(ScamAlert)
        .where(ScamAlert.is_active.is_(True), ScamAlert.severity == "high",
               ScamAlert.language.in_((user.lang or "en", "en")),
               ScamAlert.created_at > now() - 14 * 86400)
        .order_by(ScamAlert.created_at.desc())
        .limit(1)
    ).first()
    return {"id": str(a.id), "title": a.title, "body": _first_line(a.body, 160)} if a else None


def _seva(db, user: User) -> dict | None:
    rows = db.scalars(
        select(Listing)
        .where(Listing.status == "open", Listing.owner_id != user.id, Listing.type.in_(("mentor", "skill", "help", "space")))
        .order_by(Listing.created_at.desc())
        .limit(60)
    ).all()
    if not rows:
        return None
    words = " ".join(filter(None, [user.cadre or ""])).lower().split()
    seeker = matching.Seeker(
        tags={w for w in words if len(w) > 3}, langs={user.lang or "hi"}, lat=user.lat, lng=user.lng
    )
    best, best_score = None, 0.0
    for l in rows:
        offer = matching.Offer(
            tags=set(l.tags or []), langs=set(l.langs or []), lat=l.lat, lng=l.lng,
            radius_km=l.radius_km, online=l.lat is None,
        )
        sc = matching.score(seeker, offer)
        if sc > best_score:
            best, best_score = (l, offer), sc
    if best is None or best_score < 0.5:
        return None
    listing, offer = best
    return {"id": str(listing.id), "title": listing.title, "why": matching.explain(seeker, offer)}


def _live_room(db, user: User) -> dict | None:
    mine = {
        m.circle_id for m in db.scalars(
            select(CircleMember).where(CircleMember.user_id == user.id, CircleMember.pending_at.is_(None))
        ).all()
    }
    rows = db.execute(
        select(VoiceRoom, RoomSession, Circle)
        .join(RoomSession, RoomSession.room_id == VoiceRoom.id)
        .join(Circle, Circle.id == VoiceRoom.circle_id)
        .where(RoomSession.ended_at.is_(None), RoomSession.started_at >= now() - 6 * 3600,
               Circle.is_archived.is_(False))
    ).all()
    for room, session, circle in rows:
        if room.circle_id in mine or circle.type == "voice_only":
            host = db.get(User, room.host_id)
            return {"id": str(room.id), "title": room.title, "host": _name(host), "listeners": session.peak_listeners}
    return None


def _vrat(db, user: User, prefs) -> str | None:  # noqa: ANN001
    if not prefs.show_tithi:
        return None
    tz = _tz(user)
    today = datetime.fromtimestamp(now(), tz).date()
    import calendar as cal

    start = cal.timegm(today.timetuple())
    region = user.spiritual_state.festivals_region if user.spiritual_state else None
    f = db.scalars(
        select(Festival)
        .where(Festival.starts_on >= start, Festival.starts_on < start + 86400)
        .order_by(Festival.kind)
    ).all()
    for row in f:
        if row.region is None or region is None or row.region == region:
            return row.name
    return None


def _opens(prefs) -> tuple[dict, dict]:  # noqa: ANN001
    cutoff = now() - OPEN_WINDOW_DAYS * 86400
    opens, last = {}, {}
    for section, stamps in (prefs.today_opens or {}).items():
        if section not in SECTIONS:
            continue
        opens[section] = sum(1 for t in stamps if t >= cutoff)
        last[section] = max(stamps) if stamps else None
    return opens, {k: v for k, v in last.items() if v is not None}


def safe(name: str, fn, default: Any = None):  # noqa: ANN001
    try:
        return fn()
    except Exception:
        log.exception("today section %s failed", name)
        return default


# ---------------------------------------------------------------- endpoints --
@router.get("/today/feed")
def today_feed(user: CurrentUser, db: DbSession):
    prefs = prefs_for(db, user.id)
    at = now()
    headline, news = safe("news", lambda: _news(db, user), (None, []))
    opens, last = _opens(prefs)
    hidden = set(prefs.today_hidden or [])

    feed = {
        "address_as": user.address_as or user.name or "",
        "date_label": date_label(user, at),
        "vrat_label": safe("vrat", lambda: _vrat(db, user, prefs)),
        "credit_day_of_month": prefs.pension_credit_day,
        # `dr` is null only when no rate has ever been entered by an admin.
        # Clients must treat it as optional; showing a made-up 0% would be worse
        # than showing nothing.
        "dr": safe("dr", lambda: _dr(db, user)),
        "headline": (
            {"id": headline["id"], "headline": headline["headline"], "source": headline["source"],
             "published_at": headline["published_at"]} if headline else None
        ),
        "reminders": [] if "reminders" in hidden else safe("reminders", lambda: _reminders(db, user), []),
        "practice": None if "practice" in hidden else safe("practice", lambda: _practice(db, user)),
        "circles": [] if "circles" in hidden else safe("circles", lambda: _circles(db, user), []),
        "news": [] if "news" in hidden else news,
        "weather": None if "weather" in hidden else safe("weather", lambda: _weather(user)),
        "scam_alert": None if "scam" in hidden else safe("scam", lambda: _scam(db, user)),
        "seva_match": None if "seva" in hidden else safe("seva", lambda: _seva(db, user)),
        "live_room": None if "live_room" in hidden else safe("live_room", lambda: _live_room(db, user)),
        "opens_in_14_days": opens,
        "last_tapped": last,
        "hidden": sorted(hidden),
        "cached_at": None,
        "credit_no_streak": prefs.credit_no_streak,
        "generated_at": at,
    }
    db.commit()
    return feed


class OpenedIn(BaseModel):
    section: str


class HiddenIn(BaseModel):
    section: str
    hidden: bool | str | int


class CreditIn(BaseModel):
    day: str
    credited: bool | str | int


def _truthy(v) -> bool:  # noqa: ANN001
    return v in (True, 1, "1", "true", "True", "yes")


@router.post("/today/opened")
def card_opened(body: OpenedIn, request: Request, user: CurrentUser, db: DbSession):
    """Record a tap. Ordering rule (6.2): the rest are ranked by opens in 14 days."""
    limit_write(request, user.id)
    if body.section not in SECTIONS:
        raise HTTPException(400, f"section must be one of {', '.join(SECTIONS)}")
    prefs = prefs_for(db, user.id)
    cutoff = now() - 30 * 86400
    opens = {k: [t for t in v if t >= cutoff] for k, v in (prefs.today_opens or {}).items()}
    opens.setdefault(body.section, []).append(now())
    prefs.today_opens = opens
    db.commit()
    return {"ok": True}


@router.post("/today/hidden")
def hide_card(body: HiddenIn, request: Request, user: CurrentUser, db: DbSession):
    """'Edit Today': the user hides or shows a card. The check-in card cannot be hidden here - it has its own switch."""
    limit_write(request, user.id)
    if body.section not in SECTIONS:
        raise HTTPException(400, f"section must be one of {', '.join(SECTIONS)}")
    prefs = prefs_for(db, user.id)
    hidden = [s for s in (prefs.today_hidden or []) if s != body.section]
    if _truthy(body.hidden):
        hidden.append(body.section)
    prefs.today_hidden = hidden
    db.commit()
    return {"ok": True, "hidden": hidden}


@router.post("/today/credit_answer")
def credit_answer(body: CreditIn, request: Request, user: CurrentUser, db: DbSession):
    """
    'Did your pension credit?' Five days of 'No' in a row offers the grievance
    helper (plan 6.2). A second answer on the same day replaces the first rather
    than counting twice, so a double tap cannot manufacture a streak.
    """
    limit_write(request, user.id)
    if len(body.day) != 10 or body.day[4] != "-" or body.day[7] != "-":
        raise HTTPException(400, "day must look like 2026-11-01")
    prefs = prefs_for(db, user.id)
    credited = _truthy(body.credited)
    if credited:
        prefs.credit_no_streak = 0
    elif prefs.credit_last_answer_day != body.day:
        prefs.credit_no_streak = (prefs.credit_no_streak or 0) + 1
    prefs.credit_last_answer_day = body.day
    db.commit()
    return {
        "ok": True,
        "no_streak": prefs.credit_no_streak,
        "offer_grievance": prefs.credit_no_streak >= 5,
    }
