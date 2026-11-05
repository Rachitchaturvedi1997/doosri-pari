# =============================================================================
# Content and safety. Plan sections 6.5, 6.12, 6.15 and 14.
#
# Three things share a file:
#   * stories — user content with a visibility rule;
#   * the scam checker — a corpus and the checks it decides on;
#   * the audit log and the safety queue — the two records the plan promises we
#     keep, and the two we can be held to.
#
# Subscriptions are NOT here. They live in identity.py beside FamilyCode, which
# is what they hang off: one payment covers up to six pensioners, and a seat is
# claimed with a family code. Splitting them across two files would have meant
# two mappers for one table.
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


# -----------------------------------------------------------------------------
# 6.5 — Baat aur Bhav (stories, prompts, the reading book)
# -----------------------------------------------------------------------------
class Story(Base):
    """
    A story someone recorded.

    `visibility` defaults to `only_me` in the client, and the server refuses
    anything else that a user did not explicitly choose. A story is the most
    exposed thing in the app: it is a voice, it is unedited, and its audience
    includes people this user has never met.
    """

    __tablename__ = "stories"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    author_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # Key into our object storage. Never a URL — a story outlives whatever
    # bucket it was uploaded to.
    media_key: Mapped[str] = mapped_column(String(255))
    duration_s: Mapped[int] = mapped_column(Integer, default=0)
    caption: Mapped[str | None] = mapped_column(String(300))
    # Transcribed, so a story can be moderated and so the reading book can
    # offer it as audio-plus-text to somebody who cannot hear it well.
    transcript: Mapped[str | None] = mapped_column(Text)
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    # circle | connections | only_me. Not a free string.
    visibility: Mapped[str] = mapped_column(String(16), default="only_me", index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # Expires by itself. A story is meant to be gone.
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    hidden: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    hidden_reason: Mapped[str | None] = mapped_column(String(120))
    # The author pinned this as a prompt for their own retelling.
    prompt_id: Mapped[int | None] = mapped_column(Integer, index=True)
    # How many people in the author's circle chose to retell it. This is the
    # only measure of success the feature has, and the reason it exists.
    remade_count: Mapped[int] = mapped_column(Integer, default=0, index=True)

    __table_args__ = (Index("ix_stories_feed", "visibility", "created_at"),)


class StoryPrompt(Base):
    """
    A question to answer: "What did your first salary buy?"
    """

    __tablename__ = "story_prompts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    text: Mapped[str] = mapped_column(String(300))
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    # A month of prompts, chosen by an editor.
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class StoryBook(Base):
    """
    Somebody's own stories, in order — a memory book they can hand to family.

    Private to the owner. It is not a feed and it is not shareable, because a
    person building a book out of their own voice is doing something
    particular and did not ask for it to become a profile.
    """

    __tablename__ = "story_books"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(160))
    # Story ids, in the order the user put them.
    story_ids: Mapped[list] = mapped_column(JsonType, default=list)
    created_at: Mapped[float] = mapped_column(Float, default=now)
    updated_at: Mapped[float] = mapped_column(Float, default=now, index=True)


# -----------------------------------------------------------------------------
# 6.12 — the scam checker, and the alerts it raises
# -----------------------------------------------------------------------------
class ScamCheckRequest(Base):
    """
    One "is this real?" check.

    The user pastes a message, or forwards a screenshot, and the job decides.
    What is stored is the text and the decision — never the screenshot, and
    never a contact from the message body. A scam is very often a request for
    an OTP or a bank detail, and a scam-checker that archived the scam would be
    the worst place in the app to have put it.
    """

    __tablename__ = "scam_check_requests"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    text: Mapped[str] = mapped_column(Text)
    # false | true | unknown. `unknown` is the honest answer far more often
    # than the two others, and the UI has to be able to show it.
    is_scam: Mapped[bool | None] = mapped_column(Boolean, index=True)
    category: Mapped[str | None] = mapped_column(String(32), index=True)
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    # The two or three markers that drove the decision, in words a person can
    # check. "It asked for your OTP" beats a score of 0.87.
    signals: Mapped[list] = mapped_column(JsonType, default=list)
    # An advisory from the safety team, matched to a pattern rather than to
    # this particular request.
    alert_id: Mapped[int | None] = mapped_column(
        ForeignKey("scam_alerts.id", ondelete="SET NULL"), index=True
    )
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)


class ScamAlert(Base):
    """
    A known scam, warned about before the user meets it.

    `pattern` is matched against, never executed, and the matching is done by
    the job that writes alerts. An admin writing an alert is writing a
    description of a fraud, not code that tries to catch one.
    """

    __tablename__ = "scam_alerts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    category: Mapped[str] = mapped_column(String(32), index=True)
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(Text)
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    # Words that give it away. Shown to the user as a checklist.
    markers: Mapped[list] = mapped_column(JsonType, default=list)
    # "gov.in", "rbi.org.in" — the domains involved, so we can say whether the
    # link in the message is the real one.
    domains: Mapped[list] = mapped_column(JsonType, default=list)
    # Which of the ten languages this alert has been written in. An alert with
    # no translation yet is not sent, because a warning in the wrong language
    # is worse than silence.
    severity: Mapped[str] = mapped_column(String(8), default="high", index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    expires_at: Mapped[float | None] = mapped_column(Float, index=True)


class NewsItem(Base):
    """
    A pension-relevant item, from an official source.

    `url` and `publisher` are non-null on purpose: the plan's position is that
    Sahayak answers with citations, and the same rule applies to the news
    strip. A pensioner reading "DA hiked" deserves to see who said so.
    """

    __tablename__ = "news_items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    title: Mapped[str] = mapped_column(String(300))
    summary: Mapped[str | None] = mapped_column(Text)
    publisher: Mapped[str] = mapped_column(String(120), index=True)
    url: Mapped[str] = mapped_column(String(500))
    language: Mapped[str] = mapped_column(String(12), default="en", index=True)
    # The tags decide which circles see it — a "Railway" item is not shown to
    # somebody who has never held a railway post.
    tags: Mapped[list] = mapped_column(JsonType, default=list)
    published_at: Mapped[float] = mapped_column(Float, index=True)
    fetched_at: Mapped[float] = mapped_column(Float, default=now)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)

    __table_args__ = (Index("ix_news_live", "is_active", "published_at"),)


# -----------------------------------------------------------------------------
# 14 — the record we promise to show
# -----------------------------------------------------------------------------
class AuditEntry(Base):
    """
    One line of "what happened, and when you asked to see it".

    `ip_hash` is a digest with a per-install salt, never an address. The
    endpoint that reads this table is the one the plan promises: a pensioner
    can ask what we hold and get a plain list, and the answer to "where did
    this log in from" is a hash, because an IP address identifies a household
    and sometimes a person, and it is not needed for anything we do.
    """

    __tablename__ = "audit_entries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    # read_profile | export_data | delete_account | guardian_added | ...
    action: Mapped[str] = mapped_column(String(48), index=True)
    # What was looked at, when it was a read rather than a write.
    resource: Mapped[str | None] = mapped_column(String(120), index=True)
    resource_id: Mapped[int | None] = mapped_column(Integer)
    at: Mapped[float] = mapped_column(Float, default=now, index=True)
    ip_hash: Mapped[str | None] = mapped_column(String(64), index=True)
    # Which admin performed it, when it was an admin acting on the user.
    actor_admin_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    detail: Mapped[dict] = mapped_column(JsonType, default=dict)

    __table_args__ = (Index("ix_audit_user_at", "user_id", "at"),)


# -----------------------------------------------------------------------------
# Safety triage — the queue a human works through. Plan section 12, `safety_scan`.
# -----------------------------------------------------------------------------
class SafetyQueueItem(Base):
    """
    Something the automated scan could not clear, waiting for a person.

    Automated moderation is for the obvious cases — a known scam script, a
    phone number already reported. Everything it is not sure about lands here,
    and the queue is finite on purpose: a backlog nobody reads is the same as
    no moderation, and the plan's 4-hour promise only means something if the
    queue is something we actually clear.
    """

    __tablename__ = "safety_queue"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    # post | comment | story | listing | message | user
    target_type: Mapped[str] = mapped_column(String(16), index=True)
    target_id: Mapped[int] = mapped_column(Integer, index=True)
    why: Mapped[str] = mapped_column(String(120), index=True)
    score: Mapped[float] = mapped_column(Float, default=0.0, index=True)
    status: Mapped[str] = mapped_column(String(16), default="open", index=True)
    assigned_to: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), index=True
    )
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    # The 4-hour promise. Null once handled.
    due_at: Mapped[float] = mapped_column(Float, index=True)
    resolved_at: Mapped[float | None] = mapped_column(Float, index=True)
    resolution: Mapped[str | None] = mapped_column(String(240))
