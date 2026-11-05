# =============================================================================
# Feature · Paying for a session, and being paid — plan sections 6.8 and 9.
#
#   POST /bookings/{id}/pay             create the Razorpay order for a booking
#   POST /bookings/{id}/pay/verify      the app's checkout result, verified
#   GET  /payouts/account               where the mentor is paid (masked)
#   PUT  /payouts/account               set it (a UPI id -> ids at Razorpay)
#   DELETE /payouts/account
#   GET  /payouts/history               this provider's sessions and their payout state
#
# Two ways a payment becomes real, and either alone is enough:
#   * the app's checkout signature, verified HERE (verify_checkout), then checked
#     against the order Razorpay holds - amount and booking id come from
#     Razorpay's copy, never from the request;
#   * the signed webhook (features/seva.py payments_webhook).
# Both set `booking.payment_id` to the same value, so racing is harmless.
#
# Money is integer paise throughout. We never store a UPI id: it goes to
# RazorpayX to create a contact and fund account, and only the two resulting ids
# (and a masked display string) are kept.
# =============================================================================
from __future__ import annotations

import logging
import re

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select

from python import config
from python.deps import CurrentUser, DbSession
from python.features.seva import booking_view, to_paise
from python.integrations import razorpay
from python.models import AuditEntry, Booking, Listing, PayoutAccount, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.payments")
router = APIRouter(tags=["payments"])

_VPA = re.compile(r"^[A-Za-z0-9.\-_]{2,64}@[A-Za-z]{2,32}$")


class VerifyIn(BaseModel):
    razorpay_order_id: str = Field(min_length=6, max_length=64)
    razorpay_payment_id: str = Field(min_length=6, max_length=64)
    razorpay_signature: str = Field(min_length=16, max_length=128)


class AccountIn(BaseModel):
    name: str = Field(min_length=2, max_length=50)
    upi_id: str = Field(min_length=5, max_length=80)


def _booking_for_seeker(db, user, booking_id: int) -> Booking:
    b = db.get(Booking, booking_id)
    # 404 for someone else's booking: a 403 confirms the id exists.
    if b is None or b.seeker_id != user.id:
        raise HTTPException(404, "booking not found")
    return b


@router.post("/bookings/{booking_id}/pay")
def start_payment(booking_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    b = _booking_for_seeker(db, user, booking_id)
    if b.status not in ("requested", "confirmed"):
        raise HTTPException(409, f"this booking is {b.status}; nothing to pay")
    if not b.is_paid:
        raise HTTPException(409, "this session is free; there is nothing to pay")
    if b.payment_id:
        raise HTTPException(409, "this booking is already paid")
    if not razorpay.available():
        raise HTTPException(503, "payments are not switched on yet")

    amount = to_paise(b.amount)
    order = razorpay.create_order(
        amount_paise=amount, receipt=f"bk{b.id}", notes={"booking_id": b.id, "seeker_id": user.id}
    )
    if order is None:
        raise HTTPException(502, "could not start the payment; nothing was charged")
    return {
        "booking_id": b.id,
        "order_id": order["id"],
        "amount_paise": amount,
        "currency": "INR",
        "key_id": config.RAZORPAY_KEY_ID,
        "description": "Doosri Pari session",
        "fee_note": "12% of this pays for the app; the rest goes to your mentor 48 hours after the session.",
    }


@router.post("/bookings/{booking_id}/pay/verify")
def verify_payment(booking_id: int, body: VerifyIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    b = _booking_for_seeker(db, user, booking_id)
    if not razorpay.verify_checkout(body.razorpay_order_id, body.razorpay_payment_id, body.razorpay_signature):
        raise HTTPException(400, "the payment could not be verified")

    order = razorpay.fetch_order(body.razorpay_order_id)
    if order is None:
        raise HTTPException(502, "could not confirm the payment with Razorpay; it will be confirmed shortly")
    notes = order.get("notes") or {}
    if str(notes.get("booking_id")) != str(b.id):
        raise HTTPException(400, "this payment belongs to a different booking")
    if int(order.get("amount", -1)) != to_paise(b.amount):
        raise HTTPException(400, "the amount paid does not match this booking")

    if b.payment_id in (None, body.razorpay_payment_id):
        b.payment_id = body.razorpay_payment_id
        db.add(AuditEntry(action="payment_verified", resource=f"booking:{b.id}", resource_id=b.id,
                          detail={"via": "checkout"}))
        db.commit()
    return booking_view(b)


# ------------------------------------------------------------------ refunds --
def refund_booking(db, booking: Booking, reason: str) -> bool:
    """
    Give the seeker's money back. Idempotent: a booking with no payment, or one
    already refunded, does nothing. Best effort - a failure is logged and the
    booking stays flagged for a human via the audit trail.
    """
    if not booking.payment_id or booking.payout_id:
        return False
    already = db.scalar(
        select(AuditEntry.id).where(AuditEntry.action == "booking_refunded", AuditEntry.resource == f"booking:{booking.id}")
    )
    if already:
        return True
    result = razorpay.refund(booking.payment_id, notes={"booking_id": booking.id, "reason": reason})
    db.add(AuditEntry(
        action="booking_refunded" if result else "booking_refund_failed",
        resource=f"booking:{booking.id}", resource_id=booking.id, detail={"reason": reason},
    ))
    db.commit()
    if not result:
        log.error("refund for booking %s failed; needs a human", booking.id)
    return bool(result)


# ------------------------------------------------------------------ payouts --
def _mask(vpa: str) -> str:
    name, _, bank = vpa.partition("@")
    return f"{name[:2]}•••@{bank}"


@router.get("/payouts/account")
def get_account(user: CurrentUser, db: DbSession):
    a = db.get(PayoutAccount, user.id)
    return {"account": {"method": a.method, "display": a.display, "created_at": a.created_at} if a else None,
            "available": razorpay.payouts_available()}


@router.put("/payouts/account")
def put_account(body: AccountIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    vpa = body.upi_id.strip()
    if not _VPA.match(vpa):
        raise HTTPException(400, "that does not look like a UPI id, for example name@bank")
    if not razorpay.payouts_available():
        raise HTTPException(503, "payouts are not switched on yet")
    ids = razorpay.create_fund_account_upi(user_id=user.id, name=body.name.strip(), vpa=vpa)
    if ids is None:
        raise HTTPException(502, "could not save that UPI id; please check it and try again")
    a = db.get(PayoutAccount, user.id)
    if a is None:
        a = PayoutAccount(user_id=user.id, contact_id=ids["contact_id"], fund_account_id=ids["fund_account_id"])
        db.add(a)
    a.contact_id, a.fund_account_id, a.method, a.display = ids["contact_id"], ids["fund_account_id"], "upi", _mask(vpa)
    db.commit()
    return {"account": {"method": "upi", "display": a.display}}


@router.delete("/payouts/account")
def delete_account(request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    a = db.get(PayoutAccount, user.id)
    if a is not None:
        db.delete(a)
        db.commit()
    return {"deleted": True}


@router.get("/payouts/history")
def history(user: CurrentUser, db: DbSession, limit: int = 30):
    if not 1 <= limit <= 100:
        raise HTTPException(400, "limit must be 1..100")
    rows = db.scalars(
        select(Booking).where(Booking.provider_id == user.id, Booking.amount > 0, Booking.status.in_(("completed", "disputed")))
        .order_by(Booking.completed_at.desc().nullslast()).limit(limit)
    ).all()
    at = now()
    out = []
    for b in rows:
        if b.payout_id:
            state = "paid"
        elif b.status == "disputed":
            state = "on_hold_dispute"
        elif b.payout_held_until and b.payout_held_until > at:
            state = "held"
        else:
            state = "due"
        net = to_paise(b.amount) - to_paise(b.fee)
        out.append({
            "booking_id": b.id, "completed_at": b.completed_at, "gross_paise": to_paise(b.amount),
            "fee_paise": to_paise(b.fee), "net_paise": net, "state": state, "releases_at": b.payout_held_until,
        })
    return {"payouts": out}
