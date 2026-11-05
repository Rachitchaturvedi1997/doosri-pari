# =============================================================================
# Identity — who a person is. Plan sections 6.1 and 10.
#
# The most important thing in this file is what is NOT here: no Aadhaar, no PPO
# number, no bank account, no full document number. Plan section 14 is explicit
# that the full numbers never reach our servers. What we keep is a tier and a
# last-four, which is enough to answer "has this person been verified" and not
# enough to impersonate them.
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
from sqlalchemy.orm import Mapped, mapped_column, relationship

from python.models.base import Base, JsonType, now


class User(Base):
    """
    A pensioner. The single root of every other table.

    `deleted_at` is a soft delete and is checked on every read, because plan
    section 14 promises a 30-day deletion window and that promise is only real
    if a deleted account stops appearing in the meantime.
    """

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    phone_e164: Mapped[str | None] = mapped_column(String(20), unique=True, index=True)
    # Sign in with Apple / Google subjects, when those are used.
    apple_sub: Mapped[str | None] = mapped_column(String(255), unique=True, index=True)
    google_sub: Mapped[str | None] = mapped_column(String(255), unique=True, index=True)
    name: Mapped[str | None] = mapped_column(String(80))
    # How the app should address them — "Sharma ji", "Col. Kaur". Plan 3.5.
    address_as: Mapped[str | None] = mapped_column(String(60))
    lang: Mapped[str] = mapped_column(String(12), default="en")
    # In-app text scale on top of the system size: 1.0, 1.15 or 1.3.
    text_scale: Mapped[float] = mapped_column(Float, default=1.0)
    service_type: Mapped[str | None] = mapped_column(String(32), index=True)
    state_code: Mapped[str | None] = mapped_column(String(4))
    department_id: Mapped[int | None] = mapped_column(
        ForeignKey("departments.id", ondelete="SET NULL"), index=True
    )
    cadre: Mapped[str | None] = mapped_column(String(64))
    join_year: Mapped[int | None] = mapped_column(Integer)
    retire_year: Mapped[int | None] = mapped_column(Integer)
    role: Mapped[str] = mapped_column(String(24), default="member")
    tier: Mapped[str] = mapped_column(String(4), default="T0")
    city_id: Mapped[int | None] = mapped_column(Integer, index=True)
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    # Whether this person's profile is findable by others in a circle.
    discoverable: Mapped[bool] = mapped_column(Boolean, default=False)
    platform: Mapped[str | None] = mapped_column(String(16))
    time_zone: Mapped[str] = mapped_column(String(64), default="UTC")

    created_at: Mapped[float] = mapped_column(Float, default=now)
    deleted_at: Mapped[float | None] = mapped_column(Float, index=True)
    # Plan 15's strikes: warning, 7-day pause, 30-day pause, removal. A pause
    # blocks posting and other social writes; it never blocks check-in, SOS,
    # medicines or reading (python/deps.py PAUSE_ALLOWED).
    strikes: Mapped[int] = mapped_column(Integer, default=0)
    suspended_until: Mapped[float | None] = mapped_column(Float, index=True)
    suspension_reason: Mapped[str | None] = mapped_column(String(120))

    settings: Mapped["CheckInSettings"] = relationship(  # noqa: F821
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    kendra_profile: Mapped["KendraProfile"] = relationship(  # noqa: F821
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    spiritual_state: Mapped["SpiritualState"] = relationship(  # noqa: F821
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )

    @property
    def is_deleted(self) -> bool:
        return self.deleted_at is not None

    @property
    def tier_rank(self) -> int:
        from python.models.base import TIER_RANK

        return TIER_RANK.get(self.tier or "T0", 0)


class Department(Base):
    """
    A government department, and the aliases people actually type.

    `aliases` is not cosmetic. "Railways" gets typed as "rail", "Rly", "Railway
    Board" and "रेल"; without these, a search for one's own department comes
    up empty and the Sahayak then answers the wrong service's rules.
    """

    __tablename__ = "departments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(120), index=True)
    parent_id: Mapped[int | None] = mapped_column(
        ForeignKey("departments.id", ondelete="SET NULL"), index=True
    )
    service_type: Mapped[str] = mapped_column(String(32), default="other", index=True)
    aliases: Mapped[list] = mapped_column(JsonType, default=list)


class Verification(Base):
    """
    A record that somebody proved who they are.

    `last4` is four characters and that is the entire point: the full PPO or
    document number is never stored, the uploaded document is deleted within
    seven days (plan section 14), and what survives is enough to answer
    "verified?" without enough to be used.
    """

    __tablename__ = "verifications"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    tier: Mapped[str] = mapped_column(String(4), default="T1")
    method: Mapped[str] = mapped_column(String(24), index=True)
    # Who vouched or approved. Null for a plain phone OTP.
    by_user_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    # Only ever the last four. Never the whole number.
    last4: Mapped[str | None] = mapped_column(String(4))
    verified_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    notes: Mapped[str | None] = mapped_column(Text)
    # Set when the uploaded document hits the retention deadline. Until then a
    # background job can find the blob and delete it.
    document_deleted_at: Mapped[float | None] = mapped_column(Float, index=True)
    # Storage key of the uploaded document while it exists. Nulled the moment the
    # object is deleted (within 7 days, plan 14), so a row with a key is a row
    # with a document that must still be purged.
    document_key: Mapped[str | None] = mapped_column(String(255), index=True)

    __table_args__ = (Index("ix_verifications_user_method", "user_id", "method"),)


class Vouch(Base):
    """
    One member saying another is who they say they are.

    Deliberately weaker than a document check, and typed separately, so the
    difference between "somebody said so" and "we checked" survives in the data
    rather than only in the UI.
    """

    __tablename__ = "vouches"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    voucher_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    vouchee_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    department_id: Mapped[int | None] = mapped_column(
        ForeignKey("departments.id", ondelete="SET NULL")
    )
    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (
        # One vouch per person, and not yourself.
        UniqueConstraint("voucher_id", "vouchee_id", name="uq_vouch_pair"),
    )


class Device(Base):
    """A push token. Reminders go out on push first (plan section 6.3)."""

    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    platform: Mapped[str] = mapped_column(String(16))          # android | ios
    push_token: Mapped[str] = mapped_column(String(255), unique=True)
    app_version: Mapped[str | None] = mapped_column(String(32))
    last_seen_at: Mapped[float | None] = mapped_column(Float, index=True)


class Guardian(Base):
    """
    Somebody who is told when a check-in is missed — a son, a daughter, a
    neighbour. Plan section 6.3: 1-5, each of whom must accept.

    `accepted_at` being null means the invite is outstanding and NO alert is
    sent to that number. Sending a panic message to somebody who never agreed
    to it is both a privacy problem and the fastest way to get a phone number
    blocked by a carrier.
    """

    __tablename__ = "guardians"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Set once the invitee accepts. Alerts only go to accepted rows.
    guardian_user_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    phone: Mapped[str] = mapped_column(String(20))
    name: Mapped[str] = mapped_column(String(80))
    relation: Mapped[str | None] = mapped_column(String(32))
    order_no: Mapped[int] = mapped_column(Integer, default=1)
    # Three separate consents rather than one switch (plan section 14). A single
    # "share with family" is how a son-in-law ends up with his mother-in-law's
    # medication schedule.
    perms: Mapped[dict] = mapped_column(JsonType, default=dict)
    accepted_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (UniqueConstraint("user_id", "phone", name="uq_guardian_phone"),)

    @property
    def is_active(self) -> bool:
        return self.accepted_at is not None

    @property
    def gets_location(self) -> bool:
        return self.is_active and bool((self.perms or {}).get("location"))


class FamilyCode(Base):
    """
    A family plan invitation (plan section 11, `/auth/family-code`).

    One payment covers up to six pensioners, and a 74-year-old is not going to
    type a 16-character code on a phone with a cracked screen. So the code is
    short, expires in a day, and can be used a bounded number of times.
    """

    __tablename__ = "family_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    # The person who pays.
    payer_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    code: Mapped[str] = mapped_column(String(12), unique=True, index=True)
    max_uses: Mapped[int] = mapped_column(Integer, default=5)
    uses: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)

    def is_usable_at(self, at: float) -> bool:
        return at < self.expires_at and self.uses < self.max_uses


class Subscription(Base):
    """
    The family plan. Plan section 17.

    There is exactly one paid tier and it buys one thing: the Anubhav board.
    Everything else in the app is free and stays free, and the client never
    shows a paywall on any other screen — a lock on the check-in button would
    be a lock on the thing somebody installed the app to do.

    Billing runs through Play and the App Store; we hold a receipt reference and
    nothing else. A card number never reaches this database because a card
    number never reaches this server.
    """

    __tablename__ = "subscriptions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    payer_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    store: Mapped[str] = mapped_column(String(16))                 # play | app_store
    product_id: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16), default="none", index=True)
    renews_at: Mapped[float | None] = mapped_column(Float, index=True)
    # A handle from the store, not a payment instrument.
    receipt_ref: Mapped[str | None] = mapped_column(String(255))
    # The store's own transaction id, unique, so a refund notification from Play
    # can be matched to exactly one row. The store is the source of truth about
    # payment; this is only the key we reconcile on.
    store_transaction_id: Mapped[str | None] = mapped_column(String(160), unique=True)
    # Left until access ends. A user who cancels keeps what they paid for — the
    # Anubhav board is where their listings are, and it does not empty the
    # moment a card is not renewed.
    valid_until: Mapped[float | None] = mapped_column(Float, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)

    @property
    def is_active(self) -> bool:
        return self.status in ("active", "grace_period")


class SubscriptionSeat(Base):
    """
    One pensioner covered by a plan.

    Separate from the subscription because the payer and the covered person are
    different rows in `users`, and because revoking one family member must not
    touch the payment.

    `revoked_at` rather than a delete: the seat count has to stay auditable, or
    a person could add and remove family members indefinitely on one payment.
    A seat is claimed through a FamilyCode, never by sharing a login — a shared
    login means the previous holder of the phone number keeps this person's
    Anubhav board, and the Anubhav board holds their address.
    """

    __tablename__ = "subscription_seats"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    subscription_id: Mapped[int] = mapped_column(
        ForeignKey("subscriptions.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    added_at: Mapped[float] = mapped_column(Float, default=now)
    revoked_at: Mapped[float | None] = mapped_column(Float, index=True)

    __table_args__ = (UniqueConstraint("user_id", name="uq_seat_user"),)


class OtpChallenge(Base):
    """
    A phone OTP, in flight.

    The code is stored as a hash, never in the clear, and the row is deleted
    the moment it is redeemed or it expires — so a database dump does not hand
    over somebody's phone login.
    """

    __tablename__ = "otp_challenges"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    phone_e164: Mapped[str] = mapped_column(String(20), index=True)
    code_hash: Mapped[str] = mapped_column(String(64))
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    max_attempts: Mapped[int] = mapped_column(Integer, default=5)
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    redeemed_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=now)

    def is_valid_at(self, at: float) -> bool:
        return at < self.expires_at and self.redeemed_at is None and self.attempts < self.max_attempts


class RefreshToken(Base):
    """
    A refresh token, stored so it can be revoked.

    Revocation is the point. Plan section 14 requires a signed-out user to be
    signed out everywhere, and a stateless JWT cannot do that on its own.
    """

    __tablename__ = "refresh_tokens"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    device_id: Mapped[int | None] = mapped_column(
        ForeignKey("devices.id", ondelete="SET NULL")
    )
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    revoked_at: Mapped[float | None] = mapped_column(Float, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)
