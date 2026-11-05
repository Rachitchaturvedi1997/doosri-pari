# =============================================================================
# Rate limiting. Plan section 11: "every write is rate-limited per user and IP".
#
# Two backends, one interface:
#
#   * Redis, when REDIS_URL is set. This is the real one — a fixed-window counter
#     with a TTL, shared across every process, so a limit cannot be sidestepped
#     by the request landing on a different worker.
#   * An in-process dictionary otherwise. Correct for a single process and for
#     the test suite; wrong the moment there are two, which is why the choice is
#     made on configuration rather than on convenience.
#
# The counter is a FIXED window, not a sliding one, because a fixed window is one
# INCR and one EXPIRE and cannot be made to grow without bound by a client that
# spaces its requests. Its known edge is that it resets on the boundary, so a
# client can send 2x the limit straddling a boundary. That is the right trade
# for a pensioner's app: an OTP is the one endpoint where it matters, and that
# one has a limit of 5 against a 10-minute code, so the boundary is irrelevant.
# =============================================================================
from __future__ import annotations

import logging
import threading
import time
from collections import defaultdict

from fastapi import HTTPException, Request

from python import config

log = logging.getLogger("doosri.ratelimit")

# key -> (window_start, count)
_counters: dict[str, tuple[float, int]] = defaultdict(lambda: (0.0, 0))
_lock = threading.Lock()

_redis = None
_redis_checked = False


def _get_redis():
    """Lazily connect, and stay None if Redis is not configured or not installed.

    The import is inside the function because `redis` is an optional dependency:
    the test suite and a fresh clone have no reason to install it, and a module
    that failed to import would take the whole app down over a rate limiter.
    """
    global _redis, _redis_checked
    if _redis_checked:
        return _redis
    _redis_checked = True
    if not config.REDIS_URL:
        return None
    try:
        import redis  # noqa: PLC0415 — optional dependency, deliberately late

        _redis = redis.from_url(
            config.REDIS_URL, decode_responses=True,
            socket_timeout=0.5, socket_connect_timeout=0.5, health_check_interval=30,
        )
    except Exception as exc:  # pragma: no cover — depends on deployment
        log.warning("rate limit falling back to in-process: %s", exc)
        _redis = None
    return _redis


def client_ip(request: Request) -> str:
    """
    The caller's address.

    X-Forwarded-For is honoured only when the app is behind a proxy, because it
    is a client-supplied header: read it unconditionally and any caller can
    spoof a fresh identity per request, which turns a per-IP limit into no
    limit at all. Set TRUST_PROXY only when a proxy really is in front, and note
    that it then has to overwrite the header rather than append to it.
    """
    if config.TRUST_PROXY:
        forwarded = request.headers.get("x-forwarded-for", "")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _hit_in_process(key: str, limit: int, window_s: int) -> tuple[int, int]:
    now = time.monotonic()
    with _lock:
        start, count = _counters[key]
        if now - start >= window_s:
            start, count = now, 0
        count += 1
        _counters[key] = (start, count)
    return count, limit


def _hit_redis(client, key: str, limit: int, window_s: int) -> tuple[int, int]:
    # The expiry is set only when the key is new (NX). Setting it on every hit
    # pushed the window forward with each request, so a member writing steadily
    # under the limit never saw the counter reset and was eventually refused.
    pipe = client.pipeline()
    pipe.incr(key)
    pipe.expire(key, window_s, nx=True)
    count = int(pipe.execute()[0])
    return count, limit


def check(key: str, limit: int, window_s: int) -> None:
    """
    Count one hit and raise 429 when over the limit.

    `Retry-After` is set, because a client that is told only "no" will retry
    immediately and make the limit worse for everyone behind it.
    """
    client = _get_redis()
    if client is not None:
        try:
            count, limit = _hit_redis(client, f"rl:{key}", limit, window_s)
        except Exception as exc:  # pragma: no cover — Redis being down
            # Fail open on the limiter, never on the request. A Redis outage
            # should slow nothing down and take nothing down with it.
            log.warning("rate limit check failed, allowing: %s", exc)
            return
    else:
        count, limit = _hit_in_process(f"rl:{key}", limit, window_s)

    if count > limit:
        raise HTTPException(
            429,
            "too many requests",
            headers={"Retry-After": str(window_s)},
        )


def limit_write(request: Request, user_id: int | None = None) -> None:
    """The default for any mutating endpoint. Per user and per IP, both."""
    if user_id is not None:
        check(f"write:u{user_id}", config.RATE_LIMIT_WRITE, 60)
    check(f"write:ip{client_ip(request)}", config.RATE_LIMIT_WRITE * 5, 60)


def limit_otp(request: Request, phone_e164: str) -> None:
    """
    Sending an OTP.

    Three limits, and the phone number one is the important one: without it, a
    six-digit code is guessed ten times a second and a number that belongs to
    somebody else can be driven to receive SMS until the carrier blocks it. The
    IP limit stops one host from spraying many numbers; the phone limit stops
    one number being spammed from many hosts.
    """
    check(f"otp:ip{client_ip(request)}", config.RATE_LIMIT_OTP, 3600)
    check(f"otp:num{phone_e164}", config.RATE_LIMIT_OTP, 3600)


def reset_in_process() -> None:
    """Test hook: forget every counter."""
    with _lock:
        _counters.clear()
