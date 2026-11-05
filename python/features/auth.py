# =============================================================================
# Feature · Sign in, tokens and the family plan — plan section 11.
#
#   POST /auth/otp                 ask for a 6-digit code on an Indian number
#   POST /auth/verify              phone + code; the first sign-up happens here
#   POST /auth/google              Sign in with Google  (id_token)
#   POST /auth/apple               Sign in with Apple   (id_token)
#   POST /auth/refresh             rotate a refresh token
#   POST /auth/family-code         mint a code the paying family can redeem
#   POST /auth/family-code/redeem  take a seat on somebody else's payment
#
# The one non-obvious rule this module enforces: a phone number has exactly one
# live OTP. Re-sending replaces the pending challenge instead of adding a
# second row, because two live codes for one number is how somebody gets locked
# out of their own pensioner's account — the SMS they act on is the older one,
# it has already been superseded, the code they type is rejected, and the only
# way forward is a son or daughter setting the account up for them again. The
# old row is what a re-send invalidates, so "the last code we sent" and "the
# only code that will work" are the same thing.
# =============================================================================
from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass

import jwt
from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from python import config
from python.deps import CurrentUser, DbSession, revoke_family
from python.models import (
    MAX_PLAN_SEATS,
    TIER_RANK,
    FamilyCode,
    OtpChallenge,
    RefreshToken,
    Subscription,
    SubscriptionSeat,
    User,
    Verification,
    Vouch,
    now,
)
from python.ratelimit import limit_otp, limit_write
from python.security import (
    hash_otp,
    hash_refresh_token,
    issue_access_token,
    new_family_code,
    new_opp,
    new_refresh_token,
    verify_otp,
)

log = logging.getLogger("doosri.auth")
router = APIRouter(tags=["auth"])

# Ten minutes. ratelimit.py assumes this window when it allows five sends an
# hour, so shortening it here without touching that would leave a number that
# has burned its quota with no way to get back in.
OTP_TTL_S = 600
# Six digits is a million possibilities; five tries keeps the expected guesses
# under one in two hundred thousand per code, and the challenge dies afterwards
# so the next attempt needs a fresh SMS.
OTP_MAX_ATTEMPTS = 5
# Plan section 6.1: a family setup code is good for 24 hours, because it is
# often read out over a phone during a single sitting.
FAMILY_CODE_TTL_S = 24 * 3600
# Plan section 17: one payment covers up to six pensioners. The payer is one of
# them, so the code exists to admit the other five.
FAMILY_CODE_MAX_USES = MAX_PLAN_SEATS - 1


# ------------------------------------------------------------------- schemas --
class OtpIn(BaseModel):
    phone: str = Field(min_length=10, max_length=20)


class VerifyIn(BaseModel):
    phone: str = Field(min_length=10, max_length=20)
    code: str = Field(pattern=r"^\d{6}$")


class ProviderTokenIn(BaseModel):
    id_token: str = Field(min_length=20, max_length=4096)
    # Set at the end of onboarding, where there is no authenticated caller to
    # set them. Both are optional, and neither is trusted for anything that
    # matters: a language is a display choice and a timezone decides which
    # calendar day a check-in belongs to.
    lang: str | None = Field(default=None, max_length=12)
    time_zone: str | None = Field(default=None, max_length=64)


class RefreshIn(BaseModel):
    refresh_token: str = Field(min_length=20, max_length=512)


class FamilyCodeRedeemIn(BaseModel):
    # Loose on purpose. A mistyped code is a wrong code, and a 404 saying so is
    # more use to a person reading eight characters off a screen than a 422
    # about the ninth.
    code: str = Field(min_length=8, max_length=12)


# ------------------------------------------------------------- phone numbers --
_INDIAN_MOBILE = re.compile(r"^[6-9]\d{9}$")


def normalise_phone(raw: str) -> str:
    """
    An Indian mobile number to E.164, one form only.

    Every OTP hash, every rate-limit key and every unique constraint in the app
    is keyed on this string, so a number that reaches the table as "09876543210"
    and one that arrives as "+91 98765 43210" have to become the same row. Six
    digits of a wrong guess do not fail loudly here — they fail as "wrong code"
    an hour later, which is the worst place to find out.
    """
    digits = re.sub(r"\D", "", raw or "")
    if len(digits) == 12 and digits.startswith("91"):
        national = digits[2:]
    elif len(digits) == 10:
        national = digits
    elif len(digits) == 11 and digits.startswith("0"):
        national = digits[1:]
    else:
        raise HTTPException(400, "phone must be 10 digits, or +91 followed by 10")
    if not _INDIAN_MOBILE.match(national):
        raise HTTPException(400, "not a valid Indian mobile number")
    return f"+91{national}"


# ---------------------------------------------------------------- serializers --
def view(row: User) -> dict:
    """
    The profile, as the app sees it.

    The only definition of it in the codebase: /auth/verify returns this and
    GET /me returns this, and a person whose Settings screen disagrees with
    their profile screen has no way to tell which one is lying.
    """
    return {
        "id": row.id,
        "phone_e164": row.phone_e164,
        "name": row.name,
        "address_as": row.address_as,
        "lang": row.lang,
        "text_scale": row.text_scale,
        "tier": row.tier,
        "role": row.role,
        "service_type": row.service_type,
        "state_code": row.state_code,
        "department_id": row.department_id,
        "cadre": row.cadre,
        "join_year": row.join_year,
        "retire_year": row.retire_year,
        "city_id": row.city_id,
        "discoverable": row.discoverable,
        "platform": row.platform,
        "time_zone": row.time_zone,
        "lat": row.lat,
        "lng": row.lng,
        "created_at": row.created_at,
    }


def view_token(row: User, access: str, refresh: str) -> dict:
    """The token pair plus the profile, so the app can render without a second call."""
    return {
        "access_token": access,
        "refresh_token": refresh,
        "token_type": "bearer",
        "expires_in": config.ACCESS_TTL,
        "user": view(row),
    }


# --------------------------------------------------------------------- tiers --
def derive_tier(db: Session, user: User) -> str:
    """
    The tier, computed from what is actually on file.

    No endpoint in this app accepts a tier in a request body, and this is the
    reason. Tiers are the gate on paid work, hosting a voice room and vouching
    for strangers; a tier that travelled in a JSON body would be a tier anybody
    could send, and the only person who benefits from that is somebody who is
    not a pensioner.

    T1 is reached at two distinct vouches (plan section 6.1); T2 arrives when an
    office-bearer or moderator approves through /admin/verify/{id}, which is
    outside this module; T3 is what an approved document becomes.
    """
    tier = "T0"
    for row in db.scalars(select(Verification).where(Verification.user_id == user.id)).all():
        candidate = row.tier or "T0"
        if TIER_RANK.get(candidate, 0) > TIER_RANK[tier]:
            tier = candidate
    vouched_by = {
        v.voucher_id
        for v in db.scalars(select(Vouch).where(Vouch.vouchee_id == user.id)).all()
    }
    if len(vouched_by) >= 2 and TIER_RANK[tier] < TIER_RANK["T1"]:
        tier = "T1"
    return tier


# --------------------------------------------------------------- the JWKS seam --
# A decode is not an authentication. `jwt.decode(..., options={"verify_signature":
# False})` will hand back any claim anybody typed, so "iss" and "sub" read off
# an unverified token are a perfect way to sign in as somebody else. There is no
# path in this file that reads a claim before a signature has been checked, and
# the one place a key would come from is the function below.
#
# PRODUCTION MUST fill in `provider_signing_keys` with a fetch-and-cache of
#   Google  https://www.googleapis.com/oauth2/v3/certs
#   Apple   https://appleid.apple.com/auth/keys
# returning {kid: PEM}. It must refresh the cached set when it meets an unknown
# kid, because that is how both providers rotate their signing keys, and it
# must never take the algorithm from the token header. Until it does, the seam
# has no keys and both sign-in endpoints answer 401 — which is the correct
# answer, and much better than a token anybody can mint.
@dataclass(frozen=True)
class Provider:
    name: str
    jwks_url: str
    issuers: tuple[str, ...]
    # The users column this provider's subject is matched on.
    subject_field: str


PROVIDERS = {
    "google": Provider(
        "google",
        "https://www.googleapis.com/oauth2/v3/certs",
        ("https://accounts.google.com", "accounts.google.com"),
        "google_sub",
    ),
    "apple": Provider(
        "apple",
        "https://appleid.apple.com/auth/keys",
        ("https://appleid.apple.com",),
        "apple_sub",
    ),
}


def provider_signing_keys(provider: Provider, refresh: bool = False) -> dict[str, str] | None:
    """
    `{kid: PEM public key}` for this provider, or None.

    Fetched from the provider's JWKS, converted to PEM and cached for an hour by
    python/integrations/jwks.py; `refresh=True` refetches, which the caller asks
    for when a token names a key id it has not seen (that is how both providers
    rotate keys). Returning None is a refusal, not a fallback: the caller turns
    it into 401 rather than proceeding with an unverified token.
    """
    from python.integrations import jwks  # noqa: PLC0415

    return jwks.signing_keys(provider.jwks_url, refresh=refresh)


def _audiences(provider: Provider) -> tuple[str, ...]:
    """
    The client ids this build answers for.

    `aud` is checked, because an id_token minted for a different application is
    a token the bearer chose, not one we issued. An unset id means the
    deployment has not been configured, and that is a refusal too — silently
    skipping the check would be the same bug as skipping the signature.
    """
    if provider.name == "google":
        raw = (os.getenv("GOOGLE_CLIENT_ID"), os.getenv("GOOGLE_ANDROID_CLIENT_ID"))
    else:
        # A native iOS sign-in's id_token is minted for the app's bundle id; a
        # web or Android sign-in uses a Services ID. Either is ours.
        raw = (os.getenv("APPLE_SERVICE_ID"), config.APPSTORE_BUNDLE_ID)
    return tuple(x for x in raw if x)


def verify_provider_token(provider_name: str, id_token: str) -> dict:
    """
    Check a provider id_token, or raise 401.

    Every part of this is the security of the account: the signature proves the
    token came from Google or Apple, `iss` says which of them, `aud` says it was
    minted for this app, and `sub` is the only thing this function returns that
    the caller is allowed to act on.
    """
    provider = PROVIDERS.get(provider_name)
    if provider is None:
        raise HTTPException(400, f"provider must be one of {', '.join(sorted(PROVIDERS))}")

    audiences = _audiences(provider)
    if not audiences:
        raise HTTPException(401, f"{provider.name} sign-in is not configured")
    keys = provider_signing_keys(provider)
    if not keys:
        raise HTTPException(401, f"{provider.name} sign-in is not configured")

    try:
        kid = jwt.get_unverified_header(id_token).get("kid")
    except Exception:
        raise HTTPException(401, "malformed id_token") from None
    pem = keys.get(kid) if kid else None
    if pem is None and kid:
        # An unseen key id usually means the provider rotated. Refetch once.
        keys = provider_signing_keys(provider, refresh=True) or {}
        pem = keys.get(kid)
    if pem is None:
        raise HTTPException(401, "unknown signing key")

    try:
        return jwt.decode(
            id_token,
            pem,
            # Pinned, never read from the token's own header. Letting the token
            # choose is how `alg: none` gets in.
            algorithms=["RS256"],
            audience=list(audiences),
            issuer=list(provider.issuers),
            options={"require": ["exp", "iat", "sub", "aud", "iss"]},
        )
    except Exception:
        # Deliberately not "wrong signature" versus "expired". The same reason
        # python/deps.py gives: the answer is free help and useless to the app.
        raise HTTPException(401, "id_token failed verification") from None


# ------------------------------------------------------------------ delivery --
def _send_sms(to_e164: str, message: str, code: str | None = None) -> bool:
    """
    The SMS seam, now backed by python/integrations/sms.py.

    It is a plain function rather than a call to python/notify.py because
    notify writes a `notifications` row against a user id, and an OTP is sent
    precisely when there is no user yet — a first-time sign-in has nobody to
    attach the record to. That is also why there is no notification history for
    this message: the record that the phone number was verified is the
    Verification row written at /auth/verify, and it is the one that has to
    survive.

    Returns whether a provider accepted the message. In development with no
    provider configured it logs the code so the app can be driven locally (the
    code is also returned by /auth/otp outside production).
    """
    from python.integrations import sms  # late: keeps auth importable without httpx

    if config.SMS_PROVIDER:
        ok = sms.send_otp(to_e164, code) if code else sms.SmsChannel().send(
            to=to_e164, title="Doosri Pari", body=message, kind="otp"
        ).ok
        if not ok:
            log.warning("otp sms to …%s was not accepted by the provider", to_e164[-2:])
        return ok
    if config.ENV != "production":
        log.info("[sms] to=…%s: %s", to_e164[-2:], message)
    else:
        log.error("[sms] no SMS provider configured; OTP for …%s not sent", to_e164[-2:])
    return False


# ------------------------------------------------------------------ sessions --
def issue_session(db: Session, user: User, device_id: int | None = None) -> dict:
    """
    Mint an access token and a refresh pair for one sign-in.

    The access token carries the tier so a bearer read does not need a query,
    and `deps.get_current_user` re-loads the user anyway — which is what lets a
    signed-out or deleted person be signed out within the hour.
    """
    raw, digest = new_refresh_token()
    db.add(
        RefreshToken(
            user_id=user.id,
            token_hash=digest,
            device_id=device_id,
            expires_at=now() + config.REFRESH_TTL,
        )
    )
    access = issue_access_token(user.id, user.tier, family_hash=digest)
    db.commit()
    return view_token(user, access, raw)


def _social_user(db: Session, provider: Provider, sub: str) -> User:
    """
    Find or create the person behind a provider subject.

    Deliberately no linking by email. The account is keyed on the phone number
    and the phone number is what a pensioner can still answer a call on; a
    provider that hands us an email address we did not ask for is not a reason
    to attach one account to another. Two accounts until the person signs in
    both, which is inconvenient and safe.
    """
    user = db.scalar(select(User).where(getattr(User, provider.subject_field) == sub))
    if user is not None:
        if user.deleted_at is not None:
            raise HTTPException(409, "this account has been deleted")
        return user
    user = User(**{provider.subject_field: sub})
    db.add(user)
    db.flush()
    return user


# ---------------------------------------------------------------- endpoints --
@router.post("/auth/otp")
def send_otp(body: OtpIn, request: Request, db: DbSession):
    """
    Send a six-digit code to an Indian mobile number.
    """
    phone = normalise_phone(body.phone)
    # Per IP and per number, both: the IP limit stops one host spraying many
    # numbers, the number limit stops one number being spammed from many
    # hosts. Either alone leaves the other open.
    limit_otp(request, phone)

    ts = now()
    superseded = db.scalar(
        select(OtpChallenge).where(
            OtpChallenge.phone_e164 == phone,
            OtpChallenge.redeemed_at.is_(None),
        )
    )
    if superseded is not None:
        # The banner explains why this is a delete and not a second row.
        db.delete(superseded)
        db.flush()

    code = new_opp()
    row = OtpChallenge(
        phone_e164=phone,
        # The code is stored as a keyed hash, never in the clear, so a copy of
        # the database is not a list of working logins.
        code_hash=hash_otp(phone, code),
        max_attempts=OTP_MAX_ATTEMPTS,
        expires_at=ts + OTP_TTL_S,
    )
    db.add(row)
    db.commit()

    _send_sms(phone, f"Your Doosri Pari code is {code}. It is valid for 10 minutes.", code=code)

    out: dict = {
        "phone_e164": phone,
        "expires_in": OTP_TTL_S,
        "resent": superseded is not None,
    }
    if config.ENV != "production":
        # Returned so a developer can drive the app with no SMS gateway. This is
        # the most dangerous line in the file if it ever ships: anyone who can
        # reach /auth/otp would be able to mint a working login for any Indian
        # number, which is a working impersonation of a pensioner. It is keyed
        # on the environment rather than a flag, so a deployment cannot turn it
        # on by accident.
        out["dev_code"] = code
    return out


@router.post("/auth/verify")
def verify(body: VerifyIn, request: Request, db: DbSession):
    """
    Redeem a code, and sign in — creating the account on the first success.

    Creating the user here is the whole of the signup (plan section 6.1), which
    is why a person can be verified before they have told us anything else: a
    name, a department and a language all arrive afterwards through PATCH /me.
    """
    phone = normalise_phone(body.phone)
    # Not per-user: nobody is signed in yet. The per-IP half of the write limit
    # is the whole of the limit on this endpoint.
    limit_write(request, None)

    ts = now()
    row = db.scalar(
        select(OtpChallenge)
        .where(
            OtpChallenge.phone_e164 == phone,
            OtpChallenge.redeemed_at.is_(None),
        )
        .order_by(OtpChallenge.created_at.desc())
    )
    if row is None:
        raise HTTPException(401, "no code has been sent to this number")
    if row.attempts >= row.max_attempts or ts >= row.expires_at:
        raise HTTPException(401, "that code has expired or run out of attempts")
    if not verify_otp(phone, body.code, row.code_hash):
        # One attempt spent per guess, whatever was guessed. Ten SMS messages
        # with the same code in them is a phone number that a carrier will
        # block, and then the person cannot get in at all.
        row.attempts += 1
        db.commit()
        if row.attempts >= row.max_attempts:
            raise HTTPException(429, "too many wrong attempts — ask for a new code")
        raise HTTPException(401, "wrong code")

    row.redeemed_at = ts
    user = db.scalar(select(User).where(User.phone_e164 == phone))
    if user is None:
        user = User(phone_e164=phone, lang="en", time_zone="Asia/Kolkata")
        db.add(user)
        db.flush()
        # Plan section 6.1, T0: an OTP-verified phone number. Kept as a row so
        # the export at GET /me/export can say what this account is, and so
        # the tier has something to be derived from rather than being a field
        # somebody can set.
        db.add(Verification(user_id=user.id, tier="T0", method="otp", verified_at=ts))
    elif user.deleted_at is not None:
        # The code is already proven, so this is the account holder finding out
        # that the account is gone — not an enumeration probe.
        raise HTTPException(409, "this account has been deleted")

    user.tier = derive_tier(db, user)
    return issue_session(db, user)


@router.post("/auth/google")
def google_sign_in(body: ProviderTokenIn, request: Request, db: DbSession):
    """Sign in with Google. The id_token's signature is checked, or refused."""
    return _social_sign_in("google", body, request, db)


@router.post("/auth/apple")
def apple_sign_in(body: ProviderTokenIn, request: Request, db: DbSession):
    """
    Sign in with Apple.

    Required whenever another social sign-in is offered (plan section 6.1):
    Apple reviews an app that offers Google sign-in without it, and the request
    would be refused at review rather than in production.
    """
    return _social_sign_in("apple", body, request, db)


def _social_sign_in(
    provider_name: str, body: ProviderTokenIn, request: Request, db: Session
) -> dict:
    limit_write(request, None)
    provider = PROVIDERS[provider_name]
    claims = verify_provider_token(provider_name, body.id_token)

    sub = str(claims.get("sub") or "").strip()
    if not sub:
        raise HTTPException(401, "id_token carries no subject")

    user = _social_user(db, provider, sub)
    if body.lang:
        user.lang = body.lang
    if body.time_zone:
        user.time_zone = body.time_zone
    user.tier = derive_tier(db, user)
    return issue_session(db, user)


@router.post("/auth/refresh")
def rotate_refresh(body: RefreshIn, request: Request, db: DbSession):
    """
    Exchange a refresh token for a new pair. The presented token is spent.

    Presentation of a token that is already revoked is the one signal on this
    endpoint that is not a normal failure. A legitimate client never does it:
    it holds one refresh token, uses it once, and stores the replacement. Two
    presentations of the same token therefore mean the token was copied, and
    the only safe reading of a copy is that the attacker and the real user are
    both holding it. So the whole set goes, not just the one, and the real user
    signs in again. Sign-in is an OTP; being signed out is recoverable.
    """
    digest = hash_refresh_token(body.refresh_token)
    row = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == digest))
    if row is None:
        raise HTTPException(401, "invalid refresh token")

    if row.revoked_at is not None:
        # No `family` argument: a stolen-token signal takes down every device
        # this person is signed in on, which is the only outcome that is safe
        # when it is not known which device is the attacker.
        revoke_family(db, row.user_id)
        db.commit()
        log.warning("refresh token reuse for user %s; family revoked", row.user_id)
        raise HTTPException(401, "refresh token already used — all sessions signed out")

    if row.expires_at <= now():
        raise HTTPException(401, "refresh token expired")

    user = db.get(User, row.user_id)
    if user is None or user.deleted_at is not None:
        raise HTTPException(401, "account unavailable")

    limit_write(request, user.id)
    row.revoked_at = now()
    return issue_session(db, user, device_id=row.device_id)


@router.post("/auth/family-code")
def mint_family_code(request: Request, user: CurrentUser, db: DbSession):
    """
    Mint a code the payer reads out to the pensioners they cover.

    One live code per payer, because a code that has been sitting in a group
    chat for a month is a way into somebody's account that nobody would think
    to revoke. Minting a new one retires the old.
    """
    limit_write(request, user.id)

    sub = sub_for(db, user.id)
    if sub is None:
        # The code buys a seat on a payment; without a payment there is no
        # seat. Redemption checks this again, because a subscription can lapse
        # between minting and redeeming.
        raise HTTPException(409, "you do not have an active family plan")

    db.execute(delete(FamilyCode).where(FamilyCode.payer_id == user.id))

    code = new_family_code()
    row = FamilyCode(
        payer_id=user.id,
        # Stored as issued rather than as a digest. `hash_family_code` exists,
        # but the column is a 12-character indexed string and redemption is a
        # unique lookup on the code itself, which a 64-character digest breaks.
        # The code is short-lived, capped at five uses, and rate limited on the
        # way in; the payer can retire it by minting another.
        code=code,
        max_uses=FAMILY_CODE_MAX_USES,
        expires_at=now() + FAMILY_CODE_TTL_S,
    )
    db.add(row)
    db.commit()
    return {
        "code": row.code,
        "expires_at": row.expires_at,
        "max_uses": row.max_uses,
        "seats_used": seat_count(db, sub),
        "seats_total": MAX_PLAN_SEATS,
    }


@router.post("/auth/family-code/redeem")
def redeem_family_code(
    body: FamilyCodeRedeemIn, request: Request, user: CurrentUser, db: DbSession
):
    """
    Take a seat on somebody else's family plan.

    A seat, not a shared login (plan section 6.13, §17). The person keeps their
    own account and their own phone number; what the payment covers is the
    Anubhav board and the family features. A shared login would mean that when
    the son changes the phone number the old number keeps his mother's listing,
    and her listing has her address on it.
    """
    limit_write(request, user.id)
    code = body.code.strip().upper()
    ts = now()

    row = db.scalar(select(FamilyCode).where(FamilyCode.code == code))
    if row is None:
        raise HTTPException(404, "family code not found")
    if row.payer_id == user.id:
        raise HTTPException(409, "you cannot redeem your own family code")
    if not row.is_usable_at(ts):
        raise HTTPException(409, "this family code has expired or been fully used")

    sub = sub_for(db, row.payer_id)
    if sub is None:
        raise HTTPException(409, "that person is not on an active family plan")

    # A seat is one membership, not a stack: the unique constraint on
    # subscription_seats.user_id says a person is covered by one plan. Honouring
    # a second redemption would either trip that constraint or leave two paying
    # families both believing they cover the same pensioner.
    existing = db.scalar(select(SubscriptionSeat).where(SubscriptionSeat.user_id == user.id))
    if existing is not None:
        if existing.subscription_id == sub.id and existing.revoked_at is None:
            # Already covered by this plan. A second tap on "join" is a no-op
            # and must not spend one of the code's five uses.
            db.commit()
            return seat_view(existing, sub, seat_count(db, sub))
        raise HTTPException(409, "you are already covered by another family plan")

    used = seat_count(db, sub)
    if used >= MAX_PLAN_SEATS:
        # 409 rather than 429: nothing about the caller's rate is wrong, the
        # plan is simply full. The payer retires a seat in Settings.
        raise HTTPException(409, f"that family plan already covers {MAX_PLAN_SEATS} people")

    seat = SubscriptionSeat(subscription_id=sub.id, user_id=user.id)
    db.add(seat)
    row.uses += 1
    db.flush()
    db.commit()
    return seat_view(seat, sub, used + 1)


def sub_for(db: Session, payer_id: int) -> Subscription | None:
    """The payer's live plan, most recent first."""
    return db.scalar(
        select(Subscription)
        .where(
            Subscription.payer_id == payer_id,
            Subscription.status.in_(("active", "grace_period")),
        )
        .order_by(Subscription.created_at.desc())
    )


def seat_count(db: Session, sub: Subscription) -> int:
    """Seats in use. Revoked ones are excluded — the count is what is covered now."""
    return len(
        db.scalars(
            select(SubscriptionSeat).where(
                SubscriptionSeat.subscription_id == sub.id,
                SubscriptionSeat.revoked_at.is_(None),
            )
        ).all()
    )


def seat_view(seat: SubscriptionSeat, sub: Subscription, used: int) -> dict:
    return {
        "subscription_id": sub.id,
        "seat_id": seat.id,
        "seats_used": used,
        "seats_total": MAX_PLAN_SEATS,
        "valid_until": sub.valid_until,
        "status": sub.status,
    }
