# =============================================================================
# Text embeddings, 1024 dimensions. Plan sections 6.4 and 10.
#
# `SahayakChunk.embedding` is declared vector(1024), so the dimension is not a
# tuning knob: a model that returns anything else is refused here, loudly,
# rather than failing later inside Postgres or — worse — being stored on SQLite
# where nothing checks.
#
# Two providers, both over HTTPS:
#   * Voyage AI (voyage-3 is 1024-dimensional), the default;
#   * any OpenAI-compatible /embeddings endpoint, e.g. a self-hosted
#     text-embeddings-inference server running BGE-M3 (also 1024) — set
#     EMBEDDINGS_URL and the Voyage key is ignored.
# =============================================================================
from __future__ import annotations

import logging
import math
from typing import Sequence

from python import config
from python.integrations import _http

log = logging.getLogger("doosri.embeddings")

DIM = 1024
BATCH = 64


def available() -> bool:
    return bool(config.EMBEDDINGS_URL or config.VOYAGE_API_KEY)


def embed(texts: Sequence[str], *, query: bool = False) -> list[list[float]]:
    """
    One 1024-vector per input, in order. A text that could not be embedded gets
    an empty list, so the caller can leave that chunk without a vector instead of
    failing the whole ingest (jobs.embed_chunks' documented contract).
    """
    out: list[list[float]] = []
    for i in range(0, len(texts), BATCH):
        batch = list(texts[i : i + BATCH])
        vecs = _batch(batch, query)
        if vecs is None or len(vecs) != len(batch):
            out.extend([[] for _ in batch])
        else:
            out.extend(vecs)
    return out


def _batch(batch: list[str], query: bool) -> list[list[float]] | None:
    if config.EMBEDDINGS_URL:
        headers = {"content-type": "application/json"}
        if config.EMBEDDINGS_KEY:
            headers["authorization"] = f"Bearer {config.EMBEDDINGS_KEY}"
        r = _http.request(
            "POST",
            config.EMBEDDINGS_URL,
            headers=headers,
            json={"model": config.EMBEDDINGS_MODEL, "input": batch},
            timeout=30.0,
        )
        rows = (r.body or {}).get("data") if isinstance(r.body, dict) else None
        vecs = [d.get("embedding") for d in rows] if rows else None
    elif config.VOYAGE_API_KEY:
        r = _http.request(
            "POST",
            "https://api.voyageai.com/v1/embeddings",
            headers={"authorization": f"Bearer {config.VOYAGE_API_KEY}"},
            json={
                "model": config.VOYAGE_MODEL,
                "input": batch,
                "input_type": "query" if query else "document",
            },
            timeout=30.0,
        )
        rows = (r.body or {}).get("data") if isinstance(r.body, dict) else None
        vecs = [d.get("embedding") for d in rows] if rows else None
    else:
        return None

    if not vecs:
        log.warning("embedding batch failed: status=%s", r.status)
        return None
    for v in vecs:
        if not v or len(v) != DIM:
            log.error("embedding model returned %s dims, column is %s", len(v or []), DIM)
            return None
    return vecs


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    """Cosine similarity; 0.0 when either vector is empty. Used on SQLite,
    where there is no pgvector operator to do it in the database."""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0
