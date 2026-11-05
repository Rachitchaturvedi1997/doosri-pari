# =============================================================================
# Sign in with Google / Apple: the providers' public signing keys.
#
# python/features/auth.py verifies an id_token's signature, issuer, audience and
# expiry against `{kid: PEM}`. This module supplies that map: fetch the provider's
# JWKS, convert each RSA JWK to a PEM public key, cache it, and refetch when a
# token names a key id we have not seen - which is exactly how both providers
# announce a key rotation.
#
# It supplies keys and nothing else. Whether a token is acceptable is decided in
# auth.py; if the keys cannot be fetched this returns None and the caller turns
# that into a 401, never into "accept it anyway".
# =============================================================================
from __future__ import annotations

import json
import logging
import time

from python.integrations import _http

log = logging.getLogger("doosri.jwks")

TTL_S = 3600
MIN_REFETCH_GAP_S = 60   # a stream of tokens with a bogus kid must not hammer the provider

_cache: dict[str, tuple[float, float, dict[str, str]]] = {}   # url -> (fetched_at, last_attempt, keys)


def _to_pem(jwk: dict) -> str | None:
    try:
        from jwt.algorithms import RSAAlgorithm  # noqa: PLC0415
        from cryptography.hazmat.primitives import serialization  # noqa: PLC0415

        key = RSAAlgorithm.from_jwk(json.dumps(jwk))
        return key.public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        ).decode()
    except Exception as exc:
        log.warning("could not convert a JWK: %s", type(exc).__name__)
        return None


def signing_keys(url: str, refresh: bool = False) -> dict[str, str] | None:
    now = time.time()
    hit = _cache.get(url)
    fresh = hit is not None and now - hit[0] < TTL_S
    if fresh and not refresh:
        return hit[2]
    if hit is not None and now - hit[1] < MIN_REFETCH_GAP_S:
        return hit[2] or None

    r = _http.request("GET", url, timeout=10.0, retries=1)
    attempt = now
    if not r.ok or not isinstance(r.body, dict):
        _cache[url] = (hit[0] if hit else 0.0, attempt, hit[2] if hit else {})
        return hit[2] if hit else None
    keys: dict[str, str] = {}
    for jwk in r.body.get("keys", []):
        if jwk.get("kty") == "RSA" and jwk.get("kid"):
            pem = _to_pem(jwk)
            if pem:
                keys[jwk["kid"]] = pem
    _cache[url] = (now, attempt, keys)
    return keys or None
