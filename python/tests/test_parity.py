"""
Parity between the Kotlin state machine and the Python one.

The machine exists twice: shared/feature-checkin is authoritative, and
python/escalation.py is the server's copy of the same rules, because the server
cannot import Kotlin. A duplicated rule is only safe if something checks the two
have not drifted, which is what this file is. Both implementation files point
here in their headers.

These tests parse the Kotlin source rather than running it. That is a weaker
check than executing both machines over a shared set of scenarios, and it is
the honest trade for a build that has to pass in CI on a machine with no Xcode
and no Android SDK. What it does catch is the realistic failure: somebody
retimes a stage on one side and not the other.
"""
import re
from pathlib import Path

import pytest

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
)

KOTLIN = (
    Path(__file__).resolve().parents[2]
    / "shared" / "feature-checkin" / "src" / "commonMain" / "kotlin"
    / "in" / "doosripari" / "feature" / "checkin" / "CheckIn.kt"
)

# Kotlin spells durations as `1.hours` / `10.minutes`; we compare in seconds.
DURATION_UNITS = {
    "hours": 3600,
    "minutes": 60,
    "seconds": 1,
    "days": 86400,
}


@pytest.fixture(scope="module")
def kotlin_source() -> str:
    if not KOTLIN.exists():
        pytest.fail(
            f"{KOTLIN} is missing, so the Kotlin and Python escalation rules "
            "cannot be compared. The server would be running an unverified copy."
        )
    return KOTLIN.read_text(encoding="utf-8")


def _const(source: str, name: str) -> int:
    """Read `const val NAME: Int = <n>` out of the Kotlin companion object."""
    m = re.search(rf"const val {name}: Int = (\d+)", source)
    assert m, f"CheckIn.kt no longer declares `const val {name}: Int` — update this test"
    return int(m.group(1))


def _duration(source: str, name: str) -> int:
    """Read `val NAME: Duration = <n>.<unit>` and return it in whole seconds."""
    m = re.search(rf"val {name}: Duration = (\d+)\.(\w+)", source)
    assert m, f"CheckIn.kt no longer declares `val {name}: Duration` — update this test"
    return int(m.group(1)) * DURATION_UNITS[m.group(2)]


# ------------------------------------------------------------------ timings --
def test_the_reminder_delay_is_the_same_on_both_sides(kotlin_source):
    assert _duration(kotlin_source, "REMIND_AFTER") == config.REMIND_AFTER_S


def test_the_gap_between_automated_calls_is_the_same_on_both_sides(kotlin_source):
    assert _duration(kotlin_source, "IVR_RETRY_GAP") == config.IVR_RETRY_GAP_S


def test_the_number_of_automated_calls_is_the_same_on_both_sides(kotlin_source):
    assert _const(kotlin_source, "IVR_ATTEMPTS") == config.IVR_ATTEMPTS


def test_the_wait_for_helpers_is_the_same_on_both_sides(kotlin_source):
    assert (
        _duration(kotlin_source, "HELPER_ESCALATION_AFTER")
        == config.HELPER_ESCALATION_S
    )


def test_the_pause_limits_are_the_same_on_both_sides(kotlin_source):
    assert _const(kotlin_source, "MIN_PAUSE_DAYS") == config.PAUSE_MIN_DAYS
    assert _const(kotlin_source, "MAX_PAUSE_DAYS") == config.PAUSE_MAX_DAYS


def test_the_guardian_and_helper_roster_limits_are_the_same_on_both_sides(kotlin_source):
    assert _const(kotlin_source, "MAX_GUARDIANS") == config.MAX_GUARDIANS
    assert _const(kotlin_source, "MAX_HELPERS") == config.MAX_HELPERS


# ------------------------------------------------------------- stage strings --
# The stage names are the wire format both apps see, so a rename on either side
# silently breaks every installed app. Compare them name for name.
def _kotlin_stages(source: str) -> set[str]:
    body = re.search(r"enum class CheckInStage \{(.*?)\n\}", source, re.S)
    assert body, "CheckIn.kt no longer declares `enum class CheckInStage`"
    # Strip the doc comments FIRST. Several of them contain a semicolon
    # ("...no check-in; the user has been nudged."), and cutting at that
    # instead of the enum's own terminator silently truncated this to one stage.
    entries = re.sub(r"/\*.*?\*/", "", body.group(1), flags=re.S)
    # A Kotlin enum with members ends its constants at the semicolon. Cutting
    # there keeps the companion object — added for fromWire — out of the set.
    entries = entries.split(";", 1)[0]
    names = set()
    for part in entries.split(","):
        m = re.match(r"\s*([A-Za-z][A-Za-z0-9]*)", part)
        if m:
            names.add(m.group(1))
    return names


def test_both_sides_know_the_same_stages(kotlin_source):
    python_stages = {
        STAGE_WAITING, STAGE_REMINDED, STAGE_CALLED, STAGE_GUARDIANS_ALERTED,
        STAGE_HELPERS_ALERTED, STAGE_CHECKED_IN, STAGE_RESOLVED,
    }
    assert _kotlin_stages(kotlin_source) == python_stages


def test_both_sides_agree_on_which_stages_are_terminal(kotlin_source):
    # In Kotlin: `val isTerminal: Boolean get() = this == CheckedIn || this == Resolved`
    m = re.search(
        r"val isTerminal: Boolean get\(\) = this == (\w+) \|\| this == (\w+)",
        kotlin_source,
    )
    assert m, "CheckIn.kt no longer defines isTerminal the way this test expects"
    assert {m.group(1), m.group(2)} == set(TERMINAL_STAGES)


def test_the_default_window_is_the_same_on_both_sides(kotlin_source):
    """A different default would mean one app opens the day an hour after the other."""
    from python.models import CheckInSettings

    m = re.search(
        r"val DEFAULT_WINDOW_START: LocalTime = LocalTime\((\d+),\s*(\d+)\)",
        kotlin_source,
    )
    assert m, "CheckIn.kt no longer declares DEFAULT_WINDOW_START as LocalTime(h, m)"
    columns = CheckInSettings.__table__.c
    assert columns.window_start.default.arg == (
        f"{int(m.group(1)):02d}:{int(m.group(2)):02d}"
    )

    m = re.search(r"const val DEFAULT_WINDOW_MINUTES: Int = (\d+)", kotlin_source)
    assert m, "CheckIn.kt no longer declares DEFAULT_WINDOW_MINUTES"
    assert columns.window_mins.default.arg == int(m.group(1))
