# =============================================================================
# Credential loading: a value in config may be a file path or the contents.
#
# Deployments differ — a container mounts a service-account file, a PaaS puts
# the JSON in an env var — and neither should need a code change. This is the
# one place that decides which it is.
# =============================================================================
from __future__ import annotations

import json
import os
import time
from typing import Any


def read_text(value: str) -> str:
    """The contents if `value` is a path to a file, otherwise `value` itself."""
    if not value:
        return ""
    stripped = value.strip()
    if stripped.startswith("{") or stripped.startswith("-----BEGIN"):
        return stripped
    try:
        if os.path.isfile(stripped):
            with open(stripped, "r", encoding="utf-8") as fh:
                return fh.read()
    except OSError:
        return ""
    return stripped


def read_json(value: str) -> dict[str, Any]:
    text = read_text(value)
    if not text:
        return {}
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except ValueError:
        return {}


class TokenCache:
    """An access token and when it stops being good, with a minute of slack."""

    def __init__(self) -> None:
        self._token: str | None = None
        self._expires = 0.0

    def get(self) -> str | None:
        if self._token and time.time() < self._expires - 60:
            return self._token
        return None

    def set(self, token: str, ttl: float) -> str:
        self._token = token
        self._expires = time.time() + ttl
        return token
