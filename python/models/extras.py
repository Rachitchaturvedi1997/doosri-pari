# =============================================================================
# The rest of the schema — tables the plan needs that the first three passes did
# not create. Plan sections 5, 6.1, 6.5, 6.6, 6.8, 6.9, 6.11, 6.14, 6.15, 7.2,
# 12 and 17.
#
# Grouped here rather than scattered so the first-pass files stay as they were
# reviewed. Every table follows the existing conventions: epoch-float
# timestamps via `now`, JsonType for lists and maps, indexes on what the jobs
# and feeds actually filter by, and no personal identifier that plan section 14
# says must not reach the server (no full Aadhaar, PPO or bank number anywhere).
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
# Preferences, consent and notifications (6.15, 14)
# -----------------------------------------------------------------------------
class UserPrefs(Base):
    """
    Everything a person can switch on the Settings screen, in one row.

    Consents are stored with the moment they were given or withdrawn, because
    plan 14 wants separate consents for location, health data and guardian
    sharing, and a withdrawal has to be provable later.
    """

    __tablename__ = "user_prefs"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    # Plan 6.15 channels: safety cannot be turned off; the rest can.
    channels: Mapped[dict] = mapped_column(
        JsonType,
        default=lambda: {
            "safety": True, "medicines": True, "pension": True,
            "circles": True, "seva": True, "practice": True,
        },
    )
    digest_time: Mapped[str] = mapped_column(String(5), default="18:00")
    quiet_start: Mapped[int] = mapped_column(Integer, default=21)
    quiet_end: Mapped[int] = mapped_column(Integer, default=7)
    simple_mode: Mapped[bool] = mapped_column(Boolean, default=False)
    show_tithi: Mapped[bool] = mapped_column(Boolean, default=False)
    # Today (6.2): cards the user hid, and their last-14-days open counts.
    today_hidden: Mapped[list] = mapped_column(JsonType, default=list)
    today_opens: Mapped[dict] = mapped_column(JsonType, default=dict)
    # {"location": {"granted": true, "at": 171...}, "health": {...}, "guardians": {...}}
    consents: Mapped[dict] = mapped_column(JsonType, default=dict)
    # Open the emergency folder to guardians after an SOS (6.5).
    ice_after_sos: Mapped[bool] = mapped_column(Boolean, default=False)
    # Colleagues can find me: everyone | verified | nobody (6.6). Default verified.
    findable_by: Mapped[str] = mapped_column(String(12), default="verified")
    # Pension card (6.2). Only the DAY of the month the pension credits, and the
    # yes/no history of "did it credit?" - never an amount (plan 10, 14).
    pension_credit_day: Mapped[int] = mapped_column(Integer, default=1)
    credit_no_streak: Mapped[int] = mapped_column(Integer, default=0)
    credit_last_answer_day: Mapped[str | None] = mapped_column(String(10))
    # Family plan / simple mode extras.
    text_scale_set: Mapped[bool] = mapped_column(Boolean, default=False)
    updated_at: Mapped[float] = mapped_column(Float, default=now)


class PushInbox(Base):
    """
    The notification history the app shows in its bell screen.

    `notifications` records delivery attempts per channel (an operator's view);
    this is the *person's* view of what they were told, one row per message, so a
    push that failed to display can still be read in the app.
    """

    __tablename__ = "inbox"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(32), index=True)
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str | None] = mapped_column(String(400))
    payload: Mapped[dict] = mapped_column(JsonType, default=dict)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    read_at: Mapped[float | None] = mapped_column(Float)

    __table_args__ = (Index("ix_inbox_user_unread", "user_id", "read_at"),)


# -----------------------------------------------------------------------------
# Uploads (photos, voice notes, verification documents) — 6.6, 6.9
# -----------------------------------------------------------------------------
class Upload(Base):
    """
    An object the phone is uploading straight to storage.

    Created pending when the presigned URL is issued and marked ready when the
    client confirms; a pending row that never confirms is swept by the deletion
    job. `ref_*` says what it ended up attached to.
    """

    __tablename__ = "uploads"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    key: Mapped[str] = mapped_column(String(255), unique=True)
    purpose: Mapped[str] = mapped_column(String(24), index=True)
    content_type: Mapped[str] = mapped_column(String(64))
    size: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[str] = mapped_column(String(12), default="pending", index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    ready_at: Mapped[float | None] = mapped_column(Float)
    ref_type: Mapped[str | None] = mapped_column(String(16))
    ref_id: Mapped[int | None] = mapped_column(Integer)


# -----------------------------------------------------------------------------
# Vault: optional encrypted backup, share links, emergency folder (6.5)
# -----------------------------------------------------------------------------
class VaultBlob(Base):
    """
    Ciphertext the server cannot read.

    The phone encrypts with a passphrase the user chose; we hold bytes and a
    hash. If the passphrase is lost the blob is unrecoverable and the app says so
    (plan 6.5). `kind` is backup (the whole safe), share (one document, expires
    in 24h) or ice (the emergency folder).
    """

    __tablename__ = "vault_blobs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(8), index=True)
    key: Mapped[str] = mapped_column(String(255), unique=True)
    size: Mapped[int] = mapped_column(Integer, default=0)
    sha256: Mapped[str | None] = mapped_column(String(64))
    label: Mapped[str | None] = mapped_column(String(80))
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    expires_at: Mapped[float | None] = mapped_column(Float, index=True)


class VaultShareLink(Base):
    """A 24-hour link to one blob. Only the digest of the token is stored."""

    __tablename__ = "vault_share_links"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    blob_id: Mapped[int] = mapped_column(ForeignKey("vault_blobs.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    max_downloads: Mapped[int] = mapped_column(Integer, default=3)
    downloads: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class VaultIceRequest(Base):
    """
    A guardian asking to open the emergency folder.

    Pending until the owner approves, or until an SOS fires and the owner chose
    `ice_after_sos`. Denied or unanswered requests never open anything.
    """

    __tablename__ = "vault_ice_requests"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    guardian_id: Mapped[int] = mapped_column(ForeignKey("guardians.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(12), default="pending", index=True)
    reason: Mapped[str | None] = mapped_column(String(24))
    requested_at: Mapped[float] = mapped_column(Float, default=now)
    decided_at: Mapped[float | None] = mapped_column(Float)
    expires_at: Mapped[float | None] = mapped_column(Float)


# -----------------------------------------------------------------------------
# Payouts (6.8) — ids only, never a bank or UPI number
# -----------------------------------------------------------------------------
class PayoutAccount(Base):
    __tablename__ = "payout_accounts"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    contact_id: Mapped[str] = mapped_column(String(64))
    fund_account_id: Mapped[str] = mapped_column(String(64))
    method: Mapped[str] = mapped_column(String(8), default="upi")
    # "•••@okhdfc" style mask so the person recognises which account it is.
    display: Mapped[str | None] = mapped_column(String(40))
    created_at: Mapped[float] = mapped_column(Float, default=now)


# -----------------------------------------------------------------------------
# Messages and connections (5, 6.6, 6.8)
# -----------------------------------------------------------------------------
class Connection(Base):
    """"Send a namaste": a connection request between two people (6.6)."""

    __tablename__ = "connections"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    from_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    to_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    status: Mapped[str] = mapped_column(String(12), default="pending", index=True)
    note: Mapped[str | None] = mapped_column(String(160))
    created_at: Mapped[float] = mapped_column(Float, default=now)
    decided_at: Mapped[float | None] = mapped_column(Float)

    __table_args__ = (UniqueConstraint("from_id", "to_id", name="uq_connection_pair"),)


class Conversation(Base):
    __tablename__ = "conversations"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    kind: Mapped[str] = mapped_column(String(12), default="dm", index=True)
    # A stable key for a two-person conversation so it is found, not duplicated.
    pair_key: Mapped[str | None] = mapped_column(String(32), unique=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)
    last_message_at: Mapped[float | None] = mapped_column(Float, index=True)


class ConversationMember(Base):
    __tablename__ = "conversation_members"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    conversation_id: Mapped[int] = mapped_column(
        ForeignKey("conversations.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    last_read_at: Mapped[float] = mapped_column(Float, default=0.0)
    muted: Mapped[bool] = mapped_column(Boolean, default=False)

    __table_args__ = (UniqueConstraint("conversation_id", "user_id", name="uq_conv_member"),)


class ChatMessage(Base):
    __tablename__ = "chat_messages"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    conversation_id: Mapped[int] = mapped_column(
        ForeignKey("conversations.id", ondelete="CASCADE"), index=True
    )
    sender_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(8), default="text")
    text: Mapped[str | None] = mapped_column(Text)
    media_key: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    deleted_at: Mapped[float | None] = mapped_column(Float)
    hidden: Mapped[bool] = mapped_column(Boolean, default=False)

    __table_args__ = (Index("ix_chat_conv_created", "conversation_id", "created_at"),)


# -----------------------------------------------------------------------------
# Seekho (6.11)
# -----------------------------------------------------------------------------
class Lesson(Base):
    __tablename__ = "lessons"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    slug: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    title: Mapped[str] = mapped_column(String(160))
    summary: Mapped[str | None] = mapped_column(String(300))
    category: Mapped[str] = mapped_column(String(24), index=True)
    minutes: Mapped[int] = mapped_column(Integer, default=4)
    language: Mapped[str] = mapped_column(String(12), default="en", index=True)
    # [{"title": ..., "text": ..., "image_key": ...}] — big-type steps.
    steps: Mapped[list] = mapped_column(JsonType, default=list)
    # [{"q": ..., "options": [...], "answer": 1, "why": ...}] — the 3-question check.
    quiz: Mapped[list] = mapped_column(JsonType, default=list)
    badge: Mapped[str | None] = mapped_column(String(40))
    position: Mapped[int] = mapped_column(Integer, default=0, index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class LessonProgress(Base):
    __tablename__ = "lesson_progress"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    lesson_id: Mapped[int] = mapped_column(ForeignKey("lessons.id", ondelete="CASCADE"), index=True)
    started_at: Mapped[float] = mapped_column(Float, default=now)
    completed_at: Mapped[float | None] = mapped_column(Float)
    score: Mapped[int | None] = mapped_column(Integer)
    badge_awarded: Mapped[bool] = mapped_column(Boolean, default=False)

    __table_args__ = (UniqueConstraint("user_id", "lesson_id", name="uq_lesson_progress"),)


class LiveHelpRequest(Base):
    """"Teach me live": a 20-minute volunteer call (6.11)."""

    __tablename__ = "live_help_requests"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    topic: Mapped[str] = mapped_column(String(160))
    status: Mapped[str] = mapped_column(String(12), default="open", index=True)
    volunteer_id: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), index=True
    )
    preferred_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    matched_at: Mapped[float | None] = mapped_column(Float)
    completed_at: Mapped[float | None] = mapped_column(Float)


# -----------------------------------------------------------------------------
# Pension grievance helper (6.5)
# -----------------------------------------------------------------------------
class GrievanceCase(Base):
    """
    A grievance the person is filing themselves on the government portal.

    We draft the text and remember the registration number and follow-up dates;
    we never file on their behalf (plan 6.5 step 3). The draft is the person's
    own words about their own case, so it is kept only for as long as the case
    is open plus a short tail.
    """

    __tablename__ = "grievance_cases"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    topic: Mapped[str] = mapped_column(String(32), index=True)
    answers: Mapped[dict] = mapped_column(JsonType, default=dict)
    draft_en: Mapped[str | None] = mapped_column(Text)
    draft_local: Mapped[str | None] = mapped_column(Text)
    lang: Mapped[str] = mapped_column(String(12), default="en")
    registration_no: Mapped[str | None] = mapped_column(String(40))
    filed_at: Mapped[float | None] = mapped_column(Float)
    followup_30_at: Mapped[float | None] = mapped_column(Float, index=True)
    followup_60_at: Mapped[float | None] = mapped_column(Float, index=True)
    status: Mapped[str] = mapped_column(String(12), default="draft", index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)
    closed_at: Mapped[float | None] = mapped_column(Float)


# -----------------------------------------------------------------------------
# Teerth yatra planner (7.2 item 10)
# -----------------------------------------------------------------------------
class YatraPlace(Base):
    """Senior-friendly pilgrimage information. Content, curated, sourced."""

    __tablename__ = "yatra_places"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    slug: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(160))
    state_code: Mapped[str | None] = mapped_column(String(4), index=True)
    path: Mapped[str | None] = mapped_column(String(24), index=True)
    lat: Mapped[float | None] = mapped_column(Float)
    lng: Mapped[float | None] = mapped_column(Float)
    # {"lift": bool, "wheelchair": bool, "steps": "none|few|many", "doli": bool, "notes": str}
    accessibility: Mapped[dict] = mapped_column(JsonType, default=dict)
    best_season: Mapped[str | None] = mapped_column(String(80))
    summary: Mapped[str | None] = mapped_column(Text)
    official_url: Mapped[str | None] = mapped_column(String(500))
    booking_url: Mapped[str | None] = mapped_column(String(500))
    source_ref: Mapped[str | None] = mapped_column(String(300))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class YatraPlan(Base):
    __tablename__ = "yatra_plans"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    place_id: Mapped[int | None] = mapped_column(
        ForeignKey("yatra_places.id", ondelete="SET NULL"), index=True
    )
    title: Mapped[str] = mapped_column(String(160))
    starts_on: Mapped[float | None] = mapped_column(Float, index=True)
    ends_on: Mapped[float | None] = mapped_column(Float)
    # [{"text": "BP tablets for 10 days", "done": false, "group": "medicines"}]
    checklist: Mapped[list] = mapped_column(JsonType, default=list)
    # Pause the daily check-in for the trip and tell guardians (6.3).
    pause_checkin: Mapped[bool] = mapped_column(Boolean, default=False)
    share_with_family: Mapped[bool] = mapped_column(Boolean, default=False)
    status: Mapped[str] = mapped_column(String(12), default="planned", index=True)
    created_at: Mapped[float] = mapped_column(Float, default=now)


# -----------------------------------------------------------------------------
# Association workspace (17, 19 months 6-9)
# -----------------------------------------------------------------------------
class Association(Base):
    __tablename__ = "associations"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(160), index=True)
    reg_no: Mapped[str | None] = mapped_column(String(48), unique=True)
    circle_id: Mapped[int | None] = mapped_column(
        ForeignKey("circles.id", ondelete="SET NULL"), index=True
    )
    city_id: Mapped[int | None] = mapped_column(Integer, index=True)
    owner_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    verified_at: Mapped[float | None] = mapped_column(Float)
    plan: Mapped[str] = mapped_column(String(12), default="free")
    plan_valid_until: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=now)


class AssociationMember(Base):
    __tablename__ = "association_members"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    association_id: Mapped[int] = mapped_column(
        ForeignKey("associations.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    role: Mapped[str] = mapped_column(String(16), default="member")
    membership_no: Mapped[str | None] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(12), default="pending", index=True)
    approved_by: Mapped[int | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL")
    )
    approved_at: Mapped[float | None] = mapped_column(Float)
    dues_paid_until: Mapped[float | None] = mapped_column(Float)
    requested_at: Mapped[float] = mapped_column(Float, default=now)

    __table_args__ = (UniqueConstraint("association_id", "user_id", name="uq_assoc_member"),)


class AssociationNotice(Base):
    __tablename__ = "association_notices"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    association_id: Mapped[int] = mapped_column(
        ForeignKey("associations.id", ondelete="CASCADE"), index=True
    )
    posted_by: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str] = mapped_column(Text)
    pinned: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)


class AssociationDue(Base):
    """
    Recorded by an office-bearer. We do not collect association dues; the
    association does, and marks them here so members can see their standing.
    """

    __tablename__ = "association_dues"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    association_id: Mapped[int] = mapped_column(
        ForeignKey("associations.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    amount_paise: Mapped[int] = mapped_column(Integer, default=0)
    period_label: Mapped[str] = mapped_column(String(40))
    due_on: Mapped[float | None] = mapped_column(Float, index=True)
    paid_at: Mapped[float | None] = mapped_column(Float)
    recorded_by: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))


# -----------------------------------------------------------------------------
# Daily thought, radio, partner offers (6.14, 7.2, 17)
# -----------------------------------------------------------------------------
class DailyThought(Base):
    __tablename__ = "daily_thoughts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    text: Mapped[str] = mapped_column(Text)
    author: Mapped[str | None] = mapped_column(String(120))
    source_ref: Mapped[str | None] = mapped_column(String(300))
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    path: Mapped[str | None] = mapped_column(String(24), index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


class RadioStation(Base):
    __tablename__ = "radio_stations"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(120))
    kind: Mapped[str] = mapped_column(String(16), index=True)
    language: Mapped[str] = mapped_column(String(12), default="hi", index=True)
    region: Mapped[str | None] = mapped_column(String(64))
    stream_url: Mapped[str] = mapped_column(String(500))
    credit: Mapped[str | None] = mapped_column(String(160))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)
    position: Mapped[int] = mapped_column(Integer, default=0)


class PartnerOffer(Base):
    """A labelled "Partner offer" (17): at most one a day, never in Sahayak/Safety/Health."""

    __tablename__ = "partner_offers"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    partner_id: Mapped[int] = mapped_column(ForeignKey("partners.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(160))
    body: Mapped[str | None] = mapped_column(String(400))
    url: Mapped[str] = mapped_column(String(500))
    kind: Mapped[str] = mapped_column(String(16), default="health")
    starts_at: Mapped[float | None] = mapped_column(Float)
    ends_at: Mapped[float | None] = mapped_column(Float, index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, index=True)


# -----------------------------------------------------------------------------
# Operations
# -----------------------------------------------------------------------------
class JobRun(Base):
    """
    One execution of a background job, for the operator.

    Also the multi-process lock: a `running` row younger than the job's timeout
    means another worker has it, so two workers started by mistake do not both
    escalate the same check-in.
    """

    __tablename__ = "job_runs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    job: Mapped[str] = mapped_column(String(40), index=True)
    started_at: Mapped[float] = mapped_column(Float, default=now, index=True)
    finished_at: Mapped[float | None] = mapped_column(Float)
    status: Mapped[str] = mapped_column(String(10), default="running", index=True)
    result: Mapped[dict] = mapped_column(JsonType, default=dict)
    worker: Mapped[str | None] = mapped_column(String(64))


class DeviceHeartbeat(Base):
    """The phone said it is alive. Used for "phone may be off" on alerts (6.3)."""

    __tablename__ = "device_heartbeats"

    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    at: Mapped[float] = mapped_column(Float, default=now, index=True)
    battery_pct: Mapped[int | None] = mapped_column(Integer)
    app_version: Mapped[str | None] = mapped_column(String(32))


class EventPhoto(Base):
    """The shared album after an event (plan 6.6)."""

    __tablename__ = "event_photos"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    event_id: Mapped[int] = mapped_column(ForeignKey("events.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    media_key: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[float] = mapped_column(Float, default=now, index=True)


class SetupInvite(Base):
    """
    "Set up for my parent" (plan 6.1). A son or daughter fills in the profile,
    check-in settings and family list on their own phone and gets a 6-digit code
    (and a QR of it) valid for 24 hours; on the parent's phone, "Scan code from
    family" imports it. The parent only confirms the OTP.

    The code is stored as a keyed digest, not in the clear, and the row records
    who redeemed it so the same code cannot be used twice.
    """

    __tablename__ = "setup_invites"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    creator_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    payload: Mapped[dict] = mapped_column(JsonType, default=dict)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    expires_at: Mapped[float] = mapped_column(Float, index=True)
    redeemed_by: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"))
    redeemed_at: Mapped[float | None] = mapped_column(Float)
    created_at: Mapped[float] = mapped_column(Float, default=now)
