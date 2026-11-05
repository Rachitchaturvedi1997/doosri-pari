# =============================================================================
# Load operator-supplied datasets that this repository deliberately does not ship.
#
#     python -m python.tools.load_data facilities cghs_echs.csv
#     python -m python.tools.load_data radio stations.json
#     python -m python.tools.load_data spiritual texts.json
#     python -m python.tools.load_data dr-rates rates.json
#
# Why these are inputs and not files in the repo: a hospital list, a stream URL, a
# scripture translation and a dearness rate each have a source and a licence, and
# the right person to attach both is whoever obtained the data. Each loader
# refuses a row that lacks its source.
#
# facilities  CSV   name,address,city_id,state_code,lat,lng,schemes,phone,beds
#                   (schemes as pipe-separated: CGHS|ECHS). Replaces a facility of
#                   the same name and state, so the monthly refresh is one command.
# radio       JSON  [{"name","kind","language","region","stream_url","credit"}]
# spiritual   JSON  [{"kind","title","body","language","path","source_ref","duration_s"}]
#                   source_ref is REQUIRED - the text's licence/translator.
# dr-rates    JSON  [{"service_type","rate_percent","effective_from":"YYYY-MM-DD","source_doc_id"}]
#                   source_doc_id is REQUIRED - a rate without its order is a rumour.
# =============================================================================
from __future__ import annotations

import argparse
import calendar
import csv
import json
import logging
from datetime import date
from pathlib import Path

from sqlalchemy import select

from python.deps import SessionLocal, engine
from python.models import Base, DrRate, Facility, RadioStation, SahayakDoc, SERVICE_TYPES, SpiritualContent, now

log = logging.getLogger("doosri.load")


def _epoch(v: str) -> float:
    return float(calendar.timegm(date.fromisoformat(v).timetuple()))


def load_facilities(db, path: Path) -> dict:
    added = updated = 0
    with path.open(encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            lat, lng = float(row["lat"]), float(row["lng"])
            if not (-90 <= lat <= 90 and -180 <= lng <= 180):
                raise SystemExit(f"bad coordinates for {row['name']}")
            existing = db.scalar(select(Facility).where(Facility.name == row["name"], Facility.state_code == row.get("state_code")))
            values = dict(
                name=row["name"], address=row.get("address"), city_id=int(row["city_id"]) if row.get("city_id") else None,
                state_code=row.get("state_code"), lat=lat, lng=lng,
                schemes=[s for s in (row.get("schemes") or "").split("|") if s], phone=row.get("phone") or None,
                beds=int(row["beds"]) if row.get("beds") else None, updated_at=now(),
            )
            if existing is None:
                db.add(Facility(**values))
                added += 1
            else:
                for k, v in values.items():
                    setattr(existing, k, v)
                updated += 1
    return {"added": added, "updated": updated}


def load_radio(db, path: Path) -> dict:
    n = 0
    for i, r in enumerate(json.loads(path.read_text(encoding="utf-8"))):
        if not str(r.get("stream_url", "")).startswith("https://"):
            raise SystemExit(f"station {r.get('name')!r}: stream_url must be https")
        if db.scalar(select(RadioStation.id).where(RadioStation.stream_url == r["stream_url"])) is None:
            db.add(RadioStation(name=r["name"], kind=r["kind"], language=r.get("language", "hi"), region=r.get("region"),
                                stream_url=r["stream_url"], credit=r.get("credit"), position=i))
            n += 1
    return {"added": n}


def load_spiritual(db, path: Path) -> dict:
    n = 0
    for r in json.loads(path.read_text(encoding="utf-8")):
        if not r.get("source_ref"):
            raise SystemExit(f"{r.get('title')!r}: source_ref (translator / licence) is required")
        if db.scalar(select(SpiritualContent.id).where(SpiritualContent.title == r["title"], SpiritualContent.language == r.get("language", "hi"))) is None:
            db.add(SpiritualContent(kind=r["kind"], title=r["title"], body=r.get("body"), language=r.get("language", "hi"),
                                    path=r.get("path"), source_ref=r["source_ref"], duration_s=r.get("duration_s"),
                                    audio_key=r.get("audio_key")))
            n += 1
    return {"added": n}


def load_dr_rates(db, path: Path) -> dict:
    n = 0
    for r in json.loads(path.read_text(encoding="utf-8")):
        if r["service_type"] not in SERVICE_TYPES:
            raise SystemExit(f"unknown service_type {r['service_type']!r}")
        if not r.get("source_doc_id") or db.get(SahayakDoc, r["source_doc_id"]) is None:
            raise SystemExit("every rate needs the source_doc_id of the order it comes from")
        eff = _epoch(r["effective_from"])
        if db.scalar(select(DrRate.id).where(DrRate.service_type == r["service_type"], DrRate.effective_from == eff)) is None:
            db.add(DrRate(service_type=r["service_type"], rate_percent=float(r["rate_percent"]), effective_from=eff,
                          source_doc_id=r["source_doc_id"]))
            n += 1
    return {"added": n}


LOADERS = {"facilities": load_facilities, "radio": load_radio, "spiritual": load_spiritual, "dr-rates": load_dr_rates}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("what", choices=sorted(LOADERS))
    ap.add_argument("path", type=Path)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO)
    Base.metadata.create_all(bind=engine)
    with SessionLocal() as db:
        result = LOADERS[args.what](db, args.path)
        db.commit()
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
