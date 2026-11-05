# =============================================================================
# Razorpay. Plan sections 6.8, 9 and 17.
#
# Two separate Razorpay products are used, and they are kept apart on purpose:
#
#   Payments  a seeker pays for a mentoring session. We create an ORDER, the app
#             opens Razorpay Checkout, and the result is verified two ways: the
#             client's checkout signature (verify_checkout) and the signed
#             webhook (python/features/seva.py). Either is enough; both agree.
#   Payouts   RazorpayX pays the mentor, 48 hours after completion, minus the
#             12% fee. Every payout carries a deterministic reference sent as
#             X-Payout-Idempotency, so a retried job is a no-op at Razorpay
#             rather than a second payment to a retired teacher.
#
# We never store a bank account or card. The mentor gives a UPI id once; it goes
# to Razorpay to create a contact and fund account, and only the two resulting
# ids are kept (models/extras.py PayoutAccount).
#
# All amounts are integer paise. There is no float arithmetic in this file.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import logging

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.razorpay")

BASE = "https://api.razorpay.com/v1"


def available() -> bool:
    return bool(config.RAZORPAY_KEY_ID and config.RAZORPAY_KEY_SECRET)


def payouts_available() -> bool:
    return available() and bool(config.RAZORPAYX_ACCOUNT_NUMBER)


def _auth() -> tuple[str, str]:
    return (config.RAZORPAY_KEY_ID, config.RAZORPAY_KEY_SECRET)


# ------------------------------------------------------------------ payments --
def create_order(*, amount_paise: int, receipt: str, notes: dict) -> dict | None:
    """A Checkout order. `notes.booking_id` is what the webhook matches on."""
    if not available() or amount_paise <= 0:
        return None
    r = _http.request(
        "POST",
        f"{BASE}/orders",
        auth=_auth(),
        json={
            "amount": int(amount_paise),
            "currency": "INR",
            "receipt": receipt[:40],
            "notes": {k: str(v) for k, v in notes.items()},
            "payment_capture": 1,
        },
        retries=1,
    )
    if r.ok and isinstance(r.body, dict) and r.body.get("id"):
        return r.body
    log.warning("razorpay order failed: status=%s", r.status)
    return None


def verify_checkout(order_id: str, payment_id: str, signature: str) -> bool:
    """HMAC-SHA256(order_id|payment_id) with the key secret, constant-time."""
    if not (config.RAZORPAY_KEY_SECRET and order_id and payment_id and signature):
        return False
    expected = hmac.new(
        config.RAZORPAY_KEY_SECRET.encode(), f"{order_id}|{payment_id}".encode(), hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


def fetch_order(order_id: str) -> dict | None:
    """The order as Razorpay holds it - the authority on its amount and notes."""
    if not available():
        return None
    r = _http.request("GET", f"{BASE}/orders/{order_id}", auth=_auth())
    return r.body if r.ok and isinstance(r.body, dict) else None


def fetch_payment(payment_id: str) -> dict | None:
    if not available():
        return None
    r = _http.request("GET", f"{BASE}/payments/{payment_id}", auth=_auth())
    return r.body if r.ok and isinstance(r.body, dict) else None


def refund(payment_id: str, amount_paise: int | None = None, notes: dict | None = None) -> dict | None:
    if not available():
        return None
    body: dict = {"notes": {k: str(v) for k, v in (notes or {}).items()}}
    if amount_paise:
        body["amount"] = int(amount_paise)
    r = _http.request("POST", f"{BASE}/payments/{payment_id}/refund", auth=_auth(), json=body)
    return r.body if r.ok and isinstance(r.body, dict) else None


# ------------------------------------------------------------------- payouts --
def create_fund_account_upi(*, user_id: int, name: str, vpa: str) -> dict | None:
    """
    A RazorpayX contact and UPI fund account. Returns
    {"contact_id", "fund_account_id"}; only these ids are stored by the caller.
    """
    if not payouts_available():
        return None
    contact = _http.request(
        "POST",
        f"{BASE}/contacts",
        auth=_auth(),
        json={"name": name[:50], "type": "vendor", "reference_id": f"dp-user-{user_id}"},
    )
    if not (contact.ok and isinstance(contact.body, dict) and contact.body.get("id")):
        log.warning("razorpayx contact failed: status=%s", contact.status)
        return None
    fund = _http.request(
        "POST",
        f"{BASE}/fund_accounts",
        auth=_auth(),
        json={
            "contact_id": contact.body["id"],
            "account_type": "vpa",
            "vpa": {"address": vpa},
        },
    )
    if not (fund.ok and isinstance(fund.body, dict) and fund.body.get("id")):
        log.warning("razorpayx fund account failed: status=%s", fund.status)
        return None
    return {"contact_id": contact.body["id"], "fund_account_id": fund.body["id"]}


def payout(*, reference: str, fund_account_id: str, amount_paise: int, narration: str) -> str | None:
    """
    Pay a mentor. Returns the payout id, or None if it did not go through.

    `reference` is deterministic per booking (jobs.payouts builds it) and is sent
    as both reference_id and the idempotency header, so calling this twice for
    the same booking yields one payout.
    """
    if not payouts_available() or amount_paise <= 0:
        return None
    r = _http.request(
        "POST",
        f"{BASE}/payouts",
        auth=_auth(),
        headers={"X-Payout-Idempotency": reference},
        json={
            "account_number": config.RAZORPAYX_ACCOUNT_NUMBER,
            "fund_account_id": fund_account_id,
            "amount": int(amount_paise),
            "currency": "INR",
            "mode": "UPI",
            "purpose": "payout",
            "queue_if_low_balance": True,
            "reference_id": reference[:40],
            "narration": narration[:30],
        },
        retries=1,
    )
    if r.ok and isinstance(r.body, dict) and r.body.get("id"):
        return str(r.body["id"])
    log.warning("razorpayx payout failed: status=%s", r.status)
    return None
