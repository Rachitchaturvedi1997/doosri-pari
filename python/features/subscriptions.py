# =============================================================================
# Feature · Family plan subscriptions — plan sections 17, 12 and 15.
#
#   POST /subscriptions/verify           a Play or App Store purchase, verified
#   POST /subscriptions/webhook/apple    App Store Server Notifications V2 (JWS)
#   POST /subscriptions/webhook/google   Play RTDN via Pub/Sub push
#
# The rules this module enforces:
#
#   1. A subscription exists here only because the store says so. The request
#      body is a claim — `is_active: true` in a JSON body is not a subscription,
#      it is a wish. Status, renewal date and every entitlement are written
#      from a verified call to the Google Play Developer API or the App Store
#      Server API, or nothing is written at all.
#   2. The two webhooks are unauthenticated by user and authenticated by the
#      provider: Apple signs with a JWS, Google pushes through Pub/Sub with a
#      shared verification token. Unverified means refused. Sandbox and test
#      messages are acknowledged and grant exactly nothing.
#   3. One payment covers up to six pensioners (17). Seats are claimed with a
#      family code — already implemented in features/auth.py, never duplicated
#      here — and a seat is revoked (`revoked_at`), never deleted, so the count
#      stays auditable (models/identity.py).
#   4. A receipt or purchase token is a bearer credential against the store's
#      own APIs. It is stored as a handle and never logged whole, and every
#      provider event id is remembered so a replayed webhook changes nothing.
# =============================================================================
from __future__ import annotations

import base64
import json
import logging
import os

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession
from python.models import (
    BILLING_STORES,
    MAX_PLAN_SEATS,
    SUBSCRIPTION_STATUSES,
    AuditEntry,
    Subscription,
    SubscriptionSeat,
    now,
)
from python.ratelimit import limit_write
from python.security import constant_time_equals

log = logging.getLogger("doosri.subscriptions")
router = APIRouter(tags=["subscriptions"])

RECEIPT_MAX = 8192   # an App Store transaction JWS is a few KB
PRODUCT_MAX = 64     # the column


# ----------------------------------------------------------------- helpers --
def _redact(value: str | None, keep: int = 6) -> str:
    """
    The front of a token, its length, and nothing else.

    WHY THIS EXISTS: a purchase token or receipt is a bearer credential —
    anyone holding it can query the store as us, and on Play it can even be
    used to acknowledge and consume the purchase. A full token in a log line is
    copied into a log aggregator, an error tracker and somebody's inbox, and it
    lives there longer than the subscription does. So logs and audit rows see
    this form, never the value. It matches jobs._redact, written out here so a
    feature module does not depend on the job runner for six lines.
    """
    if not value:
        return "-"
    return f"{value[:keep]}…({len(value)})"


# ------------------------------------------------------------- SEAM: stores --
def verify_with_store(store: str, receipt: str) -> dict | None:
    """
    The only place a receipt is believed (python/integrations/billing.py).

    WHAT PRODUCTION MUST CALL
      store="play"      Google Play Developer API
                        `purchases.subscriptionsv2.get`, with the purchase
                        token from the client. That call is the verification:
                        Google's answer is the truth about the subscription.
      store="app_store" App Store Server API — Get Transaction Info / Get All
                        Subscription Statuses, against the transaction id or
                        the signed transaction JWS the client sent.

    A receipt must NEVER be trusted unverified. The client sends a token; a
    token is a claim, and this function is the cross-examination. If the store
    cannot be reached or is not configured, return None and the caller refuses
    — never "probably fine".

    Returns the normalised state, or None when there is no verified state:
        status               mapped onto SUBSCRIPTION_STATUSES by this seam
                             (active | grace_period | cancelled | expired |
                             refunded; "none" when the store says there is
                             nothing)
        product_id           str
        renews_at            epoch seconds | None
        valid_until          epoch seconds | None
        store_transaction_id str | None   the unique key we reconcile on
        receipt_ref          str | None   SHORT handle for later reconciliation
                             (Play's purchase token, Apple's transaction id).
                             Never a whole JWS: the column is String(255).
        payer_id             int | None   only when the store echoes our
                             appAccountToken (the payer's user id, set at
                             purchase time)

    The hourly `subscriptions_sync` job (plan section 12) calls this same
    function; there is one view of the store, not two.
    """
    from python.integrations import billing  # noqa: PLC0415 - keeps this importable with no network

    try:
        return billing.fetch_state(store, receipt)
    except Exception:
        # A store client failing is "no verified state", never "probably fine".
        log.exception("store verification raised for %s", store)
        return None


def verify_apple_jws(signed_payload: str) -> dict | None:
    """
    Verify and decode an App Store Server Notification V2
    (python/integrations/billing.py, which walks the x5c chain to Apple's root).

    WHAT PRODUCTION MUST DO, in this order, before returning anything:
      1. Decode the JWS header, read its `x5c` certificate chain.
      2. Verify the chain up to **Apple Root CA G3** (and check the leaf's
         subject: an App Store signing cert, not any old Apple cert).
      3. Verify the ES256 signature over the payload with that leaf's public
         key. Then verify `signedTransactionInfo` / `signedRenewalInfo` the
         same way — they are JWSs inside the JWS.

    An unverified JWS is a stranger's JSON. Anybody can POST one here and claim
    a renewal; the signature is the only thing standing between that POST and a
    free lifetime subscription, which is why an unconfigured seam refuses
    rather than parses.

    Returns None when the signature does not verify (or nothing is configured),
    else a normalised event:
        event_id     notificationUUID — the idempotency key
        event_type   notificationType ("TEST", "DID_RENEW", "EXPIRED", …)
        environment  "Production" | "Sandbox"
        state        the same normalised dict as verify_with_store (including
                     payer_id from the transaction's appAccountToken), or None
                     when the notification carries no transaction state
    """
    from python.integrations import billing  # noqa: PLC0415

    try:
        return billing.verify_apple_notification(signed_payload)
    except Exception:
        log.exception("apple notification verification raised")
        return None


def _decode_google_message(raw: dict) -> dict | None:
    """
    Unwrap a Pub/Sub push envelope into the Play developer notification.

    Pub/Sub delivers `{"message": {"data": <base64>, "messageId": ...}, ...}`
    and the developer notification is the base64-decoded `data`. Returns None
    when there is nothing to act on (Pub/Sub's own test push, which is how a
    test subscription announces itself).
    """
    message = raw.get("message") or {}
    data = message.get("data") or ""
    if not data:
        return None
    try:
        return json.loads(base64.b64decode(data).decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise HTTPException(400, "message data is not a developer notification") from None


# ------------------------------------------------------------------ schemas --
class VerifyIn(BaseModel):
    store: str = Field(...)
    # The purchase token (Play) or the transaction receipt/JWS (Apple). A
    # bearer credential: see _redact for why it is never logged. It is NEVER
    # believed on its own — see verify_with_store.
    receipt: str = Field(..., min_length=8, max_length=RECEIPT_MAX)
    product_id: str | None = Field(default=None, max_length=PRODUCT_MAX)


# ------------------------------------------------------------------ views --
def subscription_view(row: Subscription, seats_used: int) -> dict:
    """
    The subscription as the client sees it. `receipt_ref` stays server-side: it
    is the handle that fetches the receipt, and this response is not the place
    to hand that out.

    `is_active` is computed here, from the row the STORE verified — from what
    Google or Apple told us through the seam. It is never taken from the
    request body and never mirrored from the client. A body that says
    `is_active: true` is describing the client's wish, not a subscription; what
    this field says is what the server knows. (This is the plan's entitlement;
    `User.tier` in models/identity.py is the verification tier of plan 6.1 and
    is a different axis entirely — billing never buys verification.)
    """
    return {
        "id": row.id,
        "store": row.store,
        "product_id": row.product_id,
        "status": row.status,
        "is_active": row.is_active,
        "renews_at": row.renews_at,
        "valid_until": row.valid_until,
        "store_transaction_id": row.store_transaction_id,
        # 17: "₹149/mo or ₹1,299/yr, one payment covers up to six pensioners,
        # claimed with a family code" — the claim itself is
        # POST /auth/family-code/redeem (features/auth.py).
        "seats_used": seats_used,
        "seats_total": MAX_PLAN_SEATS,
    }


# ------------------------------------------------------- server-side state --
def _seat_count(db: Session, subscription_id: int) -> int:
    """Seats in use. Revoked seats do not count — but they are never deleted,
    so the count stays auditable (models/identity.py, SubscriptionSeat)."""
    return int(
        db.scalar(
            select(func.count(SubscriptionSeat.id)).where(
                SubscriptionSeat.subscription_id == subscription_id,
                SubscriptionSeat.revoked_at.is_(None),
            )
        )
        or 0
    )


def _apply_store_state(
    db: Session, store: str, state: dict, fallback_payer_id: int | None = None
) -> tuple[Subscription | None, str]:
    """
    Write the STORE's verdict into the one row it belongs to.

    Reconciliation key is `store_transaction_id` — "the key we reconcile on"
    (models/identity.py). A notification without a matching row is recorded and
    left alone (a subscription we never issued is not ours to invent), and a
    state whose status is not one of SUBSCRIPTION_STATUSES is refused rather
    than guessed at.

    Returns (row, "applied" | "unmapped" | "unmatched").
    """
    status = state.get("status")
    if status not in SUBSCRIPTION_STATUSES:
        return None, "unmapped"

    txn = state.get("store_transaction_id")
    row = None
    if txn:
        row = db.scalar(select(Subscription).where(Subscription.store_transaction_id == txn))
    if row is None:
        payer_id = state.get("payer_id") or fallback_payer_id
        if payer_id is None:
            return None, "unmatched"
        row = db.scalar(
            select(Subscription).where(Subscription.payer_id == payer_id, Subscription.store == store)
        )
        if row is None:
            row = Subscription(payer_id=payer_id, store=store, product_id="", status="none")
            db.add(row)

    row.product_id = state.get("product_id") or row.product_id or ""
    row.status = status
    row.renews_at = state.get("renews_at")
    row.valid_until = state.get("valid_until")
    if txn:
        row.store_transaction_id = txn
    if state.get("receipt_ref"):
        row.receipt_ref = state["receipt_ref"]
    return row, "applied"


def _event_seen(db: Session, action: str, event_id: str) -> AuditEntry | None:
    """The processed-event ledger. One verified event, processed once.

    Both stores redeliver: Apple retries unanswered notifications for days and
    Pub/Sub is documented at-least-once. A replayed RENEWED that re-extends a
    cancelled subscription, or a replayed REFUND that revokes a seat twice, is
    exactly the kind of quiet corruption nobody notices until a pensioner
    loses access to their own listings. AuditEntry is the ledger because this
    schema (owned by others) has no store_events table; the pre-check covers
    the ordinary retry path. A unique index on (action, resource) is the real
    fix under concurrency and needs a migration this module cannot make.
    """
    return db.scalar(
        select(AuditEntry).where(AuditEntry.action == action, AuditEntry.resource == event_id)
    )


def _record_event(
    db: Session,
    action: str,
    event_id: str,
    detail: dict,
    subscription_id: int | None = None,
) -> None:
    db.add(
        AuditEntry(
            action=action,
            resource=event_id,
            resource_id=subscription_id,
            # The redacted receipt handle, never the receipt itself.
            detail=detail,
        )
    )


# ---------------------------------------------------------------- endpoints --
@router.post("/subscriptions/verify")
def verify_subscription(body: VerifyIn, request: Request, user: CurrentUser, db: DbSession):
    """
    "I bought the family plan" — verified against the store, or refused.

    The whole shape of this endpoint is rule 1: the client sends a purchase
    token and a store name, and NOTHING else in the body is ever an input to
    entitlement. If `verify_with_store` is not configured, this refuses with
    503 and writes no row — a server that records "active" because the app said
    so is not a billing system, it is a comment field with a paywall.

    The verified state is what the hourly `subscriptions_sync` job (plan 12)
    later re-reads through the same seam, so a cancelled card ages out without
    anybody tapping anything.
    """
    limit_write(request, user.id)

    if body.store not in BILLING_STORES:
        raise HTTPException(400, f"store must be one of {', '.join(BILLING_STORES)}")

    # The receipt is a bearer credential: logged as a redacted handle, never
    # whole. See _redact.
    log.info("subscription verify requested by user %s (%s, receipt=%s)",
             user.id, body.store, _redact(body.receipt))

    state = verify_with_store(body.store, body.receipt)
    if state is None:
        # Refuse. Not "assume active", not "try again later silently". If this
        # message appears in production, the store client is down and money is
        # being taken for something we cannot confirm — that is an incident.
        raise HTTPException(
            503,
            "store verification is not configured on this server; nothing was recorded",
        )

    row, decision = _apply_store_state(db, body.store, state, fallback_payer_id=user.id)
    if decision == "unmapped":
        raise HTTPException(
            502, "the store returned a state we do not understand; nothing was recorded"
        )
    if decision == "unmatched" or row is None:  # pragma: no cover — fallback_payer_id set
        raise HTTPException(502, "the store confirmed a purchase we cannot place; nothing was recorded")

    seats = _seat_count(db, row.id)
    db.commit()

    out = subscription_view(row, seats)
    out["message"] = (
        "Verified with the store. Your family members join with the family "
        "code — up to six people on this plan."
    )
    return out


@router.post("/subscriptions/webhook/apple")
async def apple_webhook(request: Request, db: DbSession):
    """
    App Store Server Notifications V2 — unauthenticated by user, authenticated
    by Apple's signature.

    Rule 2: the payload is a signed JWS and nothing is believed until the chain
    and the signature verify (see verify_apple_jws for exactly what production
    does). Until then this is a stranger's JSON claiming a renewal. No user, no
    session, no body field decides anything here — the signature does.

    Sandbox TEST notifications are acknowledged and grant exactly nothing:
    acknowledging is how the handshake ends, granting is how fraud begins.
    """
    limit_write(request, None)

    try:
        payload = json.loads(await request.body())
    except ValueError:
        raise HTTPException(400, "body is not JSON") from None
    signed_payload = (payload or {}).get("signedPayload")
    if not isinstance(signed_payload, str) or not signed_payload:
        raise HTTPException(400, "no signedPayload")

    event = verify_apple_jws(signed_payload)
    if event is None:
        # Refuse, loudly. Either the signature does not verify (an attack or a
        # misconfiguration) or nothing is configured yet. Parsing an unverified
        # JWS is the one unforgivable move here.
        raise HTTPException(
            503,
            "App Store notification verification is not configured; no event accepted",
        )

    event_id = str(event.get("event_id") or "")
    event_type = str(event.get("event_type") or "")
    if not event_id:
        raise HTTPException(400, "event has no notificationUUID; refusing it")

    if event_type == "TEST" or event.get("environment") == "Sandbox":
        # A sandbox handshake. Acknowledged so Apple stops retrying; no row is
        # written, no status changes, nobody gains access. Plan 15's trust
        # rule applied to billing: a test signal in production grants nothing.
        log.info("apple %s acknowledged (sandbox/test); nothing granted", event_id)
        return {"ok": True, "changed": False, "reason": "sandbox or test notification"}

    if _event_seen(db, "apple_notification", event_id) is not None:
        # Replay: Apple retries until it gets a 2xx and then some. No-op.
        return {"ok": True, "duplicate": True}

    state = event.get("state")
    result: dict = {"ok": True}
    row = None
    if state:
        row, decision = _apply_store_state(db, "app_store", state)
        if decision == "applied":
            result["changed"] = True
            result["subscription"] = subscription_view(row, _seat_count(db, row.id))
        else:
            result["changed"] = False
            result["reason"] = decision
    else:
        result["changed"] = False
        result["reason"] = "no transaction state in the notification"

    _record_event(
        db,
        "apple_notification",
        event_id,
        {
            "event": event_type,
            "environment": event.get("environment"),
            "reason": result.get("reason"),
        },
        subscription_id=row.id if row is not None else None,
    )
    db.commit()
    return result


@router.post("/subscriptions/webhook/google")
async def google_webhook(request: Request, db: DbSession):
    """
    Play real-time developer notifications, delivered as a Pub/Sub push.

    Unauthenticated by user and authenticated by the provider: Pub/Sub presents
    the shared verification token, compared in constant time. Google's push
    body is a hint, never a verdict — a RENEWED message carries a purchase
    token and no dates, so the STATE comes from the Play Developer API through
    `verify_with_store`. Granting on the push body alone would let anyone who
    learned the endpoint format mint subscriptions with a curl.

    Both test paths — Pub/Sub's own test push (empty data) and Play's
    testNotification — are acknowledged and grant exactly nothing.
    """
    limit_write(request, None)

    # Pub/Sub's push auth token, presented as "Authorization: Bearer <token>"
    # (newer setups also mirror it into X-Goog-Auth-Verify-Token). config.py is
    # owned elsewhere; production supplies this from the secrets manager (9).
    expected = os.getenv("GOOGLE_PUBSUB_VERIFY_TOKEN") or None
    if not expected:
        raise HTTPException(503, "Pub/Sub push is not configured; no event accepted")

    bearer = request.headers.get("authorization", "")
    presented = bearer[7:] if bearer.startswith("Bearer ") else (
        request.headers.get("x-goog-auth-verify-token", "")
    )
    if not presented or not constant_time_equals(expected, presented):
        raise HTTPException(401, "invalid Pub/Sub verification token")

    try:
        raw = json.loads(await request.body())
    except ValueError:
        raise HTTPException(400, "body is not JSON") from None

    message_id = str(((raw or {}).get("message") or {}).get("messageId") or "")
    note = _decode_google_message(raw or {})
    if note is None:
        # Pub/Sub's test subscription push. Acknowledged, nothing else.
        return {"ok": True, "changed": False, "reason": "test push"}
    if not message_id:
        # Idempotency needs the id. Without it there is no safe way to accept.
        raise HTTPException(400, "message has no messageId; refusing it")

    if "testNotification" in note:
        log.info("google notification %s acknowledged (test); nothing granted", message_id)
        return {"ok": True, "changed": False, "reason": "test notification"}

    sub_note = note.get("subscriptionNotification") or {}
    if not sub_note:
        return {"ok": True, "changed": False, "reason": "not a subscription notification"}

    if _event_seen(db, "google_notification", message_id) is not None:
        # Pub/Sub is at-least-once by contract. Replay = no-op.
        return {"ok": True, "duplicate": True}

    purchase_token = sub_note.get("purchaseToken")
    result: dict = {"ok": True, "changed": False}
    row = None
    if not purchase_token:
        result["reason"] = "notification carries no purchase token"
    else:
        state = verify_with_store("play", purchase_token)
        if state is None:
            # The push told us something changed; it did not tell us what is
            # now true, and we will not guess. Acknowledged so the retries
            # stop; the hourly subscriptions_sync job reconciles through the
            # same seam once the store client is up (plan 12). Nothing is
            # granted from an unverified message.
            result["reason"] = "store reconciliation not configured; nothing changed"
        else:
            row, decision = _apply_store_state(db, "play", state)
            if decision == "applied":
                result["changed"] = True
                result["subscription"] = subscription_view(row, _seat_count(db, row.id))
            else:
                result["reason"] = decision

    _record_event(
        db,
        "google_notification",
        message_id,
        {
            "event": sub_note.get("notificationType"),
            # The purchase token is a bearer credential: redacted here like
            # everywhere else (see _redact).
            "receipt_ref": _redact(purchase_token),
            "reason": result.get("reason"),
        },
        subscription_id=row.id if row is not None else None,
    )
    db.commit()
    return result
