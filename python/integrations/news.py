# =============================================================================
# Pension news. Plan sections 6.14 and 15.
#
# RSS/Atom feeds only, parsed with the standard library. Feeds are listed in
# python/data/sahayak_sources.json under "news_feeds" and ship disabled until an
# operator has checked each publisher's terms.
#
# What is stored is the plan's minimum: title, a neutral summary we write,
# publisher, url and date. We always link to the original and never reproduce the
# article. Politics is excluded *here*, at the source of the feed, by keyword: an
# item must mention a pension topic, and an item that also trips the politics
# list is dropped before it reaches a moderator.
# =============================================================================
from __future__ import annotations

import json
import logging
import re
import time
from email.utils import parsedate_to_datetime
from xml.etree import ElementTree as ET

from python.integrations import _http
from python.integrations.crawler import SOURCES_FILE

log = logging.getLogger("doosri.news")

_NS = {"atom": "http://www.w3.org/2005/Atom", "dc": "http://purl.org/dc/elements/1.1/"}

# Plan 15: politics is allowed in general circles only, never in pension news.
_POLITICS = re.compile(
    r"\b(election|lok sabha|rajya sabha|assembly poll|bjp|congress|aap party|"
    r"campaign rally|manifesto|opposition|no-confidence)\b",
    re.IGNORECASE,
)
_INJECTION = re.compile(
    r"(ignore (all |the )?(previous|above) (instructions|rules)|system prompt|you are now)",
    re.IGNORECASE,
)


def feeds() -> list[dict]:
    try:
        data = json.loads(SOURCES_FILE.read_text(encoding="utf-8"))
        return [f for f in data.get("news_feeds", []) if f.get("enabled")]
    except (OSError, ValueError):
        return []


def _text(el, path: str) -> str:  # noqa: ANN001
    node = el.find(path, _NS)
    return "".join(node.itertext()).strip() if node is not None else ""


def _epoch(value: str) -> float | None:
    if not value:
        return None
    try:
        return parsedate_to_datetime(value).timestamp()
    except (TypeError, ValueError):
        pass
    try:
        from datetime import datetime

        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def fetch(since: float, limit: int = 200) -> list[dict]:
    """Items newer than `since`: title, summary (source text), publisher, url, language, published_at, tags."""
    items: list[dict] = []
    for feed in feeds():
        r = _http.request("GET", feed["url"], timeout=15.0, retries=1)
        if not r.ok or not r.text:
            continue
        try:
            root = ET.fromstring(r.text)
        except ET.ParseError:
            log.warning("news feed %s did not parse", feed.get("name"))
            continue
        entries = root.findall(".//item") or root.findall(".//atom:entry", _NS)
        keywords = [k.lower() for k in feed.get("keywords", [])]
        for e in entries:
            title = _text(e, "title") or _text(e, "atom:title")
            link = _text(e, "link") or (e.find("atom:link", _NS).get("href", "") if e.find("atom:link", _NS) is not None else "")
            body = _text(e, "description") or _text(e, "atom:summary") or _text(e, "atom:content")
            published = _epoch(_text(e, "pubDate") or _text(e, "atom:updated") or _text(e, "dc:date")) or time.time()
            if published < since or not title or not link:
                continue
            blob = f"{title} {body}".lower()
            if keywords and not any(k in blob for k in keywords):
                continue
            items.append(
                {
                    "title": title[:300],
                    "body": re.sub(r"<[^>]+>", " ", body)[:2000],
                    "publisher": feed.get("publisher", feed.get("name", ""))[:120],
                    "url": link[:500],
                    "language": feed.get("language", "en"),
                    "published_at": published,
                    "tags": [k for k in keywords if k in blob][:6],
                }
            )
            if len(items) >= limit:
                return items
    return items


def moderate(item: dict) -> tuple[bool, str]:
    """
    The automatic checks of plan 15. Everything uncertain returns False: a wrong
    "DA increased to 58%" is a worse outcome than an app with no news for an hour.
    """
    blob = f"{item.get('title', '')} {item.get('body', '')}"
    if _INJECTION.search(blob):
        return False, "possible prompt injection in feed text"
    if _POLITICS.search(blob):
        return False, "political content is not published as pension news"
    url = item.get("url", "")
    if not url.startswith("https://"):
        return False, "non-https source"
    if not item.get("publisher"):
        return False, "no publisher"
    return True, "ok"
