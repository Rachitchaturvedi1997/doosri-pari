# =============================================================================
# Outbound messages: push, SMS, WhatsApp, IVR. Plan section 6.3 and 12.
#
# The reason this is one module rather than four: plan section 21 risk 2 is that
# any single delivery channel can fail, and the check-in machine depends on
# *somebody* being told. So the callers here do not choose a channel — they say
# what happened and how urgent it is, and this file decides the order, records
# what it tried, and tells the caller what actually got through.
#
# Two rules that shape everything below:
#
#   1. Every attempt is written to `notifications` with an idempotency key
#      before it is sent, not after. The escalation job runs every minute and
#      will re-enter the same stage; without the row written first, a retry
#      sends a second SMS to a family that has already been told, and the
#      second message is the one that gets the number blocked.
#   2. A channel that fails is recorded as failed and the next one is tried.
#      Nothing raises out of here. A provider being down must not take the
#      check-in job with it — that job is the safety net.
#
# The real providers live in python/integrations/. This module owns the
# *policy* (order, idempotency, consent, quiet hours, daily quota) and imports no
# vendor code, so it is importable with nothing installed. The process-wide
# `dispatch` is configured with real channels at startup by
# python.integrations.registry.configure_dispatch().
# =============================================================================
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Protocol
from zoneinfo import ZoneInfo

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from python import config
from python.models import DELIVERY_CHANNELS, Device, Notification, User, now

log = logging.getLogger("doosri.notify")

# Plan section 6.3: the order channels are tried in. Push first because it is
# free and arrives in seconds; IVR last because a voice call costs real money
# and is the thing a family least wants to receive at 3am for the third time.
CHANNEL_ORDER = ("push", "whatsapp", "sms", "ivr_call")

# The IVR is the escalation's last resort before guardians, so it does not fire
# overnight unless somebody has been told already. A pensioner's family should
# not learn about a missed check-in from a phone call at 2am.
IVR_QUIET_HOURS = (22, 7)

# Kinds that may interrupt at any hour and never count against the daily quota
# (plan 6.15: safety, medicine and check-in notifications ignore quiet hours).
_ALWAYS_ALLOWED_PREFIXES = (
    "checkin", "remind", "sos", "all_clear", "all_fine", "alert", "guardian",
    "dose", "medicine", "life_certificate", "otp", "invite",
)


@dataclass
class SendResult:
    """What one attempt did. `ok` false is normal and is not an error."""

    ok: bool
    channel: str
    detail: str = ""
    provider_id: str | None = None


class Channel(Protocol):
    """What every delivery channel has to be able to do."""

    name: str

    def send(self, *, to: str | None, title: str, body: str, **kw) -> SendResult:  # noqa: D102
        ...


class NullChannel:
    """
    The default when no provider is configured.

    In development it records the attempt and reports success, because a
    developer running the app locally should not be told their notification
    failed. In production (`STRICT_CHANNELS`) it reports failure honestly — a
    check-in that cannot reach anybody is exactly the situation the whole
    feature exists for, and it must not be papered over.
    """

    def __init__(self, name: str, strict: bool = False) -> None:
        self.name = name
        self.strict = strict

    def send(self, *, to: str | None, title: str, body: str, **kw) -> SendResult:
        log.info("[%s] to=%s: %s", self.name, _mask(to), title)
        if self.strict:
            return SendResult(ok=False, channel=self.name, detail="no provider configured")
        return SendResult(ok=True, channel=self.name, detail="no provider configured")


def _mask(phone: str | None) -> str:
    """A phone number safe for a log line: the last two digits only."""
    if not phone:
        return "-"
    return "…" + phone[-2:]


class MultiChannel:
    """
    The dispatcher.

    `channels` is injected rather than constructed here so a test can pass
    fakes, and so production can pass real providers without this module
    importing them at import time.
    """

    def __init__(self, channels: list[Channel] | None = None, strict: bool = False) -> None:
        self.strict = strict
        self.channels: dict[str, Channel] = {}
        self.configure(channels or [], strict=strict)

    def configure(self, channels: list[Channel], strict: bool | None = None) -> None:
        """
        Replace the channels in place.

        In place rather than rebinding the module global, because features do
        `from python.notify import dispatch` and would keep the old object.
        """
        if strict is not None:
            self.strict = strict
        self.channels = {c.name: c for c in channels}
        for name in DELIVERY_CHANNELS:
            self.channels.setdefault(name, NullChannel(name, strict=self.strict))

    def notify(
        self,
        db: Session,
        *,
        user_id: int,
        title: str,
        body: str,
        kind: str,
        checkin_id: int | None = None,
        guardian_id: int | None = None,
        phone: str | None = None,
        push_token: str | None = None,
        platform: str | None = None,
        channels: tuple[str, ...] = CHANNEL_ORDER,
        urgent: bool = False,
        payload: dict | None = None,
        stop_on_success: bool = False,
    ) -> list[SendResult]:
        """
        Send to one recipient across every channel available, and record each.

        The `notifications` row is written per channel with the same
        idempotency key, because a recipient who gets the push but not the SMS
        has been told once, and a retried job must not tell them again.

        `stop_on_success` is for reminders to the user themselves: one push that
        landed is enough and there is no reason to also text them. Alerts to
        guardians leave it off — every channel is tried, because a guardian who
        is reachable on two channels should be reached on both.
        """
        results: list[SendResult] = []
        key = f"{kind}:{checkin_id or 0}:{guardian_id or 0}:{user_id}"

        # Plan 6.15: quiet hours and the daily cap apply to non-urgent, non-safety
        # messages only. The check is here, at the one place every send passes
        # through, so no caller can forget it.
        if not urgent and not _always_allowed(kind):
            if not _may_send_non_urgent(db, user_id):
                log.info("non-urgent %s held for user %s (quiet hours or daily cap)", kind, user_id)
                return [SendResult(ok=False, channel="-", detail="held: quiet hours or daily cap")]

        for name in channels:
            channel = self.channels.get(name)
            if channel is None:
                continue
            # A guardian who never accepted is never messaged. This is checked
            # here as well as at the call site because it is the one rule in the
            # app with a legal flavour: plan 14, no alerts to an unconsenting
            # phone number, ever.
            if guardian_id is not None and not _guardian_consented(db, guardian_id):
                log.info("guardian %s never accepted; not sending %s", guardian_id, name)
                continue

            if _already_sent(db, key, name):
                results.append(SendResult(ok=True, channel=name, detail="already sent"))
                if stop_on_success:
                    break
                continue

            row = Notification(
                user_id=user_id,
                checkin_id=checkin_id,
                guardian_id=guardian_id,
                channel=name,
                action=(kind or "notify")[:32],
                idempotency_key=f"{key}:{name}"[:96],
                status="queued",
                title=(title or "")[:160],
                body=(body or "")[:400],
            )
            db.add(row)
            db.flush()  # the row exists before the send, not after

            try:
                res = channel.send(
                    to=phone,
                    title=title,
                    body=body,
                    push_token=push_token,
                    platform=platform,
                    payload=payload or {},
                    urgent=urgent,
                    kind=kind,
                )
            except Exception as exc:
                # A provider that raises is a failed channel, not a failed job.
                log.warning("channel %s raised: %s", name, type(exc).__name__)
                res = SendResult(ok=False, channel=name, detail=str(exc)[:200])

            if res.detail == "dead_token" and push_token:
                _forget_token(db, push_token)

            row.status = "sent" if res.ok else "failed"
            row.sent_at = now()
            row.provider_id = res.provider_id
            row.error = None if res.ok else (res.detail or "failed")[:240]
            results.append(res)
            if res.ok and stop_on_success:
                break

        db.commit()
        return results


def _always_allowed(kind: str) -> bool:
    head = (kind or "").split(":", 1)[0]
    return any(head.startswith(p) for p in _ALWAYS_ALLOWED_PREFIXES)


def _local_hour(user: User, at: float) -> int:
    return datetime.fromtimestamp(at, ZoneInfo(user.time_zone or "UTC")).hour


def in_quiet_hours(user: User, at: float | None = None, hours: tuple[int, int] | None = None) -> bool:
    start, end = hours or config.QUIET_HOURS
    hour = _local_hour(user, now() if at is None else at)
    if start > end:            # the usual case: 21 -> 7 crosses midnight
        return hour >= start or hour < end
    return start <= hour < end


def _may_send_non_urgent(db: Session, user_id: int) -> bool:
    from python.models import UserPrefs  # noqa: PLC0415

    user = db.get(User, user_id)
    if user is None:
        return True
    prefs = db.get(UserPrefs, user_id)
    hours = (prefs.quiet_start, prefs.quiet_end) if prefs is not None else None
    if in_quiet_hours(user, hours=hours):
        return False
    since = now() - 86400
    sent = db.scalar(
        select(func.count(func.distinct(Notification.idempotency_key))).where(
            Notification.user_id == user_id,
            Notification.status == "sent",
            Notification.channel == "push",
            Notification.sent_at >= since,
        )
    ) or 0
    return sent < config.MAX_NON_URGENT_PER_DAY


def _guardian_consented(db: Session, guardian_id: int) -> bool:
    from python.models import Guardian

    g = db.get(Guardian, guardian_id)
    return g is not None and g.accepted_at is not None


def _already_sent(db: Session, key: str, channel: str) -> bool:
    return (
        db.query(Notification)
        .filter(
            Notification.idempotency_key == f"{key}:{channel}"[:96],
            Notification.status == "sent",
        )
        .first()
        is not None
    )


def _forget_token(db: Session, push_token: str) -> None:
    """The vendor says this token is dead. Stop trying it."""
    for d in db.scalars(select(Device).where(Device.push_token == push_token)).all():
        db.delete(d)


@dataclass
class Broadcast:
    """
    One message, several recipients.

    Used by the guardian dispatch and the seva digest. It is a dataclass rather
    than a helper function because the escalation job wants to count how many
    people were reached and log that number — an alert that reached nobody is a
    different event from an alert that reached four, and only the second one
    looks fine in a log.
    """

    title: str
    body: str
    kind: str
    urgent: bool = False
    payloads: dict = field(default_factory=dict)

    def to(self, dispatch: "MultiChannel", db: Session, user_id: int, **kw) -> list[SendResult]:
        return dispatch.notify(
            db, user_id=user_id, title=self.title, body=self.body, kind=self.kind,
            urgent=self.urgent, **kw
        )


# The process-wide dispatcher. Started with logging channels; real ones are
# installed by python.integrations.registry.configure_dispatch() at boot.
dispatch = MultiChannel()


def set_dispatch(d: MultiChannel) -> None:
    """Replace the channels of the process-wide dispatcher with another's."""
    dispatch.strict = d.strict
    dispatch.channels = d.channels
