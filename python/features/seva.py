# =============================================================================
# Feature · Seva (the Anubhav board) — plan sections 6.8, 12 and 15.
#
#   GET  /listings                 the board: filtered and paginated
#   POST /listings                 offer time or work; starts pending review
#   POST /bookings                 book somebody's time
#   POST /bookings/{id}/confirm    requested -> confirmed -> completed, or cancel
#   POST /payments/webhook         Razorpay: HMAC over the raw bytes, and
#                                  idempotent on the provider's event id
#
# The rules this module enforces:
#
#   1. A listing is user-generated content from a retired person offering a
#      service. It is validated hard and starts pending a moderator — plan 15's
#      "held for review" — and is never live on creation. A listing that asks a
#      pensioner to pay is blocked outright: 6.8's anti-scam list is hard-coded
#      on purpose, and "Never pay to get work" rides on every paid listing.
#   2. The board shows a name and a city. Never a home address and never an
#      exact coordinate: the people on this board live alone (plan 14), and the
#      lat/lng on the row exists for the matching score (6.8) and nothing else.
#   3. A booking moves through one machine, and an out-of-order transition is a
#      409 rather than a silent overwrite. A quietly rewritten booking is a
#      double booking, and then it is a money dispute between two pensioners.
#   4. Money is integer paise, never float rupees, and it never moves from a
#      request handler. The handler records state; the daily `payouts` job pays
#      bookings whose 48-hour hold has elapsed (plan 12).
#   5. The payment webhook is the one place money is acknowledged. Providers
#      replay events, and a doubled payout to a retired person is real harm, so
#      every verified event id is remembered and its replay is a no-op.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import os
import re
from decimal import Decimal, ROUND_HALF_UP
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import String, cast, func, select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession
from python.models import (
    BOOKING_STATUSES,
    LANGUAGES,
    LISTING_MODES,
    LISTING_TYPES,
    PAYOUT_HOLD_SECONDS,
    TIERS,
    TIER_RANK,
    AuditEntry,
    Availability,
    Booking,
    Listing,
    Partner,
    Rating,
    SafetyQueueItem,
    ScamAlert,
    User,
    now,
)
from python.ratelimit import limit_write
from python.security import constant_time_equals

log = logging.getLogger("doosri.seva")
router = APIRouter(tags=["seva"])

# Plan 6.1's tier names, written out the way features/circles.py writes them:
# a check that reads `rank >= 1` tells the next person nothing about who is
# allowed to offer mentoring to a room full of retired colleagues.
TIER_PHONE = "T0"
TIER_VOUCHED = "T1"

# The held state of a new listing. `LISTING_STATUSES` in models/base.py has no
# pending member — the tuple predates plan 15's review rule and belongs to
# models, which this module does not own. `open` is the live state; GET
# /listings shows that and nothing else, so anything else is not live. A
# moderator's takedown is still `removed` (features/admin.py), which reads the
# column as a string and does not care what this constant is.
LISTING_STATUS_PENDING = "pending"
LISTING_STATUS_OPEN = "open"

# 6.8's table, mapped onto the schema's five kinds: mentor, skill and space are
# offers and need T1+; help is a request anyone may make and is always free;
# work is posted by a verified organisation only ("Verified partners only" /
# "Verified NGOs and schools").
OFFER_KINDS = ("mentor", "skill", "space")
PAID_WORK_KIND = "work"
HELP_KIND = "help"

# Money. Rule 4: integer paise everywhere in this file. A float rupee in a
# payout system is a rounding bug waiting for somebody's rent — 0.1 + 0.2 is
# not 0.3, and a one-paisa error multiplied over 20 000 sessions is a number
# nobody can explain to the person it was taken from. python/jobs.py holds the
# same rule's other copy in `to_paise`; the schema's amount columns are Float
# rupees because that is the schema this repository was given, so the single
# float conversion happens at the storage boundary and nowhere else.
COMMISSION_RATE = Decimal("0.12")  # 6.8 / 17: "12% per session"
MAX_RATE_PAISE = 10_000_000        # ₹1,00,000 for a session. A board, not a bank.

TITLE_MAX = 160      # the column
BODY_MAX = 4000
TAGS_MAX = 8
TAG_MAX = 32
NOTE_MAX = 200
AVAILABILITY_MAX = 14

PAGE_DEFAULT = 20
PAGE_MAX = 50

# A slot is somebody's real hour. It cannot be in the past (the session either
# happened or the row is a mistake) and it cannot be a year out (a booking that
# never comes due is a listing held hostage).
SLOT_MIN_LEEWAY_S = 300
SLOT_MAX_HORIZON_S = 180 * 24 * 3600


# ------------------------------------------------------------------- money --
def to_paise(rupees: float | None) -> int:
    """Rupees (the schema's Float) to integer paise, without float arithmetic.

    Decimal on the decimal *string* of the stored value: Decimal(str(0.1)) is
    0.1 exactly where 0.1 is not. Same conversion as jobs.to_paise, written out
    here because a feature module does not reach into the job runner for a
    six-line helper — and because if the two ever disagree, the tests that pin
    both will say so.
    """
    if rupees is None:
        return 0
    return int(Decimal(str(rupees)).scaleb(2).to_integral_value(rounding=ROUND_HALF_UP))


def to_rupees(paise: int) -> float:
    """Integer paise to the Float rupee column. The one conversion, at the edge."""
    return float(Decimal(paise).scaleb(-2))


def fee_paise(amount_paise: int) -> int:
    """The 12% commission, in paise, rounded half-up once.

    Computed once at booking time and stored, not recomputed at payout: the
    number the provider is quoted and the number the job pays have to be the
    same number even if the rate changes between them.
    """
    return int(
        (Decimal(amount_paise) * COMMISSION_RATE).to_integral_value(rounding=ROUND_HALF_UP)
    )


# ------------------------------------------------- plan 15's automatic checks --
# The same three checks features/circles.py runs on posts (plan 15's trust
# section), with one difference that the plan itself draws: a payment request in
# a *listing* is not held for review, it is blocked, because 6.8's anti-scam
# rules say "any listing asking the pensioner to pay is blocked" and a board
# where a job scam is merely queued is a board that ships the scam tomorrow.
_URL_RE = re.compile(r"https?://[^\s<>\"')\]]+", re.IGNORECASE)
_PHONE_RE = re.compile(r"(?<![\d+])(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)")
_UPI_RE = re.compile(r"[\w.\-]{2,}\s*@\s*[a-z]{2,}", re.IGNORECASE)
_PAYMENT_WORDS = (
    "send money",
    "pay me",
    "pay the amount",
    "advance payment",
    "processing fee",
    "registration fee",
    "security deposit",
    "bank account",
    "account number",
    "ifsc",
    "upi id",
    "upi id is",
    "gpay",
    "phonepe",
    "paytm",
    "phone pay",
    "gift card",
)


def _blocked_domains(db: Session) -> set[str]:
    """The link blocklist, read from the safety team's ScamAlert rows.

    Plan 15: "links go through a blocklist". Reading it from the table means a
    domain added by a moderator binds the next listing instead of the next
    deploy — the difference between a blocklist that works and one that is out
    of date before it ships.
    """
    out: set[str] = set()
    for alert in db.scalars(select(ScamAlert).where(ScamAlert.is_active.is_(True))).all():
        for d in alert.domains or []:
            host = str(d).strip().lower().lstrip(".")
            if host:
                out.add(host)
    return out


def _refuse_blocked_link(db: Session, text: str) -> None:
    """A link to a known scam is refused, not held. Decided already; not a queue."""
    blocked = _blocked_domains(db)
    if not blocked:
        return
    for raw in _URL_RE.findall(text or ""):
        host = (urlsplit(raw).hostname or "").lower()
        if not host:
            continue
        for domain in blocked:
            # The suffix test makes "login.rbi.org.in.evil.example" a hit on an
            # "rbi.org.in" alert instead of a near miss.
            if host == domain or host.endswith("." + domain):
                raise HTTPException(
                    400, "that link is on our scam blocklist and cannot be posted here"
                )


def _pay_to_work_reason(text: str) -> str | None:
    """
    Why this listing is refused outright, or None.

    6.8's anti-scam rules, hard-coded: "any listing asking the pensioner to pay
    is blocked" and the board always says "Never pay to get work". The price of
    a paid session is one number, collected by the platform through Razorpay
    (6.8 step 1: "money moves through a verified partner, never person to
    person" — models/seva.py). So any *text* asking the reader for money, a
    deposit, a fee or a bank detail is not a price, it is the job scam this
    feature exists to keep off the board. Blocked means blocked: no queue, no
    appeal inside this request, nothing stored.
    """
    if not text:
        return None
    low = text.lower()
    if _UPI_RE.search(text):
        return "a listing must never ask for a payment id; session money moves through the app"
    for word in _PAYMENT_WORDS:
        if word in low:
            return "a listing must never ask a pensioner to pay to get work"
    return None


def _hold_reason(user: User, text: str | None) -> str | None:
    """
    Why this listing is held for a person to read, or None.

    Plan 15's first automatic check: "phone numbers and payment requests in
    posts from T0 users are held for review". Payment requests in a listing are
    already refused above, so what is left is the phone number — a T0 account is
    a phone OTP and nothing else, which is exactly the tier a fraud account
    registers at, and a listing is read by retired public servants who are, by
    training, the most polite people on any platform. A false positive costs a
    moderator ten seconds; a false negative costs a pensioner their savings.
    """
    if not text:
        return None
    if user.tier_rank > TIER_RANK[TIER_PHONE]:
        return None
    if _PHONE_RE.search(text):
        return "phone number in a listing from an unverified account"
    return None


# ------------------------------------------------------------------ schemas --
class AvailabilityIn(BaseModel):
    weekday: int = Field(ge=1, le=7)      # 1 = Monday, matching java.time
    start_min: int = Field(ge=0, le=1440)
    end_min: int = Field(ge=0, le=1440)
    time_zone: str = Field(default="UTC", max_length=64)


class ListingIn(BaseModel):
    # `kind`, not `type`: features/admin.py already puts this on the wire as
    # "kind", and two names for one column is how a client ends up sending the
    # wrong one.
    kind: str = Field(...)
    title: str = Field(..., min_length=1, max_length=TITLE_MAX)
    body: str = Field(..., min_length=1, max_length=BODY_MAX)
    tags: list[str] = Field(default_factory=list, max_length=TAGS_MAX)
    langs: list[str] = Field(default_factory=list, max_length=len(LANGUAGES))
    mode: str = Field(default="free")
    # Integer paise on the wire. Rule 4: a rupee float never enters this file.
    price_paise: int = Field(default=0, ge=0, le=MAX_RATE_PAISE)
    # Tiers this is free to. Empty means everyone pays (models/seva.py).
    free_for: list[str] = Field(default_factory=list, max_length=len(TIERS))
    partner_id: int | None = None
    availability: list[AvailabilityIn] = Field(default_factory=list, max_length=AVAILABILITY_MAX)
    # Optional, used only by the matching score (6.8) and never serialised back.
    # Not a home address: the poster's area centre and a radius they are willing
    # to travel, which is all "1.0 within 3 km, 0 beyond 25 km" needs.
    lat: float | None = None
    lng: float | None = None
    radius_km: float | None = Field(default=None, ge=0, le=100)


class BookingIn(BaseModel):
    listing_id: int = Field(...)
    slot_at: float = Field(...)


class BookingConfirmIn(BaseModel):
    action: str = Field(default="confirm")
    note: str | None = Field(default=None, max_length=NOTE_MAX)


# ------------------------------------------------------------------ views --
def listing_view(row: Listing, owner: User | None, ratings: dict | None = None) -> dict:
    """
    The listing as the board shows it.

    The field list is the privacy boundary. The row carries lat/lng/radius for
    the matching score and nothing here may carry them: this board is made of
    people who live alone, and a listing with a house-level coordinate on it is
    a burglary list with a search box (plan 14). Location on the wire is a city
    id and a state — CITY granularity, from the poster's profile, never from the
    row and never finer.
    """
    return {
        "id": row.id,
        "kind": row.type,
        "title": row.title,
        "body": row.body,
        "tags": row.tags or [],
        "langs": row.langs or [],
        "mode": row.mode,
        "rate_paise": to_paise(row.price),
        "free_for": row.free_for or [],
        "status": row.status,
        # CITY granularity. Not an address, not a street, not a coordinate.
        "location": {
            "city_id": owner.city_id if owner else None,
            "state_code": owner.state_code if owner else None,
        },
        "provider": {
            "id": row.owner_id,
            "name": (owner.address_as or owner.name) if owner else None,
            "partner_id": row.partner_id,
        },
        "availability": row.availability or [],
        "ratings": ratings or {"average": None, "count": 0},
        "created_at": row.created_at,
        # 6.8: "every paid listing shows 'Never pay to get work'". The server
        # says it, so no build of the client can forget to.
        "notice": "Never pay to get work" if row.mode == "paid" else None,
    }


def booking_view(row: Booking) -> dict:
    """Money as integer paise on the wire, even though the columns are Float."""
    return {
        "id": row.id,
        "listing_id": row.listing_id,
        "seeker_id": row.seeker_id,
        "provider_id": row.provider_id,
        "slot_at": row.slot_at,
        "status": row.status,
        "amount_paise": to_paise(row.amount),
        "fee_paise": to_paise(row.fee),
        "payment_id": row.payment_id,
        "payout_id": row.payout_id,
        "payout_held_until": row.payout_held_until,
        "created_at": row.created_at,
        "completed_at": row.completed_at,
    }


# ----------------------------------------------------------------- helpers --
def _names(db: Session, user_ids: set[int]) -> dict[int, User]:
    """One query for a page of providers, so a board is not N+1."""
    if not user_ids:
        return {}
    rows = db.scalars(select(User).where(User.id.in_(user_ids))).all()
    return {u.id: u for u in rows}


def _ratings_by_provider(db: Session, provider_ids: set[int]) -> dict[int, dict]:
    """Average stars and how many, per provider, for a whole page in one query.

    A plain average, not 6.8's Bayesian prior: the prior belongs to the
    matching score, where a single 5-star must not outrank a decade of work.
    Here the count travels with the average and the client can see both.
    """
    if not provider_ids:
        return {}
    rows = db.execute(
        select(
            Booking.provider_id,
            func.avg(Rating.stars),
            func.count(Rating.id),
        )
        .join(Rating, Rating.booking_id == Booking.id)
        .where(Booking.provider_id.in_(provider_ids))
        .group_by(Booking.provider_id)
    ).all()
    return {
        pid: {"average": round(float(avg), 1) if avg is not None else None, "count": int(n)}
        for pid, avg, n in rows
    }


def _windows_for(db: Session, listing_ids: set[int]) -> dict[int, list[dict]]:
    """The recurring windows, from the table that exists so they can be queried.

    models/seva.py is explicit that a JSON blob is not a query: "show me
    Thursday afternoon" is a query. Listings with no window rows still carry
    their `availability` JSON, and the view falls back to it.
    """
    if not listing_ids:
        return {}
    rows = db.scalars(
        select(Availability).where(Availability.listing_id.in_(listing_ids))
    ).all()
    out: dict[int, list[dict]] = {}
    for w in rows:
        out.setdefault(w.listing_id, []).append(
            {
                "weekday": w.weekday,
                "start_min": w.start_min,
                "end_min": w.end_min,
                "time_zone": w.time_zone,
            }
        )
    return out


def _queue_listing(db: Session, listing_id: int, why: str, scam_signal: bool) -> None:
    """
    Put the new listing in front of a person. Plan 15's response times: two
    hours for a scam signal, twenty-four for the rest.

    Every listing goes in the queue, not only the flagged ones, because the
    listing starts pending and a pending thing nobody can see is a listing that
    is never published. The plan calls the queue "held for review"; the review
    is the publication step. The console that works this queue is the same one
    that unhides a held post (features/circles.py) and it is not built yet —
    what is built here is the record, at the moment of the post rather than
    discovered later.
    """
    at = now()
    db.add(
        SafetyQueueItem(
            target_type="listing",
            target_id=listing_id,
            why=why,
            score=0.9 if scam_signal else 0.1,
            status="open",
            created_at=at,
            due_at=at + (2 if scam_signal else 24) * 60 * 60,
        )
    )


# ---------------------------------------------------------- state machine --
# The booking machine, written as data so the rules are reviewable in one
# glance. requested -> confirmed -> completed, cancelled from either of the
# first two. Both sides confirm a session in the plan's words (6.8 step 3):
# the provider accepts the time, the payer confirms it happened — the schema
# has one status column, so the two confirms are the two transitions rather
# than two votes on one. Anything else is 409: a silent overwrite on a booking
# is a double booking and then a money dispute.
#
#   from       action   who        to
#   requested  confirm  provider   confirmed
#   confirmed  confirm  seeker     completed
#   requested  cancel   either     cancelled
#   confirmed  cancel   either     cancelled
#
# completed and cancelled are terminal. `disputed` (BOOKING_STATUSES) is set
# only by the moderator flow — 6.8 step 5, "disputes go to moderators" — and
# has no transition from here on purpose.
_CONFIRM = "confirm"
_CANCEL = "cancel"
_ACTIONS = (_CONFIRM, _CANCEL)


# ---------------------------------------------------------------- endpoints --
@router.get("/listings")
def list_listings(
    user: CurrentUser,
    db: DbSession,
    kind: str | None = None,
    mode: str | None = None,
    lang: str | None = None,
    city_id: int | None = None,
    weekday: int | None = None,
    q: str | None = None,
    limit: int = PAGE_DEFAULT,
    offset: int = 0,
):
    """
    The board: filtered, paginated, and live listings only.

    A pending listing is not here. Neither is a filled, withdrawn or removed
    one. What is here is deliberately small on the wire — see listing_view for
    the fields that are NOT here and why.
    """
    if kind is not None and kind not in LISTING_TYPES:
        raise HTTPException(400, f"kind must be one of {', '.join(LISTING_TYPES)}")
    if mode is not None and mode not in LISTING_MODES:
        raise HTTPException(400, f"mode must be one of {', '.join(LISTING_MODES)}")
    if lang is not None and lang not in LANGUAGES:
        raise HTTPException(400, f"lang must be one of {', '.join(LANGUAGES)}")
    if weekday is not None and not 1 <= weekday <= 7:
        raise HTTPException(400, "weekday must be 1..7")
    if not 1 <= limit <= PAGE_MAX:
        raise HTTPException(400, f"limit must be 1..{PAGE_MAX}")
    if offset < 0:
        raise HTTPException(400, "offset must be >= 0")

    stmt = select(Listing).where(Listing.status == LISTING_STATUS_OPEN)
    if kind is not None:
        stmt = stmt.where(Listing.type == kind)
    if mode is not None:
        stmt = stmt.where(Listing.mode == mode)
    if lang is not None:
        # The poster's working languages, so a seeker finds somebody they can
        # actually talk to. 6.8's language_match starts here. langs is a JSON
        # list (JSONB on Postgres), so it is cast to text and matched as a
        # quoted token — `"te"` finds ["te","hi"] and never ["ten"].
        stmt = stmt.where(cast(Listing.langs, String).contains(f'"{lang}"'))
    if city_id is not None:
        # City lives on the poster's profile, not on the listing — one place
        # decides where somebody is, and it is theirs to change.
        stmt = stmt.where(
            Listing.owner_id.in_(select(User.id).where(User.city_id == city_id))
        )
    if weekday is not None:
        # Exactly what models/seva.py says the availability table is for.
        stmt = stmt.where(
            Listing.id.in_(
                select(Availability.listing_id).where(Availability.weekday == weekday)
            )
        )
    if q:
        # contains() auto-escapes the wildcards, so `%` searches for a percent
        # sign and one pensioner cannot enumerate the board with one character.
        stmt = stmt.where(Listing.title.contains(q))

    total = db.scalar(select(func.count()).select_from(stmt.subquery())) or 0
    rows = db.scalars(
        stmt.order_by(Listing.created_at.desc()).offset(offset).limit(limit)
    ).all()

    owners = _names(db, {r.owner_id for r in rows})
    ratings = _ratings_by_provider(db, {r.owner_id for r in rows})
    windows = _windows_for(db, {r.id for r in rows})

    out = []
    for r in rows:
        v = listing_view(r, owners.get(r.owner_id), ratings.get(r.owner_id))
        if windows.get(r.id):
            v["availability"] = windows[r.id]
        out.append(v)
    return {"listings": out, "total": total, "limit": limit, "offset": offset}


@router.post("/listings")
def create_listing(body: ListingIn, request: Request, user: CurrentUser, db: DbSession):
    """
    A retired person offering their time, their skill or their help.

    Hard validation, then plan 15, then — whatever the checks say — the listing
    is stored PENDING, not live. It appears on the board when a moderator works
    the queue, the same way a held post waits for a person. User-generated
    content does not publish itself.

    The text arrives as somebody's untrusted words and is stored as exactly
    that: screened, queued, never logged, never templated into anything. It is
    data. Treating it as anything else is how a board ends up taking
    instructions from its own users.
    """
    limit_write(request, user.id)

    if body.kind not in LISTING_TYPES:
        raise HTTPException(400, f"kind must be one of {', '.join(LISTING_TYPES)}")
    if body.mode not in LISTING_MODES:
        raise HTTPException(400, f"mode must be one of {', '.join(LISTING_MODES)}")
    for lang in body.langs:
        if lang not in LANGUAGES:
            raise HTTPException(400, f"langs must be drawn from {', '.join(LANGUAGES)}")
    for tag in body.tags:
        if not tag.strip() or len(tag) > TAG_MAX:
            raise HTTPException(400, f"each tag must be 1..{TAG_MAX} characters")
    for tier in body.free_for:
        if tier not in TIERS:
            raise HTTPException(400, f"free_for must be drawn from {', '.join(TIERS)}")
    for w in body.availability:
        if w.end_min <= w.start_min:
            raise HTTPException(400, "an availability window must end after it starts")
    if body.lat is not None and not -90 <= body.lat <= 90:
        raise HTTPException(400, "lat out of range")
    if body.lng is not None and not -180 <= body.lng <= 180:
        raise HTTPException(400, "lng out of range")

    # Plan 6.8's table, enforced rather than described.
    if body.kind in OFFER_KINDS and user.tier_rank < TIER_RANK[TIER_VOUCHED]:
        raise HTTPException(403, "offering mentoring or a skill needs a verified account")
    if body.kind == HELP_KIND and body.mode != "free":
        raise HTTPException(400, "a help request is always free")
    if body.mode == "paid" and body.price_paise <= 0:
        raise HTTPException(400, "a paid listing needs a price above zero")
    if body.mode != "paid" and body.price_paise != 0:
        raise HTTPException(400, "only a paid listing carries a price")

    partner = None
    if body.kind == PAID_WORK_KIND:
        # 6.8's anti-scam list: "partners must be verified businesses". Paid
        # work is posted by an organisation, never by a person, and paid work
        # that charges the pensioner is the scam the rule is about.
        if body.partner_id is None:
            raise HTTPException(400, "paid work must be posted by a verified partner")
        partner = db.get(Partner, body.partner_id)
        if partner is None or partner.verified_at is None:
            raise HTTPException(403, "that partner is not verified")
        if body.mode == "paid" and not partner.can_take_payment:
            raise HTTPException(403, "that partner cannot take payment")
    elif body.partner_id is not None:
        raise HTTPException(400, "only paid work listings carry a partner")

    text = f"{body.title}\n{body.body}"
    _refuse_blocked_link(db, text)

    # Plan 15's third automatic check and 6.8's first anti-scam rule. Blocked,
    # not held: see _pay_to_work_reason for why the distinction matters here.
    reason = _pay_to_work_reason(text)
    if reason is not None:
        raise HTTPException(400, reason)

    hold = _hold_reason(user, text)
    at = now()
    row = Listing(
        owner_id=user.id,
        partner_id=body.partner_id,
        type=body.kind,
        title=body.title,
        body=body.body,
        tags=[t.strip() for t in body.tags],
        langs=body.langs,
        mode=body.mode,
        lat=body.lat,
        lng=body.lng,
        radius_km=body.radius_km,
        # Integer paise in, Float rupees out — the one conversion, at storage.
        price=to_rupees(body.price_paise),
        free_for=body.free_for,
        # Pending. Not "open": user-generated content starts in front of a
        # moderator and becomes live when a person says so (plan 15).
        status=LISTING_STATUS_PENDING,
        availability=[w.model_dump() for w in body.availability],
        created_at=at,
    )
    db.add(row)
    db.flush()  # the id exists before the queue item points at it

    for w in body.availability:
        db.add(
            Availability(
                listing_id=row.id,
                weekday=w.weekday,
                start_min=w.start_min,
                end_min=w.end_min,
                time_zone=w.time_zone,
            )
        )

    _queue_listing(
        db,
        row.id,
        hold or "new listing awaiting review",
        scam_signal=hold is not None,
    )
    # Ids only. The text of a listing is somebody's words and does not go into a
    # log line, where it would be copied into a log aggregator, an error report
    # and somebody's inbox.
    log.info("listing %s created pending review by user %s: %s", row.id, user.id, hold or "-")
    db.commit()

    out = listing_view(row, user)
    out["message"] = (
        "Your listing is with a moderator for a moment. We check every new "
        "listing before it reaches the board."
    )
    return out


@router.post("/bookings")
def create_booking(body: BookingIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Book somebody's time.

    The amount is denormalised from the listing HERE, in integer paise, because
    a later edit to the listing must not change what two people already agreed
    to (models/seva.py, Booking). What the seeker pays is the platform price —
    never a sum in a message, and never anything to a listing that asked them
    to pay, because that listing never made it onto the board.
    """
    limit_write(request, user.id)

    at = now()
    if body.slot_at < at - SLOT_MIN_LEEWAY_S:
        raise HTTPException(400, "the slot cannot be in the past")
    if body.slot_at > at + SLOT_MAX_HORIZON_S:
        raise HTTPException(400, "the slot is too far ahead")

    listing = db.get(Listing, body.listing_id)
    if listing is None:
        raise HTTPException(404, "listing not found")
    if listing.status != LISTING_STATUS_OPEN:
        # Not 404: the seeker can see the board, and "that one is not live" is
        # the honest answer — pending, filled, withdrawn and removed all land
        # here and none of them is bookable.
        raise HTTPException(409, "that listing is not open for bookings")
    if listing.owner_id == user.id:
        raise HTTPException(409, "you cannot book your own listing")

    amount_paise = to_paise(listing.price) if listing.mode == "paid" else 0
    if listing.mode == "paid" and user.tier in (listing.free_for or []):
        # 6.8: `free_for` names the tiers that pay nothing. Free to them is not
        # zero-priced — models/seva.py keeps `mode` for exactly this reason.
        amount_paise = 0

    row = Booking(
        listing_id=listing.id,
        seeker_id=user.id,
        # Denormalised at booking time: the provider of record is the owner
        # today, whatever happens to the listing tomorrow.
        provider_id=listing.owner_id,
        slot_at=body.slot_at,
        status="requested",
        amount=to_rupees(amount_paise),
        fee=to_rupees(fee_paise(amount_paise)),
        created_at=at,
    )
    db.add(row)
    db.commit()
    return booking_view(row)


@router.post("/bookings/{booking_id}/confirm")
def confirm_booking(
    booking_id: int, body: BookingConfirmIn, request: Request, user: CurrentUser, db: DbSession
):
    """
    Advance or cancel a booking. One machine, two actors, no silent overwrites.

    requested --confirm(provider)--> confirmed --confirm(seeker)--> completed,
    and cancel is open to either side from either of the first two states. An
    out-of-order tap is 409 with the reason, never a quiet rewrite: the person
    tapping has to SEE that the booking is already done, or they will tell
    somebody it is still on.

    On completion this records state and stops. Rule 4: money does not move
    here. The daily `payouts` job (plan section 12) pays a completed booking
    once its 48-hour hold has elapsed, through a deterministic Razorpay
    reference it can safely retry (python/jobs.py, transfer_paise). Paying from
    the request path would move money on a schedule nobody can audit, and would
    tie a pensioner's payment to the uptime of their own phone signal.
    """
    limit_write(request, user.id)
    if body.action not in _ACTIONS:
        raise HTTPException(400, f"action must be one of {', '.join(_ACTIONS)}")

    row = db.get(Booking, booking_id)
    if row is None:
        raise HTTPException(404, "booking not found")
    if user.id not in (row.seeker_id, row.provider_id):
        raise HTTPException(403, "this is not your booking")

    at = now()
    status = row.status

    if status in ("completed", "cancelled"):
        raise HTTPException(409, f"booking is already {status}")

    if body.action == _CANCEL:
        # 6.8: "cancelled from either" — a pensioner who cannot make it, or a
        # helper who has to visit the hospital, cancels without a negotiation.
        row.status = "cancelled"
        db.commit()
        # A seeker who already paid gets it back (6.8): refunded through Razorpay,
        # idempotently, and recorded either way. Best effort - a failed refund is
        # logged for a human and never undoes the cancellation.
        if row.payment_id:
            from python.features.payments import refund_booking  # noqa: PLC0415

            try:
                refund_booking(db, row, "cancelled before the session")
            except Exception:
                log.exception("refund for booking %s raised", row.id)
        return booking_view(row)

    # action == confirm
    if status == "requested":
        if user.id != row.provider_id:
            raise HTTPException(403, "only the provider can accept a requested booking")
        if row.is_paid and not row.payment_id:
            # 6.8 step 1: the seeker pays first. Accepting a paid session
            # nobody has paid for is how a mentor teaches for free by accident.
            raise HTTPException(409, "payment has not been received for this booking")
        row.status = "confirmed"
    elif status == "confirmed":
        if user.id != row.seeker_id:
            raise HTTPException(403, "only the person who paid can confirm the session happened")
        row.status = "completed"
        row.completed_at = at
        if row.is_paid:
            # The 48-hour hold (plan 12, `payouts`). Recorded, not paid — the
            # job finds rows like this one by payout_held_until and pays them.
            row.payout_held_until = at + PAYOUT_HOLD_SECONDS
    else:  # pragma: no cover — BOOKING_STATUSES has no other live value
        raise HTTPException(409, f"booking is {status} and cannot be confirmed")

    db.commit()
    return booking_view(row)


@router.post("/payments/webhook")
async def payments_webhook(request: Request, db: DbSession):
    """
    Razorpay tells us money moved. Rule 5, and the most important code in this
    file: a provider replays webhook events — after a timeout, on a retry, from
    a second delivery pipeline — and a doubled payout to a retired person is
    real harm. So every verified event is keyed on the PROVIDER'S event id and
    its replay is a no-op: the second copy changes nothing and says so.

    Two more things this endpoint is deliberately careful about:

      * Authentication is HMAC over the RAW request bytes, compared in constant
        time. Not a JSON field — a client that could set its own signature
        field is a client that can pay itself. The bytes are read and verified
        BEFORE anything parses them.
      * The money unit is the provider's own: Razorpay's `amount` is already
        integer paise. It is compared against the booking in paise and
        mismatched amounts change nothing, because a half-payment that credits
        a whole session is the same bug as the float rule in a different coat.

    This endpoint has no user on purpose: it is not a user's request. It is a
    provider's signed message, rate-limited by IP like any other unauthenticated
    write (python/ratelimit.py), and its receipt is a record, not a permission.
    """
    limit_write(request, None)

    secret = os.getenv("RAZORPAY_WEBHOOK_SECRET") or None
    if not secret:
        # Refuse, loudly. Verifying against the wrong key is worse than not
        # verifying at all, and "the webhook is down" is an operational alarm
        # somebody will fix. config.py is owned elsewhere; production supplies
        # this from the secrets manager (plan 9).
        raise HTTPException(503, "payments webhook is not configured; no event accepted")

    raw = await request.body()  # the RAW bytes, before any parsing
    signature = request.headers.get("x-razorpay-signature", "")
    expected = hmac.new(secret.encode("utf-8"), raw, hashlib.sha256).hexdigest()
    if not signature or not constant_time_equals(expected, signature):
        # Nothing is parsed, nothing is stored, nothing is logged beyond this.
        raise HTTPException(401, "invalid webhook signature")

    try:
        payload = json.loads(raw)
    except ValueError:
        raise HTTPException(400, "body is not JSON") from None
    if not isinstance(payload, dict):
        raise HTTPException(400, "body is not a webhook event")

    event_id = payload.get("id")
    if not event_id:
        # Without the provider's id there is no idempotency, and without
        # idempotency there is no safe way to accept this at all.
        raise HTTPException(400, "event has no id; refusing it")
    event_id = str(event_id)
    event_type = str(payload.get("event") or "")
    ledger_key = f"razorpay:{event_id}"

    # THE IDEMPOTENCY GUARD, and it comes first: seen once, processed once.
    # AuditEntry is the processed-event ledger because this schema (owned by
    # others) has no payment_events table; the pre-check makes the ordinary
    # retry path — which is what replays are — a no-op. A unique index on
    # resource+action is the real fix under concurrency and needs a migration
    # this module is not allowed to make; until then this is the guard and the
    # comment is the receipt.
    seen = db.scalar(
        select(AuditEntry).where(
            AuditEntry.action == "payment_webhook", AuditEntry.resource == ledger_key
        )
    )
    if seen is not None:
        log.info("payment event %s already processed; replay ignored", event_id)
        return {"ok": True, "duplicate": True}

    payment = ((payload.get("payload") or {}).get("payment") or {}).get("entity") or {}
    payment_ref = str(payment.get("id") or "") or None
    amount_paise = payment.get("amount")
    notes = payment.get("notes") or {}
    booking_id = None
    try:
        booking_id = int(notes.get("booking_id"))
    except (TypeError, ValueError):
        pass

    result: dict = {"ok": True}
    booking = db.get(Booking, booking_id) if booking_id is not None else None

    if event_type == "payment.captured":
        if booking is None:
            # Real money that matches no booking we know. Recorded and
            # acknowledged so the provider stops retrying, and loud in the log
            # so a human reconciles it — a payment is somebody's rent for the
            # month sitting on our floor.
            log.warning("payment event %s matched no booking (ref=%s)", event_id, payment_ref)
            result["unmatched"] = True
        elif booking.payment_id not in (None, payment_ref):
            # A second capture on one booking is either a double charge or a
            # confused provider. It must not become a second credit.
            log.warning("booking %s already paid by another payment", booking.id)
            result["already_paid"] = True
        elif amount_paise is None or int(amount_paise) != to_paise(booking.amount):
            log.warning("payment event %s amount mismatch on booking %s", event_id, booking.id)
            result["amount_mismatch"] = True
        else:
            # The booking's `payment_id` is the handle the payouts job and the
            # dispute flow reconcile on. Nothing else moves here.
            booking.payment_id = payment_ref
            result["booking"] = booking_view(booking)
    elif event_type in ("payment.failed", "refund.processed"):
        # A failed capture leaves the booking requested for the seeker to
        # retry; a refund is a moderator's decision (6.8 step 5) and is
        # recorded here only so the ledger shows the provider told us.
        result["ignored"] = event_type
    else:
        result["ignored"] = event_type or "unknown"

    db.add(
        AuditEntry(
            action="payment_webhook",
            resource=ledger_key,
            resource_id=booking.id if booking is not None else None,
            detail={
                "event": event_type,
                # The payment id is a reference, not money and not personal
                # data; it is the only identifier that helps a human reconcile.
                "payment_ref": payment_ref,
                "decision": {k: v for k, v in result.items() if k != "booking"},
            },
        )
    )
    db.commit()
    return result
