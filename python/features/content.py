# =============================================================================
# Feature · Content feeds and app configuration — plan sections 6.14, 7.2, 14, 17.
#
#   GET /app/config            what the app needs to know before anything else
#   GET /news                  pension news (official sources, neutral summaries)
#   GET /radio/stations        old film songs, ghazals, devotional, regional
#   GET /thought/today         the daily thought, from a public-domain source
#   GET /offers/today          at most one labelled "Partner offer" a day
#   GET /spiritual/content     texts, chalisas, aartis; /spiritual/content/{id}
#   GET /spiritual/start       today's spiritual start (weekday, mantra, 2 minutes)
#   GET /spiritual/darshan     links to places of worship's own official streams
#
# Content policy (plan 7.2): suggestions, not claims - "traditionally...", "many
# people find...". Only public-domain or openly licensed texts and recordings,
# each credited; everything else is a link to its source. No faith is ranked above
# another. Live darshan opens OUTSIDE the app: nothing is embedded without
# permission, so those rows carry a URL to the place's own stream and nothing
# else.
# =============================================================================
from __future__ import annotations

import hashlib
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException
from sqlalchemy import or_, select

from python import config
from python.deps import CurrentUser, DbSession, OptionalUser
from python.integrations import storage
from python.models import (
    LANGUAGES,
    SERVICE_TYPES,
    SPIRITUAL_PATHS,
    TIERS,
    DailyThought,
    NewsItem,
    Partner,
    PartnerOffer,
    RadioStation,
    SpiritualContent,
    now,
)

log = logging.getLogger("doosri.content")
router = APIRouter(tags=["content"])

APP_DISCLAIMER = "Independent app, not affiliated with any government department."

# A weekday tradition is offered as tradition, in the plan's own wording.
_WEEKDAY_TRADITION = {
    0: "Traditionally, Monday is kept for Shiva.",
    1: "Traditionally, Tuesday is kept for Hanuman.",
    2: "Traditionally, Wednesday is kept for Ganesha.",
    3: "Traditionally, Thursday is kept for Vishnu and for one's teachers.",
    4: "Traditionally, Friday is kept for the Devi.",
    5: "Traditionally, Saturday is kept for Hanuman and Shani.",
    6: "Traditionally, Sunday is kept for Surya.",
}
_WEEKDAY_DEITY = {0: "shiva", 1: "hanuman", 2: "ganesh", 3: "vishnu", 4: "devi", 5: "hanuman", 6: "surya"}


def _tz(user) -> ZoneInfo:  # noqa: ANN001
    try:
        return ZoneInfo(user.time_zone or "UTC")
    except Exception:
        return ZoneInfo("UTC")


def _day_index(user) -> int:  # noqa: ANN001
    return datetime.fromtimestamp(now(), _tz(user)).toordinal()


# --------------------------------------------------------------- app config --
@router.get("/app/config")
def app_config(user: OptionalUser):
    """
    Public and cacheable. The clients read this at launch, before sign-in, so it
    carries nothing about any person.
    """
    return {
        "app": {
            "name": "Doosri Pari",
            "disclaimer": APP_DISCLAIMER,
            "support_email": config.SUPPORT_EMAIL,
            "grievance_officer": config.GRIEVANCE_OFFICER,
        },
        "languages": list(LANGUAGES),
        "service_types": list(SERVICE_TYPES),
        "tiers": list(TIERS),
        "spiritual_paths": list(SPIRITUAL_PATHS),
        "text_scales": [1.0, 1.15, 1.3],
        "limits": {
            "max_guardians": config.MAX_GUARDIANS,
            "max_helpers": config.MAX_HELPERS,
            "pause_days": [config.PAUSE_MIN_DAYS, config.PAUSE_MAX_DAYS],
            "max_non_urgent_per_day": config.MAX_NON_URGENT_PER_DAY,
            "sos_countdown_seconds": 5,
            "sos_hold_seconds": 2,
            "listeners_per_room": 200,
            "speakers_per_room": 8,
            "voice_note_max_seconds": 180,
            "story_max_seconds": 600,
            "photos_per_post": 6,
        },
        "checkin": {"default_window_start": "09:00", "default_window_mins": 180},
        "plans": {
            "family": {"monthly_inr": 149, "yearly_inr": 1299, "seats": 6, "billing": "store"},
            "seva_fee_percent": 12,
        },
        "emergency_numbers": {"emergency": "112", "cyber_crime": "1930"},
        "notification_channels": ["safety", "medicines", "pension", "circles", "seva", "practice"],
        "features": {
            "voice_rooms": bool(config.LIVEKIT_URL),
            "payments": bool(config.RAZORPAY_KEY_ID),
            "sahayak_ai": bool(config.ANTHROPIC_API_KEY),
            "sms_otp": bool(config.SMS_PROVIDER),
        },
        "signed_in": user is not None,
    }


# --------------------------------------------------------------------- news --
@router.get("/news")
def news(user: CurrentUser, db: DbSession, limit: int = 20, before: float | None = None, lang: str | None = None):
    if not 1 <= limit <= 50:
        raise HTTPException(400, "limit must be 1..50")
    want = lang or user.lang or "en"
    q = select(NewsItem).where(NewsItem.is_active.is_(True), NewsItem.language.in_((want, "en")))
    if before is not None:
        q = q.where(NewsItem.published_at < before)
    rows = db.scalars(q.order_by(NewsItem.published_at.desc()).limit(limit)).all()
    return {
        "items": [
            {
                "id": n.id, "title": n.title, "summary": n.summary, "publisher": n.publisher,
                "url": n.url, "language": n.language, "tags": n.tags or [], "published_at": n.published_at,
            }
            for n in rows
        ],
        "next_before": rows[-1].published_at if len(rows) == limit else None,
        # 6.14: a summary is ours, the article is the publisher's; always link out.
        "note": "Summaries are short and neutral. Open the source for the full story.",
    }


# -------------------------------------------------------------------- radio --
@router.get("/radio/stations")
def radio(user: CurrentUser, db: DbSession, kind: str | None = None, lang: str | None = None):
    q = select(RadioStation).where(RadioStation.is_active.is_(True))
    if kind:
        q = q.where(RadioStation.kind == kind)
    if lang:
        q = q.where(RadioStation.language == lang)
    rows = db.scalars(q.order_by(RadioStation.position, RadioStation.id)).all()
    return {
        "stations": [
            {"id": r.id, "name": r.name, "kind": r.kind, "language": r.language, "region": r.region,
             "stream_url": r.stream_url, "credit": r.credit}
            for r in rows
        ]
    }


# ------------------------------------------------------------------ thought --
@router.get("/thought/today")
def thought_today(user: CurrentUser, db: DbSession, lang: str | None = None):
    """
    One short line, the same for everyone in a language on a given day, so a
    family can discuss it. Public-domain sources only; the source is shown.
    """
    want = lang or user.lang or "en"
    rows = db.scalars(
        select(DailyThought).where(DailyThought.is_active.is_(True), DailyThought.language == want)
        .order_by(DailyThought.id)
    ).all()
    if not rows:
        rows = db.scalars(
            select(DailyThought).where(DailyThought.is_active.is_(True), DailyThought.language == "en")
            .order_by(DailyThought.id)
        ).all()
    if not rows:
        return {"thought": None}
    t = rows[_day_index(user) % len(rows)]
    return {
        "thought": {
            "id": t.id, "text": t.text, "author": t.author, "source": t.source_ref, "language": t.language,
            # What the share card says under the line. Never an app-store pitch.
            "share_text": f"“{t.text}”" + (f" — {t.author}" if t.author else "") + "\n\nDoosri Pari",
        }
    }


# ------------------------------------------------------------------- offers --
@router.get("/offers/today")
def offer_today(user: CurrentUser, db: DbSession):
    """
    At most one partner offer a day, labelled (plan 17). Never shown inside
    Sahayak, Safety, Health or the Kendra - the clients only call this for the
    Today screen. Chosen by a stable hash of the person and the date, so it does
    not change if they open the app three times.
    """
    at = now()
    rows = db.execute(
        select(PartnerOffer, Partner)
        .join(Partner, Partner.id == PartnerOffer.partner_id)
        .where(
            PartnerOffer.is_active.is_(True),
            Partner.verified_at.isnot(None),
            or_(PartnerOffer.starts_at.is_(None), PartnerOffer.starts_at <= at),
            or_(PartnerOffer.ends_at.is_(None), PartnerOffer.ends_at >= at),
        )
        .order_by(PartnerOffer.id)
    ).all()
    if not rows:
        return {"offer": None}
    h = int(hashlib.sha256(f"{user.id}:{_day_index(user)}".encode()).hexdigest()[:8], 16)
    offer, partner = rows[h % len(rows)]
    return {
        "offer": {
            "id": offer.id, "label": "Partner offer", "title": offer.title, "body": offer.body,
            "url": offer.url, "partner": partner.name, "kind": offer.kind,
        }
    }


# ---------------------------------------------------------------- spiritual --
def _content_view(c: SpiritualContent, full: bool = False) -> dict:
    out = {
        "id": c.id, "kind": c.kind, "title": c.title, "language": c.language, "path": c.path,
        "source": c.source_ref, "duration_s": c.duration_s,
        "audio_url": storage.presign_download(c.audio_key, 3600) if c.audio_key else None,
    }
    if full:
        out["body"] = c.body
    else:
        out["preview"] = " ".join((c.body or "").split())[:140]
    return out


@router.get("/spiritual/content")
def spiritual_content(
    user: CurrentUser, db: DbSession, kind: str | None = None, path: str | None = None,
    lang: str | None = None, limit: int = 50,
):
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    if path is not None and path not in SPIRITUAL_PATHS:
        raise HTTPException(400, f"path must be one of {', '.join(SPIRITUAL_PATHS)}")
    q = select(SpiritualContent).where(SpiritualContent.is_active.is_(True))
    if kind:
        q = q.where(SpiritualContent.kind == kind)
    if path:
        q = q.where(or_(SpiritualContent.path == path, SpiritualContent.path.is_(None)))
    if lang:
        q = q.where(SpiritualContent.language.in_((lang, "en")))
    rows = db.scalars(q.order_by(SpiritualContent.kind, SpiritualContent.title).limit(limit)).all()
    return {"items": [_content_view(c) for c in rows]}


@router.get("/spiritual/content/{content_id}")
def spiritual_content_one(content_id: int, user: CurrentUser, db: DbSession):
    c = db.get(SpiritualContent, content_id)
    if c is None or not c.is_active:
        raise HTTPException(404, "not found")
    return _content_view(c, full=True)


@router.get("/spiritual/start")
def spiritual_start(user: CurrentUser, db: DbSession):
    """
    Today's spiritual start (plan 7.2 item 2): a suggestion by weekday and the
    person's chosen deity or practice, described as tradition and never as a
    promise; a mantra or prayer with its meaning; a two-minute practice.

    A record of what someone already does is not instructed, corrected or
    claimed by this app (models/kendra.py): with no path chosen, or a path with
    no content yet, the answer is an honest empty rather than a borrowed one.
    """
    state = user.spiritual_state
    paths = [p for p in (state.paths if state else []) if p != "none"]
    if not paths:
        return {"start": None, "reason": "choose_path"}

    tz = _tz(user)
    weekday = datetime.fromtimestamp(now(), tz).weekday()
    deity = (state.deity or "").lower().strip() if state else ""
    hindu_like = any(p in ("hindu", "shaiva", "vaishnava", "shakta", "general") for p in paths)

    suggestion = _WEEKDAY_TRADITION[weekday] if hindu_like and not deity else None
    want = deity or (_WEEKDAY_DEITY[weekday] if hindu_like else "")

    base = select(SpiritualContent).where(
        SpiritualContent.is_active.is_(True),
        SpiritualContent.language.in_((user.lang or "hi", "en")),
        or_(SpiritualContent.path.in_(paths), SpiritualContent.path.is_(None)),
    )
    mantras = db.scalars(base.where(SpiritualContent.kind.in_(("mantra", "prayer"))).order_by(SpiritualContent.id)).all()
    practices = db.scalars(base.where(SpiritualContent.kind == "practice").order_by(SpiritualContent.id)).all()

    mantra = None
    if want:
        mantra = next((m for m in mantras if want in (m.title or "").lower() or want in (m.body or "").lower()[:200]), None)
    if mantra is None and mantras:
        mantra = mantras[_day_index(user) % len(mantras)]
    practice = practices[_day_index(user) % len(practices)] if practices else None
    if mantra is None and practice is None:
        return {"start": None, "reason": "no_content_yet", "suggestion": suggestion}

    return {
        "start": {
            "suggestion": suggestion,
            "mantra": _content_view(mantra, full=True) if mantra else None,
            "practice": _content_view(practice, full=True) if practice else None,
            "minutes": 2,
        },
        "disclaimer": "This is offered as tradition, not as a promise. Practise as you already do.",
    }


@router.get("/spiritual/darshan")
def darshan(user: CurrentUser, db: DbSession, path: str | None = None):
    """
    Links to a temple's, gurdwara's or other place of worship's OWN official live
    stream. They open outside the app; nothing here is embedded or re-hosted.
    """
    q = select(SpiritualContent).where(SpiritualContent.is_active.is_(True), SpiritualContent.kind == "darshan")
    if path:
        q = q.where(or_(SpiritualContent.path == path, SpiritualContent.path.is_(None)))
    rows = db.scalars(q.order_by(SpiritualContent.title)).all()
    return {
        "places": [{"id": r.id, "name": r.title, "url": r.body, "path": r.path, "credit": r.source_ref} for r in rows],
        "note": "These open the place's own official page. We do not host or record them.",
    }
