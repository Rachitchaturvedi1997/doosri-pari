# =============================================================================
# Yog Kendra and Adhyatm Kendra. Plan section 7.
#
# Two genuinely different things under one name, and this file keeps them
# apart. `kendra_profiles` and everything below it is EXERCISE, and it carries
# safety flags because a fall is a real risk for this audience. `spiritual_state`
# is PRACTICE, and it carries no flags, no doctor note and no safety check —
# borrowing the exercise side's language for it would be wrong and insulting.
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


class KendraProfile(Base):
    """
    A practitioner's profile and the safety flags that gate their sessions.

    `flags` is the whole reason this table exists. A pose that requires standing
    is not offered to somebody with `fall_risk`, and a programme is checked
    against these before it appears on the Today screen — not after somebody has
    attempted the movement.
    """

    __tablename__ = "kendra_profiles"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    flags: Mapped[list] = mapped_column(JsonType, default=list)
    doctor_note: Mapped[str | None] = mapped_column(Text)
    # "dadi" (grandmother, unhurried, seated) or "dada". The same pose is taught
    # quite differently by each, and the difference is the difference between
    # content that lands and content that does not.
    instructor: Mapped[str | None] = mapped_column(String(8))
    mirror: Mapped[bool] = mapped_column(Boolean, default=True)
    voice_langs: Mapped[list] = mapped_column(JsonType, default=list)

    user: Mapped["User"] = relationship(back_populates="kendra_profile")  # noqa: F821

    @property
    def most_restrictive(self) -> str:
        from python.models.base import SAFETY_FLAG_SEVERITY

        if not self.flags:
            return "none"
        return max(self.flags, key=lambda f: SAFETY_FLAG_SEVERITY.get(f, 0))

    @property
    def needs_doctor_note(self) -> bool:
        """True when a flag cannot be worked around with a modified movement."""
        from python.models.base import MODIFIABLE_SAFETY_FLAGS

        return any(f not in MODIFIABLE_SAFETY_FLAGS for f in (self.flags or []))


class KendraProgramme(Base):
    """
    A programme: chair yoga, mobility, pranayama, the safety check.

    `targets` lists the flags this programme is designed FOR, and a programme
    is withheld only when it targets a flag that cannot be worked around. Chair
    yoga is therefore offered to everybody, including somebody at fall risk —
    sitting down is the safest thing in the building, and saying otherwise
    would keep the people who most need to move from moving.
    """

    __tablename__ = "kendra_programmes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    slug: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    title: Mapped[str] = mapped_column(String(160))
    description: Mapped[str | None] = mapped_column(Text)
    kind: Mapped[str] = mapped_column(String(24), index=True)
    total_minutes: Mapped[int] = mapped_column(Integer, default=10)
    # The poses, in order. Kept as a JSON list of ids resolved against `poses`
    # so reordering a programme is one write rather than a join table.
    pose_ids: Mapped[list] = mapped_column(JsonType, default=list)
    targets: Mapped[list] = mapped_column(JsonType, default=list)
    languages: Mapped[list] = mapped_column(JsonType, default=list)
    instructor: Mapped[str | None] = mapped_column(String(8))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class Pose(Base):
    """
    One movement.

    `contraindications` is checked BEFORE the pose is offered, and
    `modification` is what decides whether it is withheld or adapted. A pose
    with a modification is offered modified rather than removed, because the
    plan's argument is that a 68-year-old should still be able to do a forward
    bend in some form.
    """

    __tablename__ = "poses"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    slug: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(120))
    # In plain words, in the user's language. Not Sanskrit.
    instruction: Mapped[str] = mapped_column(Text)
    hold_seconds: Mapped[int | None] = mapped_column(Integer)
    reps: Mapped[int | None] = mapped_column(Integer)
    requires_standing: Mapped[bool] = mapped_column(Boolean, default=False)
    requires_floor: Mapped[bool] = mapped_column(Boolean, default=False)
    contraindications: Mapped[list] = mapped_column(JsonType, default=list)
    modification: Mapped[str | None] = mapped_column(Text)
    breathing_cue: Mapped[str | None] = mapped_column(String(240))
    # The drawn instruction, from ui-canvas.
    illustration_key: Mapped[str | None] = mapped_column(String(120))


class KendraSession(Base):
    __tablename__ = "kendra_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    programme_id: Mapped[int] = mapped_column(
        ForeignKey("kendra_programmes.id", ondelete="CASCADE"), index=True
    )
    started_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    minutes: Mapped[int] = mapped_column(Integer, default=0)
    completed: Mapped[bool] = mapped_column(Boolean, default=False)
    mobility_check_id: Mapped[int | None] = mapped_column(
        ForeignKey("mobility_checks.id", ondelete="SET NULL")
    )
    # Which flags were in force during the session, so a later change to a
    # profile does not rewrite what was actually offered.
    flags_at_session: Mapped[list] = mapped_column(JsonType, default=list)


class MobilityCheck(Base):
    """
    Chair stands in 30 seconds, and a single-leg hold.

    These two numbers are what a doctor asks for, and they are what the
    families' monthly report is built from (plan section 19) — shared only with
    permission, which is why the report is a separate act and not a default.
    """

    __tablename__ = "mobility_checks"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    chair_stands_30s: Mapped[int] = mapped_column(Integer)
    single_leg_secs: Mapped[int] = mapped_column(Integer)
    at: Mapped[float] = mapped_column(Float, default=now, index=True)

    @property
    def is_at_fall_risk(self) -> bool:
        return self.chair_stands_30s < 8 or self.single_leg_secs < 5


class YogaPair(Base):
    """
    Two people who work out together.

    Matched rather than chosen, and only between two people who have both opted
    in — the plan treats a yoga partner as a real arrangement between two
    people who expect to turn up.
    """

    __tablename__ = "yoga_pairs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_a: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    user_b: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)
    ended_at: Mapped[float | None] = mapped_column(Float, index=True)

    __table_args__ = (Index("ix_yoga_pairs_b_active", "user_b", "ended_at"),)


class SpiritualState(Base):
    """
    The user's own practice, saved across sessions.

    This is a RECORD of what somebody already does. The app does not instruct,
    correct or claim, and no spiritual content is authored here at all. The
    row holds the user's choices and nothing else — which is why it carries no
    safety flags, no doctor note and no moderation state, unlike everything
    above it in this file.
    """

    __tablename__ = "spiritual_state"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    paths: Mapped[list] = mapped_column(JsonType, default=list)
    deity: Mapped[str | None] = mapped_column(String(80))
    japa: Mapped[dict | None] = mapped_column(JsonType)
    sankalp: Mapped[dict | None] = mapped_column(JsonType)
    saved: Mapped[list] = mapped_column(JsonType, default=list)
    festivals_region: Mapped[str | None] = mapped_column(String(64), index=True)

    user: Mapped["User"] = relationship(back_populates="spiritual_state")  # noqa: F821


class SpiritualContent(Base):
    """
    A text, a sung aarti, a festival reading.

    Content, and it is moderated like any other content. Two fields matter for
    correctness rather than moderation: `source_ref` so a user can check where a
    text came from, and `audio_key` rather than a URL, because a sung aarti is
    licensed and is served from storage we control.
    """

    __tablename__ = "spiritual_content"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    kind: Mapped[str] = mapped_column(String(32), index=True)
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str | None] = mapped_column(Text)
    audio_key: Mapped[str | None] = mapped_column(String(255))
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    # Some traditions distinguish their recitations; not all content is
    # interchangeable across them.
    path: Mapped[str | None] = mapped_column(String(24), index=True)
    source_ref: Mapped[str | None] = mapped_column(String(300))
    duration_s: Mapped[int | None] = mapped_column(Integer)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class Festival(Base):
    """
    A festival or vrat date, loaded from a licensed source.

    Never computed here. A lunar calendar is not something to reimplement, and a
    calendar that is confidently wrong by a day is worse than none at all for
    somebody keeping a fast — so the job loads dates and this table only stores
    what it was given.
    """

    __tablename__ = "festivals"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(160), index=True)
    kind: Mapped[str] = mapped_column(String(24), default="festival", index=True)
    starts_on: Mapped[float] = mapped_column(Float, index=True)
    ends_on: Mapped[float | None] = mapped_column(Float)
    # Null means "everywhere". Regional variation is the normal case, not an
    # exception, so most rows have one.
    region: Mapped[str | None] = mapped_column(String(64), index=True)
    languages: Mapped[list] = mapped_column(JsonType, default=list)
    # The year the row is for, so a year's calendar can be replaced wholesale.
    year: Mapped[int] = mapped_column(Integer, index=True)
    source_key: Mapped[str | None] = mapped_column(String(120))

    __table_args__ = (UniqueConstraint("name", "year", "region", name="uq_festival"),)
