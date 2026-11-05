# =============================================================================
# Load official documents into the Sahayak corpus from local files.
#
#     python -m python.tools.ingest_sahayak manifest.json
#     python -m python.tools.ingest_sahayak manifest.json --evaluate
#
# The crawl (jobs.sahayak_crawl) finds documents on the web by itself once a
# source is enabled. This tool is for the first load and for anything an operator
# has downloaded by hand - the CCS (Pension) Rules, an office memorandum - so the
# corpus can start before the crawl is trusted.
#
# manifest.json:
#   {"documents": [
#     {"file": "ccs_pension_rules_2021.pdf",       # path relative to the manifest
#      "title": "CCS (Pension) Rules, 2021",
#      "issuer": "Department of Pension & Pensioners' Welfare",
#      "doc_no": "G.S.R. ...",                    # as printed; optional
#      "url": "https://...",                      # the ORIGINAL, always linked to
#      "issued_on": "2021-12-20",                 # optional
#      "effective_on": "2021-12-20",              # optional
#      "service_types": ["central_civil"]}        # [] = applies to everyone
#   ]}
#
# Every document is stored as a DRAFT with its chunks (and embeddings if a model is
# configured). Nothing reaches a pensioner until an admin publishes it against a
# PASSING evaluation run (features/admin.py). --evaluate runs the held-out set now
# and records the run, which is what the admin then publishes against.
# =============================================================================
from __future__ import annotations

import argparse
import calendar
import json
import logging
from datetime import date
from pathlib import Path

from sqlalchemy import select

from python import jobs
from python.deps import SessionLocal, engine
from python.integrations import extract
from python.models import Base, SahayakChunk, SahayakDoc

log = logging.getLogger("doosri.ingest")


def _epoch(value: str | None) -> float | None:
    if not value:
        return None
    d = date.fromisoformat(value)
    return float(calendar.timegm(d.timetuple()))


def ingest(manifest: Path, evaluate: bool) -> dict:
    spec = json.loads(manifest.read_text(encoding="utf-8"))
    base = manifest.parent
    Base.metadata.create_all(bind=engine)
    added = skipped = chunks_made = 0
    with SessionLocal() as db:
        for d in spec.get("documents", []):
            if db.scalar(select(SahayakDoc.id).where(SahayakDoc.title == d["title"], SahayakDoc.issuer == d["issuer"])):
                skipped += 1
                continue
            path = base / d["file"]
            data = path.read_bytes()
            pages = extract.pdf_to_pages(data) if data[:4] == b"%PDF" else [(1, extract.html_to_text(data.decode("utf-8", "replace")))]
            chunks = extract.chunk_pages(pages)
            if not chunks:
                log.warning("no text could be read from %s; skipped", path.name)
                skipped += 1
                continue
            doc = SahayakDoc(
                title=d["title"], issuer=d["issuer"], doc_no=d.get("doc_no"), url=d.get("url"),
                issued_on=_epoch(d.get("issued_on")), effective_on=_epoch(d.get("effective_on")),
                status="draft", service_types=d.get("service_types", []),
            )
            db.add(doc)
            db.flush()
            vectors = jobs.embed_chunks([c["text"] for c in chunks])
            for i, c in enumerate(chunks):
                vec = vectors[i] if i < len(vectors) and vectors[i] else None
                db.add(SahayakChunk(
                    doc_id=doc.id, heading_path=c["heading_path"], text=c["text"], page=c.get("page"),
                    service_types=list(doc.service_types or []), applies_from=doc.effective_on,
                    embedding=vec, tsv=c["text"].lower(),
                ))
            chunks_made += len(chunks)
            added += 1
        db.commit()

        run = None
        if evaluate:
            run = jobs._record_evaluation(db, jobs.run_sahayak_evaluation(db, include_drafts=True))
    out = {"documents_added": added, "skipped": skipped, "chunks": chunks_made}
    if run is not None:
        out["evaluation"] = {"run_id": run.id, "passed": run.passed, "mrr": run.mrr, "citation_rate": run.citation_rate}
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest", type=Path)
    ap.add_argument("--evaluate", action="store_true", help="run the evaluation set and record the run")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO)
    print(json.dumps(ingest(args.manifest, args.evaluate), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
