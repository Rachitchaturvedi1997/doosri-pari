# =============================================================================
# FastAPI dependencies: database session, and the current user.
#
# Authentication is a bearer JWT, verified against the database on every
# request. That last part is the whole design, and it is not the usual one:
#
#   A purely stateless JWT cannot be revoked. Plan section 14 requires that a
#   pensioner who signs out is signed out everywhere — on the phone they lost,
#   on the phone at their daughter's house, and on a handset still sitting in a
#   used-phone shop. A self-contained token with no server-side check cannot do
#   that, and an access token for a person who cannot tell a 403 from a 500 has
#   to expire in an hour regardless.
#
#   So the access token carries only what avoids a lookup (the id), and
#   `get_current_user` loads the user anyway. The signature check stops a
#   forged token; the database check stops a real token from a user who has
#   since deleted their account, been suspended, or signed out.
#
# The `X-User-Id` header that used to be the whole of this file is still here,
# but only behind ALLOW_DEV_HEADER_AUTH, which config.py refuses to enable
# outside development. It is not a missing feature to be finished later — left
# on, it lets anyone who guesses a small integer read a stranger's check-in
# history and resolve their open alerts.
# =============================================================================
from __future__ import annotations

import os
import time
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from python import config
from python.models import Base, RefreshToken, User
from python.security import decode_access_token

# SQLite needs check_same_thread disabled for TestClient; Postgres does not care.
if config.DATABASE_URL.startswith("sqlite"):
    engine = create_engine(
        config.DATABASE_URL, future=True, connect_args={"check_same_thread": False}
    )
else:
    # pre_ping because PgBouncer and cloud load balancers close idle server
    # connections, and the first request after a quiet night must not be the
    # one that finds out. recycle under the usual 30-minute idle cut-off.
    engine = create_engine(
        config.DATABASE_URL,
        future=True,
        pool_size=config.DB_POOL_SIZE,
        max_overflow=config.DB_MAX_OVERFLOW,
        pool_timeout=config.DB_POOL_TIMEOUT,
        pool_pre_ping=True,
        pool_recycle=1500,
        pool_use_lifo=True,
    )
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False, future=True)

# auto_error=False so a missing header produces our own 401 with the plan's
# error shape, rather than FastAPI's bare {"detail": "Not authenticated"}.
_bearer = HTTPBearer(auto_error=False)


def get_db() -> Session:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# A pause (plan 15) stops posting and the rest of the social writes. It must never
# stop the things a person's safety rests on, so these prefixes always pass.
PAUSE_ALLOWED = (
    "/checkin", "/sos", "/guardian", "/guardians", "/medicines", "/doses", "/health", "/vitals",
    "/reminders", "/heartbeat", "/devices", "/me", "/auth", "/inbox", "/vault", "/kendra", "/spiritual",
)


def _load_user(db: Session, user_id: int, request: Request | None = None) -> User:
    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(401, "unknown user")
    if user.deleted_at is not None:
        # A soft-deleted account stays deleted for the 30-day window (plan 12);
        # its content is still there, but this token must stop working now.
        raise HTTPException(401, "account deleted")
    if (
        request is not None
        and user.suspended_until
        and user.suspended_until > time.time()
        and request.method not in ("GET", "HEAD", "OPTIONS")
        and not request.url.path.startswith(PAUSE_ALLOWED)
    ):
        raise HTTPException(
            403,
            "Your account is paused until "
            + time.strftime("%d %B", time.localtime(user.suspended_until))
            + ". You can still check in, use SOS, and read.",
        )
    return user


def get_current_user(
    request: Request,
    db: Annotated[Session, Depends(get_db)],
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)] = None,
    x_user_id: Annotated[str | None, Header()] = None,
) -> User:
    """
    Resolve the caller.

    Order matters: the bearer token is tried first and the dev header is a
    fallback, not the other way round. A client that sends both — a stale app
    build with the header and a real token — is the real token that counts.
    """
    if creds is not None and creds.scheme.lower() == "bearer":
        try:
            claims = decode_access_token(creds.credentials)
        except Exception:
            # Deliberately not distinguishing "expired" from "bad signature".
            # Telling a caller which one it was is free help, and the client
            # cannot do anything useful with the answer either way.
            raise HTTPException(401, "invalid or expired token") from None
        try:
            user_id = int(claims["sub"])
        except (KeyError, TypeError, ValueError):
            raise HTTPException(401, "malformed token") from None
        return _load_user(db, user_id, request)

    if config.ALLOW_DEV_HEADER_AUTH:
        uid = x_user_id or os.getenv("DEV_USER_ID")
        if uid:
            try:
                return _load_user(db, int(uid))
            except ValueError:
                raise HTTPException(401, "bad dev user id") from None

    raise HTTPException(401, "missing credentials")


def get_optional_user(
    request: Request,
    db: Annotated[Session, Depends(get_db)],
    creds: Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)] = None,
) -> User | None:
    """
    The caller, or nobody.

    For the few reads that work signed out — the offline FAQ, today's festival,
    a public circle's suggestions. Not for anything a pensioner would be
    surprised to find is readable by a stranger.
    """
    if creds is None or creds.scheme.lower() != "bearer":
        return None
    try:
        user_id = int(decode_access_token(creds.credentials)["sub"])
    except Exception:
        return None
    return db.get(User, user_id)


CurrentUser = Annotated[User, Depends(get_current_user)]
OptionalUser = Annotated["User | None", Depends(get_optional_user)]
DbSession = Annotated[Session, Depends(get_db)]


def require_admin(user: CurrentUser) -> User:
    """
    An admin.

    Split out from the dependency so an admin route cannot be written without
    it appearing in the signature — the most common way an authorisation check
    gets left off a new endpoint.
    """
    if user.role not in ("admin", "moderator"):
        raise HTTPException(403, "admin only")
    return user


AdminUser = Annotated[User, Depends(require_admin)]


def revoke_family(db: Session, user_id: int, family: str | None = None) -> int:
    """
    Revoke refresh tokens, and return how many.

    Plan section 14: signing out is everywhere, not just here. Revoking by
    family catches every device signed in from the same sign-in; passing no
    family (account deletion) catches all of them.
    """
    q = db.query(RefreshToken).filter(
        RefreshToken.user_id == user_id, RefreshToken.revoked_at.is_(None)
    )
    if family:
        q = q.filter(RefreshToken.token_hash.like(f"{family}%"))
    rows = q.all()
    for r in rows:
        r.revoked_at = time.time()
    return len(rows)
