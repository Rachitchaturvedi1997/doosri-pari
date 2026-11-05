# =============================================================================
# The Anubhav board — volunteering, mentoring and paid work. Plan section 6.8.
#
# The feature with the most commercial and legal exposure, so the rules are in
# the schema rather than only in the service layer:
#   * money moves through a verified partner, never person to person;
#   * a payout is held for 48 hours after completion (plan section 12);
#   * `mode` distinguishes free from zero-priced, so a volunteer offer is never
#     sorted away by a price filter.
# =============================================================================
from __future__ import annotations

from sqlalchemy import (
    Boolean,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from python.models.base import PAYOUT_HOLD_HOURS, Base, JsonType, now


class Partner(Base):
    """
    A verified organisation.

    `gstin_or_reg` is nullable on purpose: a partner may exist for a
    non-commercial purpose — a pensioners' association running a free medical
    camp — and requiring a GSTIN for that would keep the most useful partners
    out. But payment requires one, which is the actual rule.
    """

    __tablename__ = "partners"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(160), index=True)
    gstin_or_reg: Mapped[str | None] = mapped_column(String(32), unique=True)
    verified_at: Mapped[float | None] = mapped_column(Float)
    contact: Mapped[str | None] = mapped_column(String(120))
    # Free-text description of what kind of work this partner posts.
    kind: Mapped[str | None] = mapped_column(String(32))
    created_at: Mapped[float] = mapped_column(Float, default=now)

    @property
    def can_take_payment(self) -> bool:
        return self.verified_at is not None and bool(self.gstin_or_reg)


class Listing(Base):
    __tablename__ = "listings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Paid work posted by a verified organisation rather than a person.
    partner_id: Mapped[int | None] = mapped_column(
        ForeignKey("partners.id", ondelete="SET NULL"), index=True
    )
    type: Mapped[str] = mapped_column(String(16), default="help", index=True)
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str] = mapped_column(Text)
    tags: Mapped[list] = mapped_column(JsonType, default=list)
    # Which of the ten languages the poster can actually work in.
    langs: Mapped[list] = mapped_column(JsonType, default=list)
    mode: Mapped[str] = mapped_column(String(16), default="free", index=True)
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    radius_km: Mapped[float | None] = mapped_column(Float)
    # Only meaningful when mode == "paid".
    price: Mapped[float] = mapped_column(Float, default=0.0)
    # Tiers this is free to. Empty means everyone pays.
    free_for: Mapped[list] = mapped_column(JsonType, default=list)
    status: Mapped[str] = mapped_column(String(16), default="open", index=True)
    # The day's worth of free time the poster actually has.
    availability: Mapped[list] = mapped_column(JsonType, default=list)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)

    __table_args__ = (Index("ix_listings_open_type", "status", "type"),)


class Availability(Base):
    """
    A recurring availability window, in the poster's own timezone.

    A separate table rather than a JSON blob on the listing, because "show me
    what is available on Thursday afternoon" is a query and not a filter over
    a parsed string.
    """

    __tablename__ = "availability"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    listing_id: Mapped[int] = mapped_column(
        ForeignKey("listings.id", ondelete="CASCADE"), index=True
    )
    # 1 = Monday, matching java.time and Kotlin.
    weekday: Mapped[int] = mapped_column(Integer, index=True)
    start_min: Mapped[int] = mapped_column(Integer)   # minutes from midnight
    end_min: Mapped[int] = mapped_column(Integer)
    time_zone: Mapped[str] = mapped_column(String(64), default="UTC")


class Booking(Base):
    """
    A booking of somebody's time.

    `payout_held_until` is the plan's 48-hour hold. Money is not released on
    completion; it is released two days later, so a pensioner who pays a helper
    who does not turn up has a window in which to say so.
    """

    __tablename__ = "bookings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    listing_id: Mapped[int] = mapped_column(
        ForeignKey("listings.id", ondelete="CASCADE"), index=True
    )
    seeker_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Denormalised from the listing at booking time, so a later edit to the
    # listing cannot change what somebody already agreed to pay.
    provider_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    slot_at: Mapped[float] = mapped_column(Float, index=True)
    status: Mapped[str] = mapped_column(String(16), default="requested", index=True)
    amount: Mapped[float] = mapped_column(Float, default=0.0)
    fee: Mapped[float] = mapped_column(Float, default=0.0)
    payment_id: Mapped[str | None] = mapped_column(String(64), index=True)
    payout_id: Mapped[str | None] = mapped_column(String(64), index=True)
    payout_held_until: Mapped[float | None] = mapped_column(Float, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)
    completed_at: Mapped[float | None] = mapped_column(Float)

    @property
    def is_paid(self) -> bool:
        return self.amount > 0

    @property
    def is_settled(self) -> bool:
        return self.payout_id is not None


class Rating(Base):
    __tablename__ = "ratings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    booking_id: Mapped[int] = mapped_column(
        ForeignKey("bookings.id", ondelete="CASCADE"), index=True
    )
    by_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    stars: Mapped[int] = mapped_column(Integer)
    text: Mapped[str | None] = mapped_column(Text)
    at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (UniqueConstraint("booking_id", "by_id", name="uq_rating"),)


class BookingMessage(Base):
    """
    The thread between a seeker and a provider.

    Kept separate from the post feed: this is a private arrangement between two
    people about a specific time, and it should never surface in a circle.
    """

    __tablename__ = "booking_messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    booking_id: Mapped[int] = mapped_column(
        ForeignKey("bookings.id", ondelete="CASCADE"), index=True
    )
    sender_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    text: Mapped[str] = mapped_column(Text)
    at: Mapped[float] = mapped_column(Float, default=now, index=True)


# Plan section 12, `payouts`: money only moves after the hold.
PAYOUT_HOLD_SECONDS = PAYOUT_HOLD_HOURS * 3600
