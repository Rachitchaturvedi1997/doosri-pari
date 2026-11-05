# =============================================================================
# Health — medicines, doses, vitals, reminders. Plan section 6.10 and 6.15.
#
# A dose is scheduled in two places, and neither may be the only copy: the
# phone's local notification (which fires with no network) and this table
# (which is what lets a guardian be told after two misses). `dose_logs` records
# both, and `DOSE_MISSES_BEFORE_ALERT` is the threshold.
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

from python.models.base import Base, JsonType, now


class Medicine(Base):
    """
    A medicine and when it is taken.

    `times` are local wall-clock times, not offsets. A pensioner takes a tablet
    at 8am whether or not the timezone database has heard of their district,
    and a schedule that shifted by an hour twice a year would be a schedule
    people stopped trusting.
    """

    __tablename__ = "medicines"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(120), index=True)
    # "5 mg", "1 tab" — both correct, and normalising them loses information.
    dose: Mapped[str] = mapped_column(String(40))
    # Minutes from local midnight, so a schedule can be queried for "before 9".
    times: Mapped[list] = mapped_column(JsonType, default=list)
    with_food: Mapped[bool] = mapped_column(Boolean, default=False)
    # Days remaining, from a count the user entered. There is no pharmacy feed.
    stock: Mapped[int] = mapped_column(Integer, default=0)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    # Set when a guardian is allowed to see this medicine at all.
    share_with_guardians: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (Index("ix_medicines_user_active", "user_id", "is_active"),)


class DoseLog(Base):
    """
    One scheduled dose and what happened to it.

    A row is created when the dose comes due, not when it is taken, so a dose
    that was never acknowledged exists as `missed` rather than as nothing. The
    two-miss guardian alert depends on that: a missing row would be
    indistinguishable from a dose that is still in the future.
    """

    __tablename__ = "dose_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    medicine_id: Mapped[int] = mapped_column(
        ForeignKey("medicines.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    due_at: Mapped[float] = mapped_column(Float, index=True)
    # The window in which taking it late still counts.
    due_until: Mapped[float] = mapped_column(Float, index=True)
    # taken | missed | skipped. Only `missed` counts towards the alert.
    status: Mapped[str] = mapped_column(String(16), default="missed", index=True)
    taken_at: Mapped[float | None] = mapped_column(Float)
    # The consecutive-miss counter, reset on any taken dose. Denormalised so
    # the five-minute job does not have to count a day's history each tick.
    consecutive_misses: Mapped[int] = mapped_column(Integer, default=0)
    # Set once guardians have been told, so a retried job cannot tell them twice.
    guardians_alerted_at: Mapped[float | None] = mapped_column(Float, index=True)

    __table_args__ = (
        UniqueConstraint("medicine_id", "due_at", name="uq_dose_medicine_due"),
    )


class Vital(Base):
    """
    A reading. `values` is a map because the readings differ by kind: blood
    pressure is a pair, pulse is a number. The allowed keys are fixed per kind
    in core-model's VitalKind, so the map cannot drift into free-form anything.
    """

    __tablename__ = "vitals"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(32), index=True)
    values: Mapped[dict] = mapped_column(JsonType, default=dict)
    unit: Mapped[str] = mapped_column(String(16))
    at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # True when it came from HealthKit / Health Connect rather than typed.
    from_health_platform: Mapped[bool] = mapped_column(Boolean, default=False)
    note: Mapped[str | None] = mapped_column(String(240))

    __table_args__ = (Index("ix_vitals_user_kind_at", "user_id", "kind", "at"),)


class Reminder(Base):
    """
    A server-scheduled reminder.

    Doses additionally live in the phone's local store; this copy is what lets a
    guardian be told, and what still fires if the app has been force-quit.
    `kind` decides whether the notification is allowed to be urgent at all
    (plan section 6.15) — a circle post that rings the phone gets every
    notification switched off, and the medicine reminder goes with it.
    """

    __tablename__ = "reminders"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(24), index=True)
    # What it is about, as a typed reference rather than free text.
    schedule: Mapped[dict] = mapped_column(JsonType, default=dict)
    next_at: Mapped[float] = mapped_column(Float, index=True)
    done_at: Mapped[float | None] = mapped_column(Float, index=True)
    # Set for a life certificate or an appointment, where a date matters.
    title: Mapped[str | None] = mapped_column(String(160))
    body: Mapped[str | None] = mapped_column(String(400))
    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (Index("ix_reminders_due", "next_at", "done_at"),)


class Facility(Base):
    """
    A CGHS or ECHS empanelled hospital.

    Seeded from a licensed dataset and refreshed by a job, not scraped live:
    a pensioner choosing where to go at 11pm should not be waiting on us to
    fetch a list.
    """

    __tablename__ = "facilities"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(200), index=True)
    address: Mapped[str | None] = mapped_column(Text)
    city_id: Mapped[int | None] = mapped_column(Integer, index=True)
    state_code: Mapped[str | None] = mapped_column(String(4), index=True)
    lat: Mapped[float] = mapped_column(Float, index=True)
    lng: Mapped[float] = mapped_column(Float, index=True)
    # ["CGHS", "ECHS", "Ayushman"]
    schemes: Mapped[list] = mapped_column(JsonType, default=list)
    phone: Mapped[str | None] = mapped_column(String(20))
    # How many beds, which is the question people actually ask.
    beds: Mapped[int | None] = mapped_column(Integer)
    updated_at: Mapped[float] = mapped_column(Float, default=now, index=True)
