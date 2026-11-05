# =============================================================================
# Doosri Pari — API entry point.
#
# Plan section 9: FastAPI + SQLAlchemy + Alembic, Postgres 16 with pgvector in
# production, SQLite for tests.
#
# This file wires; it does not implement. Every route in the app lives in
# python/features/, one module per plan area, and the only decisions taken here
# are the ones that are genuinely about the app as a whole: which features are
# mounted and in what order, what an error looks like, what a response may be
# cached as, and the two hard refusals in config.py.
# =============================================================================
from __future__ import annotations

import asyncio
import logging
import time
import uuid

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from python import config
from python.deps import engine
from python.features import (
    admin,
    admin_extra,
    association,
    auth,
    bolo,
    checkin,
    circles,
    content,
    grievance,
    health,
    ivr,
    kendra,
    me,
    media,
    payments,
    prefs,
    rooms,
    saathi,
    safety,
    sahayak,
    seekho,
    seva,
    seva_more,
    setup,
    stories,
    stories_more,
    subscriptions,
    today,
    toolkit,
    vault,
    yatra,
)
from python.models import Base
from python.realtime import hub
from python.realtime import router as realtime_router

logging.basicConfig(level=config.LOG_LEVEL)
log = logging.getLogger("doosri")

app = FastAPI(
    title="Doosri Pari API",
    version="1.0.0",
    # Plan section 14: this app is independent and is not a government service.
    description=(
        "Doosri Pari — the second innings app for India's retired government "
        "employees. An independent app, not affiliated with any government "
        "department."
    ),
)

# ORDER MATTERS in three places, all for the same reason: Starlette matches the
# first route whose PATH fits, and `/x/{id}` fits `/x/word`.
#   * circles before saathi   (`/circles/suggested` before `/circles/{id}`)
#   * stories before stories_more (`/stories/prompts` before `/stories/{id}`)
#   * seva before seva_more   (`/listings` routes; more.py puts its static
#                              `/listings/mine` ahead of `/listings/{id}` itself)
# Otherwise the order is the order the OpenAPI document is grouped in, so it is
# the order a person reads the app's surface in: identity, the four tabs, then
# the tools, then operations.
for module in (
    auth,
    checkin,
    ivr,
    me,
    prefs,
    setup,
    safety,
    today,
    bolo,
    circles,
    saathi,
    rooms,
    seva,
    seva_more,
    payments,
    sahayak,
    health,
    kendra,
    content,
    stories,
    stories_more,
    seekho,
    yatra,
    grievance,
    toolkit,
    vault,
    media,
    association,
    subscriptions,
    admin,
    admin_extra,
):
    app.include_router(module.router)
app.include_router(realtime_router)


# ------------------------------------------------------------------ startup --
@app.on_event("startup")
async def on_startup() -> None:
    # Real message channels first: a check-in alert with no provider is the
    # failure this whole app exists to prevent, and configure_dispatch logs
    # loudly in production if any safety-critical provider is missing.
    from python.integrations.registry import configure_dispatch

    configure_dispatch()

    # Every sync route runs on this pool. AnyIO's default of 40 threads caps a
    # process at 40 requests in flight however many sockets nginx sends it.
    import anyio.to_thread

    anyio.to_thread.current_default_thread_limiter().total_tokens = config.THREADPOOL_SIZE

    if config.RUN_MIGRATIONS_ON_START:
        # `alembic upgrade head` as a deploy step is better; this is for the
        # single-container case. In tests and first run, tables are created
        # directly (below) - production never relies on create_all.
        from alembic import command
        from alembic.config import Config as AlembicConfig

        cfg = AlembicConfig(str(config.PROJECT_ROOT / "alembic.ini"))
        await asyncio.get_running_loop().run_in_executor(None, command.upgrade, cfg, "head")
    else:
        Base.metadata.create_all(bind=engine)

    hub.start(asyncio.get_running_loop())


# --------------------------------------------------------------- middleware --
# Things that are true of every response, decided once.
_CACHEABLE_PREFIXES = ("/app/config", "/media/file", "/docs", "/openapi.json", "/redoc")


@app.middleware("http")
async def response_policy(request: Request, call_next):
    """
    * A request id on every response, so a pensioner's "it did not work" can be
      matched to one log line by a support person.
    * `Cache-Control: no-store` on anything that can carry personal data. A
      response with a person's medicines in it must not sit in a proxy or a
      shared browser cache; the few public, cacheable reads are listed above.
    * The time taken, in the log, without the query string (which can carry a
      token on the WebSocket and a search term anywhere).
    """
    rid = request.headers.get("x-request-id") or uuid.uuid4().hex[:12]
    started = time.monotonic()
    response = await call_next(request)
    response.headers["X-Request-ID"] = rid
    if not request.url.path.startswith(_CACHEABLE_PREFIXES):
        response.headers.setdefault("Cache-Control", "no-store")
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    log.info(
        "%s %s -> %s in %dms [%s]",
        request.method, request.url.path, response.status_code,
        int((time.monotonic() - started) * 1000), rid,
    )
    return response


# ----------------------------------------------------------------- errors --
# Plan section 11: one error shape everywhere —
#     {"detail": "...", "errors": [{"field", "msg"}]}
#
# FastAPI does not produce this on its own. Its HTTPException handler returns
# only `{"detail": ...}`, and a request-validation failure returns a long
# machine-readable list under "detail" that no client should ever have to know
# about. Both are rewritten here so the Ktor client in core-network has exactly
# one thing to parse, and so a pensioner with a poor connection sees a sentence
# rather than a status code and a JSON pointer.
def _error_body(detail: str, errors: list[dict] | None = None) -> dict:
    return {"detail": detail, "errors": errors or []}


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    # `detail` is a string everywhere in this codebase. If it is ever not one,
    # something has been raised with a model or a row as its message and the
    # client would get a shape it cannot parse — say so plainly rather than
    # silently serialising whatever it was.
    detail = exc.detail if isinstance(exc.detail, str) else "request failed"
    if not isinstance(exc.detail, str):
        log.error(
            "non-string HTTPException detail from %s: %r", request.url.path, exc.detail
        )
    return JSONResponse(
        status_code=exc.status_code,
        content=_error_body(detail),
        headers=getattr(exc, "headers", None),
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    # Field paths, not JSON pointers. "body.window_start" is something a person
    # can act on; "body.['window_start']" is not, and a pensioner reading it
    # aloud to their son is the actual failure this shape is here to prevent.
    fields = []
    for err in exc.errors():
        loc = [str(part) for part in err.get("loc", ()) if part != "body"]
        fields.append({"field": ".".join(loc) or "body", "msg": err.get("msg", "invalid")})
    return JSONResponse(status_code=422, content=_error_body("check the form", fields))


@app.exception_handler(ValueError)
async def value_error_handler(request: Request, exc: ValueError) -> JSONResponse:
    # A ValueError escaping to here is a bug, not a bad request, but it is still
    # reported in the one shape and still without the exception text, which can
    # carry whatever the caller sent.
    log.exception("unhandled ValueError at %s", request.url.path)
    return JSONResponse(status_code=400, content=_error_body("check the form"))


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # The last resort. A 500 in the one shape, with nothing of the exception in
    # it: the stack trace is in the log against the request id, not in a
    # pensioner's screen.
    log.exception("unhandled error at %s", request.url.path)
    return JSONResponse(
        status_code=500,
        content=_error_body("something went wrong on our side; please try again"),
    )


@app.get("/health")
def health_check() -> dict:
    # Deliberately not /healthz and deliberately not authenticated: the load
    # balancer probes it and a probe has no token. It reports only that the
    # process is up and which environment it thinks it is in — no version of
    # anything a stranger should not have.
    return {"ok": True, "app": "doosri-pari", "env": config.ENV}


@app.get("/ready")
def readiness() -> JSONResponse:
    """
    Ready to serve: the database answers, and (in production) the channels a
    missed check-in depends on are configured. A load balancer that stops
    routing to an instance that cannot page anybody is doing the safe thing.
    """
    from sqlalchemy import text

    from python.integrations.registry import SAFETY_CRITICAL, status

    problems: list[str] = []
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except Exception:
        problems.append("database")
    if config.ENV == "production":
        st = status()
        problems += [k for k in SAFETY_CRITICAL if not st.get(k)]
    return JSONResponse(status_code=503 if problems else 200, content={"ready": not problems, "missing": problems})
