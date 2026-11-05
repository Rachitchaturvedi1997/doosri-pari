# =============================================================================
# Saathi circles. Plan section 6.6, and the voice rooms of 6.7.
#
# The design problem here is not networking, it is trust. A retired government
# employee will not post into a room of strangers. So circles are joinable by
# *who you are* rather than by invitation, the join rule is a column rather
# than a runtime branch, and moderation keeps a removed post rather than
# deleting it — for this audience a wrong removal does more damage than a
# wrong retention.
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


class Circle(Base):
    __tablename__ = "circles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    type: Mapped[str] = mapped_column(String(24), default="interest", index=True)
    name: Mapped[str] = mapped_column(String(120), index=True)
    department_id: Mapped[int | None] = mapped_column(
        ForeignKey("departments.id", ondelete="SET NULL"), index=True
    )
    city_id: Mapped[int | None] = mapped_column(Integer, index=True)
    join_rule: Mapped[str] = mapped_column(String(24), default="verified")
    created_by: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    # Denormalised so the feed can show "1,204 members" without a COUNT per
    # row. Kept correct by the join/leave path, not by a nightly job.
    member_count: Mapped[int] = mapped_column(Integer, default=0)
    description: Mapped[str | None] = mapped_column(Text)
    is_archived: Mapped[bool] = mapped_column(Boolean, default=False)
    # Plan 15: politics is allowed in general circles and never in the spiritual
    # ones (and never in pension news or Sahayak). A rule about the CIRCLE, not
    # about the words, so it is a column; the post check holds - never blocks -
    # anything that trips it, for a human to read.
    no_politics: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class CircleMember(Base):
    __tablename__ = "circle_members"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    circle_id: Mapped[int] = mapped_column(
        ForeignKey("circles.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(16), default="member")
    joined_at: Mapped[float] = mapped_column(Float, default=now)
    muted: Mapped[bool] = mapped_column(Boolean, default=False)
    # Set while an Approval-rule join waits for a member to say yes.
    pending_at: Mapped[float | None] = mapped_column(Float)

    __table_args__ = (UniqueConstraint("circle_id", "user_id", name="uq_circle_member"),)


class Post(Base):
    __tablename__ = "posts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    circle_id: Mapped[int] = mapped_column(
        ForeignKey("circles.id", ondelete="CASCADE"), index=True
    )
    author_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(16), default="text")
    text: Mapped[str | None] = mapped_column(Text)
    media: Mapped[list] = mapped_column(JsonType, default=list)
    # A voice note is transcribed before it can be reported on (plan 6.6), so
    # moderation has something to read.
    transcript: Mapped[str | None] = mapped_column(Text)
    poll: Mapped[dict | None] = mapped_column(JsonType)
    # Kept for a News post so the feed can show where it came from.
    source_url: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # Hidden, not deleted. A moderator can see what was pulled and why.
    hidden: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    hidden_reason: Mapped[str | None] = mapped_column(String(120))
    report_count: Mapped[int] = mapped_column(Integer, default=0)
    comment_count: Mapped[int] = mapped_column(Integer, default=0)
    # Pinned by a moderator — the pension-notice slot on a circle.
    is_pinned: Mapped[bool] = mapped_column(Boolean, default=False, index=True)

    __table_args__ = (Index("ix_posts_circle_created", "circle_id", "created_at"),)


class Comment(Base):
    __tablename__ = "comments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    post_id: Mapped[int] = mapped_column(ForeignKey("posts.id", ondelete="CASCADE"), index=True)
    author_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    text: Mapped[str] = mapped_column(Text)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    hidden: Mapped[bool] = mapped_column(Boolean, default=False)
    report_count: Mapped[int] = mapped_column(Integer, default=0)


class Reaction(Base):
    __tablename__ = "reactions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    target_type: Mapped[str] = mapped_column(String(16), default="post")
    target_id: Mapped[int] = mapped_column(Integer, index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # A small closed set on purpose. Plan section 4.2 asks for a calm, minimal
    # interface; an emoji picker is the fastest way to lose that.
    kind: Mapped[str] = mapped_column(String(16), default="thanks")
    created_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (
        UniqueConstraint("target_type", "target_id", "user_id", name="uq_reaction"),
    )


class Report(Base):
    """
    Somebody reporting something.

    `reason` in URGENT_REPORT_REASONS goes to a human immediately; the rest
    queue. An unactionable report teaches people that reporting does nothing,
    which is why "something else" is not an option.
    """

    __tablename__ = "reports"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    target_type: Mapped[str] = mapped_column(String(16), index=True)
    target_id: Mapped[int] = mapped_column(Integer, index=True)
    reporter_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    reason: Mapped[str] = mapped_column(String(24), index=True)
    status: Mapped[str] = mapped_column(String(16), default="open", index=True)
    # What the reporter saw, so a moderator can judge without guessing.
    context: Mapped[dict] = mapped_column(JsonType, default=dict)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    actioned_at: Mapped[float | None] = mapped_column(Float)
    actioned_by: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )

    __table_args__ = (Index("ix_reports_open_urgent", "status", "reason"),)


class CircleEvent(Base):
    __tablename__ = "events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    circle_id: Mapped[int] = mapped_column(
        ForeignKey("circles.id", ondelete="CASCADE"), index=True
    )
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(String(160))
    # Free text, because a meeting place in Lucknow has no coordinates worth
    # storing and "Nehru Park, Gate 2" is more useful to the person going.
    place: Mapped[str | None] = mapped_column(String(240))
    place_json: Mapped[dict | None] = mapped_column(JsonType)
    starts_at: Mapped[float] = mapped_column(Float, index=True)
    ends_at: Mapped[float | None] = mapped_column(Float)
    cost: Mapped[float] = mapped_column(Float, default=0.0)
    capacity: Mapped[int | None] = mapped_column(Integer)
    going_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class Rsvp(Base):
    __tablename__ = "rsvps"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    event_id: Mapped[int] = mapped_column(ForeignKey("events.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(16), default="going")
    at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (UniqueConstraint("event_id", "user_id", name="uq_rsvp"),)


class VoiceRoom(Base):
    """
    Chai pe Charcha. Plan section 6.7.

    A room borrows a circle but is not one. That distinction is the whole
    consent model: joining a room does not join the circle, and leaving does
    not leave either. Recording is off unless `recording_allowed`, and even
    then RoomSession.consented_for_recording has to cover the participants.
    """

    __tablename__ = "rooms"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    circle_id: Mapped[int] = mapped_column(
        ForeignKey("circles.id", ondelete="CASCADE"), index=True
    )
    title: Mapped[str] = mapped_column(String(160))
    host_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    # An RRULE, e.g. "FREQ=WEEKLY;BYDAY=TU,TH".
    schedule_rrule: Mapped[str | None] = mapped_column(String(120))
    next_session_at: Mapped[float | None] = mapped_column(Float, index=True)
    # Off unless a moderator turns it on. Default-deny for anything that
    # captures people's voices.
    recording: Mapped[bool] = mapped_column(Boolean, default=False)
    # The LiveKit room this maps onto. Filled when a session actually starts;
    # a scheduled room has no token room yet.
    livekit_room: Mapped[str | None] = mapped_column(String(120))
    created_at: Mapped[float] = mapped_column(Float, default=now)


class RoomSession(Base):
    """
    One sitting of a voice room.

    `consented_for_recording` is a count, and a recording is only kept when it
    covers the participants. A recording of one person who said no cannot be
    undone by deleting the file later, because it was already distributed.
    """

    __tablename__ = "room_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    room_id: Mapped[int] = mapped_column(ForeignKey("rooms.id", ondelete="CASCADE"), index=True)
    started_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    ended_at: Mapped[float | None] = mapped_column(Float, index=True)
    peak_listeners: Mapped[int] = mapped_column(Integer, default=0)
    consented_for_recording: Mapped[int] = mapped_column(Integer, default=0)
    is_recorded: Mapped[bool] = mapped_column(Boolean, default=False)
    # Where the recording lives, if one was kept.
    recording_key: Mapped[str | None] = mapped_column(String(255))

    @property
    def may_keep_recording(self) -> bool:
        return self.consented_for_recording > 0
