# =============================================================================
# Feature · The rest of the Anubhav board — plan section 6.8.
#
#   GET    /listings/mine                  my listings, whatever their state
#   GET    /listings/matches               open listings ranked by the match score
#   GET    /listings/{id}
#   PATCH  /listings/{id}                  edit (goes back for review)
#   POST   /listings/{id}/status           filled | withdrawn | reopen
#   GET    /bookings                       my bookings, as seeker and as provider
#   GET    /bookings/{id}
#   GET    /bookings/{id}/messages         the private thread
#   POST   /bookings/{id}/messages
#   POST   /bookings/{id}/rating           after completion, each rates the other
#   POST   /bookings/{id}/dispute          within the 48-hour hold
#   GET    /providers/{id}                 a provider's public summary
#
# Everything here keeps to the module it extends (features/seva.py): money is
# integer paise on the wire, a listing's location is a city and never a
# coordinate, an edit of live text goes back in front of a moderator, and the
# anti-scam rules run on every message a stranger can send another stranger.
# =============================================================================
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from python import matching
from python.deps import CurrentUser, DbSession
from python.features.payments import refund_booking
from python.features.seva import (
    BODY_MAX,
    LISTING_STATUS_OPEN,
    LISTING_STATUS_PENDING,
    TAG_MAX,
    TITLE_MAX,
    _hold_reason,
    _names,
    _pay_to_work_reason,
    _queue_listing,
    _ratings_by_provider,
    _refuse_blocked_link,
    _windows_for,
    booking_view,
    listing_view,
)
from python.models import (
    LANGUAGES,
    Availability,
    Booking,
    BookingMessage,
    Listing,
    Rating,
    SafetyQueueItem,
    User,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.seva.more")
router = APIRouter(tags=["seva"])

OWNER_SETTABLE = ("filled", "withdrawn", "reopen")


class ListingPatch(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=TITLE_MAX)
    body: str | None = Field(default=None, min_length=1, max_length=BODY_MAX)
    tags: list[str] | None = Field(default=None, max_length=8)
    langs: list[str] | None = None


class StatusIn(BaseModel):
    action: str


class MessageIn(BaseModel):
    text: str = Field(min_length=1, max_length=1000)


class RatingIn(BaseModel):
    stars: int = Field(ge=1, le=5)
    text: str | None = Field(default=None, max_length=500)


class DisputeIn(BaseModel):
    reason: str = Field(min_length=3, max_length=500)


# ---------------------------------------------------------------- listings --
def _own_listing(db, user, listing_id: int) -> Listing:
    row = db.get(Listing, listing_id)
    if row is None or row.owner_id != user.id:
        raise HTTPException(404, "listing not found")
    return row


@router.get("/listings/mine")
def my_listings(user: CurrentUser, db: DbSession):
    rows = db.scalars(select(Listing).where(Listing.owner_id == user.id).order_by(Listing.created_at.desc())).all()
    windows = _windows_for(db, {r.id for r in rows})
    out = []
    for r in rows:
        v = listing_view(r, user)
        if windows.get(r.id):
            v["availability"] = windows[r.id]
        out.append(v)
    return {"listings": out}


@router.get("/listings/matches")
def matches(
    user: CurrentUser, db: DbSession, tags: str | None = None, langs: str | None = None, limit: int = 20,
):
    """
    Open listings ranked by plan 6.8's score. `tags` and `langs` are comma lists
    of what the seeker wants; without them the score leans on distance, language
    and rating, which is the honest ranking for someone just browsing.
    """
    if not 1 <= limit <= 50:
        raise HTTPException(400, "limit must be 1..50")
    want_tags = {t.strip().lower() for t in (tags or "").split(",") if t.strip()}
    want_langs = {l.strip() for l in (langs or user.lang or "").split(",") if l.strip()}
    for l in want_langs:
        if l not in LANGUAGES:
            raise HTTPException(400, f"langs must be drawn from {', '.join(LANGUAGES)}")

    rows = db.scalars(
        select(Listing).where(Listing.status == LISTING_STATUS_OPEN, Listing.owner_id != user.id)
        .order_by(Listing.created_at.desc()).limit(200)
    ).all()
    owners = _names(db, {r.owner_id for r in rows})
    ratings = _ratings_by_provider(db, {r.owner_id for r in rows})
    windows = _windows_for(db, {r.id for r in rows})
    seeker = matching.Seeker(tags=want_tags, langs=want_langs, lat=user.lat, lng=user.lng)

    scored = []
    for r in rows:
        rt = ratings.get(r.owner_id) or {}
        count = int(rt.get("count") or 0)
        avg = rt.get("average")
        offer = matching.Offer(
            tags={t.lower() for t in (r.tags or [])}, langs=set(r.langs or []), lat=r.lat, lng=r.lng,
            radius_km=r.radius_km, online=r.lat is None,
            windows=[(w["weekday"], w["start_min"], w["end_min"]) for w in windows.get(r.id, [])],
            rating_sum=(avg or 0) * count, rating_count=count,
        )
        scored.append((matching.score(seeker, offer), matching.explain(seeker, offer), r))
    scored.sort(key=lambda t: (-t[0], -t[2].created_at))
    return {
        "matches": [
            {**listing_view(r, owners.get(r.owner_id), ratings.get(r.owner_id)), "score": sc, "why": why}
            for sc, why, r in scored[:limit]
        ]
    }


@router.get("/listings/{listing_id}")
def get_listing(listing_id: int, user: CurrentUser, db: DbSession):
    row = db.get(Listing, listing_id)
    if row is None or (row.status != LISTING_STATUS_OPEN and row.owner_id != user.id):
        raise HTTPException(404, "listing not found")
    owners = _names(db, {row.owner_id})
    v = listing_view(row, owners.get(row.owner_id), _ratings_by_provider(db, {row.owner_id}).get(row.owner_id))
    w = _windows_for(db, {row.id}).get(row.id)
    if w:
        v["availability"] = w
    return v


@router.patch("/listings/{listing_id}")
def edit_listing(listing_id: int, body: ListingPatch, request: Request, user: CurrentUser, db: DbSession):
    """
    Editing live text sends the listing back to a moderator. Otherwise a listing
    would be approved as one thing and then edited into a scam, which is the
    oldest trick on any board. Price and kind cannot be edited: a booking
    already agreed a price, and a "help" request that becomes "paid work" is a
    different listing and needs a new one.
    """
    limit_write(request, user.id)
    row = _own_listing(db, user, listing_id)
    data = body.model_dump(exclude_unset=True, exclude_none=True)
    if not data:
        raise HTTPException(400, "nothing to change")
    for tag in data.get("tags", []):
        if not tag.strip() or len(tag) > TAG_MAX:
            raise HTTPException(400, f"each tag must be 1..{TAG_MAX} characters")
    for lang in data.get("langs", []):
        if lang not in LANGUAGES:
            raise HTTPException(400, f"langs must be drawn from {', '.join(LANGUAGES)}")

    new_title, new_body = data.get("title", row.title), data.get("body", row.body)
    text = f"{new_title}\n{new_body}"
    _refuse_blocked_link(db, text)
    reason = _pay_to_work_reason(text)
    if reason:
        raise HTTPException(400, reason)

    text_changed = "title" in data or "body" in data
    for k in ("title", "body", "langs"):
        if k in data:
            setattr(row, k, data[k])
    if "tags" in data:
        row.tags = [t.strip() for t in data["tags"]]
    if text_changed and row.status == LISTING_STATUS_OPEN:
        row.status = LISTING_STATUS_PENDING
        hold = _hold_reason(user, text)
        _queue_listing(db, row.id, hold or "edited listing awaiting review", scam_signal=hold is not None)
    db.commit()
    out = listing_view(row, user)
    if text_changed:
        out["message"] = "Your change is with a moderator for a moment."
    return out


@router.post("/listings/{listing_id}/status")
def set_listing_status(listing_id: int, body: StatusIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    if body.action not in OWNER_SETTABLE:
        raise HTTPException(400, f"action must be one of {', '.join(OWNER_SETTABLE)}")
    row = _own_listing(db, user, listing_id)
    if row.status == "removed":
        raise HTTPException(409, "a moderator removed this listing")
    if body.action == "reopen":
        if row.status not in ("filled", "withdrawn"):
            raise HTTPException(409, f"a {row.status} listing cannot be reopened")
        # Back to open directly: it was already approved once, and its text has
        # not changed since (an edit would have sent it back to review).
        row.status = LISTING_STATUS_OPEN
    else:
        row.status = body.action
    db.commit()
    return listing_view(row, user)


# ---------------------------------------------------------------- bookings --
def _own_booking(db, user, booking_id: int) -> Booking:
    b = db.get(Booking, booking_id)
    if b is None or user.id not in (b.seeker_id, b.provider_id):
        raise HTTPException(404, "booking not found")
    return b


@router.get("/bookings")
def my_bookings(user: CurrentUser, db: DbSession, role: str | None = None, status: str | None = None):
    q = select(Booking)
    if role == "seeker":
        q = q.where(Booking.seeker_id == user.id)
    elif role == "provider":
        q = q.where(Booking.provider_id == user.id)
    else:
        q = q.where(or_(Booking.seeker_id == user.id, Booking.provider_id == user.id))
    if status:
        q = q.where(Booking.status == status)
    rows = db.scalars(q.order_by(Booking.slot_at.desc()).limit(100)).all()
    lids = {b.listing_id for b in rows}
    titles = {l.id: l.title for l in db.scalars(select(Listing).where(Listing.id.in_(lids))).all()} if lids else {}
    people = _names(db, {b.seeker_id for b in rows} | {b.provider_id for b in rows})
    out = []
    for b in rows:
        other_id = b.provider_id if b.seeker_id == user.id else b.seeker_id
        o = people.get(other_id)
        out.append({
            **booking_view(b),
            "listing_title": titles.get(b.listing_id),
            "role": "seeker" if b.seeker_id == user.id else "provider",
            "other": {"id": other_id, "name": (o.address_as or o.name) if o else None},
        })
    return {"bookings": out}


@router.get("/bookings/{booking_id}")
def get_booking(booking_id: int, user: CurrentUser, db: DbSession):
    b = _own_booking(db, user, booking_id)
    listing = db.get(Listing, b.listing_id)
    rated = {r.by_id for r in db.scalars(select(Rating).where(Rating.booking_id == b.id)).all()}
    return {
        **booking_view(b),
        "listing_title": listing.title if listing else None,
        "role": "seeker" if b.seeker_id == user.id else "provider",
        "you_rated": user.id in rated,
        "never_pay_outside_app": "Never pay outside the app. Money for a session moves only through Doosri Pari.",
    }


@router.get("/bookings/{booking_id}/messages")
def list_messages(booking_id: int, user: CurrentUser, db: DbSession, after: float | None = None):
    b = _own_booking(db, user, booking_id)
    q = select(BookingMessage).where(BookingMessage.booking_id == b.id)
    if after is not None:
        q = q.where(BookingMessage.at > after)
    rows = db.scalars(q.order_by(BookingMessage.at).limit(200)).all()
    return {"messages": [{"id": m.id, "mine": m.sender_id == user.id, "text": m.text, "at": m.at} for m in rows]}


@router.post("/bookings/{booking_id}/messages")
def send_message(booking_id: int, body: MessageIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    b = _own_booking(db, user, booking_id)
    if b.status in ("cancelled",):
        raise HTTPException(409, "this booking was cancelled")
    # 6.8: the anti-scam rules are hard-coded and apply to what two people say to
    # each other about money as much as to a listing. A session's money moves
    # through the app; a message asking for a transfer is the scam.
    reason = _pay_to_work_reason(body.text)
    if reason:
        raise HTTPException(400, "Please do not ask for or share payment details here. Money moves only through the app.")
    _refuse_blocked_link(db, body.text)
    m = BookingMessage(booking_id=b.id, sender_id=user.id, text=body.text.strip())
    db.add(m)
    db.commit()
    return {"id": m.id, "at": m.at}


@router.post("/bookings/{booking_id}/rating")
def rate(booking_id: int, body: RatingIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    b = _own_booking(db, user, booking_id)
    if b.status != "completed":
        raise HTTPException(409, "you can rate a session once it is completed")
    if db.scalar(select(Rating.id).where(Rating.booking_id == b.id, Rating.by_id == user.id)):
        raise HTTPException(409, "you have already rated this session")
    db.add(Rating(booking_id=b.id, by_id=user.id, stars=body.stars, text=(body.text or "").strip() or None))
    db.commit()
    return {"ok": True}


@router.post("/bookings/{booking_id}/dispute")
def dispute(booking_id: int, body: DisputeIn, request: Request, user: CurrentUser, db: DbSession):
    """
    "Disputes go to moderators" (6.8 step 5). Only the seeker, only before the
    money has been paid out: the 48-hour hold exists so there is a window to say
    something went wrong. Disputing stops the payout job from picking the
    booking up.
    """
    limit_write(request, user.id)
    b = _own_booking(db, user, booking_id)
    if b.seeker_id != user.id:
        raise HTTPException(403, "only the person who booked can raise a dispute")
    if b.status not in ("confirmed", "completed"):
        raise HTTPException(409, f"a {b.status} booking cannot be disputed")
    if b.payout_id:
        raise HTTPException(409, "this session has already been paid out; please write to support")
    b.status = "disputed"
    at = now()
    db.add(SafetyQueueItem(
        target_type="booking", target_id=b.id, why=f"dispute: {body.reason[:100]}", score=0.8,
        status="open", created_at=at, due_at=at + 24 * 3600,
    ))
    db.commit()
    return {**booking_view(b), "message": "A moderator will look at this within a day. No money will move until they do."}


@router.get("/providers/{provider_id}")
def provider(provider_id: int, user: CurrentUser, db: DbSession):
    p = db.get(User, provider_id)
    if p is None or p.deleted_at is not None:
        raise HTTPException(404, "not found")
    sessions = db.scalar(
        select(func.count(Booking.id)).where(Booking.provider_id == p.id, Booking.status == "completed")
    ) or 0
    listings = db.scalars(
        select(Listing).where(Listing.owner_id == p.id, Listing.status == LISTING_STATUS_OPEN).order_by(Listing.created_at.desc())
    ).all()
    return {
        "id": p.id,
        "name": p.address_as or p.name,
        "tier": p.tier,
        "verified": p.tier_rank >= 3,
        "service_type": p.service_type,
        "city_id": p.city_id,
        "completed_sessions": sessions,
        "ratings": _ratings_by_provider(db, {p.id}).get(p.id) or {"average": None, "count": 0},
        "listings": [listing_view(l, p) for l in listings],
    }
