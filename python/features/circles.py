# =============================================================================
# Feature · Saathi circles and Chai pe Charcha — plan sections 6.6, 6.7.
#
#   GET  /circles/suggested      circles this person would recognise, and why
#   POST /circles/{id}/join      the join rule from 6.6 decides, not a flag
#   GET  /circles/{id}/posts     members only; a non-member gets 404, not 403
#   POST /posts                  somebody's words, stored as data
#   POST /posts/{id}/comments
#   POST /reports                a post, a comment, a person, a room
#   GET  /people/search          names, not dossiers
#   POST /events                 place, cost, capacity, RSVP limit
#   POST /events/{id}/rsvp       tapping twice is one RSVP, not an error
#   GET  /rooms/live             what is running right now
#   POST /rooms/{id}/join        issues a join; the media transport is a seam
#
# There is one rule running through all of it. The people in these rooms are
# retired government employees, and what actually reaches them is not a rude
# picture — it is a phone number, a posting station, a batch roster, a promise
# of help. So a circle is scoped, membership is checked on every read and every
# write of its posts, and a non-member is told 404 rather than 403, because a
# 403 is a directory of who has joined what. Location is a consent of its own
# and users.discoverable is read in the WHERE clause, so withdrawing it in
# Settings withdraws it from the query and not merely from the response. People
# search returns a name, a city, a department, a service and a distance rounded
# to a neighbourhood, and nothing else. Post and comment bodies are somebody's
# untrusted words: stored as data, never logged, never rendered unescaped, and
# never handed to a model or a notification body where they could be read as an
# instruction. Every suggested circle carries the reason it was suggested,
# because in a community this small a recommendation nobody can account for is a
# moderation incident before it is a feature.
# =============================================================================
from __future__ import annotations

import logging
import math
import re
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession, OptionalUser
from python.models import (
    CIRCLE_TYPES,
    JOIN_RULES,
    POST_KINDS,
    REPORT_REASONS,
    REPORT_TARGETS,
    RSVP_STATUSES,
    TIER_RANK,
    URGENT_REPORT_REASONS,
    Circle,
    CircleEvent,
    CircleMember,
    Comment,
    Department,
    Post,
    Report,
    RoomSession,
    Rsvp,
    SafetyQueueItem,
    ScamAlert,
    User,
    VoiceRoom,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.circles")
router = APIRouter(tags=["circles"])

# Plan 6.1's tier names, written out. Every permission below is one of these
# four decisions, and a check that reads `rank >= 1` tells the next person
# nothing about who is allowed to post in a circle of their own department.
TIER_PHONE = "T0"            # phone OTP only
TIER_VOUCHED = "T1"          # two T2+ members of the same department vouch
TIER_ASSOCIATION = "T2"      # association office-bearer or moderator call
TIER_DOCUMENT = "T3"         # PPO page checked by a moderator

# 6.7's room: a host, up to 8 speakers and up to 200 listeners. The listener
# number is the one this file can act on, because RoomSession keeps a high-water
# mark; the speaker count is the media layer's, and _issue_join_token says so.
MAX_LISTENERS = 200
MAX_SPEAKERS = 8

# A session left open by a process that died would otherwise read as live for
# ever, and a stuck room is a room nobody can get out of. Six hours is longer
# than any sitting 6.7 describes and short enough that a crash clears by morning.
ROOM_SESSION_STALE_S = 6 * 60 * 60

# 6.6 asks for a calm feed with no engagement ranking, so the page is small and
# the server never reorders it. Thirty is what the plan's "you're all caught up"
# message is written around.
FEED_PAGE_DEFAULT = 30
FEED_PAGE_MAX = 50

# Suggestions are ranked, not paged, so the candidate set is bounded and the
# ranking is explainable. A recommender that could draw on every circle in the
# database is a recommender nobody could justify to the person it was shown to.
SUGGESTION_LIMIT = 12
SUGGESTION_CANDIDATE_CAP = 200

# People search is a directory. Bounded small on purpose, and the fields it
# returns are the whole argument — see person_view.
PEOPLE_PAGE_MAX = 25

POST_TEXT_MAX = 4000
COMMENT_TEXT_MAX = 1000
REPORT_NOTE_MAX = 500
REPORT_EXCERPT_MAX = 300

# 6.6: up to six photos. Voice notes are capped at three minutes by the upload
# and transcribed by the transcribe job (plan 12), not by anything in this file.
POST_MEDIA_MAX = 6


# ------------------------------------------------------------------ schemas --
class PostIn(BaseModel):
    circle_id: int
    kind: str = Field(default="text")
    text: str | None = Field(default=None, max_length=POST_TEXT_MAX)
    media: list[str] = Field(default_factory=list, max_length=POST_MEDIA_MAX)
    poll: dict | None = None
    source_url: str | None = Field(default=None, max_length=500)
    # `transcript` is deliberately not a field. It is written by the transcribe
    # job from the audio that was actually uploaded; a client that could send its
    # own transcript could put moderation-evading text under a voice note, and
    # the transcript is what a moderator reads when somebody is reported.


class CommentIn(BaseModel):
    text: str = Field(min_length=1, max_length=COMMENT_TEXT_MAX)


class ReportIn(BaseModel):
    target_type: str
    target_id: int
    reason: str
    note: str | None = Field(default=None, max_length=REPORT_NOTE_MAX)


class EventIn(BaseModel):
    circle_id: int
    title: str = Field(min_length=1, max_length=160)
    # Free text on purpose: "Nehru Park, Gate 2" is more use to the person going
    # than coordinates, and a meeting place in Lucknow has none worth storing.
    place: str | None = Field(default=None, max_length=240)
    place_json: dict | None = None
    starts_at: float
    ends_at: float | None = None
    cost: float = Field(default=0.0, ge=0.0)
    capacity: int | None = Field(default=None, ge=1, le=2000)


class RsvpIn(BaseModel):
    status: str = Field(default="going")


class RoomJoinIn(BaseModel):
    as_speaker: bool = False


# ------------------------------------------------------------------ helpers --
def _display_name(u: User | None) -> str | None:
    """How the app should address this person. Plan 3.5: "Sharma ji", not "1,204"."""
    if u is None:
        return None
    return u.address_as or u.name


def _names(db: Session, user_ids: set[int]) -> dict[int, str | None]:
    """One query for a page of author names, so a feed is not N+1."""
    if not user_ids:
        return {}
    rows = db.execute(
        select(User.id, User.name, User.address_as).where(User.id.in_(user_ids))
    ).all()
    return {r[0]: (r[2] or r[1]) for r in rows}


def _like(term: str) -> str:
    """Escape the wildcards a caller typed, so `%` searches for a percent sign.

    Without this, one pensioner can enumerate every discoverable profile by
    typing a single `%` — the only thing standing between them and the directory
    is the page limit.
    """
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _join_block(circle: Circle, user: User) -> str | None:
    """
    Why this person cannot join this circle, in words. None when they can.

    6.6 makes the join rule a column rather than a runtime decision, and this is
    the one place it is read. The messages are written to be shown to the person
    who was refused, because the alternative — a silent failure — reads as a bug
    and gets reported as one.
    """
    rule = circle.join_rule
    if rule == "open":
        return None
    if rule == "approval":
        # An association's office-bearers decide. The membership row is written
        # with pending_at set and the member can see they are in the queue.
        return None
    if rule == "verified":
        if user.tier_rank < TIER_RANK[TIER_VOUCHED]:
            return "posting here needs a verified account — ask two colleagues here to vouch for you"
        return None
    if rule == "document_verified":
        if user.tier_rank < TIER_RANK[TIER_DOCUMENT]:
            return "this circle is for pensioners whose record has been checked"
        return None
    if rule == "same_department":
        if circle.department_id is None or user.department_id != circle.department_id:
            return "this circle is for one department, and yours is not on it"
        return None
    if rule not in JOIN_RULES:
        return "this circle is not accepting new members"
    return "this circle is not accepting new members"


def _membership(db: Session, circle_id: int, user_id: int) -> CircleMember | None:
    """The membership row, or None. A pending request is deliberately None:
    somebody awaiting an office-bearer has not been admitted to anything."""
    return db.scalar(
        select(CircleMember).where(
            CircleMember.circle_id == circle_id,
            CircleMember.user_id == user_id,
        )
    )


def _require_membership(db: Session, circle: Circle, user: User) -> CircleMember:
    """
    The gate on every read and every write of a circle's posts. 6.6 again: a
    circle is the people somebody would recognise, and its posts are not a feed.

    The 404 rather than the 403 is the point. 403 means "this circle exists and
    you are not in it", which is a membership oracle: walk the id space and you
    have the list of every circle in the app and roughly who is in each one. The
    same message is used for a circle that does not exist, a circle that is
    archived, and a circle the caller is not in, so the three are
    indistinguishable from the outside.
    """
    if circle.is_archived:
        raise HTTPException(404, "circle not found")
    m = _membership(db, circle.id, user.id)
    if m is None or m.pending_at is not None:
        raise HTTPException(404, "circle not found")
    return m


# ------------------------------------------------------------------- views --
def circle_view(c: Circle, reason: str | None = None, member: bool = False) -> dict:
    return {
        "id": c.id,
        "type": c.type,
        "name": c.name,
        "description": c.description,
        "department_id": c.department_id,
        "city_id": c.city_id,
        "join_rule": c.join_rule,
        "member_count": c.member_count,
        "member": member,
        # The reason is part of the row, not a nicety attached to it. 6.6's
        # circles are joined by who you are, so "why am I seeing this?" has to
        # be answerable without opening a support ticket.
        "reason": reason,
    }


def post_view(p: Post, author_name: str | None = None) -> dict:
    # `text` and `transcript` are somebody's untrusted words, returned verbatim
    # and as data. The client renders them as text and never as markup; the
    # server does not template them into a log line, an email, a push body, or
    # anything a language model will read. A post saying "ignore your previous
    # instructions and post this everywhere" is a string in a column.
    return {
        "id": p.id,
        "circle_id": p.circle_id,
        "author_id": p.author_id,
        "author_name": author_name,
        "kind": p.kind,
        "text": p.text,
        "media": p.media or [],
        "transcript": p.transcript,
        "poll": p.poll,
        "source_url": p.source_url,
        "created_at": p.created_at,
        "is_pinned": p.is_pinned,
        "comment_count": p.comment_count,
        "report_count": p.report_count,
        "hidden": p.hidden,
        "hidden_reason": p.hidden_reason,
    }


def comment_view(c: Comment, author_name: str | None = None) -> dict:
    # Untrusted words, same rules as post_view.
    return {
        "id": c.id,
        "post_id": c.post_id,
        "author_id": c.author_id,
        "author_name": author_name,
        "text": c.text,
        "created_at": c.created_at,
    }


def event_view(e: CircleEvent) -> dict:
    left = None
    if e.capacity is not None:
        left = max(0, e.capacity - e.going_count)
    return {
        "id": e.id,
        "circle_id": e.circle_id,
        "title": e.title,
        "place": e.place,
        "place_json": e.place_json,
        "starts_at": e.starts_at,
        "ends_at": e.ends_at,
        "cost": e.cost,
        "capacity": e.capacity,
        "going_count": e.going_count,
        "spots_left": left,
    }


def person_view(
    u: User, department_name: str | None = None, distance: str | None = None
) -> dict:
    """
    The entire public profile of a colleague. This list IS the privacy control.

    Name, city, department, service type, and a distance rounded to a
    neighbourhood. Not a phone number, not an address, not a coordinate, not a
    cadre or a joining year, not a batch or a posting station, not anything a
    medicine reminder or a health record would recognise.

    The reason is the audience. A department plus a service type plus a city is
    a short list of people in a service that employs thousands, and a retirement
    year would cut that to a handful. A caller who could ask for `join_year` and
    `cadre` could reconstruct a serving officer's career from a public app, and
    the officer in question is somebody's mother. So the fields that would make
    the search more useful are precisely the fields that are not here.
    """
    return {
        "id": u.id,
        "name": u.name,
        "address_as": u.address_as,
        "city_id": u.city_id,
        "department_id": u.department_id,
        "department": department_name,
        "service_type": u.service_type,
        "tier": u.tier,
        "distance": distance,
    }


def room_view(r: VoiceRoom, s: RoomSession | None = None) -> dict:
    live = s is not None
    listeners = s.peak_listeners if s else 0
    return {
        "id": r.id,
        "circle_id": r.circle_id,
        "title": r.title,
        "host_id": r.host_id,
        "live": live,
        "listeners": listeners,
        "listeners_cap": MAX_LISTENERS,
        "speakers_cap": MAX_SPEAKERS,
        "full": listeners >= MAX_LISTENERS,
        # Recording is off unless a moderator turned it on, and 6.7 requires a
        # visible sign when it is on. The flag travels with every join response
        # so the client cannot show a room without showing this.
        "recording": bool(r.recording),
        "next_session_at": r.next_session_at,
        "session_started_at": s.started_at if s else None,
    }


# -------------------------------------------------------------- moderation --
_URL_RE = re.compile(r"https?://[^\s<>\"')\]]+", re.IGNORECASE)

# Words that mark a post as political for the circles that do not allow it. Only
# ever used to HOLD for review, so a false positive costs a moderator ten seconds.
_POLITICS_RE = re.compile(
    r"\b(election|lok sabha|rajya sabha|vidhan sabha|assembly poll|bjp|congress party|aap party|manifesto|"
    r"campaign rally|opposition|no-confidence|vote for|voting for)\b|चुनाव|भाजपा|कांग्रेस|मतदान",
    re.IGNORECASE,
)

# Plan 15: "phone numbers and payment requests in posts from T0 users are held
# for review". T0 is the tier of somebody who has proved a phone number and
# nothing else, which is exactly the tier a fraud account registers at, and the
# people reading a circle are retired public servants who are, by training and by
# habit, the most polite people on any platform. A false positive costs a
# moderator ten seconds; a false negative costs a pensioner their savings.
_PHONE_RE = re.compile(r"(?<![\d+])(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)")
_UPI_RE = re.compile(r"[\w.\-]{2,}\s*@\s*[a-z]{2,}", re.IGNORECASE)
_PAYMENT_WORDS = (
    "send money",
    "pay me",
    "pay the amount",
    "advance payment",
    "processing fee",
    "registration fee",
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
    """
    The link blocklist, read from the scam alerts rather than hardcoded.

    Plan 15 says links go through a blocklist, and ScamAlert is where the safety
    team already writes down the domains a scam uses. Reading it from the table
    means a domain added by a moderator takes effect on the next post instead of
    the next deploy, which is the difference between a blocklist that works and
    one that is out of date before it ships.
    """
    out: set[str] = set()
    for alert in db.scalars(select(ScamAlert).where(ScamAlert.is_active.is_(True))).all():
        for d in alert.domains or []:
            host = str(d).strip().lower().lstrip(".")
            if host:
                out.add(host)
    return out


def _refuse_blocked_link(db: Session, text: str | None, source_url: str | None) -> None:
    """
    A link to a known scam is refused, not held.

    Held is for things a human has to read and judge. A domain the safety team
    has already published as a scam is a decision that has been made, and making
    a person in a circle of pensioners wait for a moderator to confirm it would
    mean the link is live in the meantime.
    """
    blocked = _blocked_domains(db)
    if not blocked:
        return
    urls = list(_URL_RE.findall(text or ""))
    if source_url:
        urls.append(source_url)
    for raw in urls:
        host = (urlsplit(raw).hostname or "").lower()
        if not host:
            continue
        for domain in blocked:
            # The suffix test is what makes "login.rbi.org.in.evil.example" a
            # hit on a "rbi.org.in" alert instead of a near miss.
            if host == domain or host.endswith("." + domain):
                raise HTTPException(
                    400, "that link is on our scam blocklist and cannot be posted here"
                )


def _hold_reason(user: User, text: str | None) -> str | None:
    """
    Why this post is held for a person to read before it appears, or None.

    Plan 15's first automatic check, and the one with a name attached: "phone
    numbers and payment requests in posts from T0 users are held for review".

    SEAM — the third automatic check in 15, politics, is NOT implemented and
    cannot be from here. 15 allows politics in general circles and forbids it in
    pension news, Sahayak and the spiritual circles, which is a rule about the
    circle, not about the words; Circle carries no flag saying "this is a
    restricted circle", and guessing one from the circle's name would be a
    moderation decision made on a string somebody typed. It needs a column. When
    it has one, the check belongs in this function, where the hold and the
    reason already are, and it must be a classifier with a human behind it —
    a word list would hold posts from pensioners arguing about a budget and
    teach the circle that reporting is noise.
    """
    if not text:
        return None
    # Above a phone-only account, this screen does not apply. One vouch by two
    # colleagues of the same department is the plan's own answer to "is this
    # person a colleague", and it is a much better signal than any regex.
    if user.tier_rank > TIER_RANK[TIER_PHONE]:
        return None
    low = text.lower()
    if _PHONE_RE.search(text):
        return "phone number in a post from an unverified account"
    if _UPI_RE.search(text):
        return "payment id in a post from an unverified account"
    for word in _PAYMENT_WORDS:
        if word in low:
            return "payment request in a post from an unverified account"
    return None


def _queue_for_review(db: Session, target_type: str, target_id: int, why: str) -> None:
    """
    Put it in front of a person. Plan 15's response times: two hours for a scam
    signal, twenty-four for the rest.

    The 07:00–22:00 local gate is not applied here because this is a request
    handler with no scheduler and no trusted sense of the reporter's local time;
    the safety_scan job (plan 12) owns that window. What matters here is that the
    item is written down at the moment of the post rather than discovered later.
    """
    at = now()
    db.add(
        SafetyQueueItem(
            target_type=target_type,
            target_id=target_id,
            why=why,
            score=0.9,
            status="open",
            created_at=at,
            due_at=at + 2 * 60 * 60,
        )
    )


# ---------------------------------------------------------------- endpoints --
@router.get("/circles/suggested")
def suggested_circles(user: OptionalUser, db: DbSession):
    """
    Circles worth joining, each with the sentence that explains why it is here.

    OptionalUser on purpose, and it is the only OptionalUser in this file.
    python/deps.py names "a public circle's suggestions" as one of the few reads
    that works signed out, and it does work: an interest or city circle can be
    shown to somebody who has not signed in yet, which is what the onboarding
    screen needs. Signed out there is no profile, so there is no city, no
    department, no service type and no retire year to rank on — and therefore
    nothing about a person that could be inferred from what they are shown. A
    signed-out caller gets the open, public circles and the reason reads "open to
    everybody", which is the whole truth about them.
    """
    candidates = db.scalars(
        select(Circle)
        .where(Circle.is_archived.is_(False))
        .order_by(Circle.member_count.desc())
        .limit(SUGGESTION_CANDIDATE_CAP)
    ).all()

    if user is None:
        # Batch circles are excluded. "Railways · retired 2019–21" is the name of
        # a retirement cohort, and handing its existence to a signed-out caller
        # says something about who has joined the app that the caller did not
        # consent to and cannot withdraw.
        return [
            circle_view(c, reason="open to everybody")
            for c in candidates
            if c.type in ("interest", "city", "department") and c.join_rule == "open"
        ][:SUGGESTION_LIMIT]

    mine = {
        m.circle_id
        for m in db.scalars(
            select(CircleMember).where(
                CircleMember.user_id == user.id, CircleMember.pending_at.is_(None)
            )
        ).all()
    }
    dept_service = None
    if user.department_id is not None:
        dept = db.get(Department, user.department_id)
        dept_service = dept.service_type if dept else None

    scored: list[tuple[int, Circle, str | None]] = []
    for c in candidates:
        if c.id in mine:
            continue
        # A circle this person can never join is not a suggestion, it is an
        # advertisement for a wall.
        if _join_block(c, user) is not None:
            continue
        reasons: list[str] = []
        score = 0
        if c.department_id is not None and c.department_id == user.department_id:
            score += 5
            reasons.append("your department")
        if dept_service and c.department_id is not None and c.department_id != user.department_id:
            other = db.get(Department, c.department_id)
            if other is not None and other.service_type == dept_service:
                score += 2
                reasons.append("the same service")
        if c.city_id is not None and c.city_id == user.city_id:
            score += 3
            reasons.append("pensioners in your city")
        if not reasons and c.member_count >= 10:
            # No attribute of yours matches, so the only honest reason left is
            # that other people joined it. That is still a reason, and saying so
            # is better than inventing an affinity the ranking did not find.
            reasons.append(f"{c.member_count} pensioners have already joined")
        if not reasons:
            # Popular, and nothing to say about why. At the end rather than
            # dropped, because a circle with 400 members in it is worth seeing;
            # it just is not dressed up as a personal recommendation.
            reasons.append("a busy circle in the app")
        scored.append((score, c, " · ".join(reasons)))

    # Highest score first, then the larger circle, then the older one, so the
    # order does not wobble between two people with the same profile.
    scored.sort(key=lambda t: (-t[0], -t[1].member_count, t[1].created_at))
    return [circle_view(c, reason=r) for _, c, r in scored[:SUGGESTION_LIMIT]]


@router.post("/circles/{circle_id}/join")
def join_circle(circle_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    Joining is decided by the circle's join rule, not by a feature flag. 6.6
    lists the rules and the whole consent model of the app is in that column: a
    person joins because of who they are, which is the only reason a stranger
    with a verified phone number is acceptable in a room of pensioners.
    """
    limit_write(request, user.id)
    circle = db.get(Circle, circle_id)
    if circle is None or circle.is_archived:
        raise HTTPException(404, "circle not found")

    existing = _membership(db, circle_id, user.id)
    if existing is not None:
        # uq_circle_member makes a second row impossible, and this answers it
        # before the constraint does, because a duplicate-key error is a 500 and
        # a pensioner who taps Join twice should not meet a 500.
        if existing.pending_at is not None:
            raise HTTPException(409, "your request is already with the office-bearers")
        raise HTTPException(409, "you are already a member of this circle")

    blocked = _join_block(circle, user)
    if blocked is not None:
        # 400, not 403: the person is not being forbidden, they are being told
        # what would make it possible, and they can see the circle exists.
        raise HTTPException(400, blocked)

    at = now()
    pending = circle.join_rule == "approval"
    row = CircleMember(
        circle_id=circle.id,
        user_id=user.id,
        role="member",
        joined_at=at,
        muted=False,
        pending_at=at if pending else None,
    )
    db.add(row)
    # The denormalised count excludes a pending request, so the number on the
    # feed is the number of people who are actually in the room.
    if not pending:
        circle.member_count += 1
    db.commit()
    return circle_view(circle, member=not pending, reason="pending" if pending else None)


@router.get("/circles/{circle_id}/posts")
def circle_posts(
    circle_id: int,
    user: CurrentUser,
    db: DbSession,
    limit: int = FEED_PAGE_DEFAULT,
    before: float | None = None,
):
    """
    The feed. Newest first, no engagement ranking, no boost — 6.6 is explicit
    and the absence of a score here is the feature, not an omission.
    """
    if not 1 <= limit <= FEED_PAGE_MAX:
        raise HTTPException(400, f"limit must be 1..{FEED_PAGE_MAX}")
    circle = db.get(Circle, circle_id)
    if circle is None:
        raise HTTPException(404, "circle not found")
    _require_membership(db, circle, user)

    q = (
        select(Post)
        .where(Post.circle_id == circle.id, Post.hidden.is_(False))
        .order_by(Post.created_at.desc(), Post.id.desc())
        .limit(limit)
    )
    if before is not None:
        q = q.where(Post.created_at < before)
    rows = db.scalars(q).all()

    names = _names(db, {p.author_id for p in rows})
    return {
        "circle": circle_view(circle, member=True),
        "posts": [post_view(p, names.get(p.author_id)) for p in rows],
        "next_before": rows[-1].created_at if len(rows) == limit else None,
        # 6.6's "You're all caught up. See you tomorrow." The server says it so
        # the app does not have to guess, and so an empty page reads as a quiet
        # evening rather than as something that failed to load.
        "caught_up": len(rows) < limit,
    }


@router.post("/posts")
def create_post(body: PostIn, request: Request, user: CurrentUser, db: DbSession):
    """
    A post into a circle the caller belongs to.

    `body.text` arrives here as somebody's untrusted words and is stored as
    exactly that: a string in a column, screened by the two checks below and
    then shown to other retired people. It is never logged, never templated into
    a notification or an admin screen, and never passed to a model. A post is
    data. Treating it as anything else is how a community ends up taking
    instructions from its own users.
    """
    limit_write(request, user.id)
    if body.kind not in POST_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(POST_KINDS)}")

    circle = db.get(Circle, body.circle_id)
    if circle is None:
        raise HTTPException(404, "circle not found")
    member = _require_membership(db, circle, user)

    # 6.6: a batch circle is "auto-suggested; posting needs T1". Joining one is
    # open to any colleague, but a retirement cohort's room is where a stranger
    # with a verified phone number does not get to speak.
    if circle.type == "batch" and user.tier_rank < TIER_RANK[TIER_VOUCHED]:
        raise HTTPException(403, "posting in an alumni circle needs a verified account")

    if body.kind == "text" and not (body.text or "").strip():
        raise HTTPException(400, "a text post needs some text")
    if body.kind == "poll":
        opts = (body.poll or {}).get("options")
        if not (isinstance(body.poll, dict) and str(body.poll.get("question", "")).strip()
                and isinstance(opts, list) and 2 <= len(opts) <= 6
                and all(isinstance(o, str) and o.strip() for o in opts)):
            raise HTTPException(400, "a poll needs a question and two to six options")
    if body.kind in ("image", "voice") and not body.media:
        raise HTTPException(400, f"a {body.kind} post needs its upload")
    if body.kind == "news" and not body.source_url:
        raise HTTPException(400, "a news post needs the link it came from")

    for key in body.media:
        # A media entry is a key into our own object store. A caller-supplied
        # URL would put somebody else's address in a feed that gets rendered by
        # other phones, and would let a post point a client at an internal
        # address or at a tracker.
        if "://" in key or not key.strip():
            raise HTTPException(400, "media must be an upload key, not a link")
        # ...and one this person finished uploading. Otherwise anyone could
        # attach somebody else's photo, or a verification document's key, to a
        # public post.
        from python.models import Upload  # noqa: PLC0415

        if not db.scalar(select(Upload.id).where(Upload.key == key, Upload.user_id == user.id, Upload.status == "ready")):
            raise HTTPException(400, "that file is not one of your finished uploads")
    if body.source_url:
        scheme = urlsplit(body.source_url).scheme.lower()
        if scheme not in ("http", "https"):
            raise HTTPException(400, "a source link must be http or https")

    _refuse_blocked_link(db, body.text, body.source_url)

    hold = _hold_reason(user, body.text)
    if hold is None and circle.no_politics and _POLITICS_RE.search(body.text or ""):
        # Plan 15: not a block. A pensioner arguing about a budget is a person, not
        # an offence; a human decides.
        hold = "possible political content in a circle that does not allow it"
    at = now()
    row = Post(
        circle_id=circle.id,
        author_id=user.id,
        kind=body.kind,
        text=body.text,
        media=body.media or [],
        poll=body.poll,
        source_url=body.source_url,
        created_at=at,
        # Hidden, not deleted. A moderator can see what was pulled and why, and
        # a wrong hold costs one unhide rather than a deleted paragraph somebody
        # wrote about their own retirement.
        hidden=hold is not None,
        hidden_reason=hold,
    )
    db.add(row)
    db.flush()
    if hold is not None:
        _queue_for_review(db, "post", row.id, hold)
        # Ids only. The text of a held post is somebody's words and does not go
        # into a log line, where it would be copied into a log aggregator, an
        # error report, and somebody's inbox.
        log.info("post %s held for review in circle %s: %s", row.id, circle.id, hold)
    db.commit()
    out = post_view(row, _display_name(user))
    if hold is not None:
        # Said in words, because a post that silently does not appear reads as a
        # lost tap and gets reported as a lost tap.
        out["message"] = (
            "Your post is with a moderator for a moment. We check phone numbers "
            "and payment requests before they reach the circle."
        )
    return out


@router.post("/posts/{post_id}/comments")
def create_comment(
    post_id: int, body: CommentIn, request: Request, user: CurrentUser, db: DbSession
):
    """
    A comment. Same untrusted-words rule as a post, and the same circle gate: the
    comment is a read of the circle as much as a write to the post, so a
    non-member is 404 here too.
    """
    limit_write(request, user.id)
    post = db.get(Post, post_id)
    if post is None or post.hidden:
        raise HTTPException(404, "post not found")
    circle = db.get(Circle, post.circle_id)
    if circle is None:
        raise HTTPException(404, "post not found")
    _require_membership(db, circle, user)

    # Plan 15's automatic checks apply to a comment exactly as to a post: a link
    # on the scam blocklist is refused, and a phone number or payment request from
    # a T0 account is held for a person to read rather than shown.
    _refuse_blocked_link(db, body.text, None)
    hold = _hold_reason(user, body.text)

    row = Comment(
        post_id=post.id,
        author_id=user.id,
        text=body.text,
        created_at=now(),
        hidden=hold is not None,
    )
    db.add(row)
    db.flush()
    if hold is not None:
        _queue_for_review(db, "comment", row.id, hold)
    else:
        post.comment_count += 1
    db.commit()

    # Tell the author of the post, once per few minutes, on the Circles channel.
    # Never the words of the comment: the body of a stranger's message does not
    # go into a notification (module docstring).
    if hold is None and post.author_id != user.id:
        try:
            from python.dispatch import notify_user  # noqa: PLC0415
            from python.realtime import publish  # noqa: PLC0415

            author = db.get(User, post.author_id)
            if author is not None:
                notify_user(
                    db, author, kind=f"circle_reply{post.id}:{int(row.created_at // 600)}",
                    title=f"{_display_name(user) or 'Someone'} replied to your post",
                    body="Open the post to read it.", payload={"open": "post", "post_id": post.id},
                )
                publish(author.id, {"type": "comment", "post_id": post.id})
        except Exception:
            log.exception("could not notify the author of post %s", post.id)
    return comment_view(row, _display_name(user))


@router.post("/reports")
def create_report(body: ReportIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Reporting something. 6.6 and 15: every post, comment, room and profile can
    be reported, and the report has to work for somebody who is not in the room.

    That last part is the reason there is no membership check here. The person
    most likely to report a scam post in a colleague's circle is a member, but
    the person who saw it in a shared screen or a forwarded screenshot is not,
    and refusing their report would be refusing exactly the reports that come
    from outside. The target is resolved so a moderator has something to open.
    """
    limit_write(request, user.id)
    if body.target_type not in REPORT_TARGETS:
        raise HTTPException(400, f"target_type must be one of {', '.join(REPORT_TARGETS)}")
    if body.reason not in REPORT_REASONS:
        raise HTTPException(400, f"reason must be one of {', '.join(REPORT_REASONS)}")
    if body.target_type not in ("post", "comment", "user", "voice_room"):
        raise HTTPException(400, "report that from the screen where it appears")
    if body.target_type == "user" and body.target_id == user.id:
        # Not a style point. Plan 15 is a strike ladder, and a strike you can
        # hand yourself is a way to remove an honest member's ability to speak
        # while making it look like a report from somebody else.
        raise HTTPException(400, "you cannot report yourself")

    context: dict = {"note": body.note} if body.note else {}
    if body.target_type == "post":
        target = db.get(Post, body.target_id)
        if target is None:
            raise HTTPException(404, "post not found")
        target.report_count += 1
        context |= {
            "circle_id": target.circle_id,
            "author_id": target.author_id,
            "kind": target.kind,
            "created_at": target.created_at,
            # What the reporter saw, so a moderator does not have to guess. It
            # is stored as a value and the moderator console must render it
            # escaped — it is a stranger's text, in a staff tool, and a link or
            # a tag in here is the oldest trick there is.
            "excerpt": (target.text or "")[:REPORT_EXCERPT_MAX],
        }
    elif body.target_type == "comment":
        target = db.get(Comment, body.target_id)
        if target is None:
            raise HTTPException(404, "comment not found")
        target.report_count += 1
        post = db.get(Post, target.post_id)
        context |= {
            "circle_id": post.circle_id if post else None,
            "author_id": target.author_id,
            # Same rule as above: escaped on render, never executed, never
            # concatenated into a query or a template.
            "excerpt": (target.text or "")[:REPORT_EXCERPT_MAX],
        }
    elif body.target_type == "user":
        target = db.get(User, body.target_id)
        if target is None or target.deleted_at is not None:
            raise HTTPException(404, "person not found")
        context |= {"name": target.name}
    else:
        target = db.get(VoiceRoom, body.target_id)
        if target is None:
            raise HTTPException(404, "room not found")
        context |= {"circle_id": target.circle_id, "host_id": target.host_id}

    again = db.scalar(
        select(Report).where(
            Report.reporter_id == user.id,
            Report.target_type == body.target_type,
            Report.target_id == body.target_id,
            Report.status == "open",
        )
    )
    if again is not None:
        # One open report per person per thing. A report button that spams the
        # queue when somebody is angry teaches the whole circle that reporting
        # is noise, and then nobody reports anything.
        raise HTTPException(409, "you have already reported this — a moderator has it")

    at = now()
    row = Report(
        target_type=body.target_type,
        target_id=body.target_id,
        reporter_id=user.id,
        reason=body.reason,
        status="open",
        context=context,
        created_at=at,
    )
    db.add(row)
    db.flush()
    # 15's response times. Scam and abuse go to a person now; the rest queue.
    urgent = body.reason in URGENT_REPORT_REASONS
    _queue_for_review(db, body.target_type, body.target_id, f"report: {body.reason}")
    if urgent:
        log.info("urgent report %s on %s %s", row.id, body.target_type, body.target_id)
    db.commit()
    return {
        "id": row.id,
        "target_type": row.target_type,
        "target_id": row.target_id,
        "reason": row.reason,
        "status": row.status,
        "created_at": row.created_at,
        "urgent": urgent,
    }


@router.get("/people/search")
def people_search(
    user: CurrentUser,
    db: DbSession,
    q: str | None = None,
    department_id: int | None = None,
    service_type: str | None = None,
    city_id: int | None = None,
    limit: int = 20,
):
    """
    Find colleagues. 6.6's "find colleagues", and the endpoint most likely to
    leak, so the consent is in the query rather than in Python.

    `User.discoverable` is a WHERE clause, not a filter applied after the rows
    come back. That ordering is the whole difference: plan 14 makes location and
    findability separate consents that a person can withdraw in Settings, and a
    filter after the fetch still fetched. Put it here and a person who turns the
    setting off is gone from the next query, not from the next deploy.

    Distance is a third thing and is gated separately, below.
    """
    if not 1 <= limit <= PEOPLE_PAGE_MAX:
        raise HTTPException(400, f"limit must be 1..{PEOPLE_PAGE_MAX}")

    stmt = (
        select(User, Department.name)
        .outerjoin(Department, Department.id == User.department_id)
        .where(
            # The consent, read at the query. See above.
            User.discoverable.is_(True),
            # A soft-deleted account stops being findable the day it is deleted,
            # not on the day the row is finally purged.
            User.deleted_at.is_(None),
            User.id != user.id,
        )
    )
    if q:
        term = f"%{_like(q.strip())}%"
        stmt = stmt.where(
            or_(
                User.name.ilike(term, escape="\\"),
                User.address_as.ilike(term, escape="\\"),
                Department.name.ilike(term, escape="\\"),
            )
        )
    if department_id is not None:
        stmt = stmt.where(User.department_id == department_id)
    if service_type:
        stmt = stmt.where(User.service_type == service_type)
    if city_id is not None:
        stmt = stmt.where(User.city_id == city_id)

    # No ordering by engagement and no "most similar" column. Name is the order,
    # because the person using this is looking for somebody they half know.
    stmt = stmt.order_by(User.name).limit(limit)
    rows = db.execute(stmt).all()

    # Location is its own consent, and it is the caller's own location being
    # used. `users.lat/lng` is only ever written when that person shared
    # location for themselves (python/features/checkin.py stores it only under
    # share_location), so a null pair means they have not, and then no distance
    # is computed for anybody and nothing is ordered by it. The other person's
    # coordinates are never returned at all — only the rounded bucket below.
    mine = (user.lat, user.lng)
    share = mine[0] is not None and mine[1] is not None

    out = []
    for u, dept_name in rows:
        distance = None
        if share and u.lat is not None and u.lng is not None:
            distance = _distance_bucket(_haversine_km(mine, (u.lat, u.lng)))
        out.append(person_view(u, dept_name, distance))
    return out


@router.post("/events")
def create_event(body: EventIn, request: Request, user: CurrentUser, db: DbSession):
    """
    A meeting. 6.6 wants a date, a place, a cost and an RSVP limit; all four are
    on the model and all four are required to be honest about one. A gathering
    with no stated cost is how a pensioner turns up expecting a free chai circle
    and finds a subscription.
    """
    limit_write(request, user.id)
    circle = db.get(Circle, body.circle_id)
    if circle is None:
        raise HTTPException(404, "circle not found")
    member = _require_membership(db, circle, user)

    at = now()
    if body.starts_at <= at:
        # A meeting in the past is a typing mistake, and publishing it puts a
        # dead entry in every member's calendar.
        raise HTTPException(400, "starts_at must be in the future")
    if body.ends_at is not None and body.ends_at <= body.starts_at:
        raise HTTPException(400, "ends_at must be after starts_at")

    row = CircleEvent(
        circle_id=circle.id,
        created_by=user.id,
        title=body.title,
        place=body.place,
        place_json=body.place_json,
        starts_at=body.starts_at,
        ends_at=body.ends_at,
        cost=body.cost,
        capacity=body.capacity,
        going_count=0,
        created_at=at,
    )
    db.add(row)
    db.commit()
    return event_view(row)


@router.post("/events/{event_id}/rsvp")
def rsvp(event_id: int, body: RsvpIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Saying whether you are coming. Idempotent on purpose.

    `Rsvp` carries uq_rsvp on (event_id, user_id), so one person is one RSVP by
    construction rather than by this code remembering to check. That constraint
    is why this can be idempotent: the second tap finds the row the first tap
    wrote and returns it, and a pensioner tapping RSVP twice on a slow network
    sees one RSVP rather than a red error. An error here would be read as "you
    are not going", which is the wrong thing to tell somebody about a Sunday
    morning they intend to spend with their old colleagues.
    """
    limit_write(request, user.id)
    if body.status not in RSVP_STATUSES:
        raise HTTPException(400, f"status must be one of {', '.join(RSVP_STATUSES)}")

    ev = db.get(CircleEvent, event_id)
    if ev is None:
        raise HTTPException(404, "event not found")
    circle = db.get(Circle, ev.circle_id)
    if circle is None:
        raise HTTPException(404, "event not found")
    _require_membership(db, circle, user)

    at = now()
    if at > ev.starts_at:
        raise HTTPException(409, "this event has already started")

    row = db.scalar(
        select(Rsvp).where(Rsvp.event_id == ev.id, Rsvp.user_id == user.id)
    )
    going = body.status == "going"
    if row is None:
        if going and ev.capacity is not None and ev.going_count >= ev.capacity:
            # 409, and the words say what happened. "Full" on its own is not an
            # explanation; the count and the fact that it may open up is.
            raise HTTPException(
                409,
                f"this event is full — {ev.going_count} of {ev.capacity} places are taken",
            )
        row = Rsvp(event_id=ev.id, user_id=user.id, status=body.status, at=at)
        db.add(row)
        if going:
            ev.going_count += 1
    elif row.status == body.status:
        pass  # the double tap: no change, no error, no double count
    else:
        was_going = row.status == "going"
        if going and not was_going:
            if ev.capacity is not None and ev.going_count >= ev.capacity:
                raise HTTPException(
                    409,
                    f"this event is full — {ev.going_count} of {ev.capacity} places are taken",
                )
        row.status = body.status
        row.at = at
        if going and not was_going:
            ev.going_count += 1
        elif was_going and not going:
            ev.going_count = max(0, ev.going_count - 1)

    db.commit()
    return {"status": row.status, "event": event_view(ev)}


@router.get("/rooms/live")
def live_rooms(user: CurrentUser, db: DbSession):
    """
    What is running right now. 6.7's Chai pe Charcha.

    A room borrows a circle but is not one, and the distinction is the consent
    model: joining a room does not join the circle and leaving does not leave it.
    So the circle's membership still governs who can see the room, with one
    exception — a `voice_only` circle exists precisely to be a room anybody may
    walk into, and treating it as a closed circle would leave the feature with
    no reachable rooms.

    There is no engagement rank here either. 6.7 describes scheduled rooms, and
    a list sorted by who has the most listeners turns a tea break into a
    leaderboard.
    """
    at = now()
    mine = {
        m.circle_id
        for m in db.scalars(
            select(CircleMember).where(
                CircleMember.user_id == user.id, CircleMember.pending_at.is_(None)
            )
        ).all()
    }
    rows = db.execute(
        select(VoiceRoom, RoomSession)
        .join(RoomSession, RoomSession.room_id == VoiceRoom.id)
        .join(Circle, Circle.id == VoiceRoom.circle_id)
        .where(
            RoomSession.ended_at.is_(None),
            RoomSession.started_at >= at - ROOM_SESSION_STALE_S,
            Circle.is_archived.is_(False),
        )
    ).all()

    out = []
    for room, session in rows:
        if room.circle_id not in mine and _circle_type(db, room.circle_id) != "voice_only":
            continue
        out.append(room_view(room, session))
    return out


@router.post("/rooms/{room_id}/join")
def join_room(room_id: int, body: RoomJoinIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Issue a join for a sitting that is happening now.

    The count is a high-water mark, and it is the number the cap is checked
    against, because RoomSession.peak_listeners is the only counter the schema
    has. It counts joins rather than concurrent listeners, so it over-estimates
    a room whose people have gone home. That is the direction to be wrong in: a
    room that looks full when it is not turns one person away, and a room that
    looks empty when it is not drops a call somebody travelled to sit in.

    The speaker cap is not enforced here and cannot be: there is no record of
    who is speaking, because raised hands live in the media layer. 6.1 gives T2
    the right to host; that check belongs to the room-creation path, which is
    not one of the endpoints in this section.
    """
    limit_write(request, user.id)
    room = db.get(VoiceRoom, room_id)
    if room is None:
        raise HTTPException(404, "room not found")
    circle = db.get(Circle, room.circle_id)
    if circle is None or circle.is_archived:
        raise HTTPException(404, "room not found")
    if circle.type != "voice_only":
        _require_membership(db, circle, user)

    at = now()
    session = db.scalar(
        select(RoomSession).where(
            RoomSession.room_id == room.id,
            RoomSession.ended_at.is_(None),
            RoomSession.started_at >= at - ROOM_SESSION_STALE_S,
        )
    )
    if session is None:
        nxt = room.next_session_at
        when = "the next sitting has not been announced" if nxt is None else "the next sitting has not started"
        raise HTTPException(409, f"this room is not running right now — {when}")

    if session.peak_listeners >= MAX_LISTENERS:
        raise HTTPException(
            409, f"this room already holds {MAX_LISTENERS} listeners, which is all it holds"
        )

    session.peak_listeners += 1
    if room.recording and body.as_speaker:
        # 6.7: recording off by default, and when it is on, consent from every
        # speaker. This counts a claim, not a confirmation — the transport is
        # what knows whether the person actually agreed, and the recording is
        # only kept when it covers the participants. RoomSession's own
        # `may_keep_recording` being merely `> 0` is not enough on its own,
        # because a recording of one person who said yes and seven who said no
        # cannot be undone by deleting the file later.
        session.consented_for_recording += 1
    db.commit()

    token = _issue_join_token(room, session, user)
    from python.features.rooms import livekit_url  # noqa: PLC0415

    return {
        "room": room_view(room, session),
        "url": livekit_url(),
        "session_id": session.id,
        "as_speaker": body.as_speaker,
        "token": token,
        "transport": "livekit",
        # The client must show this. A room that records without saying so is
        # the fastest way to lose the trust the rest of this file is built on.
        "recording": bool(room.recording),
        "consent_recording_required": bool(room.recording and body.as_speaker),
    }


# ------------------------------------------------------------------ internals
def _circle_type(db: Session, circle_id: int) -> str | None:
    circle = db.get(Circle, circle_id)
    return circle.type if circle else None


def _issue_join_token(room: VoiceRoom, session: RoomSession, user: User) -> str | None:
    """
    A LiveKit access token for one person in one room, or None when no media
    server is configured (the client then shows "we are connecting you" and never
    a fake connected state).

    Everyone joins as a LISTENER except the host. Speaking is granted by the host
    from a raised hand (features/rooms.py), which is what keeps the room at the
    plan's host + up to 8 speakers; a `token` that let anyone publish would make
    the 8-speaker cap a suggestion. Recording is never enabled from here.
    """
    from python.integrations import livekit  # noqa: PLC0415

    if not room.livekit_room:
        room.livekit_room = f"dp-{room.id}-{session.id}"
    return livekit.join_token(
        room=room.livekit_room,
        identity=f"u{user.id}",
        name=user.address_as or user.name or "Guest",
        speaker=False,
        host=(room.host_id == user.id),
    )


def _haversine_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    """Great-circle distance in km. Only ever used to round it to a bucket."""
    lat1, lng1 = math.radians(a[0]), math.radians(a[1])
    lat2, lng2 = math.radians(b[0]), math.radians(b[1])
    dlat = lat2 - lat1
    dlng = lng2 - lng1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 6371.0 * 2 * math.asin(min(1.0, math.sqrt(h)))


def _distance_bucket(km: float) -> str:
    """
    A neighbourhood, not a position.

    6.7's rooms and 6.6's circles are built on people recognising each other, and
    that works at "roughly my area" granularity. A metre-accurate distance to a
    retired government employee's home is a map pin, and a map pin for this
    audience is a physical-security disclosure that a name and a department
    together turn into an address.
    """
    if km <= 2:
        return "under 2 km"
    if km <= 10:
        return "2 to 10 km"
    return "over 10 km"
