# =============================================================================
# Realtime — the WebSocket that tells an open app something happened.
# Plan section 9: "A -->|WebSocket| RT[Realtime · chat, rooms, SOS]".
#
# What flows over it: a chat message arrived, a namaste was accepted, someone in a
# voice room raised a hand, a guardian acknowledged an alert. It carries NO
# content that matters for safety on its own - a missed check-in or an SOS is a
# push, an SMS and a call, because a socket only reaches an app that is open.
# This is the "the screen updates without a refresh" layer, nothing more, and the
# REST endpoints stay the source of truth: a client that misses an event catches
# up with the same GET it would have made anyway.
#
# One process or many:
#   * With no REDIS_URL, events are delivered to sockets held by this process.
#   * With REDIS_URL, `publish` puts the event on a channel and every worker's
#     subscriber thread forwards it to its own sockets, so a message sent through
#     worker A reaches a phone connected to worker B.
#
# `publish` is synchronous and safe to call from any route (FastAPI runs sync
# routes in a thread pool). It never raises and never blocks the request: a
# realtime failure must not fail a chat send.
# =============================================================================
from __future__ import annotations

import asyncio
import json
import logging
import threading
import time
from collections import defaultdict

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.concurrency import run_in_threadpool

from python import config
from python.security import decode_access_token

log = logging.getLogger("doosri.realtime")
router = APIRouter(tags=["realtime"])

CHANNEL = "dp:events"
MAX_SOCKETS_PER_USER = 4


class Hub:
    def __init__(self) -> None:
        self.sockets: dict[int, set[WebSocket]] = defaultdict(set)
        self.loop: asyncio.AbstractEventLoop | None = None
        self._redis = None
        self._thread: threading.Thread | None = None

    # -- local delivery ------------------------------------------------------
    async def deliver(self, user_id: int, event: dict) -> int:
        sent = 0
        for ws in list(self.sockets.get(user_id, ())):
            try:
                await ws.send_json(event)
                sent += 1
            except Exception:
                self.sockets[user_id].discard(ws)
        return sent

    def online(self, user_id: int) -> bool:
        return bool(self.sockets.get(user_id))

    # -- lifecycle -----------------------------------------------------------
    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self.loop = loop
        if not config.REDIS_URL:
            return
        try:
            import redis  # noqa: PLC0415 - optional dependency

            self._redis = redis.from_url(
                config.REDIS_URL, decode_responses=True,
                socket_connect_timeout=2, health_check_interval=30,
            )
            self._thread = threading.Thread(target=self._listen, name="realtime-sub", daemon=True)
            self._thread.start()
        except Exception as exc:
            log.warning("realtime redis unavailable, delivering locally only: %s", type(exc).__name__)
            self._redis = None

    def _listen(self) -> None:
        # Forever, with a backoff. A Redis restart used to end this thread for
        # good, and every worker would then deliver only its own events, which
        # looks like "chat works sometimes" and nothing in a log says why.
        backoff = 1.0
        while True:
            try:
                sub = self._redis.pubsub(ignore_subscribe_messages=True)
                sub.subscribe(CHANNEL)
                backoff = 1.0
                for msg in sub.listen():
                    try:
                        data = json.loads(msg["data"])
                        self._schedule(int(data["user_id"]), data["event"])
                    except Exception:
                        log.debug("bad realtime message ignored")
            except Exception as exc:
                log.warning("realtime subscriber lost redis (%s); retrying in %.0fs", type(exc).__name__, backoff)
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)

    def _schedule(self, user_id: int, event: dict) -> None:
        if self.loop is not None and self.loop.is_running():
            asyncio.run_coroutine_threadsafe(self.deliver(user_id, event), self.loop)

    # -- sending -------------------------------------------------------------
    def publish(self, user_id: int, event: dict) -> None:
        try:
            if self._redis is not None:
                self._redis.publish(CHANNEL, json.dumps({"user_id": user_id, "event": event}))
            else:
                self._schedule(user_id, event)
        except Exception as exc:
            log.debug("realtime publish failed: %s", type(exc).__name__)


hub = Hub()


def publish(user_id: int, event: dict) -> None:
    """Tell one user's open app something happened. Never raises."""
    hub.publish(user_id, event)


# ------------------------------------------------------------------ endpoint --
def _members(conversation_id: int, a: int, b: int) -> bool:
    """Both people are in the conversation. Runs in the thread pool."""
    from sqlalchemy import func, select  # noqa: PLC0415

    from python.deps import SessionLocal  # noqa: PLC0415
    from python.models import ConversationMember  # noqa: PLC0415

    with SessionLocal() as db:
        both = db.scalar(
            select(func.count(ConversationMember.id)).where(
                ConversationMember.conversation_id == conversation_id,
                ConversationMember.user_id.in_((a, b)),
            )
        )
    return both == 2


@router.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str | None = None):
    """
    Connect with ?token=<access token>. The token is checked here, once, with the
    same signature test as every HTTP request; the database check that makes a
    signed-out account stop working happens through the ordinary REST calls the
    app makes alongside.
    """
    from python.deps import SessionLocal  # noqa: PLC0415
    from python.models import User  # noqa: PLC0415

    user_id = None
    if token:
        try:
            user_id = int(decode_access_token(token)["sub"])
        except Exception:
            user_id = None
    if user_id is None:
        await ws.close(code=4401)
        return
    def _active(uid: int) -> bool:
        with SessionLocal() as db:
            u = db.get(User, uid)
            return u is not None and u.deleted_at is None

    # Off the event loop: one blocking query here stalls every socket this
    # process holds, and after a deploy all of them reconnect at once.
    if not await run_in_threadpool(_active, user_id):
        await ws.close(code=4401)
        return

    await ws.accept()
    conns = hub.sockets[user_id]
    while len(conns) >= MAX_SOCKETS_PER_USER:
        # A fifth tab or a stale reconnect: drop the oldest rather than refuse
        # the newest, because the newest is the phone in the person's hand.
        oldest = next(iter(conns))
        conns.discard(oldest)
        try:
            await oldest.close(code=4409)
        except Exception:
            pass
    conns.add(ws)
    try:
        await ws.send_json({"type": "hello", "user_id": user_id})
        while True:
            msg = await ws.receive_json()
            kind = msg.get("type") if isinstance(msg, dict) else None
            if kind == "ping":
                await ws.send_json({"type": "pong"})
            elif kind == "typing" and isinstance(msg.get("to"), int) and isinstance(msg.get("conversation_id"), int):
                # Only between the two people of a conversation: a typing signal
                # is presence, and presence is not for strangers.
                if await run_in_threadpool(_members, msg["conversation_id"], user_id, int(msg["to"])):
                    hub.publish(int(msg["to"]), {"type": "typing", "from": user_id,
                                                 "conversation_id": msg["conversation_id"]})
    except WebSocketDisconnect:
        pass
    except Exception:
        log.debug("socket closed with an error", exc_info=True)
    finally:
        conns.discard(ws)
        if not conns:
            hub.sockets.pop(user_id, None)
