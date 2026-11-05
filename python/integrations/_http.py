# =============================================================================
# One small HTTP helper, so every integration has the same timeout, retry and
# logging behaviour and none of them can log a secret.
# =============================================================================
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlsplit

log = logging.getLogger("doosri.http")

DEFAULT_TIMEOUT = 20.0
RETRY_STATUS = (429, 500, 502, 503, 504)


@dataclass
class Reply:
    """What came back. `ok` is a 2xx; `status` 0 means we never got an answer."""

    status: int = 0
    body: Any = None
    text: str = ""
    headers: dict = field(default_factory=dict)
    error: str = ""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


def safe_url(url: str) -> str:
    """Scheme, host and path — never the query string, which is where keys live."""
    p = urlsplit(url)
    return f"{p.scheme}://{p.netloc}{p.path}"


def request(
    method: str,
    url: str,
    *,
    headers: dict | None = None,
    json: Any = None,
    data: Any = None,
    files: Any = None,
    params: dict | None = None,
    auth: tuple[str, str] | None = None,
    timeout: float = DEFAULT_TIMEOUT,
    retries: int = 2,
    http2: bool = False,
    content: bytes | None = None,
) -> Reply:
    """
    Make a request with bounded retries on 429/5xx and on network errors.

    Returns a Reply and never raises. The retry delay honours Retry-After when
    the server sends one, and is otherwise 0.5s, 1s, 2s. POSTs are retried too:
    every caller in this repository sends an idempotency reference where a
    repeat would matter (Razorpay transfers, notification keys).
    """
    try:
        import httpx  # noqa: PLC0415 — optional dependency, deliberately late
    except ImportError:
        return Reply(error="httpx is not installed")

    attempt = 0
    last = Reply(error="no attempt made")
    while attempt <= retries:
        try:
            with httpx.Client(timeout=timeout, http2=http2, follow_redirects=True) as client:
                r = client.request(
                    method,
                    url,
                    headers=headers,
                    json=json,
                    data=data,
                    files=files,
                    params=params,
                    auth=auth,
                    content=content,
                )
            try:
                body = r.json()
            except Exception:
                body = None
            last = Reply(
                status=r.status_code,
                body=body,
                text=r.text if body is None else "",
                headers=dict(r.headers),
            )
            if r.status_code not in RETRY_STATUS:
                return last
            delay = _retry_after(r.headers.get("retry-after"), attempt)
        except Exception as exc:  # network, TLS, timeout
            last = Reply(error=f"{type(exc).__name__}: {str(exc)[:120]}")
            delay = 0.5 * (2 ** attempt)
        attempt += 1
        if attempt <= retries:
            time.sleep(min(delay, 10.0))
    log.warning("%s %s failed: status=%s %s", method, safe_url(url), last.status, last.error)
    return last


def _retry_after(value: str | None, attempt: int) -> float:
    if value:
        try:
            return float(value)
        except ValueError:
            pass
    return 0.5 * (2 ** attempt)
