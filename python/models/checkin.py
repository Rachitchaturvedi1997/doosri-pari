# =============================================================================
# Check-in and SOS. Plan section 6.3.
#
# These tables are the ones that decide when a family gets woken up at 3am, so
# they are the ones written first and tested hardest. The stage strings and the
# timings are pinned to the Kotlin machine by python/tests/test_parity.py —
# if you retime a stage on one side and not the other, that test fails.
# =============================================================================
from __future__ import annotations

from sqlalchemy import (
    JSON,
    Boolean,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from python.models.base import Base, JsonType, now


class CheckInSettings(Base):
    """One row per user. Plan section 6.3, defaults 09:00 for three hours."""

    __tablename__ = "checkin_settings"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    # Local time the window opens, "HH:MM" in the user's own timezone.
    window_start: Mapped[str] = mapped_column(String(5), default="09:00")
    window_mins: Mapped[int] = mapped_column(Integer, default=180)
    passive: Mapped[bool] = mapped_column(Boolean, default=False)
    # Only sent to guardians when the user opted in (plan 6.3 / 14).
    share_location: Mapped[bool] = mapped_column(Boolean, default=False)
    paused_until: Mapped[float | None] = mapped_column(Float, index=True)

    user: Mapped["User"] = relationship(back_populates="settings")  # noqa: F821


class CheckIn(Base):
    """
    One row per user per day. The escalation job walks every non-terminal row
    once a minute, and the state machine lives in the shared KMP module so the
    phone and the server cannot disagree about it.
    """

    __tablename__ = "checkins"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Local calendar day, "YYYY-MM-DD" in the user's timezone.
    day: Mapped[str] = mapped_column(String(10), index=True)
    stage: Mapped[str] = mapped_column(String(24), default="Waiting", index=True)
    # Every timeout in the machine is relative to this.
    stage_entered_at: Mapped[float] = mapped_column(Float)
    call_attempts: Mapped[int] = mapped_column(Integer, default=0)
    last_call_at: Mapped[float | None] = mapped_column(Float)
    checked_at: Mapped[float | None] = mapped_column(Float)
    method: Mapped[str | None] = mapped_column(String(16))
    # Set when a guardian taps "I've reached them" or a helper confirms.
    resolved_by: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    resolved_at: Mapped[float | None] = mapped_column(Float)
    # The full audit trail. Plan section 6.3: every step is logged and shown
    # to the user as history.
    events: Mapped[list] = mapped_column(JsonType, default=list)
    # Only present when share_location is on.
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    location_accuracy: Mapped[float | None] = mapped_column(Float)
    # Set the moment guardians are told, so the job does not re-alert on a
    # retry after a partial dispatch failure.
    guardians_alerted_at: Mapped[float | None] = mapped_column(Float, index=True)
    helpers_alerted_at: Mapped[float | None] = mapped_column(Float, index=True)

    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (UniqueConstraint("user_id", "day", name="uq_checkin_user_day"),)


class CheckInPause(Base):
    """
    A travel or hospital pause, as its own record.

    Pausing is not the same as muting. A muted user still escalates and is
    simply not told about it; a paused user does not escalate at all. Keeping
    them as one boolean was the reason a pause could be implemented as a mute
    by accident, which is a safety bug wearing a settings bug's clothes.
    """

    __tablename__ = "checkin_pauses"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    starts_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    ends_at: Mapped[float] = mapped_column(Float, index=True)
    reason: Mapped[str | None] = mapped_column(String(120))
    # Guardians are told about a pause (plan 6.3) — a fortnight in hospital is
    # exactly the case where silence would otherwise be alarming.
    guardians_notified: Mapped[bool] = mapped_column(Boolean, default=False)

    def is_active_at(self, at: float) -> bool:
        return self.starts_at <= at < self.ends_at


class SosEvent(Base):
    """
    SOS is a sibling of check-in, not a stage of it: pressing SOS is always
    immediate and never waits for a window (plan section 6.3).
    """

    __tablename__ = "sos_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    started_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # The 5-second countdown gives a false press time to back out.
    cancelled: Mapped[bool] = mapped_column(Boolean, default=False)
    resolved_at: Mapped[float | None] = mapped_column(Float)
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    accuracy: Mapped[float | None] = mapped_column(Float)
    # Per-channel delivery result: push, SMS, WhatsApp, call. A channel that
    # failed is recorded as failed rather than omitted, because a guardian who
    # was not reached needs to see that they were not.
    delivered: Mapped[dict] = mapped_column(JsonType, default=dict)
    # The sos_dispatch job retries an event that reached nobody (plan 12: "3
    # retries"). These two make that retry countable and spaced.
    dispatch_attempts: Mapped[int] = mapped_column(Integer, default=0)
    last_attempt_at: Mapped[float | None] = mapped_column(Float, index=True)
    # The fan-out reached at least one recipient. Set once, never cleared.
    reached_someone_at: Mapped[float | None] = mapped_column(Float)


class Helper(Base):
    """
    A nearby person who can be told as a last resort. Plan section 6.3.

    Distinct from a Guardian on purpose: helpers are opted in to be *sent to*
    somebody, which is a different bargain from agreeing to *receive* alerts.
    They are not family and do not get the document safe.
    """

    __tablename__ = "helpers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(80))
    phone: Mapped[str] = mapped_column(String(20))
    # The helper's own last known position, refreshed when they use the app.
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    last_seen_at: Mapped[float | None] = mapped_column(Float, index=True)
    # How far they are willing to travel, in km.
    radius_km: Mapped[float] = mapped_column(Float, default=5.0)
    accepted_at: Mapped[float | None] = mapped_column(Float)

    __table_args__ = (UniqueConstraint("user_id", "phone", name="uq_helper_phone"),)

    @property
    def is_active(self) -> bool:
        return self.accepted_at is not None


class GuardianAck(Base):
    """
    "I have reached them." Plan section 6.3.

    Kept as a row rather than a flag on the check-in so that two guardians
    tapping at once is recorded rather than raced, and so the timeline can show
    who actually turned up.
    """

    __tablename__ = "guardian_acks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    checkin_id: Mapped[int] = mapped_column(
        ForeignKey("checkins.id", ondelete="CASCADE"), index=True
    )
    guardian_id: Mapped[int] = mapped_column(ForeignKey("guardians.id", ondelete="CASCADE"))
    at: Mapped[float] = mapped_column(Float, default=now)
    note: Mapped[str | None] = mapped_column(String(240))


class Notification(Base):
    """
    Everything the app pushed, SMS'd or called, and whether it landed.

    Plan section 21 risk 2: a channel can fail, and the user has to be able to
    see that it did. A notification that silently vanished is worse than one
    that failed loudly, because the whole design assumes somebody is watching.
    """

    __tablename__ = "notifications"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    checkin_id: Mapped[int | None] = mapped_column(
        ForeignKey("checkins.id", ondelete="CASCADE"), index=True
    )
    # Who it went to, when it went to a guardian rather than to the user. Null
    # for a self-notification. Without this column a guardian asking "was I
    # told?" can only be answered about the family as a group, and the one
    # person who needs to know is the one who was not reached.
    guardian_id: Mapped[int | None] = mapped_column(
        ForeignKey("guardians.id", ondelete="CASCADE"), index=True
    )
    channel: Mapped[str] = mapped_column(String(16), index=True)
    action: Mapped[str] = mapped_column(String(32))
    # queued | sent | failed. A separate value rather than inferring from
    # sent_at being null, because "we have not tried yet" and "we tried and it
    # failed" are different states and the retry logic has to tell them apart.
    status: Mapped[str] = mapped_column(String(12), default="sent", index=True)
    # What was said. Kept so the notification history on the phone can show the
    # real message rather than reconstructing it from the stage name — a family
    # reading "Sharma ji has not checked in" and "there is no response" are
    # told different things by the same stage.
    title: Mapped[str | None] = mapped_column(String(160))
    body: Mapped[str | None] = mapped_column(String(400))
    sent_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    delivered_at: Mapped[float | None] = mapped_column(Float)
    # A failure is stored, not dropped. "push failed, sms sent" is the state a
    # guardian needs when they ask why they only got one message.
    error: Mapped[str | None] = mapped_column(String(240))
    # The provider's own id, for reconciling with a delivery receipt later.
    provider_id: Mapped[str | None] = mapped_column(String(160))
    # Guards against a retried job sending the same thing twice. The row is
    # written BEFORE the send, not after, so a crash mid-send leaves evidence
    # rather than a gap.
    idempotency_key: Mapped[str] = mapped_column(String(96), unique=True, index=True)

    __table_args__ = (Index("ix_notifications_checkin_guardian", "checkin_id", "guardian_id"),)


# Handy for the escalation job's query and for the guardian dashboard.
Index("ix_checkins_open", CheckIn.stage, CheckIn.user_id)
