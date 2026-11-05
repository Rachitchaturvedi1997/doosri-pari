# =============================================================================
# Escalation dispatch — what actually happens when the state machine moves.
#
# python/escalation.py decides *that* a row should move from Reminded to Called;
# this module decides *who is told, how, and in what words*. The split matters:
# the machine is mirrored in Kotlin and pinned by parity tests, whereas who gets
# a WhatsApp message is server policy that changes with vendors and consent.
#
# The contract with the machine is `Escalator.dispatch(action, checkin, **kw)`.
# `make_dispatcher(db, user)` returns exactly that callable.
#
# Ground rules, all from plan section 6.3 / 14:
#   * A guardian is told only if they accepted AND their "alerts" permission is
#     on. Withdrawing a permission changes what is sent today.
#   * Location goes only to a guardian whose "location" permission is on AND only
#     if the pensioner shared it for this check-in.
#   * Nothing here raises. A vendor being down is recorded, not thrown: the
#     caller is the safety net.
#   * Every message says "this is not a replacement for 112" where it asks a
#     person to act on a possible emergency.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime
from typing import Callable
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from python import messages, notify
from python.escalation import (
    ACTION_ALERT_GUARDIANS,
    ACTION_ALERT_HELPERS,
    ACTION_IVR,
    ACTION_NONE,
    ACTION_NOTIFY_ALL_FINE,
    ACTION_REMIND,
    IVR_QUIET_HOURS,
)
from python.models import CheckIn, Device, Guardian, Helper, User, now

log = logging.getLogger("doosri.dispatch")

PERM_DEFAULTS = {
    "alerts": True, "sos": True, "location": False,
    "medicines": False, "document_safe": False, "health": False,
}


# ------------------------------------------------------------------ targets --
def push_target(db: Session, user_id: int | None) -> tuple[str | None, str | None]:
    """The most recently seen device's (token, platform), or (None, None)."""
    if user_id is None:
        return None, None
    row = db.scalar(
        select(Device)
        .where(Device.user_id == user_id, Device.push_token.isnot(None))
        .order_by(Device.last_seen_at.desc().nullslast(), Device.id.desc())
        .limit(1)
    )
    return (row.push_token, row.platform) if row else (None, None)


def guardian_wants(g: Guardian, perm: str) -> bool:
    """Same gate as python/features/safety.py `wants`, stated here so this
    module has no import from a route module."""
    if g.accepted_at is None:
        return False
    return bool((g.perms or {}).get(perm, PERM_DEFAULTS[perm]))


def accepted_guardians(db: Session, user_id: int, perm: str = "alerts") -> list[Guardian]:
    rows = db.scalars(
        select(Guardian)
        .where(Guardian.user_id == user_id, Guardian.accepted_at.isnot(None))
        .order_by(Guardian.order_no, Guardian.id)
    ).all()
    return [g for g in rows if guardian_wants(g, perm)]


def _lang_for(db: Session, g: Guardian, owner: User) -> str:
    if g.guardian_user_id:
        gu = db.get(User, g.guardian_user_id)
        if gu is not None and gu.lang:
            return gu.lang
    return owner.lang or "en"


def _local_hour(user: User, at: float) -> int:
    return datetime.fromtimestamp(at, ZoneInfo(user.time_zone or "UTC")).hour


def _fmt_last(db: Session, user: User) -> str:
    """The last successful check-in, in the pensioner's own words for a date."""
    last = db.scalar(
        select(CheckIn)
        .where(CheckIn.user_id == user.id, CheckIn.checked_at.isnot(None))
        .order_by(CheckIn.checked_at.desc())
        .limit(1)
    )
    if last is None or last.checked_at is None:
        return "—"
    tz = ZoneInfo(user.time_zone or "UTC")
    return datetime.fromtimestamp(last.checked_at, tz).strftime("%d %b, %I:%M %p")


# ------------------------------------------------------------- the dispatcher --
class EscalationDispatcher:
    """Callable with the signature `Escalator` expects."""

    def __init__(self, db: Session, user: User, notifier: "notify.MultiChannel | None" = None):
        self.db = db
        self.user = user
        self.n = notifier or notify.dispatch

    def __call__(self, action: str, row: CheckIn, **kw) -> dict:
        try:
            handler = {
                ACTION_REMIND: self.remind,
                ACTION_IVR: self.call,
                ACTION_ALERT_GUARDIANS: self.alert_guardians,
                ACTION_ALERT_HELPERS: self.alert_helpers,
                ACTION_NOTIFY_ALL_FINE: self.all_fine,
            }.get(action)
            if handler is None or action == ACTION_NONE:
                return {}
            return handler(row, **kw)
        except Exception:
            # The safety net does not fall over because a template did.
            log.exception("dispatch of %s for check-in %s failed", action, getattr(row, "id", "?"))
            return {"error": True}

    # ------------------------------------------------------ to the pensioner --
    def remind(self, row: CheckIn, **kw) -> dict:
        token, platform = push_target(self.db, self.user.id)
        lang = self.user.lang
        results = self.n.notify(
            self.db,
            user_id=self.user.id,
            kind="remind",
            checkin_id=row.id,
            title=messages.t("remind_title", lang),
            body=messages.t("remind_body", lang, who=messages.who(self.user)),
            phone=self.user.phone_e164,
            push_token=token,
            platform=platform,
            channels=("push", "sms"),
            urgent=True,
            stop_on_success=True,
            payload={"checkin_id": row.id, "open": "checkin"},
        )
        return {"reached": sum(1 for r in results if r.ok)}

    def call(self, row: CheckIn, **kw) -> dict:
        """The automated call. Held overnight unless someone has been told."""
        from python.integrations import ivr  # late: keeps this module vendor-free

        start, end = IVR_QUIET_HOURS
        hour = _local_hour(self.user, now())
        quiet = hour >= start or hour < end
        if quiet and row.guardians_alerted_at is None:
            self._note(row, "ivr_held_quiet_hours")
            return {"held": True}

        token = ivr.make_token(row.id, self.user.id)
        results = self.n.notify(
            self.db,
            user_id=self.user.id,
            kind=f"ivr{row.call_attempts}",
            checkin_id=row.id,
            title="Doosri Pari check-in call",
            body=ivr.prompt_for(self.user.lang),
            phone=self.user.phone_e164,
            channels=("ivr_call",),
            urgent=True,
            payload={"ivr_token": token, "lang": self.user.lang},
        )
        return {"reached": sum(1 for r in results if r.ok)}

    # ------------------------------------------------------------ to family --
    def alert_guardians(self, row: CheckIn, **kw) -> dict:
        guardians = accepted_guardians(self.db, self.user.id, "alerts")
        who = messages.who(self.user)
        last = _fmt_last(self.db, self.user)
        reached = 0
        for g in guardians:
            lang = _lang_for(self.db, g, self.user)
            body = messages.t("missed_body", lang, who=who, last=last)
            if guardian_wants(g, "location") and row.lat is not None and row.lng is not None:
                body += f" Location: {row.lat:.5f}, {row.lng:.5f}."
            if _phone_may_be_off(self.db, self.user.id):
                body += " " + _PHONE_OFF.get(lang, _PHONE_OFF["en"])
            body += " " + _not_112(lang)
            token, platform = push_target(self.db, g.guardian_user_id)
            results = self.n.notify(
                self.db,
                user_id=g.guardian_user_id or self.user.id,
                guardian_id=g.id,
                kind="alert",
                checkin_id=row.id,
                title=messages.t("missed_title", lang, who=who),
                body=body,
                phone=g.phone,
                push_token=token,
                platform=platform,
                urgent=True,
                payload={"checkin_id": row.id, "open": "guardian_ack", "user_id": self.user.id},
            )
            reached += int(any(r.ok for r in results))
        row.guardians_alerted_at = now()
        self._note(row, f"guardians_alerted:{reached}/{len(guardians)}")
        if guardians and not reached:
            log.error("check-in %s: no guardian could be reached on any channel", row.id)
        return {"guardians": len(guardians), "reached": reached}

    def alert_helpers(self, row: CheckIn, **kw) -> dict:
        helpers = list(
            self.db.scalars(
                select(Helper).where(Helper.user_id == self.user.id, Helper.accepted_at.isnot(None))
            ).all()
        )
        who = messages.who(self.user)
        lang = self.user.lang
        reached = 0
        for h in helpers:
            results = self.n.notify(
                self.db,
                user_id=self.user.id,
                kind=f"alert_helper{h.id}",
                checkin_id=row.id,
                title=messages.t("helpers_title", lang, who=who),
                body=messages.t("helpers_body", lang, who=who) + " " + _not_112(lang),
                phone=h.phone,
                channels=("whatsapp", "sms"),
                urgent=True,
                stop_on_success=True,
            )
            reached += int(any(r.ok for r in results))
        # Guardians hear that neighbours were asked, so they are not left
        # wondering whether anyone has gone round.
        for g in accepted_guardians(self.db, self.user.id, "alerts"):
            glang = _lang_for(self.db, g, self.user)
            token, platform = push_target(self.db, g.guardian_user_id)
            self.n.notify(
                self.db,
                user_id=g.guardian_user_id or self.user.id,
                guardian_id=g.id,
                kind="alert_helpers_told",
                checkin_id=row.id,
                title=messages.t("helpers_title", glang, who=who),
                body=f"{len(helpers)} nearby helper(s) have been asked to check on {who}.",
                phone=g.phone,
                push_token=token,
                platform=platform,
                urgent=True,
                stop_on_success=True,
            )
        row.helpers_alerted_at = now()
        self._note(row, f"helpers_alerted:{reached}/{len(helpers)}")
        return {"helpers": len(helpers), "reached": reached}

    def all_fine(self, row: CheckIn, **kw) -> dict:
        """
        "All fine", sent at once (plan 6.3). Only to guardians who were told
        something was wrong: a family that was never alarmed does not need to be
        told there was nothing to be alarmed about.
        """
        if row.guardians_alerted_at is None and row.helpers_alerted_at is None:
            return {"skipped": True}
        who = messages.who(self.user)
        told = 0
        for g in accepted_guardians(self.db, self.user.id, "alerts"):
            lang = _lang_for(self.db, g, self.user)
            token, platform = push_target(self.db, g.guardian_user_id)
            results = self.n.notify(
                self.db,
                user_id=g.guardian_user_id or self.user.id,
                guardian_id=g.id,
                kind="all_clear",
                checkin_id=row.id,
                title=messages.t("all_fine_title", lang, who=who),
                body=messages.t("all_fine_body", lang, who=who),
                phone=g.phone,
                push_token=token,
                platform=platform,
                urgent=True,
                stop_on_success=True,
            )
            told += int(any(r.ok for r in results))
        return {"told": told}

    # ---------------------------------------------------------------- audit --
    def _note(self, row: CheckIn, note: str) -> None:
        events = list(row.events or [])
        events.append({"at": now(), "stage": row.stage, "action": "dispatch", "note": note})
        row.events = events


_PHONE_OFF = {
    "en": "Their phone may be switched off.",
    "hi": "हो सकता है उनका फ़ोन बंद हो।",
}


def _phone_may_be_off(db: Session, user_id: int) -> bool:
    """Plan 6.3: 'phone may be off' when the last heartbeat is over 6 hours old."""
    from python.models import DeviceHeartbeat  # noqa: PLC0415

    hb = db.get(DeviceHeartbeat, user_id)
    return hb is not None and now() - hb.at > 6 * 3600


def _not_112(lang: str) -> str:
    return {
        "hi": "यह 112 का विकल्प नहीं है।",
    }.get(lang, "This is not a replacement for 112.")


def make_dispatcher(db: Session, user: User) -> Callable[..., dict]:
    """The callable to hand to `Escalator(db, user, dispatch=...)`."""
    return EscalationDispatcher(db, user)


# -------------------------------------------------- for jobs and other features --
def channel_enabled(db: Session, user_id: int, kind: str) -> bool:
    """
    The person's own switch for this kind of message (plan 6.15). Only Safety
    cannot be turned off, and it is never asked here: safety kinds are urgent and
    the caller passes urgent=True.
    """
    from python.integrations.push import channel_id  # noqa: PLC0415
    from python.models import UserPrefs  # noqa: PLC0415

    ch = channel_id(kind)
    if ch == "safety":
        return True
    prefs = db.get(UserPrefs, user_id)
    return True if prefs is None else bool((prefs.channels or {}).get(ch, True))


def inbox(
    db: Session, user_id: int, *, kind: str, title: str, body: str | None = None,
    payload: dict | None = None,
) -> None:
    """The person's own record of what they were told (the bell screen)."""
    from python.models import PushInbox  # noqa: PLC0415

    db.add(PushInbox(user_id=user_id, kind=kind.split(":", 1)[0][:32], title=title[:160],
                     body=(body or "")[:400], payload=payload or {}))


def notify_user(
    db: Session,
    user: User,
    *,
    kind: str,
    title: str,
    body: str,
    urgent: bool = False,
    payload: dict | None = None,
    channels: tuple[str, ...] = ("push",),
    stop_on_success: bool = True,
    checkin_id: int | None = None,
    record: bool = True,
) -> bool:
    """
    One message to the pensioner themselves. True if any channel took it.

    Respects the person's channel switches, and writes the inbox row so the
    message can be read in the app even if the push never displayed.
    """
    if not urgent and not channel_enabled(db, user.id, kind):
        return False
    token, platform = push_target(db, user.id)
    results = notify.dispatch.notify(
        db,
        user_id=user.id,
        kind=kind,
        checkin_id=checkin_id,
        title=title,
        body=body,
        phone=user.phone_e164,
        push_token=token,
        platform=platform,
        channels=channels,
        urgent=urgent,
        payload=payload,
        stop_on_success=stop_on_success,
    )
    fresh = [r for r in results if r.ok and r.detail != "already sent"]
    if record and fresh:
        inbox(db, user.id, kind=kind, title=title, body=body, payload=payload)
        db.commit()
    return any(r.ok for r in results)


def notify_guardians(
    db: Session,
    user: User,
    *,
    kind: str,
    title_key: str,
    body_key: str,
    perm: str = "alerts",
    urgent: bool = False,
    **vars,
) -> int:
    """Tell every consenting guardian, in their own language. Returns how many were reached."""
    reached = 0
    who = messages.who(user)
    for g in accepted_guardians(db, user.id, perm):
        lang = _lang_for(db, g, user)
        token, platform = push_target(db, g.guardian_user_id)
        results = notify.dispatch.notify(
            db,
            user_id=g.guardian_user_id or user.id,
            guardian_id=g.id,
            kind=kind,
            title=messages.t(title_key, lang, who=who, **vars),
            body=messages.t(body_key, lang, who=who, **vars),
            phone=g.phone,
            push_token=token,
            platform=platform,
            urgent=urgent,
            stop_on_success=True,
        )
        reached += int(any(r.ok for r in results))
    return reached
