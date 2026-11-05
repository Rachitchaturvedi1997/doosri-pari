# =============================================================================
# Feature · Association workspace — plan sections 15, 17 and 19 (months 6-9).
#
#   POST /associations                         register a pensioners' association
#   GET  /associations/mine
#   GET  /associations/{id}
#   POST /associations/{id}/apply              a pensioner asks to join
#   GET  /associations/{id}/members            (office-bearers) with pending requests
#   POST /associations/{id}/members/{uid}/decide     approve -> T2 (member verification)
#   POST /associations/{id}/members/{uid}/role
#   POST /associations/{id}/notices    GET .../notices
#   POST /associations/{id}/dues       GET .../dues       GET .../dues/mine
#
# What an association is for, in the plan's words (17): member verification,
# notices, dues, events. Each is here, and each keeps the association's authority
# where it belongs:
#
#   * VERIFICATION. An office-bearer of a VERIFIED association can approve a
#     member, and that approval is a T2 verification (plan 6.1: "Approved by an
#     association office-bearer"). An association that has not been verified by
#     the Doosri Pari team (features/admin.py) cannot confer a tier - otherwise
#     anybody could register "Retired Officers Association" and hand out T2.
#   * DUES. The association collects its dues from its members in its own way.
#     This app records who has paid so a member can see their standing; it does
#     not move that money and never holds it.
#   * EVENTS. An association's linked circle carries its events, through the
#     ordinary circle event endpoints; office-bearers are that circle's moderators.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import and_, select

from python.deps import CurrentUser, DbSession
from python.dispatch import notify_user
from python.features.auth import derive_tier
from python.models import (
    TIER_RANK,
    Association,
    AssociationDue,
    AssociationMember,
    AssociationNotice,
    Circle,
    CircleMember,
    User,
    Verification,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.association")
router = APIRouter(tags=["association"])

OFFICE_ROLES = ("secretary", "president", "treasurer", "office_bearer")


class AssocIn(BaseModel):
    name: str = Field(min_length=4, max_length=160)
    reg_no: str | None = Field(default=None, max_length=48)
    city_id: int | None = Field(default=None, ge=1)


class ApplyIn(BaseModel):
    membership_no: str | None = Field(default=None, max_length=40)


class DecideIn(BaseModel):
    approve: bool


class RoleIn(BaseModel):
    role: str


class NoticeIn(BaseModel):
    title: str = Field(min_length=2, max_length=160)
    body: str = Field(min_length=2, max_length=4000)
    pinned: bool = False


class DueIn(BaseModel):
    user_id: int
    amount_paise: int = Field(ge=0, le=100_000_000)
    period_label: str = Field(min_length=2, max_length=40)
    due_on: float | None = None
    paid: bool = False


def _get(db, aid: int) -> Association:
    a = db.get(Association, aid)
    if a is None:
        raise HTTPException(404, "association not found")
    return a


def _member(db, aid: int, uid: int) -> AssociationMember | None:
    return db.scalar(select(AssociationMember).where(AssociationMember.association_id == aid, AssociationMember.user_id == uid))


def _officer(db, a: Association, user: User) -> AssociationMember:
    m = _member(db, a.id, user.id)
    if m is None or m.status != "approved" or m.role not in OFFICE_ROLES:
        raise HTTPException(404, "association not found")
    return m


def _member_ok(db, a: Association, user: User) -> AssociationMember:
    m = _member(db, a.id, user.id)
    if m is None or m.status != "approved":
        raise HTTPException(404, "association not found")
    return m


def _view(a: Association, m: AssociationMember | None = None) -> dict:
    return {
        "id": a.id, "name": a.name, "reg_no": a.reg_no, "city_id": a.city_id, "circle_id": a.circle_id,
        "verified": a.verified_at is not None, "plan": a.plan,
        "my_role": m.role if m and m.status == "approved" else None, "my_status": m.status if m else None,
    }


@router.post("/associations")
def register(body: AssocIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    if user.tier_rank < TIER_RANK["T1"]:
        raise HTTPException(403, "registering an association needs a verified account")
    if body.reg_no and db.scalar(select(Association.id).where(Association.reg_no == body.reg_no)):
        raise HTTPException(409, "an association with that registration number is already here")
    circle = Circle(type="association", name=body.name.strip(), join_rule="approval", city_id=body.city_id,
                    created_by=user.id, description="Members of the association")
    db.add(circle)
    db.flush()
    a = Association(name=body.name.strip(), reg_no=body.reg_no, circle_id=circle.id, city_id=body.city_id, owner_id=user.id)
    db.add(a)
    db.flush()
    m = AssociationMember(association_id=a.id, user_id=user.id, role="president", status="approved",
                          approved_by=user.id, approved_at=now())
    db.add(m)
    db.add(CircleMember(circle_id=circle.id, user_id=user.id, role="owner"))
    circle.member_count = 1
    db.commit()
    return {**_view(a, m), "message": "Registered. The Doosri Pari team will verify it before it can approve members."}


@router.get("/associations/mine")
def mine(user: CurrentUser, db: DbSession):
    rows = db.execute(
        select(Association, AssociationMember).join(AssociationMember, AssociationMember.association_id == Association.id)
        .where(AssociationMember.user_id == user.id)
    ).all()
    return {"associations": [_view(a, m) for a, m in rows]}


@router.get("/associations/search")
def search(q: str, user: CurrentUser, db: DbSession, limit: int = 10):
    if len(q.strip()) < 2:
        raise HTTPException(400, "type at least two letters")
    rows = db.scalars(select(Association).where(Association.verified_at.isnot(None), Association.name.contains(q.strip())).limit(min(limit, 25))).all()
    return {"associations": [_view(a) for a in rows]}


@router.get("/associations/{aid}")
def detail(aid: int, user: CurrentUser, db: DbSession):
    a = _get(db, aid)
    return _view(a, _member(db, a.id, user.id))


@router.post("/associations/{aid}/apply")
def apply(aid: int, body: ApplyIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = _get(db, aid)
    if a.verified_at is None:
        raise HTTPException(409, "this association has not been verified yet")
    m = _member(db, a.id, user.id)
    if m is not None:
        return _view(a, m)
    m = AssociationMember(association_id=a.id, user_id=user.id, membership_no=body.membership_no)
    db.add(m)
    db.commit()
    owner = db.get(User, a.owner_id)
    if owner:
        notify_user(db, owner, kind=f"assoc_apply{a.id}:{user.id}", title="A new member request",
                    body=f"{user.address_as or user.name or 'Someone'} asked to join {a.name}.",
                    payload={"open": "association_members", "association_id": a.id})
    return _view(a, m)


@router.get("/associations/{aid}/members")
def list_members(aid: int, user: CurrentUser, db: DbSession, pending: bool = False):
    a = _get(db, aid)
    _officer(db, a, user)
    q = select(AssociationMember, User).join(User, User.id == AssociationMember.user_id).where(AssociationMember.association_id == a.id)
    q = q.where(AssociationMember.status == ("pending" if pending else "approved"))
    rows = db.execute(q.order_by(AssociationMember.requested_at).limit(200)).all()
    return {"members": [
        {"user_id": u.id, "name": u.address_as or u.name, "role": m.role, "membership_no": m.membership_no,
         "status": m.status, "tier": u.tier, "requested_at": m.requested_at, "dues_paid_until": m.dues_paid_until}
        for m, u in rows
    ]}


@router.post("/associations/{aid}/members/{uid}/decide")
def decide(aid: int, uid: int, body: DecideIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = _get(db, aid)
    me = _officer(db, a, user)
    m = _member(db, a.id, uid)
    if m is None or m.status != "pending":
        raise HTTPException(404, "no such request")
    person = db.get(User, uid)
    if not body.approve:
        m.status = "declined"
        db.commit()
        return {"status": "declined"}
    m.status, m.approved_by, m.approved_at = "approved", me.user_id, now()
    conferred = None
    if a.verified_at is not None and person is not None:
        # T2 for a verified association's approved member (plan 6.1). The
        # verification row records WHO approved, so it can be audited and undone.
        db.add(Verification(user_id=uid, tier="T2", method="association", by_user_id=user.id, notes=f"association:{a.id}"))
        db.flush()
        person.tier = derive_tier(db, person)
        conferred = person.tier
    if a.circle_id and not db.scalar(select(CircleMember.id).where(CircleMember.circle_id == a.circle_id, CircleMember.user_id == uid)):
        db.add(CircleMember(circle_id=a.circle_id, user_id=uid))
        circle = db.get(Circle, a.circle_id)
        if circle:
            circle.member_count += 1
    db.commit()
    if person is not None:
        notify_user(db, person, kind=f"assoc_ok{a.id}", title=f"Welcome to {a.name}",
                    body="Your membership was approved.", payload={"open": "association", "association_id": a.id})
    return {"status": "approved", "tier": conferred}


@router.post("/associations/{aid}/members/{uid}/role")
def set_role(aid: int, uid: int, body: RoleIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = _get(db, aid)
    me = _officer(db, a, user)
    if a.owner_id != user.id:
        raise HTTPException(403, "only the association's owner can change roles")
    if body.role not in OFFICE_ROLES + ("member",):
        raise HTTPException(400, f"role must be one of {', '.join(OFFICE_ROLES + ('member',))}")
    m = _member(db, a.id, uid)
    if m is None or m.status != "approved":
        raise HTTPException(404, "no such member")
    m.role = body.role
    cm = db.scalar(select(CircleMember).where(CircleMember.circle_id == a.circle_id, CircleMember.user_id == uid)) if a.circle_id else None
    if cm is not None and cm.role != "owner":
        cm.role = "moderator" if body.role in OFFICE_ROLES else "member"
    db.commit()
    return {"user_id": uid, "role": m.role}


# ---------------------------------------------------------------- notices --
@router.post("/associations/{aid}/notices")
def post_notice(aid: int, body: NoticeIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = _get(db, aid)
    _officer(db, a, user)
    n = AssociationNotice(association_id=a.id, posted_by=user.id, title=body.title.strip(), body=body.body.strip(), pinned=body.pinned)
    db.add(n)
    db.commit()
    # A notice is worth one push, not a stream: members are told once, on the
    # Circles channel, and it obeys their daily cap and quiet hours.
    people = db.scalars(
        select(User).join(AssociationMember, AssociationMember.user_id == User.id)
        .where(AssociationMember.association_id == a.id, AssociationMember.status == "approved", User.id != user.id)
        .limit(2000)
    ).all()
    told = sum(int(notify_user(db, p, kind=f"circle_notice{n.id}", title=f"{a.name}: {n.title}"[:160], body=n.body[:120],
                               payload={"open": "association_notice", "notice_id": n.id})) for p in people)
    return {"id": n.id, "told": told}


@router.get("/associations/{aid}/notices")
def notices(aid: int, user: CurrentUser, db: DbSession, limit: int = 30):
    a = _get(db, aid)
    _member_ok(db, a, user)
    rows = db.scalars(select(AssociationNotice).where(AssociationNotice.association_id == a.id)
                      .order_by(AssociationNotice.pinned.desc(), AssociationNotice.created_at.desc()).limit(min(limit, 100))).all()
    return {"notices": [{"id": n.id, "title": n.title, "body": n.body, "pinned": n.pinned, "at": n.created_at} for n in rows]}


# ------------------------------------------------------------------- dues --
@router.post("/associations/{aid}/dues")
def record_due(aid: int, body: DueIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = _get(db, aid)
    _officer(db, a, user)
    m = _member(db, a.id, body.user_id)
    if m is None or m.status != "approved":
        raise HTTPException(404, "no such member")
    d = AssociationDue(association_id=a.id, user_id=body.user_id, amount_paise=body.amount_paise,
                       period_label=body.period_label.strip(), due_on=body.due_on,
                       paid_at=now() if body.paid else None, recorded_by=user.id)
    db.add(d)
    if body.paid and body.due_on:
        m.dues_paid_until = max(m.dues_paid_until or 0, body.due_on)
    db.commit()
    return {"id": d.id}


@router.get("/associations/{aid}/dues/mine")
def my_dues(aid: int, user: CurrentUser, db: DbSession):
    a = _get(db, aid)
    m = _member_ok(db, a, user)
    rows = db.scalars(select(AssociationDue).where(AssociationDue.association_id == a.id, AssociationDue.user_id == user.id)
                      .order_by(AssociationDue.due_on.desc().nullslast())).all()
    return {
        "dues": [{"id": d.id, "period": d.period_label, "amount_paise": d.amount_paise, "due_on": d.due_on,
                  "paid": d.paid_at is not None, "paid_at": d.paid_at} for d in rows],
        "paid_until": m.dues_paid_until,
        "note": "Dues are collected by your association. Doosri Pari only shows what they recorded.",
    }


@router.get("/associations/{aid}/dues")
def all_dues(aid: int, user: CurrentUser, db: DbSession, unpaid: bool = False):
    a = _get(db, aid)
    _officer(db, a, user)
    q = select(AssociationDue, User).join(User, User.id == AssociationDue.user_id).where(AssociationDue.association_id == a.id)
    if unpaid:
        q = q.where(AssociationDue.paid_at.is_(None))
    rows = db.execute(q.order_by(AssociationDue.due_on.desc().nullslast()).limit(500)).all()
    return {"dues": [{"id": d.id, "user_id": u.id, "name": u.address_as or u.name, "period": d.period_label,
                      "amount_paise": d.amount_paise, "due_on": d.due_on, "paid": d.paid_at is not None} for d, u in rows]}
