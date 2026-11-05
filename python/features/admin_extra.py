# =============================================================================
# Feature · The rest of the admin console and the volunteer desk — plan 6.4, 15, 17.
#
#   GET  /admin/safety-queue                   what automatic checks could not clear
#   POST /admin/safety-queue/{id}/resolve      approve a listing, hide a post, settle a dispute
#   GET  /admin/users/{id}                     the narrow view a moderator needs
#   POST /admin/users/{id}/strike              warning -> 7-day pause -> 30-day pause -> removal
#   GET  /admin/verify/{id}/document           the document, briefly, and only with a logged reason
#   GET|POST|PATCH|DELETE /admin/content/{kind}     radio, thoughts, offers, lessons, places, ...
#   GET|POST /admin/commutation-table
#   GET  /admin/associations   POST /admin/associations/{id}/verify
#   GET  /admin/volunteer-tickets   POST .../{id}/promote
#   GET  /volunteer/tickets   POST /volunteer/tickets/{id}/claim | answer     (T2 volunteers)
#   GET  /sahayak/tickets                      the person's own volunteer questions and answers
#
# Same discipline as features/admin.py: the narrowest view that lets the job be
# done, every state change audited in the same transaction, and no admin decides
# a matter that is about themselves. A pensioner's phone, address, medicines and
# pension figures are theirs whoever is reading the console.
# =============================================================================
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, inspect as sa_inspect, select
from sqlalchemy.exc import DataError, IntegrityError
from sqlalchemy.orm import Session

from python.deps import AdminUser, CurrentUser, DbSession, get_db, revoke_family
from python.dispatch import notify_user
from python.features.admin import audit, not_self, untrusted
from python.features.payments import refund_booking
from python.features.sahayak import _user_hash
from python.integrations import storage
from python.models import (
    TIER_RANK,
    Association,
    Booking,
    CommutationTable,
    Comment,
    DailyThought,
    Department,
    Facility,
    KendraProgramme,
    Lesson,
    Listing,
    OfflineFaq,
    PartnerOffer,
    Pose,
    Post,
    RadioStation,
    Report,
    SafetyQueueItem,
    SahayakDoc,
    SahayakQuestion,
    ScamAlert,
    Story,
    StoryPrompt,
    User,
    Verification,
    VolunteerTicket,
    YatraPlace,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.admin.extra")
router = APIRouter(tags=["admin"])

DOC_VIEW_SECONDS = 600


# =============================================================================
# The safety queue
# =============================================================================
class ResolveIn(BaseModel):
    action: str
    note: str | None = Field(default=None, max_length=240)


_ACTIONS = {
    "listing": ("approve", "reject"),
    "post": ("hide", "unhide", "dismiss"),
    "comment": ("hide", "dismiss"),
    "story": ("hide", "dismiss"),
    "booking": ("release", "refund", "dismiss"),
}


def _queue_view(q: SafetyQueueItem) -> dict:
    return {
        "id": q.id, "target_type": q.target_type, "target_id": q.target_id, "why": q.why, "score": q.score,
        "status": q.status, "created_at": q.created_at, "due_at": q.due_at, "overdue": q.status == "open" and q.due_at < now(),
        "actions": _ACTIONS.get(q.target_type, ("dismiss",)),
    }


@router.get("/admin/safety-queue")
def safety_queue(admin: AdminUser, db: Session = Depends(get_db), status: str = "open", target_type: str | None = None, limit: int = 50):
    if not 1 <= limit <= 200:
        raise HTTPException(400, "limit must be 1..200")
    q = select(SafetyQueueItem).where(SafetyQueueItem.status == status)
    if target_type:
        q = q.where(SafetyQueueItem.target_type == target_type)
    # Most overdue first: the plan's response-time promise is what the order serves.
    rows = db.scalars(q.order_by(SafetyQueueItem.due_at).limit(limit)).all()
    return {"items": [_queue_view(r) for r in rows]}


@router.get("/admin/safety-queue/{item_id}")
def safety_item(item_id: int, admin: AdminUser, db: Session = Depends(get_db)):
    """
    The reported thing itself - and only that. A listing's title and body, a post's
    text, a story's transcript, a booking's dispute reason. Never the author's
    phone or coordinates. The text is somebody's untrusted words, returned as data.
    """
    q = db.get(SafetyQueueItem, item_id)
    if q is None:
        raise HTTPException(404, "not found")
    subject: dict = {}
    if q.target_type == "listing":
        l = db.get(Listing, q.target_id)
        if l:
            subject = {"title": untrusted(l.title, 200), "body": untrusted(l.body, 2000), "kind": l.type, "mode": l.mode,
                       "status": l.status, "author_id": l.owner_id}
    elif q.target_type == "post":
        p = db.get(Post, q.target_id)
        if p:
            subject = {"text": untrusted(p.text, 2000), "transcript": untrusted(p.transcript, 2000), "hidden": p.hidden,
                       "author_id": p.author_id}
    elif q.target_type == "comment":
        c = db.get(Comment, q.target_id)
        if c:
            subject = {"text": untrusted(c.text, 1000), "hidden": c.hidden, "author_id": c.author_id}
    elif q.target_type == "story":
        s = db.get(Story, q.target_id)
        if s:
            subject = {"transcript": untrusted(s.transcript, 2000), "hidden": s.hidden, "author_id": s.author_id}
    elif q.target_type == "booking":
        b = db.get(Booking, q.target_id)
        if b:
            subject = {"status": b.status, "amount_paise": int(round((b.amount or 0) * 100)), "paid": bool(b.payment_id),
                       "payout_done": bool(b.payout_id)}
    return {**_queue_view(q), "subject": subject}


@router.post("/admin/safety-queue/{item_id}/resolve")
def resolve_item(item_id: int, body: ResolveIn, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    limit_write(request, admin.id)
    q = db.get(SafetyQueueItem, item_id)
    if q is None:
        raise HTTPException(404, "not found")
    if q.status != "open":
        raise HTTPException(409, f"already {q.status}")
    allowed = _ACTIONS.get(q.target_type, ("dismiss",))
    if body.action not in allowed:
        raise HTTPException(400, f"action must be one of {', '.join(allowed)}")

    owner_id = None
    before = after = None
    if q.target_type == "listing":
        l = db.get(Listing, q.target_id)
        if l is None:
            raise HTTPException(404, "the listing is gone")
        owner_id = l.owner_id
        not_self(admin, owner_id, "listing")
        before = {"status": l.status}
        l.status = "open" if body.action == "approve" else "removed"
        after = {"status": l.status}
    elif q.target_type == "post":
        p = db.get(Post, q.target_id)
        if p is None:
            raise HTTPException(404, "the post is gone")
        owner_id = p.author_id
        not_self(admin, owner_id, "post")
        before = {"hidden": p.hidden}
        if body.action == "hide":
            p.hidden, p.hidden_reason = True, (untrusted(body.note, 120) or "removed by a moderator")
        elif body.action == "unhide":
            p.hidden, p.hidden_reason = False, None
        after = {"hidden": p.hidden}
    elif q.target_type == "comment":
        c = db.get(Comment, q.target_id)
        if c is None:
            raise HTTPException(404, "the comment is gone")
        owner_id = c.author_id
        not_self(admin, owner_id, "comment")
        before = {"hidden": c.hidden}
        if body.action == "hide":
            c.hidden = True
        after = {"hidden": c.hidden}
    elif q.target_type == "story":
        s = db.get(Story, q.target_id)
        if s is None:
            raise HTTPException(404, "the story is gone")
        owner_id = s.author_id
        not_self(admin, owner_id, "story")
        before = {"hidden": s.hidden}
        if body.action == "hide":
            s.hidden, s.hidden_reason = True, (untrusted(body.note, 120) or "removed by a moderator")
        after = {"hidden": s.hidden}
    elif q.target_type == "booking":
        b = db.get(Booking, q.target_id)
        if b is None:
            raise HTTPException(404, "the booking is gone")
        not_self(admin, b.seeker_id, "booking")
        not_self(admin, b.provider_id, "booking")
        before = {"status": b.status}
        if body.action == "release":
            # The session happened; let the payout job pay after the usual hold.
            b.status, b.payout_held_until = "completed", now()
        elif body.action == "refund":
            db.flush()
            b.status = "cancelled"
            refund_booking(db, b, "dispute upheld")
        elif b.status == "disputed":
            b.status = "completed"
        after = {"status": b.status}

    q.status = "resolved"
    q.resolved_at = now()
    q.assigned_to = admin.id
    q.resolution = f"{body.action}: {untrusted(body.note, 200) or ''}"[:240]
    audit(db, action=f"safety_{body.action}", admin=admin, resource=q.target_type, resource_id=q.target_id,
          user_id=owner_id, before=before, after={**(after or {}), "note": untrusted(body.note, 240)})
    db.commit()

    if q.target_type == "listing" and owner_id:
        owner = db.get(User, owner_id)
        if owner:
            notify_user(db, owner, kind=f"seva_review{q.target_id}",
                        title="Your listing is live" if body.action == "approve" else "Your listing was not accepted",
                        body="It is now on the board." if body.action == "approve" else "It did not meet our guidelines. You can write a new one.",
                        payload={"open": "seva_mine"})
    return _queue_view(q)


# =============================================================================
# Users: the narrow view, and strikes
# =============================================================================
class StrikeIn(BaseModel):
    reason: str = Field(min_length=3, max_length=120)
    scam: bool = False


@router.get("/admin/users/{user_id}")
def admin_user(user_id: int, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    """A moderator's view of a member: no phone, no address, no health, no location."""
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(404, "not found")
    audit(db, action="admin_read_user", admin=admin, resource="user", resource_id=u.id, user_id=u.id)
    reports_about = db.scalar(select(func.count(Report.id)).where(Report.target_type == "user", Report.target_id == u.id)) or 0
    actioned = db.scalar(select(func.count(Report.id)).where(Report.target_type == "user", Report.target_id == u.id, Report.status == "actioned")) or 0
    db.commit()
    return {
        "id": u.id, "name": u.address_as or u.name, "tier": u.tier, "role": u.role, "service_type": u.service_type,
        "created_at": u.created_at, "strikes": u.strikes, "suspended_until": u.suspended_until,
        "suspension_reason": u.suspension_reason, "deleted": u.deleted_at is not None,
        "reports_about": reports_about, "reports_actioned": actioned,
    }


@router.post("/admin/users/{user_id}/strike")
def strike(user_id: int, body: StrikeIn, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    """
    Plan 15: 1 warning, 2 a 7-day pause, 3 a 30-day pause, 4 removal. A scam is
    immediate removal. Removal is a deletion request (30-day grace, sessions
    revoked now), so a mistaken removal can still be undone by an admin within the
    window - which a hard delete could not.
    """
    limit_write(request, admin.id)
    u = db.get(User, user_id)
    if u is None or u.deleted_at is not None:
        raise HTTPException(404, "not found")
    not_self(admin, u.id, "strike")
    if u.role in ("admin", "moderator") and admin.role != "admin":
        raise HTTPException(403, "only an admin can act on staff")

    before = {"strikes": u.strikes, "suspended_until": u.suspended_until}
    u.strikes = (u.strikes or 0) + 1
    ts = now()
    if body.scam or u.strikes >= 4:
        action = "removed"
        u.deleted_at = ts
        revoke_family(db, u.id)
    elif u.strikes == 3:
        action, u.suspended_until = "pause_30_days", ts + 30 * 86400
    elif u.strikes == 2:
        action, u.suspended_until = "pause_7_days", ts + 7 * 86400
    else:
        action = "warning"
    u.suspension_reason = untrusted(body.reason, 120)
    audit(db, action=f"strike_{action}", admin=admin, resource="user", resource_id=u.id, user_id=u.id, before=before,
          after={"strikes": u.strikes, "suspended_until": u.suspended_until, "action": action, "reason": u.suspension_reason})
    db.commit()
    if action != "removed":
        notify_user(db, u, kind=f"strike{u.strikes}", urgent=True,
                    title="A notice about your account" if action == "warning" else "Your account is paused",
                    body=("Please keep to the community guidelines." if action == "warning"
                          else "You can still check in, use SOS and read. Posting is paused for now."),
                    payload={"open": "guidelines"})
    return {"action": action, "strikes": u.strikes, "suspended_until": u.suspended_until}


@router.post("/admin/users/{user_id}/restore")
def restore(user_id: int, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    """Undo a removal within the 30-day window, or clear a pause."""
    limit_write(request, admin.id)
    u = db.get(User, user_id)
    if u is None:
        raise HTTPException(404, "not found")
    not_self(admin, u.id, "restore")
    before = {"deleted_at": u.deleted_at, "suspended_until": u.suspended_until}
    u.deleted_at, u.suspended_until, u.suspension_reason = None, None, None
    audit(db, action="user_restore", admin=admin, resource="user", resource_id=u.id, user_id=u.id, before=before, after={})
    db.commit()
    return {"restored": True}


# =============================================================================
# Verification documents
# =============================================================================
@router.get("/admin/verify/{verification_id}/document")
def verification_document(
    verification_id: int, reason: str, request: Request, admin: AdminUser, db: Session = Depends(get_db),
):
    """
    Plan 6.1 T3: a moderator checks the name and service type against the
    document the person chose to upload. The access is logged BEFORE the URL is
    returned, needs a stated reason, lasts ten minutes, and is refused for one's
    own document. The object is deleted within 7 days by the deletion job; only
    "verified on X by Y" and the last four survive.
    """
    limit_write(request, admin.id)
    if len(reason.strip()) < 5:
        raise HTTPException(400, "give a reason for opening this document")
    v = db.get(Verification, verification_id)
    if v is None or not v.document_key:
        raise HTTPException(404, "there is no document on file for this")
    not_self(admin, v.user_id, "document")
    subject = db.get(User, v.user_id)
    audit(db, action="admin_open_document", admin=admin, resource="verification", resource_id=v.id, user_id=v.user_id,
          before=None, after={"reason": untrusted(reason, 200)})
    db.commit()
    return {
        "url": storage.presign_download(v.document_key, DOC_VIEW_SECONDS),
        "expires_in": DOC_VIEW_SECONDS,
        # What the moderator compares it against. Nothing else about the person.
        "claimed": {"name": subject.name if subject else None, "service_type": subject.service_type if subject else None,
                    "last4": v.last4},
    }


# =============================================================================
# Content management (radio, thoughts, offers, lessons, places, prompts, alerts, faq, ...)
# =============================================================================
_CONTENT: dict[str, tuple[type, set[str], set[str]]] = {
    "radio": (RadioStation, {"name", "kind", "language", "region", "stream_url", "credit", "is_active", "position"}, {"name", "kind", "stream_url"}),
    "thought": (DailyThought, {"text", "author", "source_ref", "language", "path", "is_active"}, {"text", "language"}),
    "offer": (PartnerOffer, {"partner_id", "title", "body", "url", "kind", "starts_at", "ends_at", "is_active"}, {"partner_id", "title", "url"}),
    "lesson": (Lesson, {"slug", "title", "summary", "category", "minutes", "language", "steps", "quiz", "badge", "position", "is_active"}, {"slug", "title", "category"}),
    "place": (YatraPlace, {"slug", "name", "state_code", "path", "lat", "lng", "accessibility", "best_season", "summary", "official_url", "booking_url", "source_ref", "is_active"}, {"slug", "name"}),
    "prompt": (StoryPrompt, {"text", "language", "is_active"}, {"text"}),
    "scam_alert": (ScamAlert, {"category", "title", "body", "language", "markers", "domains", "severity", "is_active", "expires_at"}, {"category", "title", "body"}),
    "faq": (OfflineFaq, {"question", "answer", "lang"}, {"question", "answer"}),
    "department": (Department, {"name", "parent_id", "service_type", "aliases"}, {"name"}),
    "facility": (Facility, {"name", "address", "city_id", "state_code", "lat", "lng", "schemes", "phone", "beds"}, {"name", "lat", "lng"}),
    "programme": (KendraProgramme, {"slug", "title", "description", "kind", "total_minutes", "pose_ids", "targets", "languages", "instructor", "is_active"}, {"slug", "title", "kind"}),
    "pose": (Pose, {"slug", "name", "instruction", "hold_seconds", "reps", "requires_standing", "requires_floor", "contraindications", "modification", "breathing_cue", "illustration_key"}, {"slug", "name", "instruction"}),
}
_SOFT = {"radio", "thought", "offer", "lesson", "place", "prompt", "scam_alert", "programme"}


def _row_dict(row) -> dict:  # noqa: ANN001
    return {c.key: getattr(row, c.key) for c in sa_inspect(row).mapper.column_attrs if c.key != "embedding"}


def _kind(kind: str):
    if kind not in _CONTENT:
        raise HTTPException(404, f"kind must be one of {', '.join(sorted(_CONTENT))}")
    return _CONTENT[kind]


def _validate_lesson(data: dict) -> None:
    for q in data.get("quiz") or []:
        opts = q.get("options") if isinstance(q, dict) else None
        if not (isinstance(q, dict) and q.get("q") and isinstance(opts, list) and len(opts) >= 2
                and isinstance(q.get("answer"), int) and 0 <= q["answer"] < len(opts)):
            raise HTTPException(400, "each quiz question needs q, at least two options, and an answer index")


@router.get("/admin/content/{kind}")
def content_list(kind: str, admin: AdminUser, db: Session = Depends(get_db), limit: int = 100, offset: int = 0):
    model, _fields, _req = _kind(kind)
    rows = db.scalars(select(model).order_by(model.id.desc()).offset(offset).limit(min(limit, 200))).all()
    return {"items": [_row_dict(r) for r in rows]}


@router.post("/admin/content/{kind}")
def content_create(kind: str, body: dict, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    limit_write(request, admin.id)
    model, fields, required = _kind(kind)
    data = {k: v for k, v in body.items() if k in fields}
    missing = [f for f in required if data.get(f) in (None, "")]
    if missing:
        raise HTTPException(400, f"missing: {', '.join(sorted(missing))}")
    if kind == "lesson":
        _validate_lesson(data)
    row = model(**data)
    db.add(row)
    try:
        db.flush()
    except (IntegrityError, DataError):
        db.rollback()
        raise HTTPException(400, "those values were not accepted (a duplicate slug, or a wrong type)") from None
    audit(db, action=f"content_create_{kind}", admin=admin, resource=kind, resource_id=row.id, after={"fields": sorted(data)})
    db.commit()
    return _row_dict(row)


@router.patch("/admin/content/{kind}/{row_id}")
def content_update(kind: str, row_id: int, body: dict, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    limit_write(request, admin.id)
    model, fields, _req = _kind(kind)
    row = db.get(model, row_id)
    if row is None:
        raise HTTPException(404, "not found")
    data = {k: v for k, v in body.items() if k in fields}
    if not data:
        raise HTTPException(400, "nothing to change")
    if kind == "lesson":
        _validate_lesson({**_row_dict(row), **data})
    before = {k: getattr(row, k) for k in data}
    for k, v in data.items():
        setattr(row, k, v)
    try:
        db.flush()
    except (IntegrityError, DataError):
        db.rollback()
        raise HTTPException(400, "those values were not accepted") from None
    audit(db, action=f"content_update_{kind}", admin=admin, resource=kind, resource_id=row.id, before=before, after=data)
    db.commit()
    return _row_dict(row)


@router.delete("/admin/content/{kind}/{row_id}")
def content_delete(kind: str, row_id: int, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    limit_write(request, admin.id)
    model, _f, _r = _kind(kind)
    row = db.get(model, row_id)
    if row is None:
        raise HTTPException(404, "not found")
    if kind in _SOFT and hasattr(row, "is_active"):
        row.is_active = False       # withdrawn, not erased: what was published can be traced
        result = "deactivated"
    else:
        db.delete(row)
        result = "deleted"
    audit(db, action=f"content_{result}_{kind}", admin=admin, resource=kind, resource_id=row_id)
    db.commit()
    return {"id": row_id, "result": result}


# ---------------------------------------------------------- commutation table --
class CommRow(BaseModel):
    age_next_birthday: int = Field(ge=18, le=110)
    factor: float = Field(gt=0, le=30)


class CommIn(BaseModel):
    service_type: str
    effective_from: float
    source_doc_id: int | None = None
    rows: list[CommRow] = Field(min_length=1, max_length=100)


@router.post("/admin/commutation-table")
def load_commutation(body: CommIn, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    """Add a table version. Never edits an old one: the version in force at retirement decides the figure."""
    limit_write(request, admin.id)
    if body.source_doc_id is None:
        raise HTTPException(400, "a factor table without its order is a rumour with decimals; give source_doc_id")
    if db.get(SahayakDoc, body.source_doc_id) is None:
        raise HTTPException(404, "source document not found")
    n = 0
    for r in body.rows:
        db.add(CommutationTable(service_type=body.service_type, service_years=float(r.age_next_birthday), factor=r.factor,
                                effective_from=body.effective_from, source_doc_id=body.source_doc_id))
        n += 1
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise HTTPException(409, "a table with those ages and that effective date already exists") from None
    audit(db, action="commutation_table_add", admin=admin, resource="commutation_table", resource_id=body.source_doc_id,
          after={"service_type": body.service_type, "rows": n, "effective_from": body.effective_from})
    db.commit()
    return {"added": n}


# ----------------------------------------------------------------- associations --
@router.get("/admin/associations")
def admin_associations(admin: AdminUser, db: Session = Depends(get_db), verified: bool | None = None):
    q = select(Association)
    if verified is True:
        q = q.where(Association.verified_at.isnot(None))
    elif verified is False:
        q = q.where(Association.verified_at.is_(None))
    rows = db.scalars(q.order_by(Association.created_at.desc()).limit(200)).all()
    return {"associations": [{"id": a.id, "name": a.name, "reg_no": a.reg_no, "verified": a.verified_at is not None,
                              "plan": a.plan, "created_at": a.created_at} for a in rows]}


class VerifyAssocIn(BaseModel):
    verified: bool = True
    plan: str | None = Field(default=None, max_length=12)
    plan_valid_until: float | None = None


@router.post("/admin/associations/{aid}/verify")
def verify_association(aid: int, body: VerifyAssocIn, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    limit_write(request, admin.id)
    a = db.get(Association, aid)
    if a is None:
        raise HTTPException(404, "not found")
    not_self(admin, a.owner_id, "association")
    before = {"verified": a.verified_at is not None, "plan": a.plan}
    a.verified_at = now() if body.verified else None
    if body.plan:
        a.plan, a.plan_valid_until = body.plan, body.plan_valid_until
    audit(db, action="association_verify", admin=admin, resource="association", resource_id=a.id, before=before,
          after={"verified": body.verified, "plan": a.plan})
    db.commit()
    return {"id": a.id, "verified": a.verified_at is not None}


# =============================================================================
# The volunteer desk (plan 6.4 "Human escalation")
# =============================================================================
class ClaimAnswerIn(BaseModel):
    answer: str = Field(min_length=20, max_length=4000)
    source_doc_id: int | None = None


def _volunteer(user: User) -> None:
    if user.tier_rank < TIER_RANK["T2"]:
        raise HTTPException(403, "answering questions needs a T2 account")


def _ticket_view(t: VolunteerTicket, q: SahayakQuestion | None, with_question: bool) -> dict:
    return {
        "id": t.id, "status": t.status, "created_at": t.created_at, "answered_at": t.answered_at,
        "question": untrusted(q.question, 1000) if (q and with_question) else None,
        "intent": q.intent if q else None, "lang": q.lang if q else None,
        "answer": t.answer, "source_doc_id": t.source_doc_id,
    }


@router.get("/volunteer/tickets")
def volunteer_tickets(user: CurrentUser, db: DbSession, status: str = "open"):
    _volunteer(user)
    rows = db.execute(
        select(VolunteerTicket, SahayakQuestion).join(SahayakQuestion, SahayakQuestion.id == VolunteerTicket.question_id)
        .where(VolunteerTicket.status == status,
               (VolunteerTicket.assigned_to.is_(None)) | (VolunteerTicket.assigned_to == user.id))
        .order_by(VolunteerTicket.created_at).limit(50)
    ).all()
    # The question, not the asker: a ticket carries no identity to a volunteer.
    return {"tickets": [_ticket_view(t, q, True) for t, q in rows], "sla_hours": 48}


@router.post("/volunteer/tickets/{ticket_id}/claim")
def claim(ticket_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    _volunteer(user)
    t = db.get(VolunteerTicket, ticket_id)
    if t is None or t.status != "open":
        raise HTTPException(409, "someone else has this one, or it is closed")
    if t.asker_id == user.id:
        raise HTTPException(409, "you cannot answer your own question")
    t.status, t.assigned_to = "assigned", user.id
    db.commit()
    return {"id": t.id, "status": t.status}


@router.post("/volunteer/tickets/{ticket_id}/answer")
def answer(ticket_id: int, body: ClaimAnswerIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    _volunteer(user)
    t = db.get(VolunteerTicket, ticket_id)
    if t is None or t.assigned_to != user.id or t.status != "assigned":
        raise HTTPException(409, "claim this question first")
    if body.source_doc_id is not None and db.get(SahayakDoc, body.source_doc_id) is None:
        raise HTTPException(404, "source document not found")
    from python.features.sahayak import DISCLAIMER, carries_personal_data  # noqa: PLC0415

    if carries_personal_data(body.answer):
        raise HTTPException(400, "please remove any pension number, Aadhaar or bank detail from the answer")
    t.answer = untrusted(body.answer, 4000)
    t.source_doc_id = body.source_doc_id
    t.status, t.answered_at = "answered", now()
    asker_id, t.asker_id = t.asker_id, None       # delivered below; the link is not kept
    db.commit()
    if asker_id:
        asker = db.get(User, asker_id)
        if asker is not None:
            notify_user(db, asker, kind=f"pension_vol{t.id}", title="A volunteer answered your question",
                        body="Open Pension Sahayak to read the answer.", payload={"open": "sahayak_tickets", "ticket_id": t.id})
    return {"id": t.id, "status": t.status, "disclaimer": DISCLAIMER["en"]}


@router.get("/sahayak/tickets")
def my_tickets(user: CurrentUser, db: DbSession):
    """The person's own volunteer questions and any answers, found through the hashed log."""
    h = _user_hash(user.id)
    rows = db.execute(
        select(VolunteerTicket, SahayakQuestion).join(SahayakQuestion, SahayakQuestion.id == VolunteerTicket.question_id)
        .where(SahayakQuestion.user_hash == h).order_by(VolunteerTicket.created_at.desc()).limit(50)
    ).all()
    from python.features.sahayak import DISCLAIMER  # noqa: PLC0415

    return {
        "tickets": [
            {**_ticket_view(t, q, True), "disclaimer": DISCLAIMER.get(user.lang or "en", DISCLAIMER["en"]) if t.answer else None}
            for t, q in rows
        ]
    }


@router.get("/admin/volunteer-tickets")
def admin_tickets(admin: AdminUser, db: Session = Depends(get_db), status: str | None = None):
    q = select(VolunteerTicket)
    if status:
        q = q.where(VolunteerTicket.status == status)
    rows = db.scalars(q.order_by(VolunteerTicket.created_at.desc()).limit(200)).all()
    late = now() - 48 * 3600
    return {"tickets": [{"id": t.id, "status": t.status, "created_at": t.created_at, "assigned_to": t.assigned_to,
                         "overdue": t.status in ("open", "assigned") and t.created_at < late} for t in rows]}


@router.post("/admin/volunteer-tickets/{ticket_id}/promote")
def promote_faq(ticket_id: int, request: Request, admin: AdminUser, db: Session = Depends(get_db)):
    """
    A good answer becomes a reviewed FAQ - but only once it carries a source
    (plan 6.4). An answer a volunteer believed, with nothing behind it, does not
    enter the offline corpus.
    """
    limit_write(request, admin.id)
    t = db.get(VolunteerTicket, ticket_id)
    if t is None or t.status != "answered" or not t.answer:
        raise HTTPException(409, "only an answered ticket can be promoted")
    if t.source_doc_id is None:
        raise HTTPException(409, "this answer has no source document; it cannot enter the FAQ")
    q = db.get(SahayakQuestion, t.question_id)
    doc = db.get(SahayakDoc, t.source_doc_id)
    faq = OfflineFaq(
        question=q.question if q else "", lang=q.lang if q else "en",
        answer=f"{t.answer}\n\nSource: {doc.title if doc else ''}{' (' + doc.doc_no + ')' if doc and doc.doc_no else ''}",
    )
    db.add(faq)
    db.flush()
    t.promoted_faq_id = faq.id
    audit(db, action="faq_promote", admin=admin, resource="volunteer_ticket", resource_id=t.id, after={"faq_id": faq.id})
    db.commit()
    return {"faq_id": faq.id}
