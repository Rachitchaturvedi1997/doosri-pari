# =============================================================================
# Tokens, digests and the small amount of cryptography this app actually needs.
#
# What this app has, and what it does not, decides what belongs here:
#
#   * there is no password anywhere. Sign-in is a phone OTP or an Apple/Google
#     subject, so there is no user-chosen secret to be slow about hashing, and
#     argon2 is the wrong tool. A six-digit code is short-lived, single-use and
#     rate-limited; what it needs is an unguessable, non-reversible record.
#   * a refresh token is 32 random bytes. It is already unguessable, so it is
#     stored as a plain SHA-256 digest: the only property that matters is that
#     a stolen database cannot be replayed into a session.
#   * both digests are *keyed* with SECRET_KEY. An attacker with a copy of the
#     database still cannot confirm a guessed code offline, and the comparison
#     is constant-time so a timing signal does not leak the rest of the code.
#
# The rule this file exists to enforce: nothing that can be replayed is ever
# stored, and nothing that can be guessed is ever stored in the clear.
# =============================================================================
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import secrets
import time
import uuid
from typing import Any

import jwt

from python import config

# Keyed digests are domain-separated, so an OTP hash can never be mistaken for
# a refresh-token hash if one ever ends up in the wrong column. Bump these only
# alongside a migration that rehashes what is stored.
_OTP_DOMAIN = b"doosri:otp:v1:"
_TOKEN_DOMAIN = b"doosri:refresh:v1:"
_FAMILY_DOMAIN = b"doosri:family:v1:"


def _keyed_digest(domain: bytes, value: str) -> str:
    """SHA-256 under SECRET_KEY, domain-separated. Hex, so it fits a VARCHAR(64)."""
    mac = hmac.new(
        config.SECRET_KEY.encode("utf-8"), domain + value.encode("utf-8"), hashlib.sha256
    )
    return mac.hexdigest()


def hash_otp(phone_e164: str, code: str) -> str:
    """The record of a pending OTP.

    Bound to the phone number as well as the code, so a code issued for one
    number cannot be redeemed against a challenge raised for another.
    """
    return _keyed_digest(_OTP_DOMAIN, f"{phone_e164}:{code}")


def verify_otp(phone_e164: str, code: str, stored_hash: str) -> bool:
    """Constant-time. A wrong code and a wrong phone are indistinguishable."""
    return hmac.compare_digest(hash_otp(phone_e164, code), stored_hash)


def new_refresh_token() -> tuple[str, str]:
    """A refresh token and the digest to store for it.

    Returns both because the caller needs the plaintext exactly once — it goes
    to the client and is never recoverable again. 32 bytes from `secrets`, which
    is a CSPRNG; `random` would not be.
    """
    raw = secrets.token_urlsafe(32)
    return raw, _keyed_digest(_TOKEN_DOMAIN, raw)


def hash_refresh_token(raw: str) -> str:
    return _keyed_digest(_TOKEN_DOMAIN, raw)


def hash_family_code(code: str) -> str:
    """
    A family code is 8 characters from an unambiguous alphabet — about 41 bits,
    which is short on purpose so it can be read aloud to a parent. That is
    short enough to be guessed, so it is rate limited like a password, and the
    stored copy is a keyed digest rather than the code.

    The alphabet excludes O/0, I/1 and the other pairs people mistype when
    copying six characters off a screen at arm's length.
    """
    return _keyed_digest(_FAMILY_DOMAIN, code)


def new_family_code() -> str:
    """Eight characters, no look-alike pairs. See `hash_family_code`."""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(8))


def new_opp() -> str:
    """Six digits, zero-padded, from the CSPRNG."""
    return f"{secrets.randbelow(1_000_000):06d}"


# ------------------------------------------------------------------- access --
def issue_access_token(user_id: int, tier: str, family_hash: str | None = None) -> str:
    """
    A one-hour access token.

    Deliberately thin. It carries the user id, the tier and a token family, and
    nothing else — no PPO number, no name, no phone. A bearer token is read by
    whoever holds it, so the less it says the less it can leak if it is
    photographed off a screen, and it is re-checked against the database on
    every request anyway, which is what makes revocation possible.

    `jti` ties the token to a family, so signing out everywhere invalidates
    every token in the family rather than only the refresh token that was
    presented.
    """
    issued = int(time.time())
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "tier": tier,
        "iat": issued,
        "exp": issued + config.ACCESS_TTL,
        "jti": uuid.uuid4().hex,
    }
    if family_hash:
        payload["fam"] = family_hash[:16]
    return jwt.encode(payload, config.SECRET_KEY, algorithm=config.JWT_ALGO)


def decode_access_token(token: str) -> dict[str, Any]:
    """
    Verify signature and expiry, or raise.

    `algorithms` is passed explicitly rather than taken from the token header.
    Without it, `alg: none` is accepted, which is the oldest JWT trick there is
    and is still found in live code.
    """
    return jwt.decode(
        token,
        config.SECRET_KEY,
        algorithms=[config.JWT_ALGO],
        options={"require": ["exp", "sub", "iat"]},
    )


def b64url(raw: bytes) -> str:
    """URL-safe base64 without padding, for values that go in a URL or a header."""
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a, b)


def random_id(nbytes: int = 16) -> str:
    return b64url(os.urandom(nbytes))
