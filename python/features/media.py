# =============================================================================
# Feature · Uploads and media — plan sections 6.6, 6.9, 9 and 14.
#
#   POST /uploads                  ask to upload a file -> a presigned URL
#   POST /uploads/{id}/complete    "I finished uploading" -> checked and marked ready
#   GET  /uploads/{id}
#   POST /media/sign               turn storage keys into short-lived download URLs
#   PUT  /media/upload/{token}     (local-storage backend only) receives the bytes
#   GET  /media/file/{token}       (local-storage backend only) serves the bytes
#
# The phone uploads DIRECT to object storage through a presigned URL so a
# ten-minute memoir recording never streams through the API. The server decides
# the extension and the content type from the purpose - the client's claim about
# either is not trusted - and after the upload it checks the object's real size
# and, for images, its magic bytes, so a renamed archive cannot pass as a photo.
#
# Keys, not URLs, are what the rest of the database stores. A key is unguessable
# (a uuid under purpose/user/), and it is only ever disclosed to a client
# through an endpoint that already checked the caller may see the thing it
# belongs to. Signing therefore trusts possession of a key for the ordinary
# purposes, and applies an explicit check for the sensitive ones: a
# verification document and a vault backup are owner-only, a story follows its
# visibility, and chat media follows conversation membership.
# =============================================================================
from __future__ import annotations

import logging
import mimetypes
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy import select

from python import config
from python.deps import CurrentUser, DbSession
from python.integrations import storage
from python.models import ChatMessage, ConversationMember, Story, Upload, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.media")
router = APIRouter(tags=["media"])

# Chat media reuses the image/audio rules with its own purpose so it can be
# access-controlled by conversation rather than treated as public-by-key.
storage.PURPOSES.setdefault(
    "chat_image", dict(storage.PURPOSES["post_image"])
)
storage.PURPOSES.setdefault(
    "chat_voice", dict(storage.PURPOSES["voice_note"])
)

OWNER_ONLY = {"verification_doc", "vault_backup", "vault_share", "vault_ice"}
ANY_SIGNED_IN = {"post_image", "listing_image", "avatar", "voice_note"}


class UploadIn(BaseModel):
    purpose: str
    content_type: str = Field(max_length=64)
    size: int = Field(ge=1)


class SignIn(BaseModel):
    keys: list[str] = Field(min_length=1, max_length=30)


# ------------------------------------------------------------------ sniffing --
def sniff_ok(data: bytes, declared: str) -> bool:
    """Do the first bytes agree with the declared type? Unknown types pass through."""
    head = data[:16]
    if declared == "image/jpeg":
        return head.startswith(b"\xff\xd8\xff")
    if declared == "image/png":
        return head.startswith(b"\x89PNG\r\n\x1a\n")
    if declared == "image/webp":
        return head[:4] == b"RIFF" and data[8:12] == b"WEBP"
    if declared == "application/pdf":
        return head.startswith(b"%PDF")
    return True


def upload_view(u: Upload) -> dict:
    return {
        "id": u.id, "key": u.key, "purpose": u.purpose, "content_type": u.content_type,
        "size": u.size, "status": u.status, "created_at": u.created_at,
    }


# ---------------------------------------------------------------- endpoints --
@router.post("/uploads")
def create_upload(body: UploadIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    spec = storage.PURPOSES.get(body.purpose)
    if spec is None:
        raise HTTPException(400, f"purpose must be one of {', '.join(sorted(storage.PURPOSES))}")
    ext = spec["types"].get(body.content_type)
    if ext is None:
        raise HTTPException(400, f"{body.purpose} accepts: {', '.join(sorted(spec['types']))}")
    limit = min(spec["max"], config.UPLOAD_MAX_BYTES)
    if body.size > limit:
        raise HTTPException(413, f"that file is larger than the {limit // (1024 * 1024)} MB allowed")

    key = storage.new_key(body.purpose, user.id, ext)
    up = Upload(user_id=user.id, key=key, purpose=body.purpose, content_type=body.content_type, size=body.size)
    db.add(up)
    db.commit()
    return {**upload_view(up), "upload": storage.presign_upload(key, body.content_type, limit)}


@router.post("/uploads/{upload_id}/complete")
def complete_upload(upload_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    up = db.get(Upload, upload_id)
    if up is None or up.user_id != user.id:
        raise HTTPException(404, "upload not found")
    if up.status == "ready":
        return upload_view(up)
    real = storage.size(up.key)
    if real is None:
        raise HTTPException(409, "the file has not arrived yet; try again in a moment")
    limit = min(storage.PURPOSES[up.purpose]["max"], config.UPLOAD_MAX_BYTES)
    if real > limit or real == 0:
        storage.delete(up.key)
        db.delete(up)
        db.commit()
        raise HTTPException(413, "the file is empty or too large and was discarded")
    if up.content_type.startswith("image/") or up.content_type == "application/pdf":
        head = storage.get_bytes(up.key)
        if head is None or not sniff_ok(head, up.content_type):
            storage.delete(up.key)
            db.delete(up)
            db.commit()
            raise HTTPException(400, "the file is not what it says it is and was discarded")
    up.size, up.status, up.ready_at = real, "ready", now()
    db.commit()
    return upload_view(up)


@router.get("/uploads/{upload_id}")
def get_upload(upload_id: int, user: CurrentUser, db: DbSession):
    up = db.get(Upload, upload_id)
    if up is None or up.user_id != user.id:
        raise HTTPException(404, "upload not found")
    return upload_view(up)


def can_view(db, user, key: str) -> bool:  # noqa: ANN001
    if not storage.valid_key(key):
        return False
    up = db.scalar(select(Upload).where(Upload.key == key))
    if up is not None and up.user_id == user.id:
        return True
    purpose = key.split("/", 1)[0]
    if purpose in OWNER_ONLY:
        return False
    if purpose == "story_audio":
        story = db.scalar(select(Story).where(Story.media_key == key))
        if story is None:
            return False
        return story.author_id == user.id or (story.visibility != "only_me" and not story.hidden)
    if purpose in ("chat_image", "chat_voice"):
        msg = db.scalar(select(ChatMessage).where(ChatMessage.media_key == key))
        if msg is None:
            return False
        return db.scalar(
            select(ConversationMember.id).where(
                ConversationMember.conversation_id == msg.conversation_id, ConversationMember.user_id == user.id
            )
        ) is not None
    return purpose in ANY_SIGNED_IN or purpose in ("sahayak", "books", "spiritual", "lessons", "public")


@router.post("/media/sign")
def sign(body: SignIn, user: CurrentUser, db: DbSession):
    """Short-lived download URLs. A key the caller may not view comes back null."""
    urls: dict[str, str | None] = {}
    for key in dict.fromkeys(body.keys):
        urls[key] = storage.presign_download(key, ttl=3600) if can_view(db, user, key) else None
    return {"urls": urls, "expires_in": 3600}


# ------------------------------------------- local-storage backend (dev / 1 box) --
@router.put("/media/upload/{token}")
async def local_upload(token: str, request: Request):
    if storage.uses_s3():
        raise HTTPException(404, "not found")
    info = storage.read_local_token(token, "put")
    if info is None:
        raise HTTPException(403, "this upload link is invalid or has expired")
    declared = request.headers.get("content-type", "").split(";")[0].strip()
    if info["content_type"] and declared and declared != info["content_type"]:
        raise HTTPException(415, "content type does not match the upload request")
    length = request.headers.get("content-length")
    if length and info["max_bytes"] and int(length) > info["max_bytes"]:
        raise HTTPException(413, "file too large")
    data = await request.body()
    if info["max_bytes"] and len(data) > info["max_bytes"]:
        raise HTTPException(413, "file too large")
    if not storage.put_bytes(info["key"], data, info["content_type"] or "application/octet-stream"):
        raise HTTPException(500, "could not store the file")
    return Response(status_code=200)


@router.get("/media/file/{token}")
def local_file(token: str):
    if storage.uses_s3():
        raise HTTPException(404, "not found")
    info = storage.read_local_token(token, "get")
    if info is None:
        raise HTTPException(403, "this link is invalid or has expired")
    path = Path(config.STORAGE_DIR) / info["key"]
    if not path.is_file():
        raise HTTPException(404, "not found")
    ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return FileResponse(path, media_type=ctype, headers={"Cache-Control": "private, max-age=3600"})
