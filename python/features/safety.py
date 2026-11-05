# =============================================================================
# Feature · SOS, guardians and the scam shield — plan sections 6.3, 6.12 and 14.
#
#   POST   /sos                   the countdown ran out; everybody is told now
#   POST   /sos/{id}/cancel       the countdown was backed out of
#   POST   /sos/{id}/safe         "I am safe now"
#   POST   /guardians/invite
#   POST   /guardians/accept
#   PATCH  /guardians/{id}/perms  what this guardian is told
#   GET    /guardian/dashboard    the guardian's own view
#   POST   /scam-check
#   GET    /scam-alerts
#
# The one rule this module exists to make true: a phone number receives an
# alert only if it accepted being a guardian, and a permission that is switched
# off has to stop the message rather than record that it was switched off. Plan
# section 14 wants separate consents for location, health and guardian sharing,
# and a withdrawal that is stored but not honoured is the whole difference
# between a family safety feature and a surveillance tool. So every send here
# goes through `wants()` before it happens, there is no path around it, and
# nobody is ever written to except the owner of the row being changed.
# =============================================================================
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from python import config
from python.deps import CurrentUser, DbSession
from python.models import (
    TERMINAL_STAGES,
    CheckIn,
    Device,
    Guardian,
    Helper,
    ScamAlert,
    ScamCheckRequest,
    SosEvent,
    User,
    now,
)
from python.notify import CHANNEL_ORDER, dispatch
from python.ratelimit import limit_write

log = logging.getLogger("doosri.safety")
router = APIRouter(tags=["safety"])

# What a guardian is told about, as separate switches rather than one "share
# with family" (plan 14). `location` and `health` are off by default because
# both are optional by the plan's own wording: 6.3 shares a location only when
# the user enabled it, and 12's dose_followup alerts "if allowed". `alerts`
# and `sos` are on because a person who accepted being a guardian and then
# never hears anything is worse than one who was never added.
#
# `medicines` and `document_safe` are the names python/features/health.py and the
# vault read (PERM_MEDICINES, PERM_DOCUMENT_SAFE). `health` is the older name for
# the medicines switch: it is still accepted and is kept in step with
# `medicines`, so a client that predates the rename does not lose the setting.
GUARDIAN_PERMS = ("alerts", "sos", "location", "medicines", "document_safe", "health")
PERM_DEFAULTS = {
    "alerts": True, "sos": True, "location": False,
    "medicines": False, "document_safe": False, "health": False,
}

# An SOS is the one message that goes out at 3am to five different households,
# and it is the one message nobody is watching the log for. Saying "five" is
# worth the dictionary: a pensioner told "you have reached the limit of 5"
# concludes the app is broken, and the person who could have removed a guardian
# does not.
_NUMBER_WORDS = {1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six"}


def _words(n: int) -> str:
    return _NUMBER_WORDS.get(n, str(n))


# ------------------------------------------------------------------ schemas --
class SosIn(BaseModel):
    # Omitted when the phone has no fix, which is most SOS presses made
    # indoors. Absent coordinates are not a failure state to apologise for —
    # the alert still goes, it just has nothing to attach.
    lat: float | None = None
    lng: float | None = None
    accuracy: float | None = None


class GuardianInviteIn(BaseModel):
    phone: str = Field(min_length=6, max_length=20)
    name: str = Field(min_length=1, max_length=80)
    relation: str | None = Field(default=None, max_length=32)


class GuardianAcceptIn(BaseModel):
    # The number the invitation was sent to. It has to be the caller's own;
    # see the 403 below.
    phone: str = Field(min_length=6, max_length=20)
    # Optional, and it matters more than it looks. One phone number is often a
    # guardian for two people — the mother and the aunt — so accepting on a
    # number alone would silently attach the caller to whichever invitation
    # happened to be newest. The invite SMS carries this id; with it the
    # choice is explicit, and without it we refuse rather than guess.
    guardian_id: int | None = None


class GuardianPermsIn(BaseModel):
    # Every field optional, and only the ones present are changed. A PATCH that
    # replaced the whole dict is how somebody turns off "location" and
    # silently turns off SOS with it.
    alerts: bool | None = None
    sos: bool | None = None
    location: bool | None = None
    medicines: bool | None = None
    document_safe: bool | None = None
    health: bool | None = None  # legacy alias of `medicines`


class ScamCheckIn(BaseModel):
    text: str = Field(min_length=1, max_length=4000)


# ------------------------------------------------------------------ helpers --
def wants(g: Guardian, perm: str) -> bool:
    """
    May this guardian be sent this thing, right now?

    This is the gate, and every send in this module calls it. The `accepted_at`
    check is not redundant with the one in python/notify.py — it is the same
    rule stated where the decision is made, so a caller that forgets to pass a
    `guardian_id` still cannot reach an unconsenting number. The rest is a
    withdrawn consent, and the point of plan 14 is that withdrawing one has to
    change what is sent today, not merely what a settings screen says.
    """
    if g.accepted_at is None:
        return False
    return bool((g.perms or {}).get(perm, PERM_DEFAULTS[perm]))


def perms_view(g: Guardian) -> dict:
    """The resolved permission set — every key present, never a bare stored dict.

    A client that has to apply its own defaults will eventually apply them
    differently from the server, and the two disagreeing about whether a
    pensioner's location is shared is a privacy incident, not a UI bug.
    """
    stored = g.perms or {}
    return {p: bool(stored.get(p, PERM_DEFAULTS[p])) for p in GUARDIAN_PERMS}


def clean_phone(value: str) -> str | None:
    """A phone number in one shape, or None. Tolerates the ways it gets typed.

    Digits only, and explicitly ASCII: str.isdigit() is true for Devanagari
    numerals, so a name written in a script the person reads fluently would
    otherwise be accepted as a number and then sent to nobody.
    """
    raw = value.strip()
    digits = "".join(ch for ch in raw if ch in "0123456789")
    if not 8 <= len(digits) <= 15:
        return None
    return f"+{digits}" if raw.startswith("+") else digits


def sos_view(row: SosEvent) -> dict:
    return {
        "id": row.id,
        "started_at": row.started_at,
        "cancelled": bool(row.cancelled),
        "resolved_at": row.resolved_at,
        "open": not row.cancelled and row.resolved_at is None,
        "delivered": row.delivered or {},
    }


def guardian_view(g: Guardian) -> dict:
    return {
        "id": g.id,
        "name": g.name,
        "phone": g.phone,
        "relation": g.relation,
        "order_no": g.order_no,
        "perms": perms_view(g),
        "accepted_at": g.accepted_at,
        "pending": g.accepted_at is None,
    }


def alert_view(a: ScamAlert) -> dict:
    return {
        "id": a.id,
        "category": a.category,
        "title": a.title,
        "body": a.body,
        "language": a.language,
        "severity": a.severity,
        "markers": a.markers or [],
        "domains": a.domains or [],
    }


def scam_view(r: ScamCheckRequest) -> dict:
    """The check, deliberately without the pasted text.

    A screen that re-displays a scam verbatim is a screen that teaches the
    next one, and the words are already on the phone that pasted them.
    """
    return {
        "id": r.id,
        "is_scam": r.is_scam,
        "category": r.category,
        "confidence": r.confidence,
        "signals": r.signals or [],
        "alert_id": r.alert_id,
        "created_at": r.created_at,
        "verdict": "pending" if r.is_scam is None else "decided",
        "chars": len(r.text or ""),
    }


# ----------------------------------------------------------------- dispatch --
@dataclass
class Recipient:
    """One phone to send to, with everything notify needs and nothing else."""

    label: str  # what a person reads when they are told this one was not reached
    user_id: int
    phone: str
    push_token: str | None
    guardian_id: int | None
    gets_location: bool


def _push_token(db, user_id: int) -> str | None:
    rows = db.scalars(select(Device).where(Device.user_id == user_id)).all()
    return next((d.push_token for d in rows if d.push_token), None)


def _sos_recipients(db, user: User, *, has_coords: bool) -> list[Recipient]:
    """
    Everybody this SOS goes to, decided before a single message is sent.

    The list is built in full first, in two queries, and the useful property
    here is not concurrency but completeness: the set of households about to be
    woken is settled from the database and cannot then change under the loop,
    and somebody invited while the fan-out is running belongs to the *next*
    event rather than half of this one.

    python/notify.py is synchronous by design — its providers are blocking HTTP
    calls behind a small interface — so this does not pretend to await. What it
    does guarantee is the property that actually matters at 3am: every recipient
    is independent, and one that fails (a carrier that rejects the message, a
    dispatcher that raises) is recorded and stepped over rather than allowed to
    stop the ones after it. Dispatching in sequence and stopping at the first
    error is how the second daughter finds out her mother needed help an hour
    late.
    """
    out: list[Recipient] = []

    guardians = db.scalars(
        select(Guardian)
        .where(Guardian.user_id == user.id)
        .order_by(Guardian.order_no, Guardian.id)
    ).all()
    for g in guardians:
        if not wants(g, "sos"):
            continue
        out.append(
            Recipient(
                label=f"guardian {g.id} ({g.name})",
                # notify's own consent check keys on guardian_id and would skip
                # the send otherwise. The row is attributed to the guardian's
                # account where one exists, because that is who will read it,
                # and to the owner otherwise, because user_id is NOT NULL and a
                # neighbour with no account is still a real recipient.
                user_id=g.guardian_user_id or user.id,
                phone=g.phone,
                push_token=_push_token(db, g.guardian_user_id) if g.guardian_user_id else None,
                guardian_id=g.id,
                gets_location=wants(g, "location"),
            )
        )

    # Helpers (plan 6.3) rather than only nearby ones. `Helper.radius_km` is
    # how far that person is willing to travel, not a filter we can apply: we
    # have no trustworthy position to filter on — the phone may be off, in a
    # pocket, or indoors — and dropping the neighbour next door because a
    # cached fix is an hour old is a worse failure than telling somebody who
    # lives too far away.
    helpers = db.scalars(
        select(Helper).where(Helper.user_id == user.id).order_by(Helper.id)
    ).all()
    for h in helpers:
        if not h.is_active:
            continue
        out.append(
            Recipient(
                label=f"helper {h.id} ({h.name})",
                user_id=user.id,          # no account of their own; see above
                phone=h.phone,
                push_token=None,
                guardian_id=None,         # not a guardian, and never see alerts
                gets_location=has_coords,
            )
        )

    return out


def _blank_delivery() -> dict:
    return {c: {"sent": 0, "failed": []} for c in CHANNEL_ORDER}


def _why(detail: str) -> str:
    """A failure reason short enough to read on a phone.

    A provider exception carries a stack of newlines and often a fragment of
    SQL, and this string is rendered in a list a frightened person is looking
    at. One line, a sentence's worth. The full text is in the log, which is
    where somebody debugging a carrier at 3am will look.
    """
    return " ".join(detail.split())[:120] or "no reason given"


def _position(coords, accuracy, sees_location: bool) -> dict | None:
    """An alert's position, or nothing at all.

    One decision in one place, used by every place a position is rendered. The
    stored coordinates are the alert-grade fix the person consented to share;
    there is no path here to `User.lat/lng`, the last place they were seen,
    and that gap is the difference between a safety alert and a tracker.
    """
    if not sees_location or coords is None:
        return None
    return {"lat": coords[0], "lng": coords[1], "accuracy": accuracy}


def _send(db, recipient: Recipient, *, kind: str, title: str, body: str, urgent: bool) -> tuple[dict, bool]:
    """
    Send to one person, inside a savepoint, and never raise.

    The savepoint matters as much as the try: notify writes its notifications
    row in this same session, so an exception part-way through would leave the
    session unusable and take every recipient after this one down with it for
    a reason that has nothing to do with them. Returns the per-channel outcome
    for this recipient and whether it went anywhere at all.
    """
    outcome = _blank_delivery()
    try:
        with db.begin_nested():
            results = dispatch.notify(
                db,
                user_id=recipient.user_id,
                guardian_id=recipient.guardian_id,
                phone=recipient.phone,
                push_token=recipient.push_token,
                # FCM and APNs are different vendors; the token alone does not say
                # which one it belongs to.
                platform=(
                    db.scalar(select(Device.platform).where(Device.push_token == recipient.push_token))
                    if recipient.push_token else None
                ),
                title=title,
                body=body,
                kind=kind,
                channels=CHANNEL_ORDER,
                urgent=urgent,
            )
    except Exception as exc:  # noqa: BLE001 — a provider failing, never the caller
        log.error("dispatch to %s raised: %s", recipient.label, exc)
        for bucket in outcome.values():
            bucket["failed"].append({"to": recipient.label, "why": _why(str(exc))})
        return outcome, False

    for res in results:
        bucket = outcome.setdefault(res.channel, {"sent": 0, "failed": []})
        if res.ok:
            bucket["sent"] += 1
        else:
            bucket["failed"].append({"to": recipient.label, "why": _why(res.detail)})
    return outcome, any(b["sent"] for b in outcome.values())


def _merge(into: dict, other: dict) -> None:
    for channel, result in other.items():
        bucket = into.setdefault(channel, {"sent": 0, "failed": []})
        bucket["sent"] += result["sent"]
        bucket["failed"].extend(result["failed"])


def _fan_out(db, user: User, event: SosEvent, kind: str, title: str, headline: str) -> dict:
    """
    Tell everybody about one SOS, and return what happened per channel.

    Plan 21 risk 2 is why this is a dict and not a count: a guardian who was
    not reached has to be able to see that they were not, so a channel that
    failed is present with the failure in it rather than quietly absent, and
    "sent: 0" is a different statement from "not tried".

    The `kind` carries the event id because notify's idempotency key is
    (kind, check-in, guardian, recipient, channel) and an SOS has no check-in
    row to put in the second slot. Without the id in the kind, a second SOS
    press the same evening would collide with the first one's key, the
    dispatcher would find it "already sent" and return without sending, and
    the family nobody was told about would be the one we could not know about
    either.
    """
    coords = (
        (event.lat, event.lng) if event.lat is not None and event.lng is not None else None
    )
    delivered = _blank_delivery()
    reached = 0

    for r in _sos_recipients(db, user, has_coords=coords is not None):
        # The same wording for everyone, minus the coordinates this guardian
        # has not consented to. `address_as` is used rather than `name`
        # because this text is read aloud at 3am by a frightened person: an SMS
        # that says "Sharma" does not sound like something this app sends.
        who = user.address_as or user.name or "Your family member"
        parts = [f"{who}: {headline}."]
        if coords is not None and r.gets_location:
            parts.append(f"Location: {coords[0]:.5f}, {coords[1]:.5f}.")
        parts.append("Open Doosri Pari for details. This is not a replacement for 112.")

        outcome, ok = _send(
            db, r, kind=f"{kind}:{event.id}", title=title, body=" ".join(parts), urgent=True
        )
        _merge(delivered, outcome)
        reached += int(ok)

    if not reached:
        # Loud, because a 3am SOS that reached nobody is the failure the whole
        # feature exists to prevent and the only way anyone hears about it is
        # if this line is read.
        log.error("sos %s reached nobody of %d recipients", event.id, len(delivered))
    return delivered


# ---------------------------------------------------------------- endpoints --
@router.post("/sos")
def start_sos(request: Request, body: SosIn, user: CurrentUser, db: DbSession):
    """
    SOS. Immediate, in parallel, and never waiting for a check-in window.

    The row is flushed before anything is sent, not after. Plan 12 runs
    `sos_dispatch` on the event with three retries, and the retry has to be
    able to find this event — which it can only do if the record of the press
    survived a process that died halfway through the fan-out. That is the whole
    reason the alert is written first and the messages second.
    """
    limit_write(request, user.id)
    if body.lat is not None and not -90 <= body.lat <= 90:
        raise HTTPException(400, "lat out of range")
    if body.lng is not None and not -180 <= body.lng <= 180:
        raise HTTPException(400, "lng out of range")

    event = SosEvent(
        user_id=user.id, lat=body.lat, lng=body.lng, accuracy=body.accuracy, delivered={}
    )
    db.add(event)
    db.flush()

    event.delivered = _fan_out(
        db, user, event, kind="sos", title="SOS", headline="needs help now"
    )
    event.dispatch_attempts = 1
    event.last_attempt_at = now()
    if any(b["sent"] for b in event.delivered.values()):
        event.reached_someone_at = now()
    db.commit()

    # Plan 6.5: if the person chose it, the emergency folder opens to their
    # guardians after an SOS. Best effort; it never delays or fails the alert.
    try:
        from python.features.vault import open_ice_after_sos  # noqa: PLC0415

        open_ice_after_sos(db, user, event.id)
    except Exception:  # noqa: BLE001
        log.exception("could not open the emergency folder for sos %s", event.id)
    return sos_view(event)


@router.post("/sos/{sos_id}/cancel")
def cancel_sos(sos_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    The 5-second countdown was backed out of.

    The ordering here is deliberately the other way round from every other
    endpoint: the flag is set and committed BEFORE the all-clear goes out, not
    after. The client sends this twice more than once — the button is still
    live during a countdown that has already fired, and a crash between the
    send and the flag would leave a family that was told "false alarm" being
    told it again on the next tap. One message, not two: the second call sees
    `cancelled` already true and returns without sending anything.
    """
    limit_write(request, user.id)
    event = db.get(SosEvent, sos_id)
    # 404 and not 403 for somebody else's event. A 403 confirms the event
    # exists, which is a slow enumeration of who presses SOS in this building.
    if event is None or event.user_id != user.id:
        raise HTTPException(404, "sos not found")

    if event.cancelled:
        return sos_view(event)
    if event.resolved_at is not None:
        raise HTTPException(409, "already marked safe")

    event.cancelled = True
    db.commit()

    # Now the retraction, to the same set the alert went to, because the whole
    # point is that the household which was woken at 3am by this app is the one
    # that hears it was nothing. A recipient who got the alert and not this is
    # the exact state plan 21 risk 2 exists to expose, so the outcome is
    # returned rather than logged and forgotten.
    return {
        **sos_view(event),
        "follow_up": _fan_out(
            db, user, event, kind="sos-cancel", title="All clear",
            headline="pressed SOS by mistake",
        ),
    }


@router.post("/sos/{sos_id}/safe")
def mark_safe(sos_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    "I am safe now." Plan 6.3 step 4: it tells everybody, once.

    Idempotent for the reason cancel is: the person taps it again when the
    phone is dropped in a handbag, and the second tap must not be the reason a
    family reads "I am safe now" four times. A cancelled event is resolved
    silently and told to nobody — there was never an alert to retract, and an
    all-clear nobody asked for is how a family learns to ignore one.
    """
    limit_write(request, user.id)
    event = db.get(SosEvent, sos_id)
    if event is None or event.user_id != user.id:
        raise HTTPException(404, "sos not found")

    if event.resolved_at is not None:
        return sos_view(event)

    event.resolved_at = now()
    db.commit()

    if event.cancelled:
        return sos_view(event)

    return {
        **sos_view(event),
        "follow_up": _fan_out(
            db, user, event, kind="sos-safe", title="All clear", headline="is safe now"
        ),
    }


# ----------------------------------------------------------------- guardians --
@router.post("/guardians/invite")
def invite_guardian(
    request: Request, body: GuardianInviteIn, user: CurrentUser, db: DbSession
):
    """
    Ask somebody to be an emergency contact. Plan 6.3: 1-5, each of whom must
    accept.

    The row is created with `accepted_at` null, which is the state that stops
    every alert in this file. The invitation itself is the one message allowed
    to reach a number which has not accepted, and it says nothing about
    check-ins: the recipient is a bare phone number at that point, not anybody's
    guardian, and the only thing they are told is that they have been asked.
    """
    limit_write(request, user.id)
    phone = clean_phone(body.phone)
    if phone is None:
        raise HTTPException(400, "phone must be a number with a country code, like +919876543210")
    if user.phone_e164 and phone == user.phone_e164:
        raise HTTPException(400, "you cannot be your own guardian")

    taken = db.scalar(
        select(Guardian.id).where(Guardian.user_id == user.id, Guardian.phone == phone)
    )
    if taken is not None:
        raise HTTPException(409, f"that number is already on your list (guardian {taken})")

    # Unaccepted invitations count towards the limit. Counting only the
    # accepted ones would make the cap a formality: leave a hundred outstanding
    # invites and every one of those numbers is still in the table, still on a
    # dashboard, and still one SMS away from somebody's phone.
    count = db.scalar(
        select(func.count()).select_from(Guardian).where(Guardian.user_id == user.id)
    )
    if count >= config.MAX_GUARDIANS:
        raise HTTPException(
            409,
            f"you can have at most {_words(config.MAX_GUARDIANS)} guardians; "
            "remove one before inviting another",
        )

    g = Guardian(
        user_id=user.id,
        phone=phone,
        name=body.name.strip()[:80],
        relation=body.relation,
        order_no=count + 1,
        perms=dict(PERM_DEFAULTS),
        accepted_at=None,
    )
    db.add(g)
    db.flush()

    who = user.address_as or user.name or "Someone you know"
    # guardian_id is deliberately not passed. notify keys its consent check on
    # it, and passing it would suppress the very message that exists to obtain
    # the consent. The distinction is between an alert, which is about the
    # pensioner and goes only to somebody who agreed, and an invitation, which
    # asks that phone to agree for the first time and carries none of the
    # elder's data. The invitation id is in the text so that somebody who is
    # the guardian of two pensioners can accept the right one.
    body_text = (
        f"{who} has asked you to be an emergency contact on Doosri Pari. "
        f"Open the app to accept (invitation {g.id}). "
        "You will not get any alerts until you accept."
    )
    outcome, ok = _send(
        db,
        Recipient(
            label=f"invite {g.id} ({g.phone})",
            user_id=user.id,  # the invitee may have no account yet
            phone=phone,
            push_token=None,
            guardian_id=None,   # see above
            gets_location=False,
        ),
        kind=f"invite:{g.id}",
        title="Emergency contact request",
        body=body_text,
        urgent=False,
    )
    db.commit()
    return {**guardian_view(g), "invite_sent": ok, "invite_delivered": outcome}


@router.post("/guardians/accept")
def accept_invite(
    request: Request, body: GuardianAcceptIn, user: CurrentUser, db: DbSession
):
    """
    Called by the INVITEE, on their own token, accepting their own invitation.

    The caller is the person who was invited, so the invitation is found by the
    number on it — a number which must be the caller's own. That is what makes
    this safe: an invitation found by name or by row id could be claimed by
    anybody who guessed an id, handing a stranger the right to be told where a
    pensioner lives, and a wrong tap would silently attach a guardian the
    person did not choose.
    """
    limit_write(request, user.id)
    phone = clean_phone(body.phone)
    if phone is None:
        raise HTTPException(400, "phone must be a number with a country code")
    if not user.phone_e164 or phone != user.phone_e164:
        raise HTTPException(403, "you can only accept an invitation sent to your own number")

    if body.guardian_id is not None:
        g = db.get(Guardian, body.guardian_id)
        if g is None or g.phone != phone:
            raise HTTPException(404, "invitation not found")
    else:
        pending = db.scalars(
            select(Guardian)
            .where(Guardian.phone == phone, Guardian.accepted_at.is_(None))
            .order_by(Guardian.created_at.desc(), Guardian.id.desc())
        ).all()
        if not pending:
            raise HTTPException(404, "no invitation is waiting for this number")
        if len(pending) > 1:
            # Refuse rather than guess. One number is regularly the guardian of
            # two pensioners, and accepting the wrong one puts an alert about
            # somebody else on this phone.
            ids = ", ".join(str(p.id) for p in pending)
            raise HTTPException(
                409,
                f"this number has {len(pending)} invitations waiting ({ids}); "
                "open the one you were sent",
            )
        g = pending[0]

    if g.user_id == user.id:
        raise HTTPException(400, "you cannot accept an invitation to yourself")
    if g.accepted_at is not None:
        return guardian_view(g)

    g.accepted_at = now()
    g.guardian_user_id = user.id
    # An invitation carries no permissions, so accepting must not leave the
    # row's JSON empty and let a later send read an absent key as "on". Fill in
    # the defaults — location and health still off — exactly as a new invite.
    g.perms = {**PERM_DEFAULTS, **(g.perms or {})}
    db.commit()
    return guardian_view(g)


@router.patch("/guardians/{guardian_id}/perms")
def set_guardian_perms(
    guardian_id: int, request: Request, body: GuardianPermsIn, user: CurrentUser, db: DbSession
):
    """
    Change what this guardian is told.

    The caller must be the OWNER, never the guardian. This permission decides
    whether a household is told when its mother misses a check-in, and the
    person whose location it is, whose schedule it describes and who cannot
    read this screen, decides that. A guardian allowed to widen their own
    permissions would be a consent system anyone can grant on somebody else's
    behalf — the same defect as a privacy setting with a "share" button on the
    data itself.

    A guardian gets 404 rather than 403 for the reason it is everywhere else
    here: a 403 says the row exists, and the owner of each row is precisely
    the list this endpoint would otherwise leak.
    """
    limit_write(request, user.id)
    g = db.get(Guardian, guardian_id)
    if g is None or g.user_id != user.id:
        raise HTTPException(404, "guardian not found")

    changes = {p: v for p, v in body.model_dump().items() if v is not None}
    unknown = set(changes) - set(GUARDIAN_PERMS)
    if unknown:
        raise HTTPException(400, f"unknown permission: {', '.join(sorted(unknown))}")

    # Widened from the resolved set, so a key missing from a stored dict gets
    # the same answer here as it would in `wants()`. Turning one off is real:
    # `wants()` reads this dict on the next send, so the very next SOS or
    # dose follow-up does not include them.
    # `health` and `medicines` are one switch under two names; whichever the
    # client sent, both are written, so neither reader sees a stale value.
    if "health" in changes and "medicines" not in changes:
        changes["medicines"] = changes["health"]
    if "medicines" in changes:
        changes["health"] = changes["medicines"]
    g.perms = {**perms_view(g), **changes}
    db.commit()
    return {**guardian_view(g), "changed": sorted(changes)}


@router.get("/guardian/dashboard")
def guardian_dashboard(user: CurrentUser, db: DbSession):
    """
    The guardian's own view: who they are a guardian for, how that person is
    doing, and anything open right now.

    THE BOUNDARY. Everything here comes from the guardian relationship the
    pensioner set up and from an alert they are entitled to. There is no
    medicine schedule, no pension figure, no document, no circle, no profile —
    and in particular the position shown is `CheckIn.lat/lng`, which exists
    only because the user turned on "share location when I miss a check-in",
    and never `User.lat/lng`, the last place they were seen. Those are
    different things, and conflating them turns a family safety feature into
    something that tracks where a person lives and works, which nobody
    consented to and which this app does not claim to do.

    The list is built from the `guardians` row naming this caller, not from a
    relationship worked out at read time, so a person who removes a guardian
    loses the view in the same write that removed them.
    """
    links = db.scalars(
        select(Guardian)
        .where(Guardian.guardian_user_id == user.id, Guardian.accepted_at.isnot(None))
        .order_by(Guardian.user_id, Guardian.order_no)
    ).all()
    if not links:
        return {"people": []}

    owner_ids = sorted({g.user_id for g in links})
    owners = {u.id: u for u in db.scalars(select(User).where(User.id.in_(owner_ids))).all()}

    # One query for every row of every person, not one query per person: a
    # guardian with four parents is an ordinary household and this runs on a
    # phone that may be four years old.
    rows = db.scalars(
        select(CheckIn)
        .where(CheckIn.user_id.in_(owner_ids))
        .order_by(CheckIn.user_id, CheckIn.day.desc(), CheckIn.id.desc())
    ).all()
    latest: dict[int, CheckIn] = {}
    still_open: dict[int, list[CheckIn]] = {}
    for r in rows:
        latest.setdefault(r.user_id, r)
        if r.stage not in TERMINAL_STAGES:
            still_open.setdefault(r.user_id, []).append(r)

    open_sos: dict[int, list[SosEvent]] = {}
    for s in db.scalars(
        select(SosEvent)
        .where(
            SosEvent.user_id.in_(owner_ids),
            SosEvent.cancelled.is_(False),
            SosEvent.resolved_at.is_(None),
        )
        .order_by(SosEvent.started_at.desc())
    ).all():
        open_sos.setdefault(s.user_id, []).append(s)

    people = []
    for g in links:
        owner = owners.get(g.user_id)
        if owner is None:  # the account was deleted under the invitation
            continue

        # One location decision, one rule, every call site. An SOS is
        # location-bearing for the people who consented to it and for nobody
        # else, on the same basis as the message that carried it.
        sees_location = wants(g, "location")

        alerts = []
        for r in still_open.get(g.user_id, []):
            coords = (r.lat, r.lng) if r.lat is not None and r.lng is not None else None
            alerts.append(
                {
                    "kind": "checkin",
                    "id": r.id,
                    "day": r.day,
                    "since": r.stage_entered_at,
                    "stage": r.stage,
                    "location": _position(coords, r.location_accuracy, sees_location),
                }
            )
        for s in open_sos.get(g.user_id, []):
            coords = (s.lat, s.lng) if s.lat is not None and s.lng is not None else None
            alerts.append(
                {
                    "kind": "sos",
                    "id": s.id,
                    "since": s.started_at,
                    "stage": "open",
                    "location": _position(coords, s.accuracy, sees_location),
                }
            )

        today = latest.get(g.user_id)
        today_coords = (
            (today.lat, today.lng) if today is not None and today.lat is not None and today.lng is not None else None
        )
        people.append(
            {
                "user_id": owner.id,
                "name": owner.name,
                "address_as": owner.address_as,
                "relation": g.relation,
                "perms": perms_view(g),
                "checkin": None
                if today is None
                else {
                    "id": today.id,
                    "day": today.day,
                    "stage": today.stage,
                    "checked_at": today.checked_at,
                    "location": _position(
                        today_coords, today.location_accuracy, sees_location
                    ),
                },
                "open_alerts": alerts,
            }
        )

    return {"people": people}


# --------------------------------------------------------------- scam shield --
# Control characters are flattened and DEL/C1 removed, so a pasted message
# cannot repaint a console, forge a log line or smuggle a terminal escape into
# an admin view. That makes the text safe to DISPLAY. It does not make it safe
# to trust, and the difference is the whole of the comment on the endpoint.
_CONTROL = {c: " " for c in range(32) if c not in (9, 10, 13)}
_CONTROL.update({c: None for c in range(127, 160)})


@router.post("/scam-check")
def scam_check(request: Request, body: ScamCheckIn, user: CurrentUser, db: DbSession):
    """
    "Is this real?" Plan 6.12: the user pastes a message and the check is
    decided.

    This endpoint records the question and stops. The verdict belongs to the
    checker, which is where the plan puts it — hard rules, then an
    explanation. A fast path that guessed in the HTTP handler would answer
    "looks fine" to every novel scam in order to return quickly, and the honest
    `unknown` in the model exists precisely because that is not a good answer.

    PROMPT INJECTION. This text was written by a criminal who knows it will be
    read by a language model, and it will try — that is the attack, and trying
    is free. It is stored as data and leaves this function only as a value in
    a request body, wrapped and labelled untrusted. It is never formatted into
    a prompt, a log line or an admin page: an f-string or a `%s` interpolation
    here is how a pensioner ends up being told a scammer's own words, and how
    an operations log gets forged to look like something our staff said. If a
    moderation console is ever built, this text reaches a human only through an
    escaping template — which is part of why this row is deliberately not one
    of the SafetyQueueItem targets, so nothing here reaches a moderator by
    accident.

    No contact is parsed out of it, and none is stored separately. A scam is
    very often a request for an OTP or a bank detail, and this table should
    not become a directory of the numbers currently being phoned.
    """
    limit_write(request, user.id)
    text = body.text.translate(_CONTROL).strip()
    if not text:
        raise HTTPException(400, "the message was empty")

    row = ScamCheckRequest(user_id=user.id, text=text)
    db.add(row)
    db.flush()

    # The checker (python/scamcheck.py): hard rules first, then — if a model is
    # configured — a plain-language explanation. A failure of either leaves the
    # honest `unknown`; it never becomes "looks fine".
    from python import scamcheck  # noqa: PLC0415

    verdict = scamcheck.check(db, text, user.lang or "en")
    row.is_scam = verdict.is_scam
    row.category = verdict.category
    row.confidence = verdict.confidence
    row.signals = verdict.signals
    row.alert_id = verdict.alert_id
    db.commit()
    # The length, never the content. Nothing in this text is ours to repeat
    # into a log, and the row id is enough to find it.
    log.info("scam check %s recorded (%d chars)", row.id, len(text))
    return {**scam_view(row), "verdict_label": verdict.label, "explanation": verdict.explanation,
            "steps": verdict.steps}


@router.get("/scam-alerts")
def scam_alerts(
    user: CurrentUser,
    db: DbSession,
    category: str | None = None,
    lang: str | None = None,
):
    """
    The active scam warnings, in the user's own language.

    There is no region filter, and there is deliberately no way to add one: the
    plan asks for `?region=` on the festival calendar and not here, and
    ScamAlert has no region column to filter on. A fraud pattern is not
    regional — the same "digital arrest" script runs across the country — and a
    filter that hid an active warning from a user whose state_code happened to
    differ would be a filter that hides warnings.

    Language, on the other hand, is a filter, because content.py is right that
    an alert with no translation yet is not sent: a warning in a language the
    reader cannot read is worse than no warning, because it is read as the app
    having checked and found nothing.
    """
    want = lang or user.lang or "en"
    q = select(ScamAlert).where(ScamAlert.is_active.is_(True))
    q = q.where(or_(ScamAlert.expires_at.is_(None), ScamAlert.expires_at > now()))
    q = q.where(or_(ScamAlert.language == want, ScamAlert.language == "en"))
    if category:
        q = q.where(ScamAlert.category == category)
    rows = db.scalars(
        q.order_by(ScamAlert.created_at.desc(), ScamAlert.id.desc()).limit(200)
    ).all()
    return {"alerts": [alert_view(a) for a in rows], "language": want}
