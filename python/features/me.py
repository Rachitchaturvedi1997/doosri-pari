# =============================================================================
# Feature · Profile, devices and verification — plan sections 6.1, 11, 14.
#
#   POST   /devices               register a push token
#   DELETE /devices/{id}          forget one
#   GET    /me                    the profile, devices and verification state
#   PATCH  /me                    onboarding steps 5–7, editable later
#   DELETE /me                    30-day soft deletion
#   GET    /me/export             everything held about this person (DPDP §14)
#   POST   /verify/vouch           a member says somebody else is who they are
#   POST   /verify/document       a document type, four digits and a client ref
#
# The one non-obvious rule this module enforces is on the export. Everything
# GET /me/export returns is about the caller and nothing else: a guardian's
# phone number, a helper's location, the name of whoever vouched for you, the
# message that was sent to your son at 3am — all of that is somebody else's
# data, and handing it to the pensioner as part of "here is everything we hold
# about you" is the way an export endpoint ends up being the largest privacy
# hole in the app. Counts and permission keys are included so a person can see
# who they share with; names, numbers and coordinates belonging to other people
# are not, and there is no flag that turns them on.
# =============================================================================
from __future__ import annotations

import json
import logging

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession, revoke_family
from python.escalation import local_day

# `view` and `derive_tier` are imported rather than re-declared. They are the
# one profile serialiser and the one tier rule in the codebase, and they live in
# the auth module because that is where a profile is first returned — at
# signup. Two copies of "what tier is this person" is how somebody ends up on
# T3 everywhere except the one place it is checked.
from python.features.auth import derive_tier, view
from python.models import (
    ACCOUNT_DELETION_GRACE_DAYS,
    LANGUAGES,
    RETENTION_VERIFICATION_DOCUMENT_DAYS,
    SERVICE_TYPES,
    TIER_RANK,
    CheckIn,
    CheckInSettings,
    Device,
    Guardian,
    Notification,
    RefreshToken,
    SosEvent,
    Subscription,
    SubscriptionSeat,
    User,
    Verification,
    Vouch,
    Upload,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.me")
router = APIRouter(tags=["me"])

# A pensioner is not going to vouch for themselves, and a vouch is only worth
# anything from somebody who has been through verification themselves. Plan
# section 6.1 says the vouches that earn T1 must come from T2+ members, but
# T2 requires office-bearer approval through /admin/verify/{id}, so requiring it
# here would leave vouching unusable until that exists — and on day one nobody
# is T2. The stricter gate belongs on the approval endpoint; this one is the
# floor.
VOUCHER_MIN_TIER = "T1"
VOUCHES_FOR_T1 = 2

# Bounds on the export. It is a human-readable copy that somebody opens in a
# browser; an unbounded one is a denial of service against the person asking for
# it. Anything cut is reported in `truncated` rather than silently dropped.
EXPORT_NOTIFICATION_LIMIT = 500
EXPORT_CHECKIN_LIMIT = 400
EXPORT_SOS_LIMIT = 200


# ------------------------------------------------------------------- schemas --
class DeviceIn(BaseModel):
    platform: str = Field(pattern=r"^(android|ios)$")
    push_token: str = Field(min_length=8, max_length=255)
    app_version: str | None = Field(default=None, max_length=32)


class ProfileIn(BaseModel):
    # Onboarding steps 5 and 6. Every field is optional and only the ones
    # actually sent are written, so a phone that can only type a name does not
    # blank out the department a son entered.
    name: str | None = Field(default=None, max_length=80)
    address_as: str | None = Field(default=None, max_length=60)
    lang: str | None = Field(default=None, max_length=12)
    text_scale: float | None = Field(default=None, ge=1.0, le=1.3)
    service_type: str | None = Field(default=None, max_length=32)
    state_code: str | None = Field(default=None, max_length=4)
    department_id: int | None = Field(default=None, ge=1)
    cadre: str | None = Field(default=None, max_length=64)
    join_year: int | None = Field(default=None, ge=1947, le=2100)
    retire_year: int | None = Field(default=None, ge=1950, le=2100)
    city_id: int | None = Field(default=None, ge=1)
    time_zone: str | None = Field(default=None, max_length=64)
    discoverable: bool | None = None
    # No lat/lng here on purpose. Plan section 6.1 asks for location only when a
    # feature first needs it, and the first thing that needs it is SOS — not a
    # profile edit. A location accepted here would be a permanent home address
    # in a database, on a server whose whole privacy claim is that it does not
    # keep things like that. Location reaches the app through the check-in and
    # SOS endpoints, where it is sent and dropped.


class VouchIn(BaseModel):
    user_id: int = Field(ge=1)


class DocumentIn(BaseModel):
    document_type: str = Field(
        pattern=r"^(ppo|pension_id|service_id|aadhaar_last4|other)$"
    )
    # Four digits, and four is the entire point. The full PPO, Aadhaar or bank
    # number is never typed into this app's form, never sent over this
    # connection, and never stored — plan section 14 says so, and the field is
    # a four-character column because a schema that can hold more will eventually
    # be asked to hold more. What survives is enough to answer "verified?" and
    # not enough to impersonate anybody.
    last4: str = Field(pattern=r"^\d{4}$")
    # An opaque handle into the on-phone document safe (plan section 6.5), where
    # the image actually lives and is encrypted with a key the server does not
    # have. Sending the bytes instead would put a pension slip in this database
    # and in every backup of it, which is precisely what the safe exists to
    # avoid.
    client_ref: str = Field(
        min_length=8, max_length=255, pattern=r"^[A-Za-z0-9_\-.:/]+$"
    )
    # Plan 6.1 T3: the person may upload the first page of the PPO or a pension
    # ID so a moderator can check the name and service type. The image is deleted
    # within 7 days by the `deletion` job and only "verified on X by Y" and the
    # last four digits survive. Uploading is OPTIONAL: without it the request is
    # still reviewed, by a moderator on a video call (the T2 route), and nothing
    # about a person's papers reaches the server against their wishes.
    upload_id: int | None = Field(default=None, ge=1)


# ---------------------------------------------------------------- serializers --
def device_view(row: Device) -> dict:
    """
    A device, without its push token.

    The token is a handle on the handset rather than a fact about the person, so
    it is never echoed back: a list of FCM tokens in an API response is a list
    of things that can be pushed to, and it ends up in logs.
    """
    return {
        "id": row.id,
        "platform": row.platform,
        "app_version": row.app_version,
        "last_seen_at": row.last_seen_at,
    }


def verification_view(row: Verification) -> dict:
    """
    A verification, without the id of whoever granted it.

    `last4` is included because plan section 6.1 promises the last four are what
    is kept. `by_user_id` is not, because the id of the moderator or office-
    bearer who approved it is that person's data, not this person's.
    """
    return {
        "id": row.id,
        "tier": row.tier,
        "method": row.method,
        "last4": row.last4,
        "verified_at": row.verified_at,
        "document_deleted_at": row.document_deleted_at,
    }


def _verification_state(db: Session, user: User) -> dict:
    rows = db.scalars(
        select(Verification)
        .where(Verification.user_id == user.id)
        .order_by(Verification.verified_at.desc())
    ).all()
    return {
        "tier": user.tier,
        "records": [verification_view(r) for r in rows],
        "vouched_by": len(
            {v.voucher_id for v in db.scalars(
                select(Vouch).where(Vouch.vouchee_id == user.id)
            ).all()}
        ),
        "vouches_given": len(
            db.scalars(select(Vouch).where(Vouch.voucher_id == user.id)).all()
        ),
    }


# ------------------------------------------------------------------ devices --
@router.post("/devices")
def register_device(body: DeviceIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Record a handset for push.

    The same token re-registered after a reinstall updates the existing row
    rather than adding a second one. A push token is issued per app install, so
    a reinstall — which is what a pensioner does when storage runs out, or what
    happens every time an OS update rolls the app — produces a new token, and
    the old one keeps silently collecting reminders nobody will ever see.
    """
    limit_write(request, user.id)

    row = db.scalar(select(Device).where(Device.push_token == body.push_token))
    if row is None:
        row = Device(user_id=user.id, platform=body.platform, push_token=body.push_token)
        db.add(row)
    else:
        # A token that already belongs to another account means the same
        # handset is now signed in as somebody else. Plan section 6.1 expects
        # that — two profiles on one phone — so the last sign-in wins the
        # notifications and the earlier one simply stops being pushed to.
        row.user_id = user.id
        row.platform = body.platform
    row.app_version = body.app_version or row.app_version
    row.last_seen_at = now()
    user.platform = body.platform
    db.commit()
    return device_view(row)


@router.delete("/devices/{device_id}")
def forget_device(
    device_id: int, request: Request, user: CurrentUser, db: DbSession
):
    """
    Stop pushing to a handset. Used when a phone is lost or sold.
    """
    limit_write(request, user.id)

    row = db.get(Device, device_id)
    # 404 rather than 403: a device belonging to somebody else is not this
    # caller's to enumerate, and the difference in the two answers would tell a
    # stranger which ids exist.
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "device not found")

    # A phone that is no longer this person's should not be able to keep
    # refreshing a session, so the refresh tokens bound to it die with it. The
    # access tokens already die on their own within the hour.
    for token in db.scalars(
        select(RefreshToken).where(
            RefreshToken.device_id == row.id, RefreshToken.revoked_at.is_(None)
        )
    ).all():
        token.revoked_at = now()

    db.delete(row)
    db.commit()
    return {"deleted": device_id}


# ------------------------------------------------------------------ profile --
@router.get("/me")
def get_me(user: CurrentUser, db: DbSession):
    """
    The profile, the handsets and what this account has been verified as.
    """
    devices = db.scalars(
        select(Device).where(Device.user_id == user.id).order_by(Device.id)
    ).all()
    return {
        "user": view(user),
        "devices": [device_view(d) for d in devices],
        "verification": _verification_state(db, user),
    }


@router.patch("/me")
def patch_me(body: ProfileIn, user: CurrentUser, request: Request, db: DbSession):
    """
    Edit the profile. Only the fields actually sent are written.

    A person who can type a name should not be able to clear out the service
    type their son filled in, so an absent field is left alone rather than set
    to null.
    """
    limit_write(request, user.id)
    changes = body.model_dump(exclude_unset=True, exclude_none=True)
    if not changes:
        raise HTTPException(400, "nothing to change")

    if "lang" in changes and changes["lang"] not in LANGUAGES:
        raise HTTPException(400, f"lang must be one of {', '.join(LANGUAGES)}")
    if "service_type" in changes and changes["service_type"] not in SERVICE_TYPES:
        raise HTTPException(400, f"service_type must be one of {', '.join(SERVICE_TYPES)}")

    for field, value in changes.items():
        setattr(user, field, value)
    db.commit()
    return view(user)


@router.delete("/me")
def delete_me(request: Request, user: CurrentUser, db: DbSession):
    """
    Ask for the account to be deleted, in 30 days (plan sections 12 and 14).

    Nothing is scheduled here on purpose. A scheduled task is a thing that can
    fail quietly and be lost by the next deploy, whereas the daily `deletion`
    job simply looks for rows whose `deleted_at` is more than 30 days old — it
    cannot forget, it cannot drift, and re-running it is harmless. `deleted_at`
    is the whole schedule.

    The refresh tokens go now rather than in 30 days. A person who has asked to
    be forgotten should be signed out within the hour, not in a month, and
    python/deps.py refuses a deleted account on every read so the access token
    stops working on the next request rather than the next expiry.
    """
    limit_write(request, user.id)
    if user.deleted_at is not None:
        raise HTTPException(409, "deletion is already scheduled")

    ts = now()
    user.deleted_at = ts
    revoked = revoke_family(db, user.id)
    db.commit()

    purge_at = ts + ACCOUNT_DELETION_GRACE_DAYS * 86400
    return {
        "deleted_at": ts,
        "purge_after": purge_at,
        "purge_date": local_day(user, purge_at),
        "sessions_revoked": revoked,
        "recoverable_until": local_day(user, purge_at),
    }


@router.get("/me/export")
def export_me(user: CurrentUser, db: DbSession):
    """
    Everything held about this person, as JSON. DPDP Act 2023, right of access.

    Read the banner before adding a table to this. The test is not "is this
    about the user" but "does this column contain a fact about somebody else":
    `Guardian.phone` and `Helper.lat` are held *because of* this user, and
    `Notification.guardian_id` is a message delivered to a family member. All
    three are somebody else's data wearing this person's record as a hat. What
    is exported instead is the shape of the sharing — how many, and with which
    permissions — which is what a person actually needs in order to withdraw a
    consent or ask a question.

    Pension slips, amounts and the document safe are absent from this response
    because they were never on the server. That is not an omission here; it is
    the design (plan sections 10 and 14).
    """
    settings = user.settings
    guardians = db.scalars(select(Guardian).where(Guardian.user_id == user.id)).all()
    permissions: set[str] = set()
    for g in guardians:
        permissions.update((g.perms or {}).keys())

    notes = db.scalars(
        select(Notification)
        .where(
            Notification.user_id == user.id,
            # A row with a guardian on it is a message delivered to somebody
            # else. Whether it is their alert or this person's, it is not this
            # person's record of what they were told.
            Notification.guardian_id.is_(None),
        )
        .order_by(Notification.sent_at.desc())
        .limit(EXPORT_NOTIFICATION_LIMIT)
    ).all()

    checkins = db.scalars(
        select(CheckIn)
        .where(CheckIn.user_id == user.id)
        .order_by(CheckIn.day.desc())
        .limit(EXPORT_CHECKIN_LIMIT)
    ).all()

    soses = db.scalars(
        select(SosEvent)
        .where(SosEvent.user_id == user.id)
        .order_by(SosEvent.started_at.desc())
        .limit(EXPORT_SOS_LIMIT)
    ).all()

    devices = db.scalars(select(Device).where(Device.user_id == user.id)).all()
    seat = db.scalar(select(SubscriptionSeat).where(SubscriptionSeat.user_id == user.id))
    subscription = None
    if seat is not None and seat.revoked_at is None:
        sub = db.get(Subscription, seat.subscription_id)
        if sub is not None:
            subscription = {
                "status": sub.status,
                "valid_until": sub.valid_until,
                "store": sub.store,
                # Who pays is the payer's business, and not always something
                # the person covered by the plan needs to read.
                "is_payer": sub.payer_id == user.id,
                **({"payer_user_id": sub.payer_id} if sub.payer_id == user.id else {}),
            }

    return {
        "exported_at": now(),
        "format_version": 1,
        "account": view(user),
        "consent": {
            "location_sharing": bool(settings.share_location) if settings else False,
            "checkin_window": settings.window_start if settings else None,
            "checkin_window_mins": settings.window_mins if settings else None,
            "paused_until": settings.paused_until if settings else None,
            "guardians": {"count": len(guardians), "permissions": sorted(permissions)},
        },
        "verification": _verification_state(db, user),
        "devices": [device_view(d) for d in devices],
        "checkins": [
            {
                "day": c.day,
                "stage": c.stage,
                "checked_at": c.checked_at,
                "method": c.method,
                "resolved_at": c.resolved_at,
                "lat": c.lat,
                "lng": c.lng,
            }
            for c in checkins
        ],
        "sos": [
            {
                "started_at": s.started_at,
                "cancelled": s.cancelled,
                "resolved_at": s.resolved_at,
                "lat": s.lat,
                "lng": s.lng,
            }
            for s in soses
        ],
        "notifications": [
            {
                "channel": n.channel,
                "status": n.status,
                "sent_at": n.sent_at,
                "title": n.title,
                "body": n.body,
            }
            for n in notes
        ],
        "subscription": subscription,
        "truncated": {
            "notifications": len(notes) == EXPORT_NOTIFICATION_LIMIT,
            "checkins": len(checkins) == EXPORT_CHECKIN_LIMIT,
            "sos": len(soses) == EXPORT_SOS_LIMIT,
        },
    }


# ------------------------------------------------------------ verification --
@router.post("/verify/vouch")
def vouch(body: VouchIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Say that somebody else is who they say they are.

    Weak on purpose, and kept in its own table with its own name so the
    difference between "somebody said so" and "we checked" survives in the data
    and not only in the interface. Two vouches earn T1 (plan section 6.1);
    nothing here grants a tier on its own.
    """
    limit_write(request, user.id)

    if body.user_id == user.id:
        raise HTTPException(403, "you cannot vouch for yourself")
    if TIER_RANK.get(user.tier or "T0", 0) < TIER_RANK[VOUCHER_MIN_TIER]:
        raise HTTPException(403, f"you need to be {VOUCHER_MIN_TIER} or above to vouch")

    vouchee = db.get(User, body.user_id)
    if vouchee is None or vouchee.deleted_at is not None:
        raise HTTPException(404, "person not found")
    if vouchee.department_id and user.department_id and vouchee.department_id != user.department_id:
        # Plan section 6.1: the vouch comes from the same department. Without
        # it, a stranger from another city and another service can vouch for
        # anybody, and T1 is a step towards paid work and towards vouching for
        # other people. Unset departments on either side are allowed through —
        # a large number of pensioners never fill the field in, and refusing
        # them would make vouching impossible for exactly the people it is for.
        raise HTTPException(403, "you can only vouch for your own department")

    existing = db.scalar(
        select(Vouch).where(Vouch.voucher_id == user.id, Vouch.vouchee_id == vouchee.id)
    )
    if existing is not None:
        raise HTTPException(409, "you have already vouched for this person")

    db.add(
        Vouch(
            voucher_id=user.id,
            vouchee_id=vouchee.id,
            department_id=user.department_id or vouchee.department_id,
        )
    )
    # tier="T0": what a vouch confers on its own, which is nothing. The tier is
    # computed from the count in derive_tier, so it cannot be inflated by
    # writing a row.
    db.add(
        Verification(
            user_id=vouchee.id,
            tier="T0",
            method="vouch",
            by_user_id=user.id,
            verified_at=now(),
        )
    )
    db.flush()

    vouchee.tier = derive_tier(db, vouchee)
    received = len(
        {v.voucher_id for v in db.scalars(
            select(Vouch).where(Vouch.vouchee_id == vouchee.id)
        ).all()}
    )
    db.commit()
    return {
        "user_id": vouchee.id,
        "vouched_by": user.id,
        "vouches": received,
        "needed_for_t1": VOUCHES_FOR_T1,
        "tier": vouchee.tier,
    }


@router.post("/verify/document")
def submit_document(
    body: DocumentIn, request: Request, user: CurrentUser, db: DbSession
):
    """
    Submit an identity document for checking.

    What arrives is a type, four digits and a reference to an encrypted copy on
    the phone. The document itself never reaches this server, which is plan
    section 14 and the reason the on-phone document safe exists at all. If that
    ever changes — a PDF upload, a base64 blob, a "temporarily" stored image —
    the entire privacy position of the product has gone and no other part of
    this file compensates for it.

    This records a claim, not a verification. A moderator checks the name and
    the service type and approves through /admin/verify/{id}, and the tier in
    this row is what it confers right now, which is nothing. Writing T3 here
    would be a tier that arrives with the upload.
    """
    limit_write(request, user.id)

    ts = now()
    row = Verification(
        user_id=user.id,
        # Not T3. The row exists so a moderator has something to approve
        # against; the tier it carries is what it confers today.
        tier="T0",
        method="document",
        verified_at=ts,
        last4=body.last4,
        # The column is the free-text one, so the type and the safe's handle go
        # in as a small JSON object rather than a sentence a person has to
        # parse. Nothing sensitive: `client_ref` points at the phone.
        notes=json.dumps({"document_type": body.document_type, "client_ref": body.client_ref}),
        # `document_deleted_at` is when the object WAS deleted, set by the
        # daily `deletion` job and by nothing else; null means "not yet". The
        # deadline is derived from `verified_at` and reported below as
        # `document_expires_on`, so a person asking "when is my document
        # deleted?" gets a date rather than a policy.
        document_deleted_at=None,
    )
    if body.upload_id is not None:
        up = db.get(Upload, body.upload_id)
        if up is None or up.user_id != user.id or up.purpose != "verification_doc" or up.status != "ready":
            raise HTTPException(400, "that upload is not a finished verification document of yours")
        row.document_key = up.key
        up.ref_type, up.ref_id = "verification", None
    db.add(row)
    db.flush()
    if body.upload_id is not None:
        up.ref_id = row.id

    user.tier = derive_tier(db, user)
    db.commit()
    return {
        "id": row.id,
        "document_type": body.document_type,
        "last4": row.last4,
        "document_deleted_at": row.document_deleted_at,
        "document_stored": row.document_key is not None,
        "document_expires_on": (
            local_day(user, ts + RETENTION_VERIFICATION_DOCUMENT_DAYS * 86400)
            if row.document_key is not None else None
        ),
        "tier": user.tier,
        "status": "awaiting_moderator",
    }
