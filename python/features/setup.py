# =============================================================================
# Feature · Family-assisted setup — plan section 6.1.
#
#   POST /setup/parent           (the son or daughter) fill it in, get a code
#   POST /setup/redeem           (the parent's phone) "Scan code from family"
#   GET  /setup/mine             (the son or daughter) codes made, and whether used
#
# The scenario the plan cares about: a 74-year-old who will not fill in eleven
# screens. A family member does it on their own phone - profile, check-in window,
# the family list - and receives a six-digit code (and a QR of it) that lasts 24
# hours. On the parent's phone, after they have signed in with their own OTP, the
# code imports all of it. They confirm; they do not type.
#
# Consent, exactly:
#   * The parent's OWN OTP sign-in comes first. A code cannot create an account
#     or sign anyone in - it only fills in an account its holder already proved
#     they control.
#   * The person who set it up becomes an accepted guardian, because the parent
#     redeeming the code is the parent agreeing to it. Everyone else on the list
#     is invited and must accept themselves (plan 6.3): nobody is told anything
#     about a person who has not said yes to being told.
#   * Location sharing and optional guardian permissions are NOT switched on by a
#     code, whatever the creator typed. They are the parent's to grant, later, in
#     Settings - a consent given by someone else is not a consent.
# =============================================================================
from __future__ import annotations

import logging
import secrets

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select

from python import config, geo
from python.deps import CurrentUser, DbSession
from python.dispatch import notify_user
from python.escalation import parse_window_start
from python.features.checkin import settings_for
from python.features.safety import PERM_DEFAULTS, clean_phone
from python.models import (
    LANGUAGES,
    SERVICE_TYPES,
    CheckInSettings,
    Department,
    Guardian,
    SetupInvite,
    User,
    now,
)
from python.ratelimit import check, limit_write
from python.security import hash_family_code

log = logging.getLogger("doosri.setup")
router = APIRouter(tags=["setup"])

CODE_TTL_S = 24 * 3600
MAX_ATTEMPTS = 5
MAX_OPEN_CODES = 3


class GuardianSpec(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    phone: str = Field(min_length=6, max_length=20)
    relation: str | None = Field(default=None, max_length=32)


class ProfileSpec(BaseModel):
    name: str | None = Field(default=None, max_length=80)
    address_as: str | None = Field(default=None, max_length=60)
    lang: str | None = None
    text_scale: float | None = Field(default=None, ge=1.0, le=1.3)
    service_type: str | None = None
    state_code: str | None = Field(default=None, max_length=4)
    department_id: int | None = Field(default=None, ge=1)
    cadre: str | None = Field(default=None, max_length=64)
    join_year: int | None = Field(default=None, ge=1947, le=2100)
    retire_year: int | None = Field(default=None, ge=1950, le=2100)
    city_id: int | None = Field(default=None, ge=1)
    time_zone: str | None = Field(default=None, max_length=64)


class CheckinSpec(BaseModel):
    window_start: str = Field(default="09:00", pattern=r"^([01]\d|2[0-3]):[0-5]\d$")
    window_mins: int = Field(default=180, ge=30, le=24 * 60)
    passive: bool = False


class SetupIn(BaseModel):
    profile: ProfileSpec
    checkin: CheckinSpec = Field(default_factory=CheckinSpec)
    guardians: list[GuardianSpec] = Field(default_factory=list, max_length=config.MAX_GUARDIANS - 1)
    creator_relation: str | None = Field(default="son or daughter", max_length=32)


class RedeemIn(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")


def _validate(p: ProfileSpec) -> dict:
    data = p.model_dump(exclude_none=True)
    if "lang" in data and data["lang"] not in LANGUAGES:
        raise HTTPException(400, f"lang must be one of {', '.join(LANGUAGES)}")
    if "service_type" in data and data["service_type"] not in SERVICE_TYPES:
        raise HTTPException(400, f"service_type must be one of {', '.join(SERVICE_TYPES)}")
    if "city_id" in data and geo.get(data["city_id"]) is None:
        raise HTTPException(400, "unknown city")
    return data


@router.post("/setup/parent")
def create_setup(body: SetupIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    profile = _validate(body.profile)
    if "department_id" in profile and db.get(Department, profile["department_id"]) is None:
        raise HTTPException(400, "unknown department")

    guardians = []
    seen = {user.phone_e164} if user.phone_e164 else set()
    for g in body.guardians:
        phone = clean_phone(g.phone)
        if phone is None:
            raise HTTPException(400, f"{g.name}: phone must be a number with a country code")
        if phone in seen:
            raise HTTPException(400, f"{g.name}: that number is listed twice")
        seen.add(phone)
        guardians.append({"name": g.name.strip(), "phone": phone, "relation": g.relation})

    open_codes = db.scalars(
        select(SetupInvite).where(SetupInvite.creator_id == user.id, SetupInvite.redeemed_at.is_(None),
                                  SetupInvite.expires_at > now())
    ).all()
    if len(open_codes) >= MAX_OPEN_CODES:
        raise HTTPException(409, "you already have three unused codes; wait for them to expire or use one")

    code = f"{secrets.randbelow(10**6):06d}"
    for _ in range(5):
        if not db.scalar(select(SetupInvite.id).where(SetupInvite.code_hash == hash_family_code(code),
                                                      SetupInvite.expires_at > now())):
            break
        code = f"{secrets.randbelow(10**6):06d}"
    inv = SetupInvite(
        creator_id=user.id, code_hash=hash_family_code(code), expires_at=now() + CODE_TTL_S,
        payload={
            "profile": profile,
            "checkin": body.checkin.model_dump(),
            "guardians": guardians,
            "creator": {"name": user.address_as or user.name, "relation": body.creator_relation},
        },
    )
    db.add(inv)
    db.commit()
    return {
        "id": inv.id,
        "code": code,                          # shown once; only its digest is kept
        "qr": f"dpsetup:{code}",
        "expires_at": inv.expires_at,
        "message": "Give this code to your parent. On their phone, choose “Scan code from family”. It works once, for 24 hours.",
    }


@router.get("/setup/mine")
def mine(user: CurrentUser, db: DbSession):
    rows = db.scalars(select(SetupInvite).where(SetupInvite.creator_id == user.id)
                      .order_by(SetupInvite.created_at.desc()).limit(10)).all()
    return {"codes": [{"id": r.id, "created_at": r.created_at, "expires_at": r.expires_at,
                       "used": r.redeemed_at is not None, "expired": r.redeemed_at is None and r.expires_at < now()} for r in rows]}


@router.post("/setup/redeem")
def redeem(body: RedeemIn, request: Request, user: CurrentUser, db: DbSession):
    """Called from the parent's phone, signed in as the parent."""
    limit_write(request, user.id)
    # Six digits is a million codes; the limits make guessing one hopeless.
    check(f"setup:redeem:u{user.id}", 5, 3600)
    inv = db.scalar(select(SetupInvite).where(SetupInvite.code_hash == hash_family_code(body.code)))
    if inv is None or inv.redeemed_at is not None or inv.expires_at < now() or inv.attempts >= MAX_ATTEMPTS:
        raise HTTPException(400, "that code is not valid, or it has expired. Ask your family for a new one")
    if inv.creator_id == user.id:
        raise HTTPException(400, "this code is for your parent's phone, not yours")

    payload = inv.payload or {}
    creator = db.get(User, inv.creator_id)
    if creator is None or creator.deleted_at is not None:
        raise HTTPException(400, "the person who made this code no longer has an account")

    # 1. Profile: only fields the parent has not already set themselves.
    applied = []
    for field, value in (payload.get("profile") or {}).items():
        current = getattr(user, field, None)
        if current in (None, "", 0) or field in ("lang", "text_scale") or (field == "time_zone" and current == "UTC"):
            setattr(user, field, value)
            applied.append(field)

    # 2. Check-in window. Passive is fine; location sharing is NOT switched on.
    ck = payload.get("checkin") or {}
    s = settings_for(db, user)
    try:
        parse_window_start(ck.get("window_start", "09:00"))
        s.window_start = ck.get("window_start", "09:00")
        s.window_mins = int(ck.get("window_mins", 180))
        s.passive = bool(ck.get("passive", False))
    except ValueError:
        pass
    s.share_location = False

    # 3. The family list. The creator is accepted (the parent redeeming = agreeing);
    #    everyone else is an invitation they must accept themselves.
    existing = {g.phone: g for g in db.scalars(select(Guardian).where(Guardian.user_id == user.id)).all()}
    count = len(existing)
    added = []
    if creator.phone_e164 and creator.phone_e164 not in existing and count < config.MAX_GUARDIANS:
        db.add(Guardian(
            user_id=user.id, phone=creator.phone_e164, guardian_user_id=creator.id,
            name=creator.address_as or creator.name or "Family", relation=(payload.get("creator") or {}).get("relation"),
            order_no=count + 1, perms=dict(PERM_DEFAULTS), accepted_at=now(),
        ))
        count += 1
        added.append("creator")
    for g in payload.get("guardians", []):
        if g["phone"] in existing or count >= config.MAX_GUARDIANS or g["phone"] == user.phone_e164:
            continue
        db.add(Guardian(user_id=user.id, phone=g["phone"], name=g["name"], relation=g.get("relation"),
                        order_no=count + 1, perms=dict(PERM_DEFAULTS), accepted_at=None))
        count += 1
        added.append(g["name"])

    inv.redeemed_by, inv.redeemed_at = user.id, now()
    db.commit()

    notify_user(db, creator, kind=f"setup_done{inv.id}", title="Your parent is set up",
                body=f"{user.address_as or user.name or 'Your parent'} joined Doosri Pari with the code you made.",
                payload={"open": "family"})
    return {
        "applied": applied, "guardians_added": len(added),
        "next": "Please confirm your details. Nothing is shared until you say so in Settings.",
    }
