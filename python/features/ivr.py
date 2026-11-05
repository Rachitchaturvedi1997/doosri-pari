# =============================================================================
# Feature · the IVR webhooks — plan section 6.3.
#
#   POST /ivr/twiml/{token}        Twilio asks what to say when the call connects
#   POST /ivr/gather/{token}       ...and what the person pressed
#   POST /ivr/exotel/{token}       Exotel's passthru with the digits
#   POST /ivr/status/{token}       call ended: answered, busy, no-answer, failed
#
# These are called by a telephony vendor, not by the app, so there is no bearer
# token. What authenticates a call is the signed token in the path (see
# python/integrations/ivr.py): it names exactly one check-in and one user and it
# expires. A stranger who finds this URL learns nothing and cannot check anybody
# in without a token that was minted for that call.
#
# Pressing 1 records METHOD_IVR through the same Escalator.check_in the app uses,
# so a check-in by phone call is indistinguishable in the history and tells the
# guardians "all fine" through the same path.
# =============================================================================
from __future__ import annotations

import logging
from urllib.parse import parse_qs
from xml.sax.saxutils import escape

from fastapi import APIRouter, Request, Response

from python.deps import DbSession
from python.dispatch import accepted_guardians, make_dispatcher
from python.escalation import Escalator
from python.integrations import ivr
from python.models import METHOD_IVR, TERMINAL_STAGES, CheckIn, User, now

log = logging.getLogger("doosri.ivr")
router = APIRouter(tags=["ivr"])


def _xml(body: str) -> Response:
    return Response(
        content=f'<?xml version="1.0" encoding="UTF-8"?><Response>{body}</Response>',
        media_type="application/xml",
    )


async def _form(request: Request) -> dict[str, str]:
    """Parse an urlencoded body without python-multipart."""
    raw = (await request.body()).decode("utf-8", "replace")
    return {k: v[0] for k, v in parse_qs(raw, keep_blank_values=True).items()}


def _load(db, token: str):
    ids = ivr.read_token(token)
    if ids is None:
        return None, None
    checkin_id, user_id = ids
    row = db.get(CheckIn, checkin_id)
    user = db.get(User, user_id)
    if row is None or user is None or row.user_id != user.id:
        return None, None
    return row, user


def _record(db, row: CheckIn, user: User) -> bool:
    if row.stage in TERMINAL_STAGES:
        return True
    esc = Escalator(db, user, dispatch=make_dispatcher(db, user))
    esc.check_in(row, method=METHOD_IVR)
    db.commit()
    return True


@router.post("/ivr/twiml/{token}")
async def twiml_answer(token: str, request: Request, db: DbSession):
    row, user = _load(db, token)
    if row is None:
        return _xml("<Say>Sorry, this call is no longer valid.</Say><Hangup/>")
    lang = user.lang or "en"
    prompt = escape(ivr.prompt_for(lang))
    action = escape(f"/ivr/gather/{token}")
    # Repeat the prompt once if nothing is pressed, then hang up: a person who
    # picks up and does not respond must not be held in a loop.
    gather = (
        f'<Gather numDigits="1" timeout="8" action="{action}" method="POST">'
        f'<Say language="{ivr.say_lang(lang)}">{prompt}</Say></Gather>'
    )
    return _xml(gather + gather + "<Hangup/>")


@router.post("/ivr/gather/{token}")
async def twiml_gather(token: str, request: Request, db: DbSession):
    row, user = _load(db, token)
    if row is None:
        return _xml("<Hangup/>")
    digit = (await _form(request)).get("Digits", "").strip()
    lang = user.lang or "en"
    if digit == "1":
        _record(db, row, user)
        return _xml(
            f'<Say language="{ivr.say_lang(lang)}">{escape(ivr.thanks_for(lang))}</Say><Hangup/>'
        )
    if digit == "2":
        guardians = accepted_guardians(db, user.id, "alerts")
        if guardians:
            number = guardians[0].phone
            return _xml(f"<Dial>{escape(number)}</Dial>")
    action = escape(f"/ivr/twiml/{token}")
    return _xml(f'<Redirect method="POST">{action}</Redirect>')


@router.post("/ivr/exotel/{token}")
async def exotel_passthru(token: str, request: Request, db: DbSession):
    row, user = _load(db, token)
    if row is None:
        return Response(status_code=404)
    params = dict(request.query_params)
    params.update(await _form(request))
    digits = (params.get("digits") or params.get("Digits") or "").strip().strip('"')
    if digits == "1":
        _record(db, row, user)
        return Response(status_code=200)
    # Any other digit: 200 with 'retry' so the flow can loop; 302 would make
    # Exotel treat the call as failed.
    return Response(status_code=200, content="retry")


@router.post("/ivr/status/{token}")
async def call_status(token: str, request: Request, db: DbSession):
    row, user = _load(db, token)
    if row is None:
        return Response(status_code=200)
    params = await _form(request)
    status = (params.get("CallStatus") or params.get("Status") or "").lower()
    events = list(row.events or [])
    events.append({"at": now(), "stage": row.stage, "action": "ivr_status", "note": status[:24]})
    row.events = events
    db.commit()
    log.info("ivr status check-in=%s status=%s", row.id, status)
    return Response(status_code=200)
