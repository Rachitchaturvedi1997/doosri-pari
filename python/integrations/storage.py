# =============================================================================
# Object storage. Plan sections 9 and 14.
#
# Photos, voice notes, memoir chapters and the encrypted document-safe backups
# live here. Two backends behind one interface:
#
#   * S3 (or anything S3-compatible: MinIO, R2, localstack) via boto3, in
#     ap-south-1 so data stays in India (plan 9);
#   * a directory on disk, for development and for a single-box deployment.
#
# Keys, not URLs, are what the database stores: a story outlives whatever bucket
# it was uploaded to (python/models/content.py). URLs are minted per request and
# expire.
#
# Uploads go DIRECT from the phone to storage through a presigned URL, so a
# 10-minute memoir recording never streams through the API process. The local
# backend has no presigning, so it serves uploads through /media/upload/{token}
# (python/features/media.py) with an HMAC token that plays the same role.
# =============================================================================
from __future__ import annotations

import hashlib
import hmac
import logging
import os
import re
import time
import uuid
from pathlib import Path

from python import config

log = logging.getLogger("doosri.storage")

_SAFE_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/\-]{0,240}$")

# What each upload purpose may contain. The server decides the extension and the
# content type; the client's claim is not trusted (a ".jpg" that is an APK).
PURPOSES = {
    "post_image": {"types": {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}, "max": 8 * 1024 * 1024},
    "voice_note": {"types": {"audio/mp4": "m4a", "audio/aac": "aac", "audio/mpeg": "mp3", "audio/ogg": "ogg", "audio/webm": "webm"}, "max": 12 * 1024 * 1024},
    "story_audio": {"types": {"audio/mp4": "m4a", "audio/aac": "aac", "audio/mpeg": "mp3", "audio/ogg": "ogg", "audio/webm": "webm"}, "max": 25 * 1024 * 1024},
    "verification_doc": {"types": {"image/jpeg": "jpg", "image/png": "png", "application/pdf": "pdf"}, "max": 10 * 1024 * 1024},
    "vault_backup": {"types": {"application/octet-stream": "bin"}, "max": 25 * 1024 * 1024},
    "listing_image": {"types": {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}, "max": 8 * 1024 * 1024},
    "avatar": {"types": {"image/jpeg": "jpg", "image/png": "png", "image/webp": "webp"}, "max": 4 * 1024 * 1024},
}


def valid_key(key: str) -> bool:
    return bool(key) and bool(_SAFE_KEY.match(key)) and ".." not in key


def new_key(purpose: str, user_id: int, ext: str) -> str:
    """A key nobody can guess: purpose/user/uuid.ext."""
    return f"{purpose}/{user_id}/{uuid.uuid4().hex}.{ext}"


# ------------------------------------------------------------------ backends --
def _s3():
    import boto3  # noqa: PLC0415 — optional dependency
    from botocore.client import Config

    kwargs = {"region_name": config.S3_REGION, "config": Config(signature_version="s3v4")}
    if config.S3_ENDPOINT:
        kwargs["endpoint_url"] = config.S3_ENDPOINT
    return boto3.client("s3", **kwargs)


def uses_s3() -> bool:
    return bool(config.S3_BUCKET)


def _local_path(key: str) -> Path:
    root = Path(config.STORAGE_DIR).resolve()
    p = (root / key).resolve()
    if root not in p.parents:
        raise ValueError("key escapes storage root")
    return p


# ------------------------------------------------------------------- presign --
def presign_upload(key: str, content_type: str, max_bytes: int) -> dict:
    """
    How the phone uploads. Returns {"method","url","headers","expires_in"}.

    For S3 this is a presigned PUT pinned to the content type. For local storage
    it is a signed URL on this API, which python/features/media.py verifies.
    """
    ttl = config.UPLOAD_URL_TTL_S
    if uses_s3():
        try:
            url = _s3().generate_presigned_url(
                "put_object",
                Params={"Bucket": config.S3_BUCKET, "Key": key, "ContentType": content_type},
                ExpiresIn=ttl,
            )
            return {"method": "PUT", "url": url, "headers": {"Content-Type": content_type}, "expires_in": ttl}
        except Exception as exc:
            log.warning("presign failed: %s", type(exc).__name__)
    token = _sign_local("put", key, ttl, content_type, max_bytes)
    return {
        "method": "PUT",
        "url": f"{config.PUBLIC_BASE_URL}/media/upload/{token}",
        "headers": {"Content-Type": content_type},
        "expires_in": ttl,
    }


def presign_download(key: str, ttl: int = 3600) -> str | None:
    if not valid_key(key):
        return None
    if uses_s3():
        try:
            return _s3().generate_presigned_url(
                "get_object", Params={"Bucket": config.S3_BUCKET, "Key": key}, ExpiresIn=ttl
            )
        except Exception as exc:
            log.warning("presign download failed: %s", type(exc).__name__)
            return None
    return f"{config.PUBLIC_BASE_URL}/media/file/{_sign_local('get', key, ttl)}"


# --------------------------------------------------------- local token signing --
def _mac(payload: str) -> str:
    return hmac.new(
        config.SECRET_KEY.encode(), b"doosri:media:v1:" + payload.encode(), hashlib.sha256
    ).hexdigest()[:32]


def _sign_local(op: str, key: str, ttl: int, content_type: str = "", max_bytes: int = 0) -> str:
    exp = int(time.time()) + ttl
    ct = content_type.replace("/", "_")
    payload = f"{op}|{exp}|{ct}|{max_bytes}|{key}"
    import base64

    raw = base64.urlsafe_b64encode(payload.encode()).decode().rstrip("=")
    return f"{raw}.{_mac(payload)}"


def read_local_token(token: str, op: str) -> dict | None:
    import base64

    try:
        raw, sig = token.rsplit(".", 1)
        payload = base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)).decode()
        if not hmac.compare_digest(sig, _mac(payload)):
            return None
        p_op, exp, ct, mx, key = payload.split("|", 4)
        if p_op != op or int(exp) < time.time() or not valid_key(key):
            return None
        return {"key": key, "content_type": ct.replace("_", "/"), "max_bytes": int(mx)}
    except Exception:
        return None


# --------------------------------------------------------------- operations --
def put_bytes(key: str, data: bytes, content_type: str = "application/octet-stream") -> bool:
    if not valid_key(key):
        return False
    try:
        if uses_s3():
            _s3().put_object(Bucket=config.S3_BUCKET, Key=key, Body=data, ContentType=content_type)
        else:
            p = _local_path(key)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        return True
    except Exception as exc:
        log.warning("put failed: %s", type(exc).__name__)
        return False


def get_bytes(key: str) -> bytes | None:
    if not valid_key(key):
        return None
    try:
        if uses_s3():
            return _s3().get_object(Bucket=config.S3_BUCKET, Key=key)["Body"].read()
        p = _local_path(key)
        return p.read_bytes() if p.is_file() else None
    except Exception as exc:
        log.warning("get failed: %s", type(exc).__name__)
        return None


def exists(key: str) -> bool:
    if not valid_key(key):
        return False
    try:
        if uses_s3():
            _s3().head_object(Bucket=config.S3_BUCKET, Key=key)
            return True
        return _local_path(key).is_file()
    except Exception:
        return False


def size(key: str) -> int | None:
    try:
        if uses_s3():
            return int(_s3().head_object(Bucket=config.S3_BUCKET, Key=key)["ContentLength"])
        p = _local_path(key)
        return p.stat().st_size if p.is_file() else None
    except Exception:
        return None


def delete(key: str) -> bool:
    """True if the object is gone (or was never there)."""
    if not valid_key(key):
        return False
    try:
        if uses_s3():
            _s3().delete_object(Bucket=config.S3_BUCKET, Key=key)
            return True
        p = _local_path(key)
        if p.is_file():
            os.remove(p)
        return True
    except Exception as exc:
        log.warning("delete failed: %s", type(exc).__name__)
        return False
