# =============================================================================
# Escalation — the server half of the check-in state machine.
#
# NOTE ON DUPLICATION. The authoritative machine is the pure Kotlin module
# shared/feature-checkin (EscalationEngine.kt). Python cannot import it, so
# this file is a second implementation of the same rules. The timings are NOT
# duplicated: they live in python/config.py and python/tests/test_timings.py
# asserts them against the Kotlin constants. If you change a stage or a delay,
# change both and let that test tell you.
#
# Plan section 6.3: "The escalation runs on the server, not on the phone,
# because neither Android nor iOS guarantees exact background timing."
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import select
from sqlalchemy.orm import Session

from python import config
from python.models import (
    STAGE_CALLED,
    STAGE_CHECKED_IN,
    STAGE_GUARDIANS_ALERTED,
    STAGE_HELPERS_ALERTED,
    STAGE_REMINDED,
    STAGE_RESOLVED,
    STAGE_WAITING,
    TERMINAL_STAGES,
    CheckIn,
    CheckInSettings,
    User,
    now,
)

log = logging.getLogger("doosri.escalation")

# What the job should send when a stage changes. Plan section 6.3 puts these on
# separate channels because any one of them can fail.
ACTION_NONE = "none"
ACTION_REMIND = "remind_user"
ACTION_IVR = "place_ivr_call"
ACTION_ALERT_GUARDIANS = "alert_guardians"
ACTION_ALERT_HELPERS = "alert_helpers"
ACTION_NOTIFY_ALL_FINE = "notify_all_fine"

# Local hours (start, end) during which the automated call is held unless a
# guardian has already been told. Same values as python/notify.py.
IVR_QUIET_HOURS = (22, 7)


def parse_window_start(value: str) -> tuple[int, int]:
    hh, _, mm = value.partition(":")
    return int(hh), int(mm)


def local_day(user: User, at: float | None = None) -> str:
    """The user's local calendar day — a check-in window is a local window."""
    ts = now() if at is None else at
    return datetime.fromtimestamp(ts, ZoneInfo(user.time_zone or "UTC")).strftime("%Y-%m-%d")


def window_bounds(
    user: User, settings: CheckInSettings, day: str
) -> tuple[float, float]:
    """The window's open and close instants, in epoch seconds.

    A check-in window is a *local* window, so it is anchored to the user's own
    timezone rather than UTC. This is the same calculation the Kotlin
    CheckInSettings.windowOpensAt / windowClosesAt perform.
    """
    y, m, d = (int(p) for p in day.split("-"))
    hh, mm = parse_window_start(settings.window_start)
    tz = ZoneInfo(user.time_zone or "UTC")
    opens = datetime(y, m, d, hh, mm, tzinfo=tz).timestamp()
    return opens, opens + settings.window_mins * 60


class Escalator:
    """
    Applies the state machine to one user's rows.

    Deliberately thin: it reads the row, asks the machine what should happen,
    then writes the row and logs the step. The channel side effects (push, IVR,
    SMS, WhatsApp) go through `dispatch`, which is injected and stubbed by
    default — so the timing rules are testable without a telephony account,
    which is the same reason plan section 20 asks for fake-clock tests.
    """

    def __init__(self, db: Session, user: User, dispatch=None):
        self.db = db
        self.user = user
        # A transient row has no column defaults until it is flushed, so the
        # fallback spells them out; a None window_start would crash the window.
        self.settings = user.settings or CheckInSettings(
            user_id=user.id, window_start="09:00", window_mins=180,
            passive=False, share_location=False,
        )
        # Injected so tests can assert on what would have been sent.
        self.dispatch = dispatch or (lambda action, checkin, **kw: None)

    # ------------------------------------------------------------------ rows --

    def open_today(self, day: str) -> CheckIn:
        """Get today's row, creating it in Waiting if it does not exist.

        This is what the `checkin_open` job (every minute) calls, and what the
        app calls on launch so there is always something to render.
        """
        row = self.db.scalar(
            select(CheckIn).where(CheckIn.user_id == self.user.id, CheckIn.day == day)
        )
        if row is not None:
            return row

        ts = now()
        # Every timeout is measured from when the window OPENED (plan 6.3:
        # "window start + 1 h"). A row created earlier — the app launched at
        # 8:00 for a 9:00 window — must not start its reminder clock at 8:00.
        opens, _ = window_bounds(self.user, self.settings, day)
        entered = max(ts, opens)
        row = CheckIn(
            user_id=self.user.id,
            day=day,
            stage=STAGE_WAITING,
            stage_entered_at=entered,
            events=[{"at": ts, "stage": STAGE_WAITING, "action": ACTION_NONE}],
        )
        self.db.add(row)
        self.db.flush()
        return row

    # ----------------------------------------------------------------- steps --

    def check_in(
        self, row: CheckIn, *, method: str, lat=None, lng=None, accuracy=None
    ) -> CheckIn:
        """Record a check-in. The only path into the terminal CheckedIn stage."""
        if row.stage in TERMINAL_STAGES:
            return row

        ts = now()
        was_alerting = row.stage in (STAGE_GUARDIANS_ALERTED, STAGE_HELPERS_ALERTED)
        row.stage = STAGE_CHECKED_IN
        row.stage_entered_at = ts
        row.checked_at = ts
        row.method = method
        if lat is not None and lng is not None and self.settings.share_location:
            row.lat, row.lng, row.location_accuracy = lat, lng, accuracy
        self._log(row, STAGE_CHECKED_IN, ACTION_NOTIFY_ALL_FINE, note=f"via {method}")

        # Plan section 6.3: a late check-in tells guardians "all fine" at once
        # rather than waiting for the next stage boundary.
        if was_alerting:
            self.dispatch(ACTION_NOTIFY_ALL_FINE, row, resolved_early=True)
        return row

    def resolve(self, row: CheckIn, *, resolved_by: int, note: str | None = None) -> CheckIn:
        if row.stage in TERMINAL_STAGES:
            return row
        ts = now()
        row.stage = STAGE_RESOLVED
        row.stage_entered_at = ts
        row.resolved_at = ts
        row.resolved_by = resolved_by
        self._log(row, STAGE_RESOLVED, ACTION_NONE, note=note)
        self.dispatch(ACTION_NOTIFY_ALL_FINE, row, resolved_early=True)
        return row

    # ------------------------------------------------------------ the machine --

    def advance(self, row: CheckIn, at: float | None = None) -> str | None:
        """
        Move one open row forward as far as [at] allows.

        Returns the action dispatched, or None if the row did not move. Safe to
        call every minute for every open row: a row that has not crossed a
        threshold returns None and writes nothing.
        """
        at = now() if at is None else at
        if row.stage in TERMINAL_STAGES:
            return None
        if self.settings.paused_until and at < self.settings.paused_until:
            return None

        planned = self._next(row, at)
        if planned is None:
            return None

        stage, action = planned
        row.stage = stage
        row.stage_entered_at = at
        if action == ACTION_IVR:
            row.call_attempts += 1
            row.last_call_at = at
        self._log(row, stage, action)
        self.dispatch(action, row)
        return action

    def _next(self, row: CheckIn, at: float) -> tuple[str, str] | None:
        """(new stage, action) if the row should move at [at], else None."""
        _, closes = window_bounds(self.user, self.settings, row.day)
        entered = row.stage_entered_at

        if row.stage == STAGE_WAITING:
            if at < entered + config.REMIND_AFTER_S:
                return None
            return (STAGE_REMINDED, ACTION_REMIND)

        if row.stage == STAGE_REMINDED:
            if at < closes:
                return None
            return (STAGE_CALLED, ACTION_IVR)

        if row.stage == STAGE_CALLED:
            if row.call_attempts < config.IVR_ATTEMPTS:
                if row.last_call_at is not None and at < row.last_call_at + config.IVR_RETRY_GAP_S:
                    return None
                return (STAGE_CALLED, ACTION_IVR)
            # Both calls spent. The guardians wait out the same 10-minute gap on
            # the last call before they are told, so nobody's family is woken
            # for a call that is still ringing. Matches
            # EscalationEngine.advanceFromCalled.
            if row.last_call_at is None:
                return (STAGE_GUARDIANS_ALERTED, ACTION_ALERT_GUARDIANS)
            if at < row.last_call_at + config.IVR_RETRY_GAP_S:
                return None
            return (STAGE_GUARDIANS_ALERTED, ACTION_ALERT_GUARDIANS)

        if row.stage == STAGE_GUARDIANS_ALERTED:
            if at < entered + config.HELPER_ESCALATION_S:
                return None
            return (STAGE_HELPERS_ALERTED, ACTION_ALERT_HELPERS)

        if row.stage == STAGE_HELPERS_ALERTED:
            # Only a person in person can close this; there is no timeout.
            return None

        return None

    # ------------------------------------------------------------------ audit --

    def _log(self, row: CheckIn, stage: str, action: str, note: str | None = None) -> None:
        """Append to the audit trail. Plan section 6.3 logs every step, and the
        user can read the whole history back in the app."""
        events = list(row.events or [])
        events.append({"at": now(), "stage": stage, "action": action, "note": note})
        row.events = events
