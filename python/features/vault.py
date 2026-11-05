# =============================================================================
# Feature · The document safe's optional server side — plan section 6.5.
#
# The safe itself lives ON THE PHONE (Keystore / Keychain, plan 6.5 and 14). The
# server holds only ciphertext it cannot read:
#
#   GET    /vault/status
#   POST   /vault/backup                 optional encrypted backup (user's passphrase)
#   POST   /vault/backup/{id}/complete
#   GET    /vault/backup/latest          a short-lived download URL
#   DELETE /vault/backup/{id}
#   POST   /vault/share                  one document, an encrypted link, 24 hours
#   POST   /vault/share/{id}/complete    -> the link
#   GET    /vault/s/{token}              public: the link a family member opens
#   POST   /vault/ice                    the "in case of emergency" folder
#   POST   /vault/ice/{id}/complete
#   POST   /vault/ice/request            a guardian asks to open it
#   GET    /vault/ice/requests           the owner's pending requests
#   POST   /vault/ice/requests/{id}/decide
#   GET    /vault/ice/{user_id}          a guardian downloads it, if allowed
#
# THE PASSPHRASE IS THE USER'S ALONE (plan 6.5). It never reaches this server, so
# a backup cannot be recovered if it is lost - the app says so before it lets
# anyone turn the backup on, and nothing here offers a recovery path, because any
# recovery path is a way for someone other than the owner to read the papers.
#
# The emergency folder opens to a guardian only when they hold the
# `document_safe` permission AND the owner approved the request, or the owner
# chose "open after an SOS" and an SOS was actually raised. A request that goes
# unanswered opens nothing.
# =============================================================================
from __future__ import annotations

import hashlib
import logging
import secrets

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select

from python import config
from python.deps import CurrentUser, DbSession
from python.dispatch import accepted_guardians, notify_user
from python.features.prefs import prefs_for
from python.integrations import storage
from python.models import (
    Guardian,
    User,
    VaultBlob,
    VaultIceRequest,
    VaultShareLink,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.vault")
router = APIRouter(tags=["vault"])

MAX_BACKUPS_KEPT = 3
SHARE_TTL_S = 24 * 3600
SHARE_MAX_DOWNLOADS = 3
ICE_OPEN_TTL_S = 24 * 3600

storage.PURPOSES.setdefault(
    "vault_share", {"types": {"application/octet-stream": "bin"}, "max": 15 * 1024 * 1024}
)
storage.PURPOSES.setdefault(
    "vault_ice", {"types": {"application/octet-stream": "bin"}, "max": 25 * 1024 * 1024}
)


class BlobIn(BaseModel):
    size: int = Field(ge=1, le=25 * 1024 * 1024)
    sha256: str | None = Field(default=None, pattern=r"^[0-9a-fA-F]{64}$")
    label: str | None = Field(default=None, max_length=80)


class IceRequestIn(BaseModel):
    user_id: int = Field(ge=1)


class DecideIn(BaseModel):
    approve: bool


def _blob_view(b: VaultBlob) -> dict:
    return {
        "id": b.id, "kind": b.kind, "size": b.size, "label": b.label,
        "created_at": b.created_at, "expires_at": b.expires_at,
    }


def _new_blob(db, user, kind: str, purpose: str, body: BlobIn, ttl: int | None = None) -> tuple[VaultBlob, dict]:
    key = storage.new_key(purpose, user.id, "bin")
    blob = VaultBlob(
        user_id=user.id, kind=kind, key=key, size=0, sha256=body.sha256, label=body.label,
        expires_at=(now() + ttl) if ttl else None,
    )
    db.add(blob)
    db.flush()
    return blob, storage.presign_upload(key, "application/octet-stream", storage.PURPOSES[purpose]["max"])


def _complete(db, blob: VaultBlob) -> None:
    real = storage.size(blob.key)
    if not real:
        raise HTTPException(409, "the upload has not arrived yet; try again in a moment")
    blob.size = real


# ------------------------------------------------------------------ backup --
@router.get("/vault/status")
def status(user: CurrentUser, db: DbSession):
    blobs = db.scalars(select(VaultBlob).where(VaultBlob.user_id == user.id, VaultBlob.size > 0)).all()
    backups = sorted((b for b in blobs if b.kind == "backup"), key=lambda b: -b.created_at)
    ice = next((b for b in sorted(blobs, key=lambda b: -b.created_at) if b.kind == "ice"), None)
    return {
        "backup": _blob_view(backups[0]) if backups else None,
        "backups_kept": len(backups),
        "emergency_folder": _blob_view(ice) if ice else None,
        "passphrase_warning": (
            "Your backup is locked with your passphrase and we cannot open it. "
            "If you lose the passphrase, the backup cannot be recovered."
        ),
    }


@router.post("/vault/backup")
def create_backup(body: BlobIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob, upload = _new_blob(db, user, "backup", "vault_backup", body)
    db.commit()
    return {**_blob_view(blob), "upload": upload}


@router.post("/vault/backup/{blob_id}/complete")
def complete_backup(blob_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob = db.get(VaultBlob, blob_id)
    if blob is None or blob.user_id != user.id or blob.kind != "backup":
        raise HTTPException(404, "backup not found")
    _complete(db, blob)
    # Keep the newest few; a backup you can no longer name is a backup you
    # cannot trust, and unbounded copies of someone's papers is not a feature.
    older = db.scalars(
        select(VaultBlob).where(VaultBlob.user_id == user.id, VaultBlob.kind == "backup", VaultBlob.size > 0)
        .order_by(VaultBlob.created_at.desc())
    ).all()
    for old in older[MAX_BACKUPS_KEPT:]:
        if storage.delete(old.key):
            db.delete(old)
    db.commit()
    return _blob_view(blob)


@router.get("/vault/backup/latest")
def latest_backup(user: CurrentUser, db: DbSession):
    blob = db.scalars(
        select(VaultBlob).where(VaultBlob.user_id == user.id, VaultBlob.kind == "backup", VaultBlob.size > 0)
        .order_by(VaultBlob.created_at.desc()).limit(1)
    ).first()
    if blob is None:
        raise HTTPException(404, "no backup yet")
    return {**_blob_view(blob), "sha256": blob.sha256, "url": storage.presign_download(blob.key, 600)}


@router.delete("/vault/backup/{blob_id}")
def delete_backup(blob_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob = db.get(VaultBlob, blob_id)
    if blob is None or blob.user_id != user.id or blob.kind != "backup":
        raise HTTPException(404, "backup not found")
    if not storage.delete(blob.key):
        raise HTTPException(502, "could not delete the backup right now; try again")
    db.delete(blob)
    db.commit()
    return {"deleted": blob_id}


# ------------------------------------------------------------------- share --
@router.post("/vault/share")
def create_share(body: BlobIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob, upload = _new_blob(db, user, "share", "vault_share", body, ttl=SHARE_TTL_S)
    db.commit()
    return {**_blob_view(blob), "upload": upload}


@router.post("/vault/share/{blob_id}/complete")
def complete_share(blob_id: int, request: Request, user: CurrentUser, db: DbSession):
    """The link, valid 24 hours and 3 downloads. Only the token's digest is stored."""
    limit_write(request, user.id)
    blob = db.get(VaultBlob, blob_id)
    if blob is None or blob.user_id != user.id or blob.kind != "share":
        raise HTTPException(404, "share not found")
    _complete(db, blob)
    token = secrets.token_urlsafe(24)
    db.add(VaultShareLink(
        blob_id=blob.id, token_hash=hashlib.sha256(token.encode()).hexdigest(),
        expires_at=blob.expires_at or (now() + SHARE_TTL_S), max_downloads=SHARE_MAX_DOWNLOADS,
    ))
    db.commit()
    return {
        **_blob_view(blob),
        "link": f"{config.PUBLIC_BASE_URL}/vault/s/{token}",
        "expires_at": blob.expires_at,
        "max_downloads": SHARE_MAX_DOWNLOADS,
        "note": "The person you send this to needs the passphrase you chose. Tell them separately.",
    }


@router.get("/vault/s/{token}")
def open_share(token: str, request: Request, db: DbSession):
    """Public. The token is the credential; the file is ciphertext without the passphrase."""
    limit_write(request, None)
    link = db.scalar(
        select(VaultShareLink).where(VaultShareLink.token_hash == hashlib.sha256(token.encode()).hexdigest())
    )
    if link is None or link.expires_at < now() or link.downloads >= link.max_downloads:
        raise HTTPException(410, "this link has expired")
    blob = db.get(VaultBlob, link.blob_id)
    if blob is None:
        raise HTTPException(410, "this link has expired")
    link.downloads += 1
    db.commit()
    return {"url": storage.presign_download(blob.key, 300), "label": blob.label, "size": blob.size}


# --------------------------------------------------------------- emergency --
@router.post("/vault/ice")
def create_ice(body: BlobIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob, upload = _new_blob(db, user, "ice", "vault_ice", body)
    db.commit()
    return {**_blob_view(blob), "upload": upload}


@router.post("/vault/ice/{blob_id}/complete")
def complete_ice(blob_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    blob = db.get(VaultBlob, blob_id)
    if blob is None or blob.user_id != user.id or blob.kind != "ice":
        raise HTTPException(404, "emergency folder not found")
    _complete(db, blob)
    for old in db.scalars(
        select(VaultBlob).where(VaultBlob.user_id == user.id, VaultBlob.kind == "ice", VaultBlob.id != blob.id)
    ).all():
        if storage.delete(old.key):
            db.delete(old)
    db.commit()
    return _blob_view(blob)


def _guardian_link(db, guardian_user: User, owner_id: int) -> Guardian | None:
    g = db.scalar(
        select(Guardian).where(
            Guardian.user_id == owner_id, Guardian.guardian_user_id == guardian_user.id,
            Guardian.accepted_at.isnot(None),
        )
    )
    if g is None or not (g.perms or {}).get("document_safe"):
        return None
    return g


@router.post("/vault/ice/request")
def request_ice(body: IceRequestIn, request: Request, user: CurrentUser, db: DbSession):
    """A guardian with the document-safe permission asks the owner to open it."""
    limit_write(request, user.id)
    g = _guardian_link(db, user, body.user_id)
    if g is None:
        # 404, not 403: whether someone keeps papers at all is not for a
        # stranger to learn from the status code.
        raise HTTPException(404, "not found")
    owner = db.get(User, body.user_id)
    has_ice = db.scalar(
        select(VaultBlob.id).where(VaultBlob.user_id == owner.id, VaultBlob.kind == "ice", VaultBlob.size > 0)
    )
    if has_ice is None:
        raise HTTPException(404, "not found")
    pending = db.scalar(
        select(VaultIceRequest).where(
            VaultIceRequest.user_id == owner.id, VaultIceRequest.guardian_id == g.id,
            VaultIceRequest.status == "pending",
        )
    )
    if pending is None:
        pending = VaultIceRequest(user_id=owner.id, guardian_id=g.id, reason="request")
        db.add(pending)
        db.flush()
        notify_user(
            db, owner, kind=f"guardian_ice{pending.id}", urgent=True,
            title="Someone asked to open your emergency folder",
            body=f"{g.name} asked to open your emergency folder. Open Doosri Pari to allow or refuse.",
            payload={"open": "vault_requests", "request_id": pending.id},
            channels=("push", "sms"),
        )
    db.commit()
    return {"request_id": pending.id, "status": pending.status}


@router.get("/vault/ice/requests")
def list_requests(user: CurrentUser, db: DbSession):
    rows = db.scalars(
        select(VaultIceRequest).where(VaultIceRequest.user_id == user.id, VaultIceRequest.status == "pending")
        .order_by(VaultIceRequest.requested_at.desc())
    ).all()
    out = []
    for r in rows:
        g = db.get(Guardian, r.guardian_id)
        out.append({"id": r.id, "guardian": g.name if g else None, "relation": g.relation if g else None,
                    "requested_at": r.requested_at})
    return {"requests": out}


@router.post("/vault/ice/requests/{request_id}/decide")
def decide(request_id: int, body: DecideIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    r = db.get(VaultIceRequest, request_id)
    if r is None or r.user_id != user.id:
        raise HTTPException(404, "request not found")
    if r.status != "pending":
        return {"id": r.id, "status": r.status}
    r.status = "approved" if body.approve else "denied"
    r.decided_at = now()
    r.expires_at = now() + ICE_OPEN_TTL_S if body.approve else None
    db.commit()
    return {"id": r.id, "status": r.status, "expires_at": r.expires_at}


@router.get("/vault/ice/{owner_id}")
def open_ice(owner_id: int, user: CurrentUser, db: DbSession):
    """Download URL for the emergency folder, only inside an approved window."""
    g = _guardian_link(db, user, owner_id)
    if g is None:
        raise HTTPException(404, "not found")
    ok = db.scalar(
        select(VaultIceRequest).where(
            VaultIceRequest.user_id == owner_id, VaultIceRequest.guardian_id == g.id,
            VaultIceRequest.status == "approved", VaultIceRequest.expires_at > now(),
        ).order_by(VaultIceRequest.decided_at.desc()).limit(1)
    )
    if ok is None:
        raise HTTPException(403, "the owner has not allowed this yet")
    blob = db.scalars(
        select(VaultBlob).where(VaultBlob.user_id == owner_id, VaultBlob.kind == "ice", VaultBlob.size > 0)
        .order_by(VaultBlob.created_at.desc()).limit(1)
    ).first()
    if blob is None:
        raise HTTPException(404, "not found")
    return {**_blob_view(blob), "url": storage.presign_download(blob.key, 600), "sha256": blob.sha256,
            "opened_because": ok.reason, "expires_at": ok.expires_at}


def open_ice_after_sos(db, user: User, sos_id: int) -> int:
    """
    Called by POST /sos. If the person chose "open the emergency folder after an
    SOS" (plan 6.5), every guardian who holds the document-safe permission gets an
    approved 24-hour window. Returns how many. Nothing opens without that choice.
    """
    prefs = prefs_for(db, user.id)
    if not prefs.ice_after_sos:
        return 0
    has_ice = db.scalar(
        select(VaultBlob.id).where(VaultBlob.user_id == user.id, VaultBlob.kind == "ice", VaultBlob.size > 0)
    )
    if has_ice is None:
        return 0
    n = 0
    for g in accepted_guardians(db, user.id, "document_safe"):
        db.add(VaultIceRequest(
            user_id=user.id, guardian_id=g.id, status="approved", reason=f"sos{sos_id}",
            decided_at=now(), expires_at=now() + ICE_OPEN_TTL_S,
        ))
        n += 1
    db.commit()
    return n
