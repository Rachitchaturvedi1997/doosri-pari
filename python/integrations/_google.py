# =============================================================================
# Google service-account OAuth, without the Google client libraries.
#
# FCM v1 and the Play Developer API both want an OAuth2 bearer token minted from
# a service-account key: sign a JWT with the key (RS256), exchange it at the
# token endpoint. That is forty lines, which is fewer than the dependency tree
# of google-auth, and the app already carries PyJWT.
# =============================================================================
from __future__ import annotations

import logging
import time

from python.integrations import _http
from python.integrations._secrets import TokenCache, read_json

log = logging.getLogger("doosri.google")

TOKEN_URL = "https://oauth2.googleapis.com/token"
_caches: dict[str, TokenCache] = {}


def access_token(service_account: str, scope: str) -> str | None:
    """A bearer token for `scope`, or None when it cannot be minted."""
    info = read_json(service_account)
    if not info.get("private_key") or not info.get("client_email"):
        return None

    cache = _caches.setdefault(f"{info['client_email']}|{scope}", TokenCache())
    cached = cache.get()
    if cached:
        return cached

    try:
        import jwt  # noqa: PLC0415
    except ImportError:
        return None

    ts = int(time.time())
    claims = {
        "iss": info["client_email"],
        "scope": scope,
        "aud": info.get("token_uri", TOKEN_URL),
        "iat": ts,
        "exp": ts + 3600,
    }
    try:
        assertion = jwt.encode(claims, info["private_key"], algorithm="RS256")
    except Exception as exc:  # cryptography missing, or a mangled key
        log.warning("could not sign service-account assertion: %s", type(exc).__name__)
        return None

    r = _http.request(
        "POST",
        info.get("token_uri", TOKEN_URL),
        data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": assertion,
        },
    )
    if not r.ok or not isinstance(r.body, dict) or "access_token" not in r.body:
        log.warning("google token exchange failed: status=%s", r.status)
        return None
    return cache.set(r.body["access_token"], float(r.body.get("expires_in", 3600)))
