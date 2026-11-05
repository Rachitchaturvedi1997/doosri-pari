# =============================================================================
# Claude, over plain HTTPS. Plan section 9.
#
# Haiku for classification, summaries and short answers; Sonnet for complex
# Sahayak answers and memoir editing. Written against the Messages API directly
# rather than the SDK so the server has no extra dependency and every call has
# the same timeout and retry behaviour as the other integrations.
#
# The rules every caller in this repository follows, restated because a model
# call is where they get broken:
#   * User text is DATA. It goes in a delimited block of the user turn, never
#     into the system prompt (see python/features/sahayak.py RULE 4).
#   * Nothing raises. A None return means "no model answered" and every caller
#     has an honest fallback (the offline FAQ, "not found", a rule-based check).
#   * No PII in logs: prompts and completions are never logged, only sizes.
# =============================================================================
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.llm")

API_URL = "https://api.anthropic.com/v1/messages"
API_VERSION = "2023-06-01"


@dataclass
class Completion:
    text: str
    model: str
    input_tokens: int = 0
    output_tokens: int = 0


def available() -> bool:
    return bool(config.ANTHROPIC_API_KEY)


def complete(
    *,
    system: str,
    user: str,
    strong: bool = False,
    max_tokens: int = 1024,
    temperature: float | None = 0.2,
    timeout: float | None = None,
) -> Completion | None:
    """One single-turn completion, or None when no model could answer."""
    if not config.ANTHROPIC_API_KEY:
        return None
    model = config.LLM_MODEL_STRONG if strong else config.LLM_MODEL_FAST
    body: dict = {
        "model": model,
        "max_tokens": max_tokens,
        "system": system,
        "messages": [{"role": "user", "content": user}],
    }
    if temperature is not None:
        body["temperature"] = temperature
    r = _http.request(
        "POST",
        API_URL,
        headers={
            "x-api-key": config.ANTHROPIC_API_KEY,
            "anthropic-version": API_VERSION,
            "content-type": "application/json",
        },
        json=body,
        timeout=timeout or config.LLM_TIMEOUT_S,
        retries=1,
    )
    if not r.ok or not isinstance(r.body, dict):
        log.warning("llm call failed: status=%s", r.status)
        return None
    parts = [b.get("text", "") for b in r.body.get("content", []) if b.get("type") == "text"]
    text = "".join(parts).strip()
    usage = r.body.get("usage") or {}
    log.info("llm ok model=%s in=%s out=%s", model, usage.get("input_tokens"), usage.get("output_tokens"))
    return Completion(
        text=text,
        model=str(r.body.get("model") or model)[:64],
        input_tokens=int(usage.get("input_tokens") or 0),
        output_tokens=int(usage.get("output_tokens") or 0),
    )


_JSON_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.MULTILINE)


def complete_json(**kw) -> tuple[dict | list | None, Completion | None]:
    """
    Like complete(), parsed as JSON. The model is told to return only JSON; a
    reply that will not parse yields (None, completion) and the caller falls back.
    """
    c = complete(**kw)
    if c is None:
        return None, None
    text = _JSON_FENCE.sub("", c.text).strip()
    start = min([i for i in (text.find("{"), text.find("[")) if i >= 0], default=-1)
    if start > 0:
        text = text[start:]
    try:
        return json.loads(text), c
    except ValueError:
        return None, c
