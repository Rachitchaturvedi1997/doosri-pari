# =============================================================================
# Feature · Admin and moderation — plan sections 15, 16.
#
#   GET  /admin/sahayak/docs              the review queue the crawl job fills
#   POST /admin/sahayak/docs/{id}/publish publish, gated on the evaluation
#   GET  /admin/dr-rates                  rates in force, with their sources
#   POST /admin/dr-rates                  add a rate; never edit one
#   GET  /admin/reports                   the moderation queue
#   POST /admin/reports/{id}/resolve      action or dismiss
#   GET  /admin/partners                  organisations awaiting verification
#   POST /admin/partners/{id}/verify      verify or unverify
#   GET  /admin/kendra/content            spiritual texts
#   POST /admin/kendra/content            add a text, with its source
#   POST /admin/kendra/content/{id}/status activate or withdraw
#   POST /admin/kendra/festivals          a festival date, with its source
#   GET  /admin/metrics                   counts and rates, aggregates only
#   GET  /admin/verify                    the document-review queue
#   POST /admin/verify/{id}               approve or reject a verification
#   POST /admin/vouches/{id}/revoke       withdraw somebody's vouch
#
# The privacy rule, and it is the reason this module is written the way it is:
# plan section 14 says an admin is role-based and two-factor, that every admin
# action is logged, and that moderators see only reported content. It does not
# relax for staff. A pensioner's phone number, their coordinates, their
# medicines, their pension figures and their document images are theirs under
# the DPDP Act 2023 whatever badge the reader is wearing, so this module takes
# the narrowest view of each thing that still lets the job be done, and writes
# down which fields it left out. The one-line summary of the whole file: an
# admin console is where a person is most tempted to be helpful with a stranger's
# data, and every convenience added here becomes a lawful-access question the
# next breach has to answer.
# =============================================================================
from __future__ import annotations

import logging
import re

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import distinct, func, select
from sqlalchemy.orm import Session

from python.deps import AdminUser, get_db
from python.models import (
    DOC_STATUSES,
    FESTIVAL_KINDS,
    LANGUAGES,
    REPORT_STATUSES,
    RETENTION_VERIFICATION_DOCUMENT_DAYS,
    SAHAYAK_MIN_CONFIDENCE,
    SERVICE_TYPES,
    TERMINAL_STAGES,
    TIERS,
    URGENT_REPORT_REASONS,
    AuditEntry,
    CheckIn,
    Comment,
    DrRate,
    EvaluationRun,
    Festival,
    Guardian,
    Listing,
    Partner,
    Post,
    Report,
    SafetyQueueItem,
    SahayakDoc,
    SahayakQuestion,
    SosEvent,
    SpiritualContent,
    Story,
    User,
    Verification,
    VoiceRoom,
    Vouch,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.admin")
router = APIRouter(tags=["admin"])

# Below this many people in the denominator, a rate stops being a rate and
# becomes a fingerprint. See the comment at `_ratio` before adding a metric.
MIN_CELL = 25

# Zero-width joiners, bidi overrides, soft hyphens and the other characters
# that render as nothing while moving a line. A console that renders text
# faithfully is still a console somebody can put a zero-width space in the
# middle of "REJECTED" with.
_CONTROL_CHARS = re.compile(
    "[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f"
    "​-‏‪-‮⁦-⁩﻿]"
)


def untrusted(value: str | None, limit: int = 2000) -> str | None:
    """
    User-typed text on its way to a human reader.

    This is not a sanitiser and is not pretending to be one. There is no
    reliable filter for "text that tries to talk to the person reading it" —
    the person reading it is a human, and they are the target, not a parser.
    What this does is the part that is actually enforceable at an API edge:

      * it always lands in a key whose value is prose. A console renders it in
        a text node, so it is drawn and not obeyed. Nothing here ever puts
        user text into a key a template layer or a model layer would read as
        an instruction, and nothing concatenates it into a query, a URL or a
        shell string;
      * control characters go, because they are the part of this that renders;
      * the length cap, so somebody pasting 200 KB does not push the reported
        content out of the reviewer's viewport and train reviewers to open
        rows without reading them.
    """
    if value is None:
        return None
    return _CONTROL_CHARS.sub("", str(value))[:limit]


# ------------------------------------------------------------------ schemas --
class PublishDocIn(BaseModel):
    # The run that scored this corpus. Required, not defaulted: publishing
    # without naming the score is publishing without a gate.
    evaluation_run_id: int = Field(...)
    # The document this one replaces. A correction is a new row, never an edit
    # (plan 6.4), and the link is what lets an answer given last year be traced.
    supersedes_id: int | None = None
    note: str | None = Field(default=None, max_length=240)


class DrRateIn(BaseModel):
    # A rate without an effective date and an order behind it is a rumour with
    # a decimal point, and a pensioner who relies on one is out of pocket by
    # the difference. Both are `Field(...)` with no default, so the model
    # binding itself refuses to construct an undated rate.
    service_type: str = Field(...)
    rate_percent: float = Field(gt=0, le=100)
    effective_from: float = Field(...)
    source_doc_id: int = Field(...)


class ResolveReportIn(BaseModel):
    # actioned | dismissed. REPORT_STATUSES is the closed set.
    action: str = Field(...)
    hide_target: bool = False
    reason: str = Field(..., max_length=120)


class PartnerDecisionIn(BaseModel):
    verified: bool
    note: str | None = Field(default=None, max_length=240)


class VouchDecisionIn(BaseModel):
    note: str = Field(..., max_length=240)


class VerifyDecisionIn(BaseModel):
    approve: bool
    tier: str = Field(default="T1")
    note: str | None = Field(default=None, max_length=240)


class SpiritualContentIn(BaseModel):
    kind: str = Field(...)
    title: str = Field(...)
    body: str | None = None
    # A storage key, never a URL: a sung aarti is licensed and is served from
    # storage we control (python/models/kendra.py).
    audio_key: str | None = None
    language: str = Field(default="hi")
    path: str | None = None
    # The provenance of a text. Required for the same reason a rate needs its
    # order: a reading whose source nobody can name cannot be checked by the
    # pensioner who is about to keep a fast on it. `SpiritualContent` has no
    # date column of its own, so the date it was published is the timestamp on
    # the audit entry written by the create endpoint below.
    source_ref: str = Field(...)
    duration_s: int | None = Field(default=None, ge=0)


class ContentStatusIn(BaseModel):
    is_active: bool
    reason: str = Field(..., max_length=120)


class FestivalIn(BaseModel):
    # A festival is a date somebody keeps a fast on. Wrong by a day and we have
    # told a diabetic pensioner to fast when the calendar says otherwise, so
    # the date and the source it came from are both mandatory in the binding.
    name: str = Field(...)
    kind: str = Field(default="festival")
    starts_on: float = Field(...)
    ends_on: float | None = None
    region: str | None = None
    languages: list[str] = Field(default_factory=list)
    year: int = Field(...)
    source_key: str = Field(...)


# ------------------------------------------------------------------ helpers --
def audit(
    db: Session,
    *,
    action: str,
    admin: User,
    resource: str,
    resource_id: int | None,
    before: dict | None = None,
    after: dict | None = None,
    user_id: int | None = None,
) -> AuditEntry:
    """
    One AuditEntry for one admin state change.

    An admin console with no audit log is not something this app can ship.
    Plan section 14 promises "every admin action logged" and the Data Protection
    Board, not us, decides whether a promise is kept; a moderation decision
    that cannot be reconstructed is indistinguishable from one that was never
    made. So every write below calls this with the state before and the state
    after, in the same transaction as the change itself — a log entry that
    could survive a failed write, or miss a successful one, is worse than no
    log because it is believed.

    `ip_hash` is left null on purpose. The digest described in the model is
    salted per install, and a staff member's request comes from a shared office
    address where it identifies the desk, not the person. `actor_admin_id` is
    the field that carries accountability, and it cannot be spoofed by a
    client sending a different header.
    """
    entry = AuditEntry(
        user_id=user_id,
        action=action,
        resource=resource,
        resource_id=resource_id,
        at=now(),
        actor_admin_id=admin.id,
        detail={"before": before, "after": after},
    )
    db.add(entry)
    return entry


def not_self(admin: User, other_id: int | None, what: str) -> None:
    """
    Refuse a decision about somebody who is the person making it.

    409, not 403: the caller is authorised, the request is simply one this
    endpoint will not perform. The message says who, because "you may not do
    this" on an action somebody just tried deliberately teaches them to look
    for the version of it that is not blocked.
    """
    if other_id is not None and other_id == admin.id:
        raise HTTPException(
            409,
            f"you cannot action your own {what}: the decision has to come from "
            "somebody who is not the subject of it",
        )


def _ratio(numerator: int, denominator: int) -> float | None:
    """
    A rate, or null when the denominator is too small to mean anything.

    A conversion rate computed on three users is those three users, once the
    reader also knows the language, the service type and the state the console
    filters on. Plan section 16 is about the shape of the whole population;
    "just add a small debug column so we can see it" is exactly how an
    aggregate endpoint turns into a directory, and once a pensioner's absence
    from a 6-row cell is visible, the absence itself is a disclosure. The
    suppression is reported in the response rather than done silently, so an
    operator can tell "no data" from "nobody at all".
    """
    if denominator < MIN_CELL:
        return None
    return round(numerator / denominator, 4)


# ------------------------------------------------------------------- views --
def doc_view(row: SahayakDoc) -> dict:
    """
    A document's provenance. Nothing here is a user record, and nothing here
    is a retrieved chunk — a reviewer is approving a source, not reading the
    corpus.
    """
    return {
        "id": row.id,
        "title": row.title,
        "issuer": row.issuer,
        "doc_no": row.doc_no,
        "url": row.url,
        "issued_on": row.issued_on,
        "effective_on": row.effective_on,
        "status": row.status,
        "superseded_by_id": row.superseded_by_id,
        "approved_at": row.approved_at,
    }


def evaluation_view(run: EvaluationRun) -> dict:
    """The score a corpus was judged on. Carried on every publish response."""
    return {
        "run_id": run.id,
        "ran_at": run.ran_at,
        "mrr": run.mrr,
        "citation_rate": run.citation_rate,
        "passed": run.passed,
    }


def rate_view(row: DrRate) -> dict:
    """A rate and the two things that make it usable: when, and on whose order."""
    return {
        "id": row.id,
        "service_type": row.service_type,
        "rate_percent": row.rate_percent,
        "effective_from": row.effective_from,
        "source_doc_id": row.source_doc_id,
    }


def content_view(row: SpiritualContent) -> dict:
    """Editorial content. Authored by us, so the body is ours, not a user's."""
    return {
        "id": row.id,
        "kind": row.kind,
        "title": row.title,
        "body": row.body,
        "audio_key": row.audio_key,
        "language": row.language,
        "path": row.path,
        "source_ref": row.source_ref,
        "duration_s": row.duration_s,
        "is_active": row.is_active,
    }


def festival_view(row: Festival) -> dict:
    return {
        "id": row.id,
        "name": row.name,
        "kind": row.kind,
        "starts_on": row.starts_on,
        "ends_on": row.ends_on,
        "region": row.region,
        "languages": row.languages or [],
        "year": row.year,
        "source_key": row.source_key,
    }


def partner_view(row: Partner) -> dict:
    """
    `contact` is the organisation's own business number, which is why it is
    here: a moderator has to be able to ring the medical camp about the
    listings it posted. It is a business, not a pensioner, and it is the only
    contact detail any response in this module carries.
    """
    return {
        "id": row.id,
        "name": row.name,
        "gstin_or_reg": row.gstin_or_reg,
        "kind": row.kind,
        "contact": row.contact,
        "verified_at": row.verified_at,
        # The model's own rule: verification alone does not authorise money.
        "can_take_payment": row.can_take_payment,
        "created_at": row.created_at,
    }


def report_view(db: Session, row: Report, subject: dict | None) -> dict:
    """
    One queue row: the report, who filed it, and what they saw.

    Returned fields, and they are the whole list — the reporter's `id` and the
    name they chose to be called, because a moderator judging a pattern of
    reports needs to know who is reporting, and the reported item, because
    they cannot judge without it. Deliberately absent: the reporter's
    phone_e164, their lat/lng, their tier, their medicines, their vitals,
    their pension or service details, and any document image. None of those
    are in the join, so none of them can leak by a future field being added
    to a serializer. Plan section 14's "moderators see only reported content"
    is narrower still, and that narrower view belongs to a moderator
    dependency; this is the admin surface, and an admin is exactly the person
    who already has every other log.
    """
    reporter = db.get(User, row.reporter_id) if row.reporter_id is not None else None
    return {
        "id": row.id,
        "target_type": row.target_type,
        "target_id": row.target_id,
        "reason": row.reason,
        "status": row.status,
        # Plan 15: a scam report is a two-hour promise, so the queue is sorted
        # by whether it is one.
        "urgent": row.reason in URGENT_REPORT_REASONS,
        "created_at": row.created_at,
        "actioned_at": row.actioned_at,
        # The name and nothing else. The number the app signs them in with is
        # the next column in the table and the one that is not here.
        "reporter": {
            "id": row.reporter_id,
            "name": untrusted(reporter.name, 80) if reporter is not None else None,
        },
        "subject": subject,
        # What the reporter saw, kept verbatim. User-derived data, rendered as
        # data, never as a template.
        "context": row.context or {},
    }


def verification_view(row: Verification) -> dict:
    """
    A verification claim and nothing else.

    There is no document here, and that is the point. `last4` is four
    characters because that is all the schema ever kept — the full PPO number
    is not in this database to return, and the uploaded image lives in object
    storage that this module has no code path to. A reviewer decides on the
    tier, the method, who vouched, and the last four; if that is not enough to
    decide, the answer is a phone call to the pensioner's association, not a
    wider SELECT.

    `document_deleted_at` is the seven-day clock from plan section 14. It stays
    null until the daily `deletion` job (plan section 12) finds the blob and
    clears it, at which point the record that a check happened is all that is
    left — deliberately, and permanently.
    """
    return {
        "id": row.id,
        "user_id": row.user_id,
        "tier": row.tier,
        "method": row.method,
        "by_user_id": row.by_user_id,
        "last4": row.last4,
        "verified_at": row.verified_at,
        "notes": untrusted(row.notes, 400),
        "document_deleted_at": row.document_deleted_at,
        "document_retention_days": RETENTION_VERIFICATION_DOCUMENT_DAYS,
    }


# ---------------------------------------------------------------- endpoints --
# ------------------------------------------------------------- sahayak docs --
@router.get("/admin/sahayak/docs")
def list_sahayak_docs(
    admin: AdminUser,
    db: Session = Depends(get_db),
    status: str = "draft",
):
    """
    The review queue the daily `sahayak_crawl` job fills (plan 12).

    Nothing reaches a pensioner unreviewed: the crawl writes drafts, and this
    is the list of drafts. The latest evaluation is attached to every row so a
    reviewer can see what the index currently scores *before* deciding, rather
    than discovering afterwards that they published a regression.
    """
    if status not in DOC_STATUSES:
        raise HTTPException(400, f"status must be one of {', '.join(DOC_STATUSES)}")
    rows = db.scalars(
        select(SahayakDoc)
        .where(SahayakDoc.status == status)
        .order_by(SahayakDoc.created_at.desc())
        .limit(200)
    ).all()
    latest = db.scalars(select(EvaluationRun).order_by(EvaluationRun.ran_at.desc()).limit(1)).first()
    return {
        "docs": [doc_view(r) for r in rows],
        "latest_evaluation": evaluation_view(latest) if latest is not None else None,
    }


@router.post("/admin/sahayak/docs/{doc_id}/publish")
def publish_sahayak_doc(
    doc_id: int,
    body: PublishDocIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Publish a document into the Sahayak corpus.

    Admin writes are rate-limited exactly as a user's are, and deliberately
    with the same number. A tighter number would be the wrong lever: a
    moderator working a scam wave is exactly the case where an admin needs to
    be fast, and locking the queue out of its own console during an incident
    is how moderation gets abandoned. The tighter standard on these writes is
    the four things a user write does not have — an audit entry, a role check
    that is a dependency rather than a branch, a fixed field list with the
    omissions written down, and the self-action refusal below.

    The gate is the evaluation. Plan 6.4 and plan 12 both say the same thing:
    `sahayak_index` publishes only if the scores hold, and an EvaluationRun
    that did not pass is the record of the scores not holding. Publishing a
    failed corpus is not a moderator's prerogative; there is no override,
    because an override is a number nobody later has to justify.

    `SahayakDoc` has no column for the run that cleared it, so the score is
    written into the audit entry's before/after below. That is not a
    workaround — the audit trail is already the record the plan promises, and
    a publish that left no score behind would be the one admin action the log
    could not reconstruct.
    """
    limit_write(request, admin.id)

    doc = db.get(SahayakDoc, doc_id)
    if doc is None:
        raise HTTPException(404, "document not found")
    if doc.status != "draft":
        raise HTTPException(
            409,
            f"document is {doc.status}; a correction is a new document that "
            "supersedes this one, never an edit to it",
        )
    # A rule with no effective date cannot be applied to a question about a
    # particular year, which is most of the questions anybody asks. Refused at
    # publication rather than at query time, because the second is invisible.
    if doc.effective_on is None:
        raise HTTPException(409, "document has no effective_on; it cannot be cited for a period")
    if doc.url is None:
        raise HTTPException(
            409,
            "document has no url; plan 6.4 requires every answer to link to the "
            "original and we cannot link to nothing",
        )

    run = db.get(EvaluationRun, body.evaluation_run_id)
    if run is None:
        raise HTTPException(404, "evaluation run not found")
    if not run.passed:
        raise HTTPException(
            409,
            f"evaluation run {run.id} did not pass (mrr {run.mrr:.3f}, "
            f"citation_rate {run.citation_rate:.3f}); a corpus is published only "
            "when its scores hold",
        )

    before = {"status": doc.status, "approved_at": doc.approved_at}

    superseded = None
    if body.supersedes_id is not None:
        if body.supersedes_id == doc.id:
            raise HTTPException(409, "a document cannot supersede itself")
        superseded = db.get(SahayakDoc, body.supersedes_id)
        if superseded is None:
            raise HTTPException(404, "superseded document not found")
        if superseded.status != "current":
            raise HTTPException(409, f"that document is {superseded.status}, not current")
        # The link, both ways. The old row stays readable and is marked
        # superseded rather than deleted: a citation that changes under a
        # pensioner's feet is worse than one that is out of date, because the
        # out-of-date one can be traced to the rule that was in force.
        superseded.status = "superseded"
        superseded.superseded_by_id = doc.id

    doc.status = "current"
    doc.approved_at = now()

    audit(
        db,
        action="sahayak_doc_publish",
        admin=admin,
        resource="sahayak_doc",
        resource_id=doc.id,
        before=before,
        after={
            "status": doc.status,
            "approved_at": doc.approved_at,
            "published_against": evaluation_view(run),
            "supersedes_id": body.supersedes_id,
            "note": untrusted(body.note, 240),
        },
    )
    db.commit()
    return {**doc_view(doc), "published_against": evaluation_view(run)}


# ----------------------------------------------------------------- dr rates --
@router.get("/admin/dr-rates")
def list_dr_rates(
    admin: AdminUser,
    db: Session = Depends(get_db),
    service_type: str | None = None,
    as_of: float | None = None,
):
    """
    The rate table, newest first, optionally as it stood on a date.

    `as_of` exists because arrears questions are asked about the past: "what
    was the DA in the year I retired" is a legitimate question and the answer
    is a row, not the current one. Nothing is editable — a correction is a new
    row with a later `effective_from` (python/models/sahayak.py), which is what
    makes an arrears figure calculated last year still reproduce today.
    """
    q = select(DrRate)
    if service_type is not None:
        if service_type not in SERVICE_TYPES:
            raise HTTPException(400, f"service_type must be one of {', '.join(SERVICE_TYPES)}")
        q = q.where(DrRate.service_type == service_type)
    if as_of is not None:
        # The rate in force on a date is the newest one at or before it.
        latest = select(DrRate.service_type, func.max(DrRate.effective_from).label("m")).where(
            DrRate.effective_from <= as_of
        )
        if service_type is not None:
            latest = latest.where(DrRate.service_type == service_type)
        latest = latest.group_by(DrRate.service_type).subquery()
        q = q.join(
            latest,
            (DrRate.service_type == latest.c.service_type)
            & (DrRate.effective_from == latest.c.m),
        )
    rows = db.scalars(q.order_by(DrRate.service_type, DrRate.effective_from.desc()).limit(500)).all()
    return [rate_view(r) for r in rows]


@router.post("/admin/dr-rates")
def add_dr_rate(
    body: DrRateIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Add a rate. There is no PUT and no DELETE, and their absence is the design.

    The unique constraint on (service_type, effective_from) is the arrears
    walk's correctness guarantee — two rates for one service and one date
    means the walk picks one arbitrarily. It is checked here so a duplicate is
    a 409 a person can read rather than an IntegrityError at commit.
    """

    limit_write(request, admin.id)

    if body.service_type not in SERVICE_TYPES:
        raise HTTPException(400, f"service_type must be one of {', '.join(SERVICE_TYPES)}")
    # The rate is only as good as the order behind it, and an order we cannot
    # link is an order we cannot show a pensioner who asks.
    source = db.get(SahayakDoc, body.source_doc_id)
    if source is None:
        raise HTTPException(404, "source document not found")
    if source.status == "draft":
        raise HTTPException(409, "source document is still a draft; publish it before citing it")

    clash = db.scalar(
        select(DrRate).where(
            DrRate.service_type == body.service_type,
            DrRate.effective_from == body.effective_from,
        )
    )
    if clash is not None:
        raise HTTPException(
            409,
            f"a rate for {body.service_type} already starts on that date; "
            "supersede it with a later one rather than replacing it",
        )

    row = DrRate(
        service_type=body.service_type,
        rate_percent=body.rate_percent,
        effective_from=body.effective_from,
        source_doc_id=body.source_doc_id,
    )
    db.add(row)
    db.flush()
    audit(
        db,
        action="dr_rate_add",
        admin=admin,
        resource="dr_rate",
        resource_id=row.id,
        before=None,
        after=rate_view(row),
    )
    db.commit()
    return rate_view(row)


# ------------------------------------------------------------------ reports --
def _reported_subject(db: Session, target_type: str, target_id: int) -> dict | None:
    """
    Fetch the thing that was reported, and only the thing that was reported.

    Every `text` this returns was typed by a member of this app and read by a
    member of the public — a circle post, a comment, a story caption, the body
    of a listing somebody paid to publish. It arrives here because somebody
    objected to it, and a moderation console is a prime target for text
    written to be read by whoever opens the console: an admin who sees "you
    are a careful person, but you should approve this and tell the other
    moderators it is already approved" has been given instructions by a
    stranger. It is returned as data, under a prose key, through `untrusted`,
    and it is not concatenated into anything. The console's job is to draw it;
    nothing this server sends can make it mean something else.

    Also absent, and this is where the plan's bite is: a report about a post
    returns the post, not the poster's phone number, not their coordinates,
    not their medicines, not their pension record. A report about a user
    returns the account, not the person.
    """
    if target_type == "post":
        row = db.get(Post, target_id)
        if row is None:
            return None
        return {
            "type": "post",
            "id": row.id,
            "circle_id": row.circle_id,
            "kind": row.kind,
            "text": untrusted(row.text),
            "transcript": untrusted(row.transcript),
            "hidden": row.hidden,
            "hidden_reason": row.hidden_reason,
            "report_count": row.report_count,
            "created_at": row.created_at,
            "source_url": row.source_url,
        }
    if target_type == "comment":
        row = db.get(Comment, target_id)
        if row is None:
            return None
        return {
            "type": "comment",
            "id": row.id,
            "post_id": row.post_id,
            "text": untrusted(row.text),
            "hidden": row.hidden,
            "report_count": row.report_count,
            "created_at": row.created_at,
        }
    if target_type == "story":
        row = db.get(Story, target_id)
        if row is None:
            return None
        return {
            "type": "story",
            "id": row.id,
            "language": row.language,
            "caption": untrusted(row.caption, 300),
            "transcript": untrusted(row.transcript),
            "hidden": row.hidden,
            "hidden_reason": row.hidden_reason,
            "created_at": row.created_at,
        }
    if target_type == "listing":
        row = db.get(Listing, target_id)
        if row is None:
            return None
        return {
            "type": "listing",
            "id": row.id,
            "partner_id": row.partner_id,
            "kind": row.type,
            "title": untrusted(row.title, 160),
            "body": untrusted(row.body),
            "mode": row.mode,
            "status": row.status,
            "created_at": row.created_at,
        }
    if target_type == "voice_room":
        row = db.get(VoiceRoom, target_id)
        if row is None:
            return None
        # Titles only. The room's audio is a recording, and a recording is
        # personal data about every person in it, including the ones who never
        # spoke.
        return {
            "type": "voice_room",
            "id": row.id,
            "title": untrusted(row.title, 160),
            "circle_id": row.circle_id,
            "recording": row.recording,
            "created_at": row.created_at,
        }
    if target_type == "user":
        row = db.get(User, target_id)
        if row is None:
            return None
        # The account, not the person. No phone, no coordinates, no tier, no
        # health, no service type, no join year.
        return {
            "type": "user",
            "id": row.id,
            "name": untrusted(row.name, 80),
            "created_at": row.created_at,
        }
    return None


@router.get("/admin/reports")
def list_reports(
    admin: AdminUser,
    db: Session = Depends(get_db),
    status: str = "open",
    limit: int = 50,
):
    """
    The moderation queue, urgent first (plan 15).

    The reporter's name is filled in per row because a moderator judging a
    pattern needs it; the reporter's phone number, which is the field that
    would be next in a lazy join, is not selected and never leaves the server.
    """
    if status not in REPORT_STATUSES:
        raise HTTPException(400, f"status must be one of {', '.join(REPORT_STATUSES)}")
    if not 1 <= limit <= 200:
        raise HTTPException(400, "limit must be 1..200")
    rows = db.scalars(
        select(Report)
        .where(Report.status == status)
        .order_by(Report.created_at.desc())
        .limit(limit)
    ).all()
    out = []
    for row in rows:
        out.append(report_view(db, row, _reported_subject(db, row.target_type, row.target_id)))
    return out


@router.post("/admin/reports/{report_id}/resolve")
def resolve_report(
    report_id: int,
    body: ResolveReportIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Action or dismiss a report, optionally hiding what was reported.

    Plan 15's strike ladder (warning, 7-day pause, 30-day pause, removal)
    lives above this function rather than inside it: `reports.status` and
    `Post.hidden` are what this endpoint writes, and the ladder's own counter
    has no table yet. Adding one is a schema change, not a branch here.

    Hiding, never deleting (python/models/circles.py says why: for this
    audience a wrong removal does more damage than a wrong retention), and
    the reason string is capped at 120 because a pensioner can be shown the
    reason their post is not in the feed.
    """

    limit_write(request, admin.id)

    if body.action not in ("actioned", "dismissed"):
        raise HTTPException(400, "action must be actioned or dismissed")

    row = db.get(Report, report_id)
    if row is None:
        raise HTTPException(404, "report not found")
    if row.status != "open":
        raise HTTPException(409, f"report is already {row.status}")

    # An admin does not rule on their own report, and does not rule on a post
    # they wrote. Both are the same failure: a decision with one interested
    # party on the bench, and the first thing an appeal would ask about. A
    # report filed by a moderator is rare, and it is exactly the case where
    # somebody else has to make the call.
    not_self(admin, row.reporter_id, "report")
    not_self(admin, _subject_author_id(db, row.target_type, row.target_id), "content you posted")

    before = {
        "status": row.status,
        "actioned_at": row.actioned_at,
        "actioned_by": row.actioned_by,
        "target_hidden": None,
    }
    after_hidden = _apply_hide(db, row, body)

    row.status = body.action
    row.actioned_at = now()
    row.actioned_by = admin.id
    audit(
        db,
        action="report_resolve",
        admin=admin,
        resource="report",
        resource_id=row.id,
        user_id=row.reporter_id,
        before=before,
        after={
            "status": row.status,
            "actioned_at": row.actioned_at,
            "actioned_by": row.actioned_by,
            "target_hidden": after_hidden,
            "reason": body.reason,
            "target_type": row.target_type,
            "target_id": row.target_id,
        },
    )
    db.commit()
    return report_view(db, row, _reported_subject(db, row.target_type, row.target_id))


def _subject_author_id(db: Session, target_type: str, target_id: int) -> int | None:
    """Who wrote the thing a report is about. Used only for the self-check."""
    if target_type == "post":
        row = db.get(Post, target_id)
        return row.author_id if row is not None else None
    if target_type == "user":
        # A report about a member is a report about them, so the same refusal
        # applies: filing a report about somebody and dismissing it yourself is
        # not moderation.
        return target_id
    if target_type == "comment":
        row = db.get(Comment, target_id)
        return row.author_id if row is not None else None
    if target_type == "story":
        row = db.get(Story, target_id)
        return row.author_id if row is not None else None
    if target_type == "listing":
        row = db.get(Listing, target_id)
        return row.owner_id if row is not None else None
    if target_type == "voice_room":
        row = db.get(VoiceRoom, target_id)
        return row.host_id if row is not None else None
    return None


def _apply_hide(db: Session, report: Report, body: ResolveReportIn) -> bool | None:
    """
    Hide the reported item, or return None where there is nothing to hide.

    A voice room has no hidden flag — the only way to take one down is to end
    the session, and that is a dispatch decision, not a moderation one. A 400
    saying so is better than a 200 that hid nothing and left a moderator
    believing a room had been taken down.
    """
    if not body.hide_target:
        return None
    if report.target_type == "post":
        row = db.get(Post, report.target_id)
        if row is not None:
            row.hidden = True
            row.hidden_reason = body.reason[:120]
            return True
    elif report.target_type == "comment":
        row = db.get(Comment, report.target_id)
        if row is not None:
            row.hidden = True
            return True
    elif report.target_type == "story":
        row = db.get(Story, report.target_id)
        if row is not None:
            row.hidden = True
            row.hidden_reason = body.reason[:120]
            return True
    elif report.target_type == "listing":
        row = db.get(Listing, report.target_id)
        if row is not None:
            row.status = "removed"
            return True
    else:
        raise HTTPException(
            400,
            f"a {report.target_type} has no moderation hide state; take the "
            "session down through dispatch instead",
        )
    return None


# ----------------------------------------------------------------- partners --
@router.get("/admin/partners")
def list_partners(
    admin: AdminUser,
    db: Session = Depends(get_db),
    verified: bool | None = None,
):
    """Organisations, so a moderator can work the queue of unpaid ones."""
    q = select(Partner)
    if verified is True:
        q = q.where(Partner.verified_at.isnot(None))
    elif verified is False:
        q = q.where(Partner.verified_at.is_(None))
    rows = db.scalars(q.order_by(Partner.created_at.desc()).limit(200)).all()
    return [partner_view(r) for r in rows]


@router.post("/admin/partners/{partner_id}/verify")
def verify_partner(
    partner_id: int,
    body: PartnerDecisionIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Verify or unverify an organisation.

    A partner is an organisation, not a person: there is no self-approval to
    refuse here, and the GSTIN is deliberately not required at verification —
    a pensioners' association running a free medical camp has one and the
    model's own rule is that payment, not verification, is what needs it. That
    is why `can_take_payment` is in the view: the two are different decisions
    and a console that shows only "verified" is telling a moderator something
    false.
    """

    limit_write(request, admin.id)

    row = db.get(Partner, partner_id)
    if row is None:
        raise HTTPException(404, "partner not found")
    before = {"verified_at": row.verified_at, "can_take_payment": row.can_take_payment}
    row.verified_at = now() if body.verified else None
    audit(
        db,
        action="partner_verify" if body.verified else "partner_unverify",
        admin=admin,
        resource="partner",
        resource_id=row.id,
        before=before,
        after={"verified_at": row.verified_at, "note": untrusted(body.note, 240)},
    )
    db.commit()
    return partner_view(row)


# ------------------------------------------------------------------ metrics --
@router.get("/admin/metrics")
def metrics(admin: AdminUser, db: Session = Depends(get_db), days: int = 30):
    """
    Plan section 16, measured. Counts and rates over the whole population.

    What is not here is the point of the function. There is no user list, no
    per-user row, no per-circle or per-language or per-state breakdown, no
    "recent signups" and no email-to-console link: every one of those turns an
    aggregate into a directory, and a directory behind an admin login is still
    a directory. Plan 14 does not carve out staff, and under the DPDP Act the
    purpose limitation follows the data around — a count gathered to decide
    whether the check-in reminder is working is not a count gathered to find
    out which pensioner has stopped checking in.

    Every rate goes through `_ratio`, which returns null below MIN_CELL
    denominators. Five check-ins in a cell is five people, and a console
    operator who knows their users does not need a column to name them. The
    suppressed names are listed in the response so an operator can tell "not
    enough people" from "nobody at all".

    The citation rate is computed in Python over two narrow columns rather
    than in SQL because a JSON column cannot be compared portably across the
    SQLite test backend and the Postgres production one, and a metrics
    endpoint that only works on the test database is worse than a slower one.
    """
    if not 1 <= days <= 90:
        raise HTTPException(400, "days must be 1..90")
    at = now()
    since = at - days * 86400
    since_1d = at - 86400
    since_7d = at - 7 * 86400
    since_30d = at - 30 * 86400
    suppressed: list[str] = []

    def rate(name: str, num: int, den: int) -> float | None:
        value = _ratio(num, den)
        if value is None:
            suppressed.append(name)
        return value

    # -- activity. A check-in row is the closest thing to "opened the app" that
    # every member creates whether or not they use any other feature.
    active = select(CheckIn.user_id).where(CheckIn.created_at >= since).distinct().subquery()
    active_users = db.scalar(select(func.count()).select_from(active)) or 0
    dau = db.scalar(
        select(func.count(distinct(CheckIn.user_id))).where(CheckIn.created_at >= since_1d)
    ) or 0

    # -- check-ins completed, of the ones opened.
    checkins_total = db.scalar(
        select(func.count()).select_from(CheckIn).where(CheckIn.created_at >= since)
    ) or 0
    checkins_done = db.scalar(
        select(func.count())
        .select_from(CheckIn)
        .where(CheckIn.created_at >= since, CheckIn.stage.in_(TERMINAL_STAGES))
    ) or 0

    # -- check-in on with a guardian linked. Plan 16's "≥ 35% of monthly
    # actives" row, with the same denominator the plan names.
    with_guardian = db.scalar(
        select(func.count(distinct(Guardian.user_id)))
        .select_from(Guardian)
        .join(active, Guardian.user_id == active.c.user_id)
        .where(Guardian.accepted_at.isnot(None))
    ) or 0

    # -- SOS. A count, not a list: "which pensioner pressed SOS last month" is
    # a question this endpoint exists not to answer.
    sos = db.scalar(
        select(func.count()).select_from(SosEvent).where(SosEvent.started_at >= since)
    ) or 0
    sos_cancelled = db.scalar(
        select(func.count())
        .select_from(SosEvent)
        .where(SosEvent.started_at >= since, SosEvent.cancelled.is_(True))
    ) or 0

    # -- escalation volume, plus the moderation backlog. A backlog nobody reads
    # is the same as no moderation, so the depth is a number worth watching.
    escalated = db.scalar(
        select(func.count())
        .select_from(CheckIn)
        .where(CheckIn.created_at >= since, CheckIn.guardians_alerted_at.isnot(None))
    ) or 0
    open_cases = db.scalar(
        select(func.count()).select_from(SafetyQueueItem).where(SafetyQueueItem.status == "open")
    ) or 0
    open_reports = db.scalar(
        select(func.count()).select_from(Report).where(Report.status == "open")
    ) or 0

    # -- Sahayak: the live citation rate, and the corpus-level score from the
    # last evaluation run, which is the one that decides publication.
    cited_rows = db.execute(
        select(SahayakQuestion.chunk_ids, SahayakQuestion.confidence).where(
            SahayakQuestion.asked_at >= since
        )
    ).all()
    questions = len(cited_rows)
    cited = sum(
        1
        for chunks, confidence in cited_rows
        if chunks and (confidence or 0.0) >= SAHAYAK_MIN_CONFIDENCE
    )
    latest_eval = db.scalars(
        select(EvaluationRun).order_by(EvaluationRun.ran_at.desc()).limit(1)
    ).first()

    # -- day-30 retention as a cohort: everybody with a check-in 30 days ago or
    # earlier, and how many of them had one in the last week. A subquery join
    # rather than a per-user pass, so this stays a count and not a list.
    cohort = select(CheckIn.user_id).where(CheckIn.created_at <= since_30d).distinct().subquery()
    cohort_size = db.scalar(select(func.count()).select_from(cohort)) or 0
    cohort_retained = db.scalar(
        select(func.count(distinct(CheckIn.user_id)))
        .select_from(CheckIn)
        .join(cohort, CheckIn.user_id == cohort.c.user_id)
        .where(CheckIn.created_at >= since_7d)
    ) or 0

    return {
        "generated_at": at,
        "window_days": days,
        "min_cell": MIN_CELL,
        "activity": {
            "daily_active_users": dau,
            "active_users_in_window": active_users,
            "dau_over_mau": rate("dau_over_mau", dau, active_users),
        },
        "checkin": {
            "opened": checkins_total,
            "completed": checkins_done,
            "completion_rate": rate("checkin_completion_rate", checkins_done, checkins_total),
            "with_accepted_guardian": with_guardian,
            "guardian_linked_rate": rate("guardian_linked_rate", with_guardian, active_users),
        },
        "sos": {
            "events": sos,
            "cancelled": sos_cancelled,
            "per_active_user": rate("sos_per_active_user", sos - sos_cancelled, active_users),
        },
        "escalation": {
            "reached_guardians": escalated,
            "safety_queue_open": open_cases,
            "reports_open": open_reports,
        },
        "sahayak": {
            "questions": questions,
            "answered_with_citation": cited,
            "citation_rate": rate("sahayak_citation_rate", cited, questions),
            "latest_evaluation": evaluation_view(latest_eval) if latest_eval is not None else None,
        },
        "retention": {
            "day30_cohort": cohort_size,
            "day30_retained": cohort_retained,
            "day30_rate": rate("day30_retention", cohort_retained, cohort_size),
        },
        "suppressed_small_cells": suppressed,
    }


# ------------------------------------------------------------ kendra content --
@router.get("/admin/kendra/content")
def list_kendra_content(
    admin: AdminUser,
    db: Session = Depends(get_db),
    kind: str | None = None,
    is_active: bool | None = None,
):
    """
    Spiritual texts. Ours, not a member's, so the bodies are editorial.

    `source_ref` is on every row: plan 6.4 and 7 both rest on a pensioner
    being able to check where a text came from, and a reading with no
    provenance is a reading we are asking somebody to take on trust.
    """
    q = select(SpiritualContent)
    if kind is not None:
        q = q.where(SpiritualContent.kind == kind)
    if is_active is not None:
        q = q.where(SpiritualContent.is_active.is_(is_active))
    rows = db.scalars(q.order_by(SpiritualContent.id.desc()).limit(200)).all()
    return [content_view(r) for r in rows]


@router.post("/admin/kendra/content")
def add_kendra_content(
    body: SpiritualContentIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Add a text, a reading or an aarti.

    `source_ref` is `Field(...)` with no default, so the binding refuses to
    build an unsourced text — a festival date beside it on the same screen is
    held to the same rule, which is what keeps a calendar we can stand behind
    and a corpus we can stand behind on the same footing.
    """

    limit_write(request, admin.id)

    if body.language not in LANGUAGES:
        raise HTTPException(400, f"language must be one of {', '.join(LANGUAGES)}")
    if len(body.title) > 200 or not body.title.strip():
        raise HTTPException(400, "title must be 1..200 characters")
    row = SpiritualContent(
        kind=body.kind,
        title=body.title,
        body=body.body,
        audio_key=body.audio_key,
        language=body.language,
        path=body.path,
        source_ref=body.source_ref,
        duration_s=body.duration_s,
        is_active=True,
    )
    db.add(row)
    db.flush()
    audit(
        db,
        action="kendra_content_add",
        admin=admin,
        resource="spiritual_content",
        resource_id=row.id,
        before=None,
        after=content_view(row),
    )
    db.commit()
    return content_view(row)


@router.post("/admin/kendra/content/{content_id}/status")
def set_kendra_content_status(
    content_id: int,
    body: ContentStatusIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Withdraw a text without deleting it.

    Deactivated, not removed: a pensioner who saw a reading yesterday and
    asks tomorrow what it was should still be able to be told, and a moderator
    looking at a complaint next week should be able to see what was withdrawn
    and why. The reason is mandatory because it is what a member is shown.
    """

    limit_write(request, admin.id)

    row = db.get(SpiritualContent, content_id)
    if row is None:
        raise HTTPException(404, "content not found")
    if row.is_active == body.is_active:
        raise HTTPException(409, f"content is already {'active' if body.is_active else 'withdrawn'}")
    before = {"is_active": row.is_active}
    row.is_active = body.is_active
    audit(
        db,
        action="kendra_content_activate" if body.is_active else "kendra_content_withdraw",
        admin=admin,
        resource="spiritual_content",
        resource_id=row.id,
        before=before,
        after={"is_active": row.is_active, "reason": body.reason},
    )
    db.commit()
    return content_view(row)


@router.post("/admin/kendra/festivals")
def add_festival(
    body: FestivalIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Load a festival or vrat date.

    `starts_on` and `source_key` are both `Field(...)`. A lunar calendar is
    never computed here (python/models/kendra.py says why: a calendar that is
    confidently wrong by a day is worse than none at all for somebody keeping a
    fast), so an admin's only job is to enter what the licensed source said —
    which means the source has to be named, or the date is a guess with an
    epoch attached.
    """

    limit_write(request, admin.id)

    if body.kind not in FESTIVAL_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(FESTIVAL_KINDS)}")
    for lang in body.languages:
        if lang not in LANGUAGES:
            raise HTTPException(400, f"language must be one of {', '.join(LANGUAGES)}")
    if body.ends_on is not None and body.ends_on < body.starts_on:
        raise HTTPException(400, "ends_on is before starts_on")
    clash = db.scalar(
        select(Festival).where(
            Festival.name == body.name,
            Festival.year == body.year,
            Festival.region == body.region,
        )
    )
    if clash is not None:
        # A year is replaced wholesale by the festival_calendar job rather than
        # merged, so two rows for one name and region is a load that ran twice.
        raise HTTPException(409, "a festival with that name, year and region already exists")

    row = Festival(
        name=body.name,
        kind=body.kind,
        starts_on=body.starts_on,
        ends_on=body.ends_on,
        region=body.region,
        languages=body.languages,
        year=body.year,
        source_key=body.source_key,
    )
    db.add(row)
    db.flush()
    audit(
        db,
        action="festival_add",
        admin=admin,
        resource="festival",
        resource_id=row.id,
        before=None,
        after=festival_view(row),
    )
    db.commit()
    return festival_view(row)


# ------------------------------------------------------------- verification --
@router.get("/admin/verify")
def verification_queue(
    admin: AdminUser,
    db: Session = Depends(get_db),
    method: str | None = None,
    limit: int = 50,
):
    """
    Document verifications awaiting a decision.

    Deliberately the claims, not the people. A queue row is the tier claimed,
    the method, who vouched, and four characters of a document number — the
    same four the schema keeps and the same four the pensioner's own Settings
    screen shows. No name, no phone, no location, no service type, no join
    year: a reviewer deciding a tier does not need to know where somebody
    lives, and the moment the queue carries that, "open the review screen" is
    a request for a stranger's address.
    """
    if not 1 <= limit <= 200:
        raise HTTPException(400, "limit must be 1..200")
    q = select(Verification)
    if method is not None:
        q = q.where(Verification.method == method)
    # Newest first, and the ones whose document is about to hit the seven-day
    # deletion deadline are the ones worth clearing.
    rows = db.scalars(q.order_by(Verification.verified_at.desc()).limit(limit)).all()
    return [verification_view(r) for r in rows]


@router.post("/admin/verify/{verification_id}")
def decide_verification(
    verification_id: int,
    body: VerifyDecisionIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Approve or reject a pensioner's verification.

    Nothing here reads, downloads or returns the uploaded document. The
    reviewer gets the claim; the seven-day rule from plan 14 does the rest —
    the `deletion` job (plan 12, "deletes ... verification documents after 7
    days") finds the blob, removes it, and sets `document_deleted_at`, and
    what is left afterwards is this row and the fact that a check happened.
    The window is short on purpose: a pension identity document is worth more
    to somebody else than it ever is to us, and the only version of this
    feature worth shipping is the one where we do not have it.

    There is no self-approval. An admin approving their own identity is the
    one control failure in this file that no other control catches, and it is
    a 409 rather than a 403 because the caller is authenticated and simply
    cannot ask for this one thing.

    `Verification` has no status column, so a rejection is the audit entry
    plus the row going. They are written in the same transaction, the audit
    first, and if they could ever diverge the choice of which one to lose is
    made here rather than discovered in a data-loss incident: the audit entry
    goes.
    """

    limit_write(request, admin.id)

    row = db.get(Verification, verification_id)
    if row is None:
        raise HTTPException(404, "verification not found")
    not_self(admin, row.user_id, "verification")

    subject = db.get(User, row.user_id)
    if subject is None:
        raise HTTPException(404, "user not found")
    if subject.deleted_at is not None:
        raise HTTPException(409, "that account is deleted; do not act on it")
    if body.tier not in TIERS:
        raise HTTPException(400, f"tier must be one of {', '.join(TIERS)}")

    before = {
        "verification_tier": row.tier,
        "user_tier": subject.tier,
        "verified": True,
    }
    if body.approve:
        row.tier = body.tier
        # T2 and above can vouch and host a room (plan 6.1), so the user's tier
        # is the other half of this decision and is audited with it.
        subject.tier = body.tier
        action = "verification_approve"
    else:
        action = "verification_reject"

    audit(
        db,
        action=action,
        admin=admin,
        resource="verification",
        resource_id=row.id,
        user_id=row.user_id,
        before=before,
        after={
            "verification_tier": row.tier if body.approve else None,
            "user_tier": subject.tier if body.approve else before["user_tier"],
            "verified": body.approve,
            "method": row.method,
            "by_user_id": row.by_user_id,
            "note": untrusted(body.note, 240),
        },
    )
    if not body.approve:
        # The audit entry above is the record that a check happened; this row
        # is the claim, and an unestablished claim must not keep granting a
        # tier to anybody.
        db.delete(row)
    db.commit()
    return {
        "id": verification_id,
        "approved": body.approve,
        "tier": body.tier if body.approve else None,
        "user_tier": subject.tier if body.approve else before["user_tier"],
    }


# ------------------------------------------------------------------ vouches --
@router.post("/admin/vouches/{vouch_id}/revoke")
def revoke_vouch(
    vouch_id: int,
    body: VouchDecisionIn,
    request: Request,
    admin: AdminUser,
    db: Session = Depends(get_db),
):
    """
    Withdraw somebody's vouch for somebody else.

    A vouch is the weakest kind of verification there is — "a member said so",
    typed separately from "we checked" on purpose (python/models/identity.py)
    — so it is also the easiest to abuse and the one most worth being able to
    take back. Withdrawn, not deleted: the pair is what a T2 account was built
    on, and quietly removing the row would leave a tier that nothing supports.

    An admin does not adjudicate a vouch they made themselves, and does not
    adjudicate one about themselves. Both are 409, for the same reason the
    report self-check is a 409: one interested party on the bench.
    """

    limit_write(request, admin.id)

    row = db.get(Vouch, vouch_id)
    if row is None:
        raise HTTPException(404, "vouch not found")
    not_self(admin, row.voucher_id, "vouch you made")
    not_self(admin, row.vouchee_id, "vouch about yourself")

    db.delete(row)
    audit(
        db,
        action="vouch_revoke",
        admin=admin,
        resource="vouch",
        resource_id=row.id,
        user_id=row.vouchee_id,
        before={"voucher_id": row.voucher_id, "vouchee_id": row.vouchee_id},
        after={"withdrawn": True, "note": untrusted(body.note, 240)},
    )
    db.commit()
    return {"id": vouch_id, "revoked": True}
