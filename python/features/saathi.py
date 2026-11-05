# =============================================================================
# Feature · The rest of Saathi — plan sections 6.6 and 5.
#
# What features/circles.py leaves out, in the order a person meets it:
#
#   POST /circles/defaults              make (or find) my batch, department, city circles
#   GET  /circles/mine
#   GET  /circles/{id}                  a circle's public face, or its member view
#   POST /circles                       start an interest or private circle (T1+)
#   POST /circles/{id}/leave
#   POST /circles/{id}/invite           a private circle's invite link
#   POST /circles/join-invite
#   GET  /circles/{id}/members          ...and moderation of them
#   POST /circles/{id}/members/{uid}/decide | role ; DELETE .../members/{uid}
#   GET  /feed                          every circle, newest first, one small boost
#   GET  /posts/{id}   GET /posts/{id}/comments
#   POST /posts/{id}/react              Namaste, and one reaction only
#   POST /posts/{id}/vote               polls
#   POST /posts/{id}/pin   DELETE /posts/{id}
#   GET  /circles/{id}/events  GET /events/{id}  POST /events/{id}/photos
#   POST /connections   GET /connections   POST /connections/{id}/decide
#   GET  /conversations  GET|POST /conversations/{id}/messages  POST .../read
#
# The calm-feed rules from 6.6 are kept here: newest first; one small boost for
# people you have interacted with in the last 30 days; nothing ranked by
# engagement; "you're all caught up" after a page. The trust rules too: a
# non-member is told 404 (never 403), a person is findable only as they allowed,
# and a message between two people is only possible after both agreed to connect.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import logging
import re
import time
from datetime import datetime

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import and_, func, or_, select

from python import config, geo
from python.deps import CurrentUser, DbSession
from python.dispatch import notify_user
from python.features.circles import (
    FEED_PAGE_MAX,
    _display_name,
    _join_block,
    _membership,
    _names,
    _require_membership,
    circle_view,
    event_view,
    post_view,
)
from python.features.prefs import prefs_for
from python.integrations import storage
from python.models import (
    CIRCLE_TYPES,
    JOIN_RULES,
    TIER_RANK,
    Circle,
    CircleEvent,
    CircleMember,
    ChatMessage,
    Comment,
    Connection,
    Conversation,
    ConversationMember,
    Department,
    EventPhoto,
    Post,
    Reaction,
    Rsvp,
    User,
    now,
)
from python.ratelimit import limit_write
from python.realtime import publish

log = logging.getLogger("doosri.saathi")
router = APIRouter(tags=["circles"])

USER_CREATABLE_TYPES = ("interest", "private")
USER_CREATABLE_RULES = ("open", "approval", "verified", "invite")
ROLES_THAT_MODERATE = ("moderator", "owner")
INVITE_TTL_S = 30 * 86400
BOOST_S = 6 * 3600
INTERACTION_WINDOW_S = 30 * 86400
BAND_YEARS = 3
MSG_MAX = 2000

# The same words features/seva.py treats as a request for money. Applied to a
# private message between strangers, because the person on the other end of a
# first message may be a scammer and the audience is retired public servants.
_PAYMENT_HINT = re.compile(
    r"\b(send money|pay me|upi id|gpay|phonepe|paytm|account number|ifsc|gift card|processing fee|registration fee)\b",
    re.I,
)


# ------------------------------------------------------------------ schemas --
class CircleIn(BaseModel):
    name: str = Field(min_length=3, max_length=120)
    type: str = "interest"
    description: str | None = Field(default=None, max_length=600)
    join_rule: str = "open"
    city_id: int | None = Field(default=None, ge=1)
    # A spiritual circle: no politics (plan 15, 7.2). Held for review, not blocked.
    spiritual: bool = False


class InviteJoinIn(BaseModel):
    token: str = Field(min_length=10, max_length=200)


class DecideIn(BaseModel):
    approve: bool


class RoleIn(BaseModel):
    role: str


class ReactIn(BaseModel):
    kind: str = "namaste"


class VoteIn(BaseModel):
    option: int = Field(ge=0, le=9)


class PhotoIn(BaseModel):
    media_key: str = Field(min_length=8, max_length=255)


class ConnectIn(BaseModel):
    to_id: int = Field(ge=1)
    note: str | None = Field(default=None, max_length=160)


class MessageIn(BaseModel):
    text: str | None = Field(default=None, max_length=MSG_MAX)
    media_key: str | None = Field(default=None, max_length=255)
    kind: str = "text"


class ReadIn(BaseModel):
    pass


# ------------------------------------------------------------------ helpers --
def _mac(payload: str) -> str:
    return hmac.new(config.SECRET_KEY.encode(), b"doosri:circle-invite:v1:" + payload.encode(), hashlib.sha256).hexdigest()[:24]


def invite_token(circle_id: int, ttl: int = INVITE_TTL_S) -> str:
    exp = int(time.time()) + ttl
    payload = f"{circle_id}.{exp}"
    return f"{payload}.{_mac(payload)}"


def read_invite(token: str) -> int | None:
    try:
        cid, exp, sig = token.split(".")
        if not hmac.compare_digest(sig, _mac(f"{cid}.{exp}")) or int(exp) < time.time():
            return None
        return int(cid)
    except (ValueError, AttributeError):
        return None


def _band(year: int) -> tuple[int, int]:
    start = year - year % BAND_YEARS
    return start, start + BAND_YEARS - 1


def _moderator(db, circle: Circle, user: User) -> CircleMember:
    m = _membership(db, circle.id, user.id)
    if m is None or m.pending_at is not None or m.role not in ROLES_THAT_MODERATE:
        # 404: whether a circle has moderators is not for outsiders to learn.
        raise HTTPException(404, "circle not found")
    return m


def _get_circle(db, circle_id: int) -> Circle:
    c = db.get(Circle, circle_id)
    if c is None or c.is_archived:
        raise HTTPException(404, "circle not found")
    return c


def _add_member(db, circle: Circle, user_id: int, role: str = "member", pending: bool = False) -> CircleMember:
    m = CircleMember(circle_id=circle.id, user_id=user_id, role=role, pending_at=now() if pending else None)
    db.add(m)
    if not pending:
        circle.member_count += 1
    return m


# ------------------------------------------------------------ circle lifecycle --
@router.post("/circles/defaults")
def ensure_default_circles(request: Request, user: CurrentUser, db: DbSession):
    """
    Onboarding step 8: batch (department + retirement year band), department, and
    city circles - found or created, and joined, all pre-ticked. Idempotent: the
    second call finds what the first made. The person can leave any of them.
    """
    limit_write(request, user.id)
    made, joined = [], []

    def ensure(ctype: str, name: str, **where) -> Circle:
        cond = [Circle.type == ctype, Circle.is_archived.is_(False)]
        for k, v in where.items():
            cond.append(getattr(Circle, k) == v if v is not None else getattr(Circle, k).is_(None))
        c = db.scalar(select(Circle).where(*cond))
        if c is None:
            c = Circle(type=ctype, name=name[:120], join_rule="open", created_by=None, **where)
            db.add(c)
            db.flush()
            made.append(c.id)
        if _membership(db, c.id, user.id) is None:
            _add_member(db, c, user.id)
            joined.append(c.id)
        return c

    dept = db.get(Department, user.department_id) if user.department_id else None
    if dept is not None and user.retire_year:
        lo, hi = _band(user.retire_year)
        ensure("batch", f"{dept.name} · retired {lo}–{str(hi)[-2:]}", department_id=dept.id,
               description=f"batch:{lo}-{hi}")
    if dept is not None:
        ensure("department", f"{dept.name} pensioners", department_id=dept.id)
    city = geo.get(user.city_id)
    if city is not None:
        ensure("city", f"Pensioners of {city.name}", city_id=city.id)
    db.commit()
    return {"created": made, "joined": joined}


@router.get("/circles/mine")
def my_circles(user: CurrentUser, db: DbSession):
    rows = db.execute(
        select(Circle, CircleMember)
        .join(CircleMember, CircleMember.circle_id == Circle.id)
        .where(CircleMember.user_id == user.id, Circle.is_archived.is_(False))
        .order_by(Circle.type, Circle.name)
    ).all()
    return {
        "circles": [
            {**circle_view(c, member=m.pending_at is None, reason="pending" if m.pending_at else None),
             "role": m.role, "muted": m.muted}
            for c, m in rows
        ]
    }


@router.get("/circles/{circle_id}")
def circle_detail(circle_id: int, user: CurrentUser, db: DbSession):
    c = _get_circle(db, circle_id)
    m = _membership(db, c.id, user.id)
    member = m is not None and m.pending_at is None
    # A private circle does not exist for non-members.
    if c.type == "private" and not member:
        raise HTTPException(404, "circle not found")
    out = circle_view(c, member=member, reason="pending" if (m and m.pending_at) else None)
    out["role"] = m.role if member else None
    out["can_join"] = (not member) and (m is None) and _join_block(c, user) is None and c.join_rule != "invite"
    out["join_block"] = None if member else _join_block(c, user)
    return out


@router.post("/circles")
def create_circle(body: CircleIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    if user.tier_rank < TIER_RANK["T1"]:
        raise HTTPException(403, "starting a circle needs a verified account")
    if body.type not in USER_CREATABLE_TYPES:
        raise HTTPException(400, f"type must be one of {', '.join(USER_CREATABLE_TYPES)}")
    if body.join_rule not in USER_CREATABLE_RULES:
        raise HTTPException(400, f"join_rule must be one of {', '.join(USER_CREATABLE_RULES)}")
    if body.type == "private" and body.join_rule not in ("invite", "approval"):
        raise HTTPException(400, "a private circle is joined by invitation or approval")
    if body.city_id is not None and geo.get(body.city_id) is None:
        raise HTTPException(400, "unknown city")
    mine = db.scalar(select(func.count(Circle.id)).where(Circle.created_by == user.id, Circle.is_archived.is_(False))) or 0
    if mine >= 10:
        raise HTTPException(409, "you already run ten circles; archive one before starting another")
    c = Circle(type=body.type, name=body.name.strip(), description=body.description, join_rule=body.join_rule,
               city_id=body.city_id, created_by=user.id, no_politics=body.spiritual)
    db.add(c)
    db.flush()
    _add_member(db, c, user.id, role="owner")
    db.commit()
    return circle_view(c, member=True)


@router.post("/circles/{circle_id}/leave")
def leave_circle(circle_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = _get_circle(db, circle_id)
    m = _membership(db, c.id, user.id)
    if m is None:
        raise HTTPException(404, "circle not found")
    if m.role == "owner":
        others = db.scalar(
            select(func.count(CircleMember.id)).where(
                CircleMember.circle_id == c.id, CircleMember.user_id != user.id, CircleMember.pending_at.is_(None)
            )
        ) or 0
        if others:
            raise HTTPException(409, "hand the circle to another moderator before you leave")
        c.is_archived = True
    if m.pending_at is None:
        c.member_count = max(0, c.member_count - 1)
    db.delete(m)
    db.commit()
    return {"left": circle_id}


@router.post("/circles/{circle_id}/invite")
def make_invite(circle_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = _get_circle(db, circle_id)
    m = _membership(db, c.id, user.id)
    if m is None or m.pending_at is not None:
        raise HTTPException(404, "circle not found")
    if c.type == "private" and m.role not in ROLES_THAT_MODERATE:
        raise HTTPException(403, "only the circle's moderators can invite to a private circle")
    token = invite_token(c.id)
    return {"token": token, "link": f"{config.PUBLIC_BASE_URL}/join/{token}", "expires_in": INVITE_TTL_S}


@router.post("/circles/join-invite")
def join_by_invite(body: InviteJoinIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    cid = read_invite(body.token)
    c = db.get(Circle, cid) if cid else None
    if c is None or c.is_archived:
        raise HTTPException(400, "this invitation is invalid or has expired")
    existing = _membership(db, c.id, user.id)
    if existing is not None and existing.pending_at is None:
        return circle_view(c, member=True)
    if existing is not None:
        existing.pending_at = None
        c.member_count += 1
    else:
        _add_member(db, c, user.id)
    db.commit()
    return circle_view(c, member=True)


# ---------------------------------------------------------------- members --
@router.get("/circles/{circle_id}/members")
def members(circle_id: int, user: CurrentUser, db: DbSession, pending: bool = False, limit: int = 50, offset: int = 0):
    c = _get_circle(db, circle_id)
    me = _require_membership(db, c, user)
    if pending and me.role not in ROLES_THAT_MODERATE:
        raise HTTPException(403, "only moderators can see who is waiting")
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    q = select(CircleMember, User).join(User, User.id == CircleMember.user_id).where(
        CircleMember.circle_id == c.id, User.deleted_at.is_(None)
    )
    q = q.where(CircleMember.pending_at.isnot(None) if pending else CircleMember.pending_at.is_(None))
    rows = db.execute(q.order_by(CircleMember.role.desc(), User.name).offset(offset).limit(limit)).all()
    # Names and roles only. The member list is a roster, not a directory.
    return {
        "members": [
            {"id": u.id, "name": _display_name(u), "role": m.role, "tier": u.tier, "joined_at": m.joined_at}
            for m, u in rows
        ]
    }


@router.post("/circles/{circle_id}/members/{member_id}/decide")
def decide_member(circle_id: int, member_id: int, body: DecideIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = _get_circle(db, circle_id)
    _moderator(db, c, user)
    m = _membership(db, c.id, member_id)
    if m is None or m.pending_at is None:
        raise HTTPException(404, "no such request")
    if body.approve:
        m.pending_at = None
        m.joined_at = now()
        c.member_count += 1
    else:
        db.delete(m)
    db.commit()
    target = db.get(User, member_id)
    if target is not None:
        notify_user(db, target, kind=f"circle_join{c.id}",
                    title=("Welcome to " + c.name) if body.approve else "Your request was not accepted",
                    body=("You can now read and post." if body.approve else c.name),
                    payload={"open": "circle", "circle_id": c.id})
    return {"approved": body.approve}


@router.post("/circles/{circle_id}/members/{member_id}/role")
def set_role(circle_id: int, member_id: int, body: RoleIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = _get_circle(db, circle_id)
    me = _moderator(db, c, user)
    if body.role not in ("member", "moderator"):
        raise HTTPException(400, "role must be member or moderator")
    m = _membership(db, c.id, member_id)
    if m is None or m.pending_at is not None or m.role == "owner":
        raise HTTPException(404, "no such member")
    if me.role != "owner":
        raise HTTPException(403, "only the owner can change roles")
    m.role = body.role
    db.commit()
    return {"member_id": member_id, "role": m.role}


@router.delete("/circles/{circle_id}/members/{member_id}")
def remove_member(circle_id: int, member_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = _get_circle(db, circle_id)
    _moderator(db, c, user)
    m = _membership(db, c.id, member_id)
    if m is None or m.role == "owner":
        raise HTTPException(404, "no such member")
    if m.pending_at is None:
        c.member_count = max(0, c.member_count - 1)
    db.delete(m)
    db.commit()
    return {"removed": member_id}


# ----------------------------------------------------------------- the feed --
@router.get("/feed")
def feed(user: CurrentUser, db: DbSession, limit: int = 30, before: float | None = None):
    """
    Every circle, newest first (6.6). One small boost: a post by someone you have
    interacted with in the last 30 days counts as six hours newer. No engagement
    ranking; no counts of likes are used anywhere in the order.
    """
    if not 1 <= limit <= FEED_PAGE_MAX:
        raise HTTPException(400, f"limit must be 1..{FEED_PAGE_MAX}")
    since = now() - INTERACTION_WINDOW_S
    interacted = {
        r[0] for r in db.execute(
            select(Post.author_id).join(Comment, Comment.post_id == Post.id)
            .where(Comment.author_id == user.id, Comment.created_at > since)
        ).all()
    } | {
        r[0] for r in db.execute(
            select(Comment.author_id).join(Post, Post.id == Comment.post_id)
            .where(Post.author_id == user.id, Comment.created_at > since)
        ).all()
    }
    q = (
        select(Post)
        .join(CircleMember, CircleMember.circle_id == Post.circle_id)
        .join(Circle, Circle.id == Post.circle_id)
        .where(CircleMember.user_id == user.id, CircleMember.pending_at.is_(None), CircleMember.muted.is_(False),
               Circle.is_archived.is_(False), Post.hidden.is_(False))
    )
    if before is not None:
        q = q.where(Post.created_at < before + BOOST_S)
    rows = db.scalars(q.order_by(Post.created_at.desc()).limit(limit * 2)).all()

    def eff(p: Post) -> float:
        return p.created_at + (BOOST_S if p.author_id in interacted and p.author_id != user.id else 0)

    rows = sorted(rows, key=lambda p: (-eff(p), -p.id))[:limit]
    names = _names(db, {p.author_id for p in rows})
    circles = {c.id: c.name for c in db.scalars(select(Circle).where(Circle.id.in_({p.circle_id for p in rows}))).all()} if rows else {}
    mine = _my_reactions(db, user.id, [p.id for p in rows])
    counts = _reaction_counts(db, [p.id for p in rows])
    return {
        "posts": [
            {**post_view(p, names.get(p.author_id)), "circle_name": circles.get(p.circle_id),
             "namaste_count": counts.get(p.id, 0), "reacted": p.id in mine}
            for p in rows
        ],
        "next_before": rows[-1].created_at if len(rows) == limit else None,
        "caught_up": len(rows) < limit,
        "caught_up_text": "You’re all caught up. See you tomorrow." if len(rows) < limit else None,
    }


# --------------------------------------------------- posts, reactions, polls --
def _post_for_member(db, user: User, post_id: int) -> tuple[Post, Circle]:
    p = db.get(Post, post_id)
    if p is None:
        raise HTTPException(404, "post not found")
    c = db.get(Circle, p.circle_id)
    if c is None:
        raise HTTPException(404, "post not found")
    _require_membership(db, c, user)
    if p.hidden and p.author_id != user.id:
        raise HTTPException(404, "post not found")
    return p, c


def _reaction_counts(db, post_ids: list[int]) -> dict[int, int]:
    if not post_ids:
        return {}
    rows = db.execute(
        select(Reaction.target_id, func.count(Reaction.id))
        .where(Reaction.target_type == "post", Reaction.target_id.in_(post_ids))
        .group_by(Reaction.target_id)
    ).all()
    return {r[0]: r[1] for r in rows}


def _my_reactions(db, user_id: int, post_ids: list[int]) -> set[int]:
    if not post_ids:
        return set()
    return {
        r for r in db.scalars(
            select(Reaction.target_id).where(
                Reaction.target_type == "post", Reaction.user_id == user_id, Reaction.target_id.in_(post_ids)
            )
        ).all()
    }


@router.get("/posts/{post_id}")
def get_post(post_id: int, user: CurrentUser, db: DbSession):
    p, c = _post_for_member(db, user, post_id)
    names = _names(db, {p.author_id})
    out = {**post_view(p, names.get(p.author_id)), "circle_name": c.name}
    out["namaste_count"] = _reaction_counts(db, [p.id]).get(p.id, 0)
    out["reacted"] = p.id in _my_reactions(db, user.id, [p.id])
    if p.kind == "poll" and p.poll:
        out["poll_results"] = _poll_results(db, p, user.id)
    return out


@router.get("/posts/{post_id}/comments")
def list_comments(post_id: int, user: CurrentUser, db: DbSession, limit: int = 50, after: float | None = None):
    p, _c = _post_for_member(db, user, post_id)
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    q = select(Comment).where(Comment.post_id == p.id, Comment.hidden.is_(False))
    if after is not None:
        q = q.where(Comment.created_at > after)
    rows = db.scalars(q.order_by(Comment.created_at).limit(limit)).all()
    names = _names(db, {c.author_id for c in rows})
    return {
        "comments": [
            {"id": c.id, "post_id": c.post_id, "author_id": c.author_id, "author_name": names.get(c.author_id),
             "text": c.text, "created_at": c.created_at}
            for c in rows
        ]
    }


@router.post("/posts/{post_id}/react")
def react(post_id: int, body: ReactIn, request: Request, user: CurrentUser, db: DbSession):
    """One Namaste per person per post; tap again to take it back. No emoji picker (4.2)."""
    limit_write(request, user.id)
    if body.kind not in ("namaste", "thanks"):
        raise HTTPException(400, "the only reaction is namaste")
    p, _c = _post_for_member(db, user, post_id)
    r = db.scalar(select(Reaction).where(Reaction.target_type == "post", Reaction.target_id == p.id, Reaction.user_id == user.id))
    if r is None:
        db.add(Reaction(target_type="post", target_id=p.id, user_id=user.id, kind="namaste"))
        on = True
    else:
        db.delete(r)
        on = False
    db.commit()
    return {"reacted": on, "namaste_count": _reaction_counts(db, [p.id]).get(p.id, 0)}


def _poll_results(db, post: Post, user_id: int) -> dict:
    options = list((post.poll or {}).get("options", []))
    rows = db.execute(
        select(Reaction.kind, func.count(Reaction.id))
        .where(Reaction.target_type == "poll", Reaction.target_id == post.id).group_by(Reaction.kind)
    ).all()
    tally = {k: n for k, n in rows}
    mine = db.scalar(select(Reaction.kind).where(Reaction.target_type == "poll", Reaction.target_id == post.id, Reaction.user_id == user_id))
    total = sum(tally.values())
    return {
        "question": (post.poll or {}).get("question"),
        "options": [{"index": i, "text": t, "votes": tally.get(f"opt{i}", 0)} for i, t in enumerate(options)],
        "total": total,
        "my_vote": int(mine[3:]) if mine else None,
    }


@router.post("/posts/{post_id}/vote")
def vote(post_id: int, body: VoteIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    p, _c = _post_for_member(db, user, post_id)
    if p.kind != "poll" or not p.poll:
        raise HTTPException(400, "this post is not a poll")
    if body.option >= len(p.poll.get("options", [])):
        raise HTTPException(400, "no such option")
    r = db.scalar(select(Reaction).where(Reaction.target_type == "poll", Reaction.target_id == p.id, Reaction.user_id == user.id))
    if r is None:
        db.add(Reaction(target_type="poll", target_id=p.id, user_id=user.id, kind=f"opt{body.option}"))
    else:
        r.kind = f"opt{body.option}"
    db.commit()
    return _poll_results(db, p, user.id)


@router.post("/posts/{post_id}/pin")
def pin_post(post_id: int, request: Request, user: CurrentUser, db: DbSession):
    """The pension-notice slot on a circle: one pinned post at a time."""
    limit_write(request, user.id)
    p = db.get(Post, post_id)
    if p is None:
        raise HTTPException(404, "post not found")
    c = _get_circle(db, p.circle_id)
    _moderator(db, c, user)
    turning_on = not p.is_pinned
    if turning_on:
        for other in db.scalars(select(Post).where(Post.circle_id == c.id, Post.is_pinned.is_(True))).all():
            other.is_pinned = False
    p.is_pinned = turning_on
    db.commit()
    return {"pinned": p.is_pinned}


@router.delete("/posts/{post_id}")
def delete_post(post_id: int, request: Request, user: CurrentUser, db: DbSession):
    """Hidden, not erased: a moderator can still see what was pulled (models/circles.py)."""
    limit_write(request, user.id)
    p = db.get(Post, post_id)
    if p is None:
        raise HTTPException(404, "post not found")
    c = _get_circle(db, p.circle_id)
    if p.author_id != user.id:
        _moderator(db, c, user)
    p.hidden = True
    p.hidden_reason = "removed by author" if p.author_id == user.id else "removed by a moderator"
    db.commit()
    return {"removed": post_id}


# ------------------------------------------------------------------- events --
@router.get("/circles/{circle_id}/events")
def circle_events(circle_id: int, user: CurrentUser, db: DbSession, past: bool = False):
    c = _get_circle(db, circle_id)
    _require_membership(db, c, user)
    q = select(CircleEvent).where(CircleEvent.circle_id == c.id)
    q = q.where(CircleEvent.starts_at < now()) if past else q.where(CircleEvent.starts_at >= now() - 3600)
    rows = db.scalars(q.order_by(CircleEvent.starts_at.desc() if past else CircleEvent.starts_at).limit(50)).all()
    mine = {r.event_id: r.status for r in db.scalars(select(Rsvp).where(Rsvp.user_id == user.id, Rsvp.event_id.in_([e.id for e in rows]))).all()} if rows else {}
    return {"events": [{**event_view(e), "my_rsvp": mine.get(e.id)} for e in rows]}


@router.get("/events/{event_id}")
def event_detail(event_id: int, user: CurrentUser, db: DbSession):
    e = db.get(CircleEvent, event_id)
    if e is None:
        raise HTTPException(404, "event not found")
    c = _get_circle(db, e.circle_id)
    _require_membership(db, c, user)
    mine = db.scalar(select(Rsvp.status).where(Rsvp.event_id == e.id, Rsvp.user_id == user.id))
    return {**event_view(e), "circle_name": c.name, "my_rsvp": mine}


@router.post("/events/{event_id}/photos")
def add_photo(event_id: int, body: PhotoIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    e = db.get(CircleEvent, event_id)
    if e is None:
        raise HTTPException(404, "event not found")
    c = _get_circle(db, e.circle_id)
    _require_membership(db, c, user)
    if e.starts_at > now():
        raise HTTPException(409, "the album opens when the event begins")
    if not storage.valid_key(body.media_key) or not body.media_key.startswith(f"post_image/{user.id}/"):
        raise HTTPException(400, "that is not one of your uploaded photos")
    db.add(EventPhoto(event_id=e.id, user_id=user.id, media_key=body.media_key))
    db.commit()
    return {"ok": True}


@router.get("/events/{event_id}/photos")
def album(event_id: int, user: CurrentUser, db: DbSession):
    e = db.get(CircleEvent, event_id)
    if e is None:
        raise HTTPException(404, "event not found")
    c = _get_circle(db, e.circle_id)
    _require_membership(db, c, user)
    rows = db.scalars(select(EventPhoto).where(EventPhoto.event_id == e.id).order_by(EventPhoto.created_at)).all()
    return {"photos": [{"id": r.id, "media_key": r.media_key, "by": r.user_id, "at": r.created_at} for r in rows]}


# --------------------------------------------------------------- connections --
def _can_be_found_by(target: User, prefs_findable: str, asker: User) -> bool:
    if target.deleted_at is not None or not target.discoverable:
        return False
    if prefs_findable == "nobody":
        return False
    if prefs_findable == "verified" and asker.tier_rank < TIER_RANK["T1"]:
        return False
    return True


def _pair_key(a: int, b: int) -> str:
    lo, hi = sorted((a, b))
    return f"{lo}-{hi}"


@router.post("/connections")
def send_namaste(body: ConnectIn, request: Request, user: CurrentUser, db: DbSession):
    """"Send a namaste" is a connection request (6.6). Refused politely, never revealing why."""
    limit_write(request, user.id)
    if body.to_id == user.id:
        raise HTTPException(400, "that is you")
    target = db.get(User, body.to_id)
    prefs = prefs_for(db, target.id) if target else None
    if target is None or not _can_be_found_by(target, prefs.findable_by if prefs else "verified", user):
        raise HTTPException(404, "person not found")
    existing = db.scalar(
        select(Connection).where(
            or_(and_(Connection.from_id == user.id, Connection.to_id == target.id),
                and_(Connection.from_id == target.id, Connection.to_id == user.id))
        )
    )
    if existing is not None:
        if existing.status == "accepted":
            return {"id": existing.id, "status": "accepted"}
        if existing.from_id == target.id and existing.status == "pending":
            # They already asked us; sending a namaste back is saying yes.
            existing.status, existing.decided_at = "accepted", now()
            _open_conversation(db, user.id, target.id)
            db.commit()
            return {"id": existing.id, "status": "accepted"}
        return {"id": existing.id, "status": existing.status}
    c = Connection(from_id=user.id, to_id=target.id, note=body.note)
    db.add(c)
    db.commit()
    notify_user(db, target, kind=f"circle_namaste{c.id}", title="A namaste for you",
                body=f"{_display_name(user) or 'A colleague'} sent you a namaste.",
                payload={"open": "connections", "connection_id": c.id})
    publish(target.id, {"type": "connection_request", "id": c.id})
    return {"id": c.id, "status": "pending"}


@router.get("/connections")
def list_connections(user: CurrentUser, db: DbSession):
    rows = db.scalars(select(Connection).where(or_(Connection.from_id == user.id, Connection.to_id == user.id))).all()
    people = {u.id: u for u in db.scalars(select(User).where(User.id.in_({r.from_id for r in rows} | {r.to_id for r in rows}))).all()} if rows else {}

    def person(uid: int) -> dict:
        u = people.get(uid)
        return {"id": uid, "name": _display_name(u) if u else None, "city_id": u.city_id if u else None}

    out = {"accepted": [], "incoming": [], "outgoing": []}
    for r in rows:
        other = r.to_id if r.from_id == user.id else r.from_id
        item = {"id": r.id, "person": person(other), "note": r.note, "at": r.created_at}
        if r.status == "accepted":
            out["accepted"].append(item)
        elif r.status == "pending":
            out["incoming" if r.to_id == user.id else "outgoing"].append(item)
    return out


@router.post("/connections/{connection_id}/decide")
def decide_connection(connection_id: int, body: DecideIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    c = db.get(Connection, connection_id)
    if c is None or c.to_id != user.id or c.status != "pending":
        raise HTTPException(404, "request not found")
    c.status = "accepted" if body.approve else "declined"
    c.decided_at = now()
    conv_id = _open_conversation(db, c.from_id, c.to_id).id if body.approve else None
    db.commit()
    if body.approve:
        asker = db.get(User, c.from_id)
        if asker:
            notify_user(db, asker, kind=f"circle_namaste_ok{c.id}", title="Your namaste was accepted",
                        body=f"{_display_name(user) or 'They'} accepted. You can message each other now.",
                        payload={"open": "conversation", "conversation_id": conv_id})
        publish(c.from_id, {"type": "connection_accepted", "id": c.id, "conversation_id": conv_id})
    return {"status": c.status, "conversation_id": conv_id}


# ------------------------------------------------------------------- chat --
def _open_conversation(db, a: int, b: int) -> Conversation:
    key = _pair_key(a, b)
    conv = db.scalar(select(Conversation).where(Conversation.pair_key == key))
    if conv is None:
        conv = Conversation(kind="dm", pair_key=key)
        db.add(conv)
        db.flush()
        db.add(ConversationMember(conversation_id=conv.id, user_id=a))
        db.add(ConversationMember(conversation_id=conv.id, user_id=b))
    return conv


def _member_of(db, user: User, conv_id: int) -> ConversationMember:
    m = db.scalar(select(ConversationMember).where(ConversationMember.conversation_id == conv_id, ConversationMember.user_id == user.id))
    if m is None:
        raise HTTPException(404, "conversation not found")
    return m


@router.get("/conversations")
def conversations(user: CurrentUser, db: DbSession):
    rows = db.execute(
        select(Conversation, ConversationMember)
        .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
        .where(ConversationMember.user_id == user.id)
        .order_by(Conversation.last_message_at.desc().nullslast())
    ).all()
    out = []
    for conv, me in rows:
        other = db.scalar(
            select(User).join(ConversationMember, ConversationMember.user_id == User.id)
            .where(ConversationMember.conversation_id == conv.id, User.id != user.id)
        )
        last = db.scalars(
            select(ChatMessage).where(ChatMessage.conversation_id == conv.id, ChatMessage.deleted_at.is_(None))
            .order_by(ChatMessage.created_at.desc()).limit(1)
        ).first()
        unread = db.scalar(
            select(func.count(ChatMessage.id)).where(
                ChatMessage.conversation_id == conv.id, ChatMessage.sender_id != user.id,
                ChatMessage.created_at > me.last_read_at, ChatMessage.deleted_at.is_(None),
            )
        ) or 0
        out.append({
            "id": conv.id, "with": {"id": other.id, "name": _display_name(other)} if other else None,
            "last": {"text": last.text, "kind": last.kind, "at": last.created_at, "mine": last.sender_id == user.id} if last else None,
            "unread": unread, "muted": me.muted,
        })
    return {"conversations": out}


@router.get("/conversations/{conversation_id}/messages")
def list_chat(conversation_id: int, user: CurrentUser, db: DbSession, after: float | None = None, before: float | None = None, limit: int = 50):
    _member_of(db, user, conversation_id)
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    q = select(ChatMessage).where(ChatMessage.conversation_id == conversation_id, ChatMessage.deleted_at.is_(None), ChatMessage.hidden.is_(False))
    if after is not None:
        q = q.where(ChatMessage.created_at > after).order_by(ChatMessage.created_at).limit(limit)
        rows = db.scalars(q).all()
    else:
        if before is not None:
            q = q.where(ChatMessage.created_at < before)
        rows = list(reversed(db.scalars(q.order_by(ChatMessage.created_at.desc()).limit(limit)).all()))
    return {"messages": [
        {"id": m.id, "mine": m.sender_id == user.id, "kind": m.kind, "text": m.text, "media_key": m.media_key, "at": m.created_at}
        for m in rows
    ]}


@router.post("/conversations/{conversation_id}/messages")
def send_chat(conversation_id: int, body: MessageIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    _member_of(db, user, conversation_id)
    if body.kind not in ("text", "image", "voice"):
        raise HTTPException(400, "kind must be text, image or voice")
    if body.kind == "text" and not (body.text or "").strip():
        raise HTTPException(400, "write something to send")
    if body.kind != "text":
        prefix = "chat_image/" if body.kind == "image" else "chat_voice/"
        if not body.media_key or not body.media_key.startswith(f"{prefix}{user.id}/") or not storage.valid_key(body.media_key):
            raise HTTPException(400, "that is not a finished upload of yours")
    if body.text and _PAYMENT_HINT.search(body.text):
        raise HTTPException(400, "Please do not ask for or share payment details in messages.")
    m = ChatMessage(conversation_id=conversation_id, sender_id=user.id, kind=body.kind,
                    text=(body.text or "").strip() or None, media_key=body.media_key)
    db.add(m)
    conv = db.get(Conversation, conversation_id)
    conv.last_message_at = now()
    db.commit()

    for other in db.scalars(select(ConversationMember).where(ConversationMember.conversation_id == conversation_id, ConversationMember.user_id != user.id)).all():
        publish(other.user_id, {"type": "message", "conversation_id": conversation_id, "id": m.id})
        if not other.muted:
            target = db.get(User, other.user_id)
            if target is not None:
                notify_user(db, target, kind=f"circle_msg{conversation_id}:{int(m.created_at // 300)}",
                            title=_display_name(user) or "New message",
                            body=(m.text or ("Sent a photo" if body.kind == "image" else "Sent a voice message"))[:80],
                            payload={"open": "conversation", "conversation_id": conversation_id})
    return {"id": m.id, "at": m.created_at}


@router.post("/conversations/{conversation_id}/read")
def mark_conversation_read(conversation_id: int, user: CurrentUser, db: DbSession):
    me = _member_of(db, user, conversation_id)
    me.last_read_at = now()
    db.commit()
    return {"ok": True}
