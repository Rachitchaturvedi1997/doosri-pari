"""
The check-in endpoints and the escalation machine, plan section 6.3.

The timing tests drive Escalator.advance() with an explicit `at`, so they use a
fake clock and never wait for real time to pass — the same property plan
section 20 asks for when it says "check-in escalation tests with fake clocks".
"""
from datetime import datetime, timedelta, timezone

import pytest

from python import config
from python.escalation import (
    ACTION_ALERT_GUARDIANS,
    ACTION_ALERT_HELPERS,
    ACTION_IVR,
    ACTION_NOTIFY_ALL_FINE,
    ACTION_REMIND,
    Escalator,
    local_day,
    window_bounds,
)
from python.models import (
    STAGE_CALLED,
    STAGE_CHECKED_IN,
    STAGE_GUARDIANS_ALERTED,
    STAGE_HELPERS_ALERTED,
    STAGE_REMINDED,
    STAGE_RESOLVED,
    STAGE_WAITING,
    CheckIn,
    CheckInSettings,
    Guardian,
    User,
    now,
)

IST = "Asia/Kolkata"
DAY = "2026-10-14"


def _at(hour: int, minute: int = 0, day: int = 14) -> float:
    """An epoch timestamp at a wall-clock time in the user's own timezone."""
    from zoneinfo import ZoneInfo

    return datetime(2026, 10, day, hour, minute, tzinfo=ZoneInfo(IST)).timestamp()


# ----------------------------------------------------------------- endpoints --
def test_today_creates_a_waiting_row(client, auth):
    r = client.get("/checkin/today", headers=auth)
    assert r.status_code == 200
    body = r.json()
    assert body["stage"] == STAGE_WAITING
    assert body["checked_at"] is None
    # the first event is logged, because the plan requires every step recorded
    assert body["events"] and body["events"][0]["stage"] == STAGE_WAITING


def test_check_in_marks_the_day_done(client, auth):
    client.get("/checkin/today", headers=auth)
    r = client.post("/checkin", json={"method": "tapped"}, headers=auth)
    assert r.status_code == 200
    body = r.json()
    assert body["stage"] == STAGE_CHECKED_IN
    assert body["method"] == "tapped"
    assert body["checked_at"] is not None
    assert body["events"][-1]["action"] == ACTION_NOTIFY_ALL_FINE


def test_check_in_rejects_an_unknown_method(client, auth):
    r = client.post("/checkin", json={"method": "telepathy"}, headers=auth)
    assert r.status_code == 400


@pytest.mark.parametrize("payload", [
    {"method": "tapped", "lat": 91.0, "lng": 26.8},
    {"method": "tapped", "lat": 26.8, "lng": 181.0},
])
def test_check_in_rejects_impossible_coordinates(client, auth, payload):
    r = client.post("/checkin", json=payload, headers=auth)
    assert r.status_code == 400


def test_settings_round_trip(client, auth):
    r = client.put("/checkin/settings", json={
        "window_start": "07:30", "window_mins": 120,
        "passive": True, "share_location": True,
    }, headers=auth)
    assert r.status_code == 200
    assert r.json()["window_start"] == "07:30"
    assert client.get("/checkin/settings", headers=auth).json()["window_mins"] == 120


def test_settings_reject_an_impossible_window(client, auth):
    r = client.put("/checkin/settings", json={"window_start": "25:00"}, headers=auth)
    assert r.status_code == 422


def test_pause_is_capped_at_thirty_days(client, auth):
    ok = client.post("/checkin/pause", json={"days": 30}, headers=auth)
    assert ok.status_code == 200
    too_long = client.post("/checkin/pause", json={"days": 31}, headers=auth)
    assert too_long.status_code == 422
    too_short = client.post("/checkin/pause", json={"days": 0}, headers=auth)
    assert too_short.status_code == 422


def test_history_returns_newest_first(client, auth, db, user):
    for d in (13, 12, 11):
        db.add(CheckIn(
            user_id=user.id, day=f"2026-10-{d}", stage=STAGE_CHECKED_IN,
            stage_entered_at=_at(9, 0, d), checked_at=_at(9, 30, d), method="tapped",
        ))
    db.commit()
    days = [r["day"] for r in client.get("/checkin/history", headers=auth).json()]
    assert days == ["2026-10-13", "2026-10-12", "2026-10-11"]


# ------------------------------------------------------------- guardians ----
def test_a_guardian_can_close_an_open_alert(client, db, user):
    row = CheckIn(
        user_id=user.id, day=DAY, stage=STAGE_GUARDIANS_ALERTED,
        stage_entered_at=_at(12, 30),
    )
    db.add(row)
    db.flush()

    daughter = User(phone_e164="+919000000002", name="Daughter", time_zone=IST)
    db.add(daughter)
    db.flush()
    db.add(Guardian(
        user_id=user.id, guardian_user_id=daughter.id, phone="+919000000002",
        name="Daughter", order_no=1, accepted_at=now(),
    ))
    db.commit()

    r = client.post(f"/guardian/ack/{row.id}", headers={"X-User-Id": str(daughter.id)})
    assert r.status_code == 200
    assert r.json()["stage"] == STAGE_RESOLVED


def test_a_stranger_cannot_close_someone_elses_alert(client, db, user):
    row = CheckIn(
        user_id=user.id, day=DAY, stage=STAGE_GUARDIANS_ALERTED,
        stage_entered_at=_at(12, 30),
    )
    db.add(row)
    db.flush()
    stranger = User(phone_e164="+919000000003", name="Stranger", time_zone=IST)
    db.add(stranger)
    db.commit()

    r = client.post(f"/guardian/ack/{row.id}", headers={"X-User-Id": str(stranger.id)})
    assert r.status_code == 403


def test_a_guardian_who_has_not_accepted_cannot_close_the_alert(client, db, user):
    row = CheckIn(
        user_id=user.id, day=DAY, stage=STAGE_GUARDIANS_ALERTED,
        stage_entered_at=_at(12, 30),
    )
    db.add(row)
    db.flush()
    pending = User(phone_e164="+919000000004", name="Pending", time_zone=IST)
    db.add(pending)
    db.flush()
    db.add(Guardian(
        user_id=user.id, guardian_user_id=pending.id, phone="+919000000004",
        name="Pending", order_no=1, accepted_at=None,     # never accepted
    ))
    db.commit()

    r = client.post(f"/guardian/ack/{row.id}", headers={"X-User-Id": str(pending.id)})
    assert r.status_code == 403


def test_a_closed_alert_cannot_be_acked_twice(client, db, user, auth):
    row = CheckIn(
        user_id=user.id, day=DAY, stage=STAGE_RESOLVED,
        stage_entered_at=_at(12, 30), resolved_at=_at(13, 0),
    )
    db.add(row)
    db.commit()
    r = client.post(f"/guardian/ack/{row.id}", headers=auth)
    assert r.status_code == 409


def test_requests_without_a_user_are_rejected(client):
    assert client.get("/checkin/today").status_code == 401


# ---------------------------------------------------- the machine, fake clock --
def test_window_is_local_not_utc(db, user):
    """09:00 in Uttar Pradesh is 03:30 UTC. Getting this wrong pages people at 3am."""
    settings = user.settings
    opens, closes = window_bounds(user, settings, DAY)
    utc = timezone.utc
    assert datetime.fromtimestamp(opens, utc).hour == 3
    assert datetime.fromtimestamp(opens, utc).minute == 30
    assert closes - opens == 3 * 3600


def _fresh(db, user):
    """A row for DAY sitting in Waiting, entered exactly at window open."""
    settings = user.settings
    opens, _ = window_bounds(user, settings, DAY)
    row = CheckIn(
        user_id=user.id, day=DAY, stage=STAGE_WAITING, stage_entered_at=opens, events=[],
    )
    db.add(row)
    db.commit()
    return row, Escalator(db, user)


def test_one_hour_in_the_user_is_reminded(db, user):
    row, esc = _fresh(db, user)
    opens, _ = window_bounds(user, user.settings, DAY)
    assert esc.advance(row, at=opens + 59 * 60) is None
    assert esc.advance(row, at=opens + 60 * 60) == ACTION_REMIND
    assert row.stage == STAGE_REMINDED


def test_the_window_closing_starts_the_calls(db, user):
    row, esc = _fresh(db, user)
    _, closes = window_bounds(user, user.settings, DAY)
    row.stage = STAGE_REMINDED
    row.stage_entered_at = closes - 3600
    assert esc.advance(row, at=closes - 60) is None
    assert esc.advance(row, at=closes) == ACTION_IVR
    assert row.stage == STAGE_CALLED
    assert row.call_attempts == 1


def test_the_second_call_waits_ten_minutes(db, user):
    row, esc = _fresh(db, user)
    _, closes = window_bounds(user, user.settings, DAY)
    row.stage = STAGE_CALLED
    row.stage_entered_at = closes
    row.call_attempts = 1
    row.last_call_at = closes + 120

    assert esc.advance(row, at=closes + 120 + 9 * 60) is None
    assert esc.advance(row, at=closes + 120 + 10 * 60) == ACTION_IVR
    assert row.call_attempts == 2


def test_guardians_are_told_only_after_both_calls_go_unanswered(db, user):
    row, esc = _fresh(db, user)
    _, closes = window_bounds(user, user.settings, DAY)
    row.stage = STAGE_CALLED
    row.stage_entered_at = closes
    row.call_attempts = 2
    row.last_call_at = closes + 12 * 60

    assert esc.advance(row, at=closes + 21 * 60) is None
    assert esc.advance(row, at=closes + 22 * 60) == ACTION_ALERT_GUARDIANS
    assert row.stage == STAGE_GUARDIANS_ALERTED


def test_helpers_are_told_after_an_hour_with_no_guardian_ack(db, user):
    row, esc = _fresh(db, user)
    alerted = _at(12, 30)
    row.stage = STAGE_GUARDIANS_ALERTED
    row.stage_entered_at = alerted

    assert esc.advance(row, at=alerted + 59 * 60) is None
    assert esc.advance(row, at=alerted + 60 * 60) == ACTION_ALERT_HELPERS
    assert row.stage == STAGE_HELPERS_ALERTED


def test_a_terminal_row_never_moves_again(db, user):
    row, esc = _fresh(db, user)
    row.stage = STAGE_CHECKED_IN
    assert esc.advance(row, at=_at(23, 0)) is None
    row.stage = STAGE_RESOLVED
    assert esc.advance(row, at=_at(23, 0)) is None


def test_a_paused_user_is_never_paged(db, user):
    row, esc = _fresh(db, user)
    user.settings.paused_until = now() + 30 * 86400
    db.commit()
    for stage in (STAGE_WAITING, STAGE_REMINDED, STAGE_CALLED,
                  STAGE_GUARDIANS_ALERTED, STAGE_HELPERS_ALERTED):
        row.stage = stage
        row.stage_entered_at = _at(9, 0)
        assert esc.advance(row, at=_at(23, 0)) is None, stage


def test_a_late_check_in_notifies_guardians_immediately(db, user):
    """The alert already went out; a check-in now must reach guardians at once."""
    row, esc = _fresh(db, user)
    row.stage = STAGE_GUARDIANS_ALERTED
    row.stage_entered_at = _at(12, 30)

    sent = []
    esc.dispatch = lambda action, checkin, **kw: sent.append((action, kw))

    esc.check_in(row, method="tapped")
    assert row.stage == STAGE_CHECKED_IN
    assert sent and sent[0][0] == ACTION_NOTIFY_ALL_FINE
    assert sent[0][1].get("resolved_early") is True


def test_an_early_check_in_does_not_send_an_all_fine(db, user):
    row, esc = _fresh(db, user)
    sent = []
    esc.dispatch = lambda action, checkin, **kw: sent.append(action)
    esc.check_in(row, method="tapped")
    # Nobody was ever told anything was wrong, so there is nothing to retract.
    assert sent == []


def test_a_late_check_in_keeps_the_location_permission(db, user):
    row, esc = _fresh(db, user)
    user.settings.share_location = False
    db.commit()
    esc.check_in(row, method="tapped", lat=26.8, lng=80.9, accuracy=12.0)
    assert row.lat is None

    user.settings.share_location = True
    db.commit()
    row.stage = STAGE_WAITING
    esc.check_in(row, method="tapped", lat=26.8, lng=80.9, accuracy=12.0)
    assert row.lat == pytest.approx(26.8)
    assert row.location_accuracy == pytest.approx(12.0)


def test_every_step_is_appended_to_the_audit_trail(db, user):
    row, esc = _fresh(db, user)
    opens, closes = window_bounds(user, user.settings, DAY)
    esc.advance(row, at=opens + 3600)
    row.stage_entered_at = closes
    esc.advance(row, at=closes)
    esc.check_in(row, method="tapped")

    stages = [e["stage"] for e in row.events]
    assert STAGE_REMINDED in stages
    assert STAGE_CALLED in stages
    assert stages[-1] == STAGE_CHECKED_IN


def test_running_the_job_twice_does_not_double_escalate(db, user):
    """The job runs every minute; asking twice must be quiet the second time."""
    row, esc = _fresh(db, user)
    opens, _ = window_bounds(user, user.settings, DAY)
    tick = opens + 3600

    assert esc.advance(row, at=tick) == ACTION_REMIND
    # Caller applies the change and writes it back, as the job does.
    row.stage = STAGE_REMINDED
    row.stage_entered_at = tick
    assert esc.advance(row, at=tick) is None
