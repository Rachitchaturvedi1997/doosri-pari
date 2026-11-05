# =============================================================================
# Festival and vrat dates. Plan section 7.2 item 7.
#
# The rule (models/kendra.py Festival): dates are LOADED from a licensed source,
# never computed. A lunar calendar that is confidently wrong by a day is worse
# than none at all for somebody keeping a fast.
#
# Two ways to supply data, both explicit about provenance:
#
#   1. A licensed vendor over HTTPS (PANCHANG_URL + PANCHANG_KEY). The adapter
#      expects a JSON array of {name, kind, date, end_date?, region?, languages?}
#      — the shape most panchang APIs can be configured to return. The vendor is
#      the operator's choice; this file does not name or endorse one.
#   2. Files dropped into PANCHANG_DIR by an operator, one per year:
#      festivals-2027.json, same shape. This is how a calendar the organisation
#      has licensed as a spreadsheet gets in without a vendor API.
#
# `source_key` records which of the two supplied each row so a disputed date can
# be traced.
# =============================================================================
from __future__ import annotations

import calendar
import json
import logging
from datetime import date, datetime
from pathlib import Path

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.panchang")

KINDS = ("festival", "vrat", "ekadashi", "fasting_day")


def _epoch(value: str) -> float | None:
    try:
        d = datetime.fromisoformat(str(value)[:10]).date()
        return float(calendar.timegm(d.timetuple()))
    except ValueError:
        return None


def _normalise(rows: list, year: int, source: str, region: str | None) -> list[dict]:
    out: list[dict] = []
    for r in rows if isinstance(rows, list) else []:
        if not isinstance(r, dict):
            continue
        start = _epoch(r.get("date") or r.get("starts_on") or "")
        if start is None or not r.get("name"):
            continue
        end = _epoch(r.get("end_date") or r.get("ends_on") or "") if (r.get("end_date") or r.get("ends_on")) else None
        kind = r.get("kind") if r.get("kind") in KINDS else "festival"
        out.append(
            {
                "name": str(r["name"])[:160],
                "kind": kind,
                "starts_on": start,
                "ends_on": end,
                "region": (r.get("region") or region),
                "languages": list(r.get("languages") or []),
                "source_key": source[:120],
            }
        )
    return out


def fetch(year: int, region: str | None) -> list[dict]:
    """Rows for `year` (and `region`, or everywhere) from the configured source."""
    rows: list[dict] = []

    if config.PANCHANG_URL:
        r = _http.request(
            "GET",
            config.PANCHANG_URL,
            params={"year": year, **({"region": region} if region else {}), "key": config.PANCHANG_KEY},
            timeout=30.0,
        )
        if r.ok:
            rows.extend(_normalise(r.body, year, f"vendor:{year}", region))
        else:
            log.warning("panchang vendor returned status=%s", r.status)

    directory = Path(config.PANCHANG_DIR)
    if directory.is_dir():
        for f in sorted(directory.glob(f"festivals-{year}*.json")):
            try:
                rows.extend(_normalise(json.loads(f.read_text(encoding="utf-8")), year, f"file:{f.name}", region))
            except (OSError, ValueError):
                log.warning("could not read %s", f.name)
    return rows
