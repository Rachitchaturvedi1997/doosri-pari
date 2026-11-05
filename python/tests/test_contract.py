"""
The shared escalation contract, and the three implementations it binds together.

`shared/contract/escalation-contract.json` is the machine-readable statement of
what a missed morning looks like. Kotlin is authoritative; Python is the
server's copy; Swift is the iOS app's copy and can only be checked on a Mac.
`test_parity.py` already parses the Kotlin source and pins it against Python.
This file does the remaining job:

  * it pins the contract file against the live Python constants, so retiming a
    stage without regenerating the contract fails here; and
  * it pins the contract's timeline to numbers a human wrote out, so the
    contract cannot drift into being self-consistent but wrong.

The Swift half is `ios/DoosriPariTests/EscalationContractTests.swift`, which
reads the same JSON. It cannot run here — there is no macOS on this machine —
so its half of the check is unverified until someone runs it.
"""
import json
from pathlib import Path

import pytest

from python import config
from python.models import (
    METHOD_IN_PERSON,
    METHOD_IVR,
    METHOD_PASSIVE,
    METHOD_TAPPED,
    STAGE_CALLED,
    STAGE_CHECKED_IN,
    STAGE_GUARDIANS_ALERTED,
    STAGE_HELPERS_ALERTED,
    STAGE_REMINDED,
    STAGE_RESOLVED,
    STAGE_WAITING,
    TERMINAL_STAGES,
)

CONTRACT = (
    Path(__file__).resolve().parents[2]
    / "shared"
    / "contract"
    / "escalation-contract.json"
)

# The timeline, written out by hand rather than derived. If someone retimes a
# stage, this is the thing that has to be updated on purpose — the arithmetic in
# gen_contract.py would otherwise keep agreeing with itself forever.
EXPECTED_TIMELINE = [
    (0, STAGE_WAITING, "None"),
    (60, STAGE_REMINDED, "RemindUser"),
    (180, STAGE_CALLED, "PlaceIvrCall"),
    (190, STAGE_CALLED, "PlaceIvrCall"),
    (200, STAGE_GUARDIANS_ALERTED, "AlertGuardians"),
    (260, STAGE_HELPERS_ALERTED, "AlertHelpers"),
]


@pytest.fixture(scope="module")
def contract() -> dict:
    if not CONTRACT.exists():
        pytest.fail(
            f"{CONTRACT} is missing. Run `python -m python.tools.gen_contract`. "
            "Without it the iOS copy of the escalation rules is unchecked."
        )
    return json.loads(CONTRACT.read_text(encoding="utf-8"))


# ------------------------------------------------------------------- stages --
def test_the_contract_lists_every_stage_the_server_knows(contract):
    assert contract["stages"] == [
        STAGE_WAITING,
        STAGE_REMINDED,
        STAGE_CALLED,
        STAGE_GUARDIANS_ALERTED,
        STAGE_HELPERS_ALERTED,
        STAGE_CHECKED_IN,
        STAGE_RESOLVED,
    ]


def test_the_contract_agrees_about_which_stages_are_terminal(contract):
    assert contract["terminalStages"] == list(TERMINAL_STAGES)


def test_the_contract_lists_the_wire_values_for_a_check_in_method(contract):
    # These are the strings a server sends in the `method` field. Swift's enum
    # raw values must match them exactly — it is a strict decoder.
    assert contract["methods"] == [
        METHOD_TAPPED,
        METHOD_PASSIVE,
        METHOD_IVR,
        METHOD_IN_PERSON,
    ]


# ------------------------------------------------------------------ timings --
def test_the_contract_timings_are_the_live_config(contract):
    t = contract["timingsSeconds"]
    assert t["remindAfter"] == config.REMIND_AFTER_S
    assert t["ivrRetryGap"] == config.IVR_RETRY_GAP_S
    assert t["ivrAttempts"] == config.IVR_ATTEMPTS
    assert t["helperEscalationAfter"] == config.HELPER_ESCALATION_S


def test_the_contract_roster_limits_are_the_live_config(contract):
    r = contract["roster"]
    assert r["maxGuardians"] == config.MAX_GUARDIANS
    assert r["maxHelpers"] == config.MAX_HELPERS
    assert r["pauseMinDays"] == config.PAUSE_MIN_DAYS
    assert r["pauseMaxDays"] == config.PAUSE_MAX_DAYS


# ----------------------------------------------------------------- timeline --
def test_the_missed_morning_is_the_one_the_plan_draws(contract):
    """The whole chain, in order, at the minutes a person can check by hand."""
    got = [
        (step["atMinutes"], step["stage"], step["action"])
        for step in contract["missedMorning"]
    ]
    assert got == EXPECTED_TIMELINE


def test_the_timeline_only_names_stages_the_contract_declares(contract):
    known = set(contract["stages"])
    actions = set(contract["actions"])
    for step in contract["missedMorning"]:
        assert step["stage"] in known, f"unknown stage {step['stage']!r}"
        assert step["action"] in actions, f"unknown action {step['action']!r}"


def test_the_offsets_go_forward_and_nothing_repeats_a_moment(contract):
    offsets = [s["atMinutes"] for s in contract["missedMorning"]]
    assert offsets == sorted(offsets)
    assert len(offsets) == len(set(offsets))


def test_guardians_are_never_told_before_the_second_call_has_had_its_gap(contract):
    """
    The bug the parity test caught during development, pinned from the other
    direction. The second call is at 190 and guardians at 200; if these ever
    collapse to the same minute, a family is woken up before the automated
    call has had the ten minutes the plan gives it.
    """
    steps = {s["action"]: s["atMinutes"] for s in contract["missedMorning"]}
    calls = [
        s["atMinutes"] for s in contract["missedMorning"]
        if s["action"] == "PlaceIvrCall"
    ]
    assert len(calls) == config.IVR_ATTEMPTS
    assert steps["AlertGuardians"] - calls[-1] == config.IVR_RETRY_GAP_S // 60


def test_the_first_call_lands_when_the_window_closes(contract):
    """Not before it. Calling somebody at 11:50 who still has ten minutes to
    check in is exactly the kind of bug that makes people switch the app off."""
    # Both calls share the PlaceIvrCall action, so take the earliest rather than
    # building a dict keyed on action — that would silently keep the second one.
    calls = [
        s["atMinutes"] for s in contract["missedMorning"]
        if s["action"] == "PlaceIvrCall"
    ]
    assert min(calls) == contract["window"]["defaultMinutes"]


def test_helpers_wait_the_full_hour_after_guardians_were_told(contract):
    steps = {s["action"]: s["atMinutes"] for s in contract["missedMorning"]}
    gap = steps["AlertHelpers"] - steps["AlertGuardians"]
    assert gap == config.HELPER_ESCALATION_S // 60
