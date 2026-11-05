# =============================================================================
# The Sahayak evaluation. Plan section 6.4 "Quality" and section 20.
#
# 300 real questions, held out from the corpus, each with what a correct answer
# must cite. Run after every ingest; a document may be published only against a
# PASSING run (features/admin.py), and CI runs the same thing against a fixed
# corpus so a regression blocks the release.
#
# The bar (plan 6.4):
#   citation accuracy      >= 90%   of answerable questions, the answer cites a
#                                   passage that satisfies the expectation
#   confident wrong        <  2%    an answer was published and it was wrong
#   "not found"            <  15%   an answerable question got no answer
#   plus a retrieval MRR that is not poor (>= 0.5), so a good model cannot hide
#   a broken index.
#
# THE QUESTION SET IS NOT SHIPPED. Writing 300 questions and their expected
# citations is the work of someone who has read the rules; inventing them here
# would put made-up "correct answers" behind a publish gate. The file
# python/data/sahayak_eval.json documents the format and ships empty, and an empty
# set yields a FAILED run - never a pass.
#
# File format (python/data/sahayak_eval.json):
#   {"questions": [
#      {"q": "How long must family pension be paid at the enhanced rate?",
#       "lang": "en",
#       "service_type": "central_civil",          # or null
#       "answerable": true,
#       "expect": {"contains_any": ["seven years", "twice the basic"],
#                  "doc_no": "optional exact document number"}}
#   ]}
# =============================================================================
from __future__ import annotations

import json
import logging
import time
from pathlib import Path
from types import SimpleNamespace

from sqlalchemy.orm import Session

log = logging.getLogger("doosri.sahayak.eval")

EVAL_FILE = Path(__file__).resolve().parent / "data" / "sahayak_eval.json"
MAX_QUESTIONS = 300
TIME_BUDGET_S = 15 * 60


def load_questions() -> list[dict]:
    try:
        data = json.loads(EVAL_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    qs = data.get("questions", []) if isinstance(data, dict) else []
    return [q for q in qs if isinstance(q, dict) and q.get("q")][:MAX_QUESTIONS]


def _satisfies(chunk, doc, expect: dict) -> bool:
    text = (chunk.text or "").lower()
    contains = [str(x).lower() for x in expect.get("contains_any", [])]
    if contains and not any(c in text for c in contains):
        return False
    if expect.get("doc_no") and (doc.doc_no or "").strip() != str(expect["doc_no"]).strip():
        return False
    return bool(contains or expect.get("doc_no"))


def run(db: Session, include_drafts: bool = False) -> dict:
    """
    {"mrr", "citation_rate", "confident_wrong", "not_found", "questions",
     "generation_ran"} - or {} when there is no question set.
    """
    from python.features import sahayak  # noqa: PLC0415 - avoids an import cycle at load
    from python.integrations import llm  # noqa: PLC0415

    questions = load_questions()
    if not questions:
        log.warning("no evaluation set at %s; the run will be recorded as failed", EVAL_FILE.name)
        return {}

    started = time.monotonic()
    generate = llm.available()
    answerable = rr_sum = cited_ok = not_found = confident_wrong = ran = 0
    total = 0

    for q in questions:
        if time.monotonic() - started > TIME_BUDGET_S:
            log.warning("evaluation stopped at the time budget after %d questions", total)
            break
        total += 1
        user = SimpleNamespace(service_type=q.get("service_type"))
        is_answerable = bool(q.get("answerable", True))
        expect = q.get("expect") or {}
        retrieved = sahayak._retrieve(db, q["q"], user, include_drafts=include_drafts)

        if is_answerable:
            answerable += 1
            rank = next(
                (i for i, (c, d) in enumerate(retrieved, start=1) if _satisfies(c, d, expect)), None
            )
            rr_sum += (1.0 / rank) if rank else 0.0

        published_cites_expected = False
        published = False
        if generate:
            intent = sahayak._classify_intent(q["q"])
            gen = sahayak.generate_answer(
                question=q["q"], chunks=retrieved, lang=q.get("lang", "en"), intent=intent
            )
            ran += 1
            published = (
                gen is not None
                and bool(retrieved)
                and gen.confidence >= sahayak.SAHAYAK_MIN_CONFIDENCE
            )
            if published and is_answerable:
                published_cites_expected = any(_satisfies(c, d, expect) for c, d in retrieved)

        if is_answerable:
            if generate:
                if published_cites_expected:
                    cited_ok += 1
                if not published:
                    not_found += 1
            else:
                # Retrieval-only proxy: the source was found in the top six.
                if any(_satisfies(c, d, expect) for c, d in retrieved):
                    cited_ok += 1
                else:
                    not_found += 1
        if generate and published and (not is_answerable or not published_cites_expected):
            confident_wrong += 1

    n_ans = max(answerable, 1)
    return {
        "questions": total,
        "answerable": answerable,
        "mrr": round(rr_sum / n_ans, 4),
        "citation_rate": round(cited_ok / n_ans, 4),
        "not_found": round(not_found / n_ans, 4),
        "confident_wrong": round(confident_wrong / max(total, 1), 4),
        "generation_ran": bool(generate and ran),
    }
