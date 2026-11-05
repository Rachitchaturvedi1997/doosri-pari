# =============================================================================
# The Sahayak crawl. Plan section 6.4 (ingestion step 1) and section 12.
#
# Fetches the *index pages* of the official sources listed in
# python/data/sahayak_sources.json and returns document metadata: title, URL,
# issuer, document number, dates. It does not download PDFs — jobs.sahayak_crawl
# stores drafts and sahayak_index fetches a document only after an admin has
# approved it, so a duplicate costs one page, not a PDF.
#
# Politeness (jobs.fetch_official_sahayak_pages documents the contract):
#   * one request per host per 10 seconds, 5 more after a 429/503;
#   * an honest User-Agent that says what we are and that we are not the
#     government;
#   * robots.txt is honoured — a disallowed path is skipped, not fetched;
#   * only http(s) links on the source's own allow-listed hosts are followed.
# =============================================================================
from __future__ import annotations

import json
import logging
import re
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin, urlsplit
from urllib.robotparser import RobotFileParser

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.crawler")

SOURCES_FILE = Path(__file__).resolve().parents[1] / "data" / "sahayak_sources.json"
HOST_DELAY_S = 10.0

_last_hit: dict[str, float] = {}
_robots: dict[str, RobotFileParser | None] = {}

# Office-memorandum numbers look like "F. No. 38/37/2021-P&PW(A)" or
# "No. 42/3/2020-P&PW(G)". Capture the first thing that looks like one.
_DOC_NO = re.compile(r"(?:F\.?\s*No\.?|No\.?)\s*([0-9A-Za-z/().&\-]{6,60})", re.IGNORECASE)
_DATE = re.compile(r"\b(\d{1,2})[./-](\d{1,2})[./-](\d{4})\b")


def load_sources() -> list[dict]:
    try:
        return json.loads(SOURCES_FILE.read_text(encoding="utf-8")).get("sources", [])
    except (OSError, ValueError):
        log.warning("sahayak_sources.json is missing or unreadable")
        return []


def _allowed(url: str) -> bool:
    host = urlsplit(url).netloc
    if host not in _robots:
        rp = RobotFileParser()
        try:
            r = _http.request(
                "GET",
                f"{urlsplit(url).scheme}://{host}/robots.txt",
                headers={"User-Agent": config.CRAWL_USER_AGENT},
                timeout=10.0,
                retries=0,
            )
            if r.ok:
                rp.parse((r.text or "").splitlines())
                _robots[host] = rp
            else:
                _robots[host] = None  # no robots.txt: allowed
        except Exception:
            _robots[host] = None
    rp = _robots[host]
    return True if rp is None else rp.can_fetch(config.CRAWL_USER_AGENT, url)


def _polite_get(url: str) -> _http.Reply:
    host = urlsplit(url).netloc
    wait = _last_hit.get(host, 0.0) + HOST_DELAY_S - time.time()
    if wait > 0:
        time.sleep(min(wait, HOST_DELAY_S))
    r = _http.request(
        "GET", url, headers={"User-Agent": config.CRAWL_USER_AGENT}, timeout=60.0, retries=1
    )
    _last_hit[host] = time.time() + (5.0 if r.status in (429, 503) else 0.0)
    return r


class _Links(HTMLParser):
    """Collects (href, anchor text) pairs."""

    def __init__(self) -> None:
        super().__init__()
        self.links: list[tuple[str, str]] = []
        self._href: str | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag, attrs):  # noqa: ANN001
        if tag == "a":
            self._href = dict(attrs).get("href")
            self._text = []

    def handle_data(self, data):  # noqa: ANN001
        if self._href is not None:
            self._text.append(data)

    def handle_endtag(self, tag):  # noqa: ANN001
        if tag == "a" and self._href is not None:
            self.links.append((self._href, " ".join("".join(self._text).split())))
            self._href = None


def _epoch(text: str) -> float | None:
    m = _DATE.search(text)
    if not m:
        return None
    d, mo, y = (int(x) for x in m.groups())
    try:
        import calendar
        import datetime

        return float(calendar.timegm(datetime.datetime(y, mo, d).timetuple()))
    except ValueError:
        return None


def crawl(sources: list[dict] | None = None) -> list[dict]:
    """
    One dict per candidate document: issuer, doc_no, title, url, issued_on,
    effective_on, service_types. Duplicates against the database are filtered by
    the job, not here.
    """
    found: list[dict] = []
    for src in sources if sources is not None else load_sources():
        index_url = src.get("url", "")
        hosts = set(src.get("hosts") or [urlsplit(index_url).netloc])
        if not index_url or not _allowed(index_url):
            continue
        r = _polite_get(index_url)
        if not r.ok:
            continue
        parser = _Links()
        parser.feed(r.text or (json.dumps(r.body) if r.body else ""))
        pattern = re.compile(src.get("link_pattern", r"\.pdf($|\?)"), re.IGNORECASE)
        seen: set[str] = set()
        for href, text in parser.links:
            url = urljoin(index_url, href or "")
            parts = urlsplit(url)
            if parts.scheme not in ("http", "https") or parts.netloc not in hosts:
                continue
            if not pattern.search(url) or url in seen or len(text) < 6:
                continue
            seen.add(url)
            doc_no = _DOC_NO.search(text)
            issued = _epoch(text)
            found.append(
                {
                    "issuer": src.get("issuer", parts.netloc)[:120],
                    "doc_no": doc_no.group(1)[:80] if doc_no else None,
                    "title": text[:300],
                    "url": url[:500],
                    "issued_on": issued,
                    "effective_on": issued,
                    "service_types": src.get("service_types", []),
                }
            )
            if len(found) >= 200:  # a busy index must not become a thousand-row queue
                return found
    return found


def fetch_document(url: str) -> bytes | None:
    """The bytes of one approved document, politely."""
    if not _allowed(url):
        return None
    host = urlsplit(url).netloc
    wait = _last_hit.get(host, 0.0) + HOST_DELAY_S - time.time()
    if wait > 0:
        time.sleep(min(wait, HOST_DELAY_S))
    data = _raw(url)
    _last_hit[host] = time.time()
    return data


def _raw(url: str) -> bytes | None:
    try:
        import httpx  # noqa: PLC0415

        with httpx.Client(timeout=120.0, follow_redirects=True) as c:
            resp = c.get(url, headers={"User-Agent": config.CRAWL_USER_AGENT})
        return resp.content if resp.status_code == 200 else None
    except Exception:
        return None
