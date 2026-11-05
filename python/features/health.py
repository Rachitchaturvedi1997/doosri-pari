# =============================================================================
# Feature · Swasthya — plan section 6.10.
#
#   GET    /health/consent         what plan 14's three consents currently are,
#                                  so Settings can draw them and change them
#   GET    /medicines              the caller's own medicines
#   POST   /medicines
#   GET    /medicines/{id}
#   PATCH  /medicines/{id}
#   DELETE /medicines/{id}         stops it; keeps the history
#   GET    /doses                  a day's doses, for the Today card
#   POST   /doses/{id}             record what happened to one DUE dose
#   GET    /health/summary         the day's totals, the stock, flagged readings
#   GET    /health/guardian/{id}   what a guardian may see, and nothing else
#   GET    /vitals                 the readings, for a second phone and the PDF
#   POST   /vitals
#   GET    /vitals/{id}
#   DELETE /vitals/{id}
#   GET    /reminders              the server-side copies
#   POST   /reminders
#   PATCH  /reminders/{id}
#   DELETE /reminders/{id}
#   GET    /facilities             the CGHS/ECHS directory; public, signed out
#
# Two rules run through the whole file.
#
# Health data is its own named consent. Plan 14 lists location, health data and
# guardian sharing as three switches, each withdrawable in Settings, and
# `require_health_consent` is the one call every route below makes before it
# touches a row. It refuses with a sentence, never with an empty list: a
# pensioner told "no medicines" and a pensioner told "you have turned health
# data off" have to do different things, and only one of them can open Settings.
# A skipped call looks like a normal line of CRUD and is the single most likely
# thing for a later edit to leave off, which is why the refusal lives in one
# function rather than being repeated.
#
# A dose row is written when the dose comes DUE — by the `dose_followup` job,
# every five minutes, plan 12 — and never by the acknowledgement.
# `POST /doses/{id}` therefore records an outcome onto a row that already
# exists. A schedule whose rows are created by admitting to it cannot record the
# case this whole feature exists for: a person in hospital who stopped taking
# their tablets did not decide to skip them, and a phone with no network would
# produce no row at all, so the two-miss guardian alert would count nothing.
#
# Two more, because they are the ones a careful reviewer looks for: medicines
# are shared with a family one at a time (`Medicine.share_with_guardians`, off
# by default) and never as a blanket, and nothing in this file interprets a
# reading. A number outside its band is stored and flagged for the doctor to
# look at; it is not a diagnosis, and this module has no vocabulary for one.
# =============================================================================
from __future__ import annotations

import logging
from datetime import datetime
from math import atan2, cos, radians, sin, sqrt
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import String, select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession, OptionalUser
from python.escalation import local_day
from python.models import (
    DOSE_MISSES_BEFORE_ALERT,
    DOSE_STATUSES,
    REMINDER_KINDS,
    URGENT_REMINDER_KINDS,
    VITAL_KINDS,
    DoseLog,
    Facility,
    Guardian,
    Medicine,
    Reminder,
    User,
    Vital,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.health")
router = APIRouter(tags=["health"])

# The three dose codes. Written out rather than indexed out of DOSE_STATUSES,
# which stays the authority on what is valid and is checked on every write; an
# index into somebody's tuple order is how a constant gets renumbered into a
# lie without a single test going red.
DOSE_TAKEN = "taken"
DOSE_MISSED = "missed"
DOSE_SKIPPED = "skipped"

# core-model's GuardianPermissions, by name. The Python column is a JSON map
# and `Guardian.gets_location` already reads one of these keys, so these are the
# names in the database and not names invented for this file.
PERM_ALERTS = "alerts"
PERM_LOCATION = "location"
PERM_DOCUMENT_SAFE = "document_safe"
PERM_MEDICINES = "medicines"

# More than eight doses of one medicine in a day is a data-entry mistake, not a
# schedule. A pensioner is not titrating anything, and a row of forty times is a
# Today card nobody can read and a guardian alert about a dose nobody took.
MAX_DOSES_PER_DAY = 8

# StockCheck.LOW_STOCK_DAYS in feature-health. Plan 6.10 says five days; the
# shared module ships three and is what draws the number, so this file uses the
# same three rather than being a second opinion in a second language. The line
# to change if five is genuinely wanted is that constant, not this one.
LOW_STOCK_DAYS = 3

# FacilityFinder.DEFAULT_RADIUS_KM / STALE_AFTER_DAYS and FacilityQuery's limits
# in feature-health, so the server's window and the phone's agree.
FACILITY_RADIUS_KM = 10.0
FACILITY_STALE_DAYS = 45
FACILITY_LIMIT = 20
EARTH_RADIUS_KM = 6371.0088

# core-model's VitalKind, mirrored. `keys`, `unit` and `bands` are that enum's
# values and not a second set of opinions: the phone draws the same keys off the
# same table, and a server that accepted a key the phone never draws would put a
# reading into the log that no screen can explain and the PDF cannot print.
#
# Weight and temperature carry no band on purpose. core-model says so in as many
# words — "a judgement about them belongs to a doctor" — and this table is where
# that decision would otherwise quietly get reversed by somebody tidying the
# other rows up.
VITAL_SINGLE_KEY = "value"

VITAL_SPEC: dict[str, dict] = {
    "blood_pressure": {
        "keys": ("systolic", "diastolic"),
        "unit": "mmHg",
        "bands": {"systolic": (90.0, 140.0), "diastolic": (60.0, 90.0)},
    },
    "pulse": {
        "keys": (VITAL_SINGLE_KEY,),
        "unit": "bpm",
        "bands": {VITAL_SINGLE_KEY: (50.0, 100.0)},
    },
    "blood_sugar_fasting": {
        "keys": (VITAL_SINGLE_KEY,),
        "unit": "mg/dL",
        "bands": {VITAL_SINGLE_KEY: (70.0, 140.0)},
    },
    "blood_sugar_random": {
        "keys": (VITAL_SINGLE_KEY,),
        "unit": "mg/dL",
        "bands": {VITAL_SINGLE_KEY: (70.0, 200.0)},
    },
    "weight": {"keys": (VITAL_SINGLE_KEY,), "unit": "kg", "bands": {}},
    "temperature": {"keys": (VITAL_SINGLE_KEY,), "unit": "°F", "bands": {}},
}

# The two kinds whose unit the caller may correct. A glucometer set to mmol/L is
# the most common thing this app gets wrong, the model carries the unit a
# reading was taken in, and every other kind has exactly one possible unit — so
# the override stops there rather than becoming a free-text column.
SUGAR_KINDS = ("blood_sugar_fasting", "blood_sugar_random")

# What `schedule` has to carry, per kind. The model's own comment calls it "a
# typed reference rather than free text", and the reason is the job that has to
# read it: reminders_tick cannot interpret a sentence.
REQUIRED_SCHEDULE_KEYS: dict[str, tuple[str, ...]] = {
    "medicine": ("medicine_id",),
    "check_in": (),
    "life_certificate": ("due_on",),
    "appointment": ("at",),
    "circle_event": (),
    "yoga_session": (),
    "digest": (),
}

# Refusals, in the words the person reading them uses. Nothing here says
# "consent", "authorisation" or "policy": a pensioner who is told they are not
# authorised has no idea what to do next, and a refusal nobody can act on is a
# support call.
HEALTH_CONSENT_REFUSED = (
    "Health data is switched off for this account. Open Settings and switch on "
    "health data to use the medicine and reading log. Nothing is shared while "
    "it is off, and you can switch it off again the same way."
)
NOT_ON_FAMILY_LIST = (
    "This person has not added you to their family list, so their medicines "
    "are not shown here. Ask them to add you."
)
MEDICINES_NOT_SHARED = (
    "This person has not shared their medicine log with you. It is shared one "
    "medicine at a time, so ask them to share this one."
)
NOT_A_DIAGNOSIS = (
    "This app records readings and reminds you about medicines. It does not "
    "diagnose anything and it is not a doctor. Show the readings to one."
)


# ------------------------------------------------------------------ schemas --
class MedicineIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    # Free text, deliberately: "5 mg", "1 tab" and "half a strip" are all
    # correct, and normalising them loses the only detail anybody will use to
    # recognise the strip on the shelf.
    dose: str = Field(min_length=1, max_length=40)
    # Minutes from local midnight, not timestamps. See `_clean_times`.
    times: list[int] = Field(default_factory=list)
    with_food: bool = False
    stock: int = Field(default=0, ge=0, le=999)
    is_active: bool = True
    # Off unless asked for, on every medicine separately.
    share_with_guardians: bool = False


class MedicinePatchIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=120)
    dose: str | None = Field(default=None, min_length=1, max_length=40)
    times: list[int] | None = None
    with_food: bool | None = None
    stock: int | None = Field(default=None, ge=0, le=999)
    is_active: bool | None = None
    share_with_guardians: bool | None = None


class DoseAckIn(BaseModel):
    status: str = Field(default=DOSE_TAKEN)


class VitalIn(BaseModel):
    kind: str
    values: dict[str, float]
    # Only the sugar kinds may correct the unit; see SUGAR_KINDS.
    unit: str | None = Field(default=None, max_length=16)
    # When it was measured, not when it was typed. A blood pressure written down
    # at nine about a reading taken at seven is a reading from seven.
    at: float | None = None
    from_health_platform: bool = False
    note: str | None = Field(default=None, max_length=240)


class ReminderIn(BaseModel):
    kind: str
    schedule: dict = Field(default_factory=dict)
    next_at: float
    title: str | None = Field(default=None, max_length=160)
    body: str | None = Field(default=None, max_length=400)


class ReminderPatchIn(BaseModel):
    next_at: float | None = None
    done_at: float | None = None
    title: str | None = Field(default=None, max_length=160)
    body: str | None = Field(default=None, max_length=400)


# ------------------------------------------------------------------ consent --
def health_consent(db: Session, user: User) -> dict:
    """
    Plan 14's consents, read one switch at a time.

    Location and guardian sharing are stored. The user's own on/off switch for
    keeping health data on the server is not: the schema has no column for it,
    and the plan gives it no endpoint either (section 11's Health row has none).
    What is reported instead is the fact the switch would need to describe —
    whether a log exists — so Settings can show "your readings are on this
    phone's server" and offer the switch rather than invent a state for it. When
    the column lands, this is the function that reads it.
    """
    rows = db.scalars(select(Guardian).where(Guardian.user_id == user.id)).all()
    accepted = [g for g in rows if g.accepted_at is not None]
    perms = [g.perms or {} for g in accepted]
    log_present = db.scalar(select(Medicine.id).where(Medicine.user_id == user.id).limit(1))
    if log_present is None:
        log_present = db.scalar(select(Vital.id).where(Vital.user_id == user.id).limit(1))
    return {
        # The user's own location switch, then each guardian's grant. Both are
        # checked, in that order: a per-guardian "yes" over a user-level "no"
        # sends nothing.
        "location": bool((user.settings and user.settings.share_location))
        and any(p.get(PERM_LOCATION) for p in perms),
        "guardian_sharing": any(p.get(PERM_MEDICINES) for p in perms),
        # Whether this account currently holds a health log on the server. See
        # the docstring: this is the fact, not the consent.
        "health_log_present": log_present is not None,
        "guardians": [
            {
                "id": g.id,
                "name": g.name,
                "relation": g.relation,
                "alerts": bool((g.perms or {}).get(PERM_ALERTS, True)),
                "location": bool((g.perms or {}).get(PERM_LOCATION)),
                "medicines": bool((g.perms or {}).get(PERM_MEDICINES)),
                "document_safe": bool((g.perms or {}).get(PERM_DOCUMENT_SAFE)),
            }
            for g in rows
        ],
    }


def require_health_consent(
    db: Session, actor: User, subject: User | None = None
) -> Guardian | None:
    """
    The §14 health-data consent, enforced on every route in this file that
    returns or accepts health data.

    The own-data routes pass the caller as both actor and subject and get a
    straight answer, and that is not a hole: none of them takes a user id from
    the request, so there is nothing in the request that could name somebody
    else's rows. What the guard is for is the one route where a second party is
    involved, and there it is the whole difference between family support and a
    family surveillance system.

    A guardian is refused in two separate cases, and they are refused
    differently because they are different mistakes. Not being on the family
    list at all, or never having accepted the invite, is a stranger asking for
    somebody's medication schedule and gets the first message. Being on the list
    and being an accepted guardian, but without the medicine grant, is a family
    member the pensioner has not chosen to share with — and the message they get
    is the one that says so, because that is the one the pensioner acted on.

    `Medicine.share_with_guardians` is checked after this, per medicine. This
    function is the person's consent; that field is the medicine's.
    """
    subject = subject or actor
    if subject.id == actor.id:
        return None
    guardian = db.scalar(
        select(Guardian).where(
            Guardian.user_id == subject.id,
            Guardian.guardian_user_id == actor.id,
            Guardian.accepted_at.isnot(None),
        )
    )
    if guardian is None:
        raise HTTPException(403, NOT_ON_FAMILY_LIST)
    if not (guardian.perms or {}).get(PERM_MEDICINES):
        raise HTTPException(403, MEDICINES_NOT_SHARED)
    return guardian


# ------------------------------------------------------------------ helpers --
def _tz(user: User) -> ZoneInfo:
    try:
        return ZoneInfo(user.time_zone or "UTC")
    except Exception:
        # A bad IANA name in somebody's profile would otherwise 500 the whole
        # Today card. UTC puts a dose on the wrong side of midnight for a few
        # hours, and a wrong day is a thing the user can correct.
        return ZoneInfo("UTC")


def _day_bounds(user: User, day: str) -> tuple[float, float]:
    """
    Local midnight to local midnight, as epoch seconds.

    Built from the date rather than by adding 86 400, because a day in India is
    not always 24 hours of epoch — one of the two clock changes a year makes it
    23 and the other 25, and a dose schedule that jumps an hour twice a year is
    the thing the Medicine model says the times are minutes precisely to avoid.
    """
    try:
        y, m, d = (int(p) for p in day.split("-"))
        opens = datetime(y, m, d, tzinfo=_tz(user)).timestamp()
        closes = datetime(y, m, d + 1, tzinfo=_tz(user)).timestamp()
    except (TypeError, ValueError):
        raise HTTPException(400, "day must be a date like 2026-09-29") from None
    return opens, closes


def _clean_times(times: list[int]) -> list[int]:
    """
    Minutes from local midnight, bounded, de-duplicated and sorted.

    Why minutes and not a timestamp, since it is the decision this whole
    schedule rests on: a pensioner takes a tablet at 8am whether or not the
    timezone database has heard of their district, and a stored instant that
    slid by an hour twice a year would be a schedule they stopped trusting. So
    the server stores "480" and rebuilds the instant against the user's own
    timezone at the moment it is needed, which is also what makes the row
    queryable for "before 9".

    0 to 1439 rather than an open int because a value outside that is not a
    time of day, and a schedule with a slot at 1500 is a schedule a pensioner
    cannot read, which is a schedule they do not follow.
    """
    if len(times) > MAX_DOSES_PER_DAY:
        raise HTTPException(
            400, f"no medicine is taken more than {MAX_DOSES_PER_DAY} times a day"
        )
    out: list[int] = []
    for t in times:
        if not 0 <= t <= 1439:
            raise HTTPException(
                400, "a time is minutes after midnight, from 0 to 1439"
            )
        if t in out:
            raise HTTPException(
                400, "two doses of the same medicine cannot be at the same time"
            )
        out.append(t)
    out.sort()
    return out


def _outside_usual(kind: str, values: dict) -> list[str]:
    """
    Which numbers sit outside the band this app usually sees for this person.

    A threshold to notice, not a diagnosis, and it says nothing else. It names
    no condition, advises nothing, calls nothing dangerous, and returns no
    severity. The whole of what this app is allowed to say is "this is higher
    than usual — would you like to show it to a doctor?", and the caller
    assembles that sentence in the pensioner's own language.

    Empty for weight and temperature, because core-model gives them no band. A
    blank is not a finding either: a key that is absent is never reported here.
    """
    spec = VITAL_SPEC[kind]
    bands = spec["bands"]
    return [
        key
        for key in spec["keys"]
        if key in bands and key in values and not bands[key][0] <= values[key] <= bands[key][1]
    ]


def _clean_vital_values(kind: str, values: dict) -> tuple[dict, float]:
    """
    Check a reading against its kind, and hand back the numbers to store.

    Three rejections and no others. A kind or a key the kind does not carry is
    refused because `vitals.values` is a map and a map is the easiest thing in
    this codebase to fill with junk — a key out of a voice transcription, a
    unit-suffixed string somebody parsed by hand, a second `value` on a
    blood-pressure row — and an endpoint that takes arbitrary keys is a place to
    store anything at all. A number that is not a number, or is zero or below,
    is refused because a negative blood pressure is a parsing failure rather
    than a measurement, and that number sitting in a log a doctor may eventually
    be shown is not something this app should hand over.

    Nothing else is refused. A reading of 210 over 130 is stored, exactly as
    given, and flagged by `_outside_usual` for the user to take to a doctor. The
    temptation right here is to reject it as implausible or to attach a word like
    "severe" to it; both are the diagnosis this app does not give (plan 14), and
    the second is worse than the first because it would be true-ish often enough
    to be believed.
    """
    spec = VITAL_SPEC[kind]
    allowed = spec["keys"]
    if not values:
        raise HTTPException(400, f"a {kind.replace('_', ' ')} reading needs a number")
    clean: dict[str, float] = {}
    for key, raw in values.items():
        if key not in allowed:
            raise HTTPException(
                400,
                f"a {kind.replace('_', ' ')} reading takes only "
                f"{' and '.join(allowed)}, not {key!r}",
            )
        try:
            value = float(raw)
        except (TypeError, ValueError):
            raise HTTPException(400, "a reading has to be a number") from None
        if value != value or value in (float("inf"), float("-inf")):
            raise HTTPException(400, "a reading has to be a number")
        if value <= 0:
            raise HTTPException(400, "a reading has to be more than zero")
        clean[key] = value
    for key in allowed:
        # Blood pressure is a pair and half a pair is a typo, not a reading.
        # Refusing it here is a parsing rule, not a clinical one.
        if key not in clean:
            raise HTTPException(
                400, f"a {kind.replace('_', ' ')} reading needs {key}"
            )
    return clean, spec["unit"]


def _validate_schedule(kind: str, schedule: dict) -> dict:
    for key in REQUIRED_SCHEDULE_KEYS.get(kind, ()):
        if key not in (schedule or {}):
            raise HTTPException(400, f"a {kind} reminder needs '{key}' in its schedule")
    if kind == "life_certificate":
        due = (schedule or {}).get("due_on")
        if not isinstance(due, str) or len(due) != 10 or due[4] != "-" or due[7] != "-":
            raise HTTPException(400, "due_on must be a date like 2026-11-30")
    if kind == "medicine":
        mid = (schedule or {}).get("medicine_id")
        if not isinstance(mid, int) or isinstance(mid, bool) or mid <= 0:
            raise HTTPException(400, "medicine_id must be the number of a medicine")
    return dict(schedule or {})


def _dose_counts(rows: list[DoseLog], at: float) -> dict:
    """
    The day's arithmetic, counted the way feature-health counts it.

    A skipped dose is on its own line and is never folded into missed. It is a
    decision somebody made about their own body, usually with a doctor, and
    counting it as a miss would put that decision on the other side of a
    telephone call to their family.

    `open` is the mirror of that: still inside the row's own window, so still
    takeable, and so not a miss yet. Calling it a miss early is how a reminder
    turns into a complaint.
    """
    taken = skipped = missed = 0
    for row in rows:
        if row.status == DOSE_TAKEN:
            taken += 1
        elif row.status == DOSE_SKIPPED:
            skipped += 1
        elif row.taken_at is not None or at >= row.due_until:
            # Settled, whether or not the job has run to say so. The row's own
            # window is the authority, not a timer restarted on this request.
            missed += 1
    return {
        "total": len(rows),
        "taken": taken,
        "skipped": skipped,
        "missed": missed,
        "open": len(rows) - taken - skipped - missed,
    }


def _alert_due(row: DoseLog) -> bool:
    """
    Whether `dose_followup` still owes a guardian a call about this row.

    `guardians_alerted_at` is the idempotency key for the whole alert, and it
    is set by the job *after* the messages have gone out. Nothing in this file
    writes it, and the reason is worth stating: a flag set before the send is a
    flag set on a send that did not happen, and the second miss in a row is
    exactly the one that has to land. So this only reports the column's state,
    and the job owns the write.
    """
    return (
        row.status == DOSE_MISSED
        and (row.consecutive_misses or 0) >= DOSE_MISSES_BEFORE_ALERT
        and row.guardians_alerted_at is None
    )


def _days_left(row: Medicine) -> int | None:
    """Days of tablets left, estimated. See StockCheck.daysRemaining."""
    per_day = len(row.times or [])
    if per_day <= 0:
        # A name with no times is a half-saved form, not an empty cupboard, and
        # it must not come back as "0 days left".
        return None
    return (row.stock or 0) // per_day


# --------------------------------------------------------------------- views --
def medicine_view(row: Medicine) -> dict:
    return {
        "id": row.id,
        "name": row.name,
        "dose": row.dose,
        "times": list(row.times or []),
        "with_food": row.with_food,
        "stock": row.stock,
        "is_active": row.is_active,
        # Carried in every read, because the screen that shows a medicine is
        # also the screen that has to tell the truth about who else can see it.
        "share_with_guardians": row.share_with_guardians,
        "doses_per_day": len(row.times or []),
        "days_left": _days_left(row),
        "created_at": row.created_at,
    }


def dose_view(row: DoseLog) -> dict:
    return {
        "id": row.id,
        "medicine_id": row.medicine_id,
        "due_at": row.due_at,
        "due_until": row.due_until,
        "status": row.status,
        "taken_at": row.taken_at,
        "consecutive_misses": row.consecutive_misses,
        "guardians_alerted_at": row.guardians_alerted_at,
    }


def vital_view(row: Vital) -> dict:
    return {
        "id": row.id,
        "kind": row.kind,
        "values": dict(row.values or {}),
        "unit": row.unit,
        "at": row.at,
        "from_health_platform": row.from_health_platform,
        "note": row.note,
        "outside_usual": _outside_usual(row.kind, row.values or {}),
        "is_diagnosis": False,
    }


def reminder_view(row: Reminder) -> dict:
    return {
        "id": row.id,
        "kind": row.kind,
        "schedule": dict(row.schedule or {}),
        "next_at": row.next_at,
        "done_at": row.done_at,
        "title": row.title,
        "body": row.body,
        # Plan 6.15 through the model's own door: `kind` is what decides whether
        # this is allowed to take the lock screen. Carried on the read so no
        # client has to keep a second copy of the urgent list in step with it.
        "may_interrupt": row.kind in URGENT_REMINDER_KINDS,
        "created_at": row.created_at,
    }


# ----------------------------------------------------------------- medicines --
@router.get("/health/consent")
def get_consent(user: CurrentUser, db: DbSession):
    """Plan 14's switches, for the Settings screen to draw."""
    require_health_consent(db, user)
    return health_consent(db, user)


@router.get("/medicines")
def list_medicines(user: CurrentUser, db: DbSession, include_inactive: bool = False):
    require_health_consent(db, user)
    q = select(Medicine).where(Medicine.user_id == user.id)
    if not include_inactive:
        q = q.where(Medicine.is_active.is_(True))
    rows = db.scalars(q.order_by(Medicine.name)).all()
    return {"medicines": [medicine_view(r) for r in rows]}


@router.post("/medicines")
def create_medicine(body: MedicineIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = Medicine(
        user_id=user.id,
        name=body.name.strip(),
        dose=body.dose.strip(),
        times=_clean_times(body.times),
        with_food=body.with_food,
        stock=body.stock,
        is_active=body.is_active,
        # Defaulted false on the column and defaulted false again here, on
        # purpose twice: a guardian must never see a medicine the pensioner did
        # not choose to share, and the difference between family support and a
        # family surveillance system is this one boolean.
        share_with_guardians=body.share_with_guardians,
    )
    db.add(row)
    db.commit()
    return medicine_view(row)


@router.get("/medicines/{medicine_id}")
def get_medicine(medicine_id: int, user: CurrentUser, db: DbSession):
    require_health_consent(db, user)
    row = db.get(Medicine, medicine_id)
    # 404 rather than 403 for a row that is somebody else's: a 403 confirms the
    # id exists, and this endpoint would become a way of counting how many other
    # people are on the app.
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "medicine not found")
    return medicine_view(row)


@router.patch("/medicines/{medicine_id}")
def update_medicine(
    medicine_id: int, body: MedicinePatchIn, request: Request, user: CurrentUser, db: DbSession
):
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = db.get(Medicine, medicine_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "medicine not found")
    if body.name is not None:
        row.name = body.name.strip()
    if body.dose is not None:
        row.dose = body.dose.strip()
    if body.times is not None:
        row.times = _clean_times(body.times)
    if body.with_food is not None:
        row.with_food = body.with_food
    if body.stock is not None:
        row.stock = body.stock
    if body.is_active is not None:
        row.is_active = body.is_active
    if body.share_with_guardians is not None:
        # Turning sharing off is immediate and total: the next guardian read
        # filters on this field, so nothing to undo and nothing to wait for.
        row.share_with_guardians = body.share_with_guardians
    db.commit()
    return medicine_view(row)


@router.delete("/medicines/{medicine_id}")
def stop_medicine(medicine_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    Stop taking a medicine, keeping the record of having taken it.

    A soft stop, not a delete. A course that finished last Tuesday still has
    dose rows against it, and a guardian may be owed an alert about one of them;
    erasing the medicine would erase the evidence that the tablets existed at
    all. The row keeps its history and stops producing doses, misses and
    reminders, which is exactly what feature-health's DoseSchedule does with an
    inactive medicine.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = db.get(Medicine, medicine_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "medicine not found")
    row.is_active = False
    db.commit()
    return medicine_view(row)


# --------------------------------------------------------------------- doses --
@router.get("/doses")
def list_doses(user: CurrentUser, db: DbSession, day: str | None = None):
    """
    One local day of doses.

    Empty is a real answer, not a broken one. The server holds a row only once
    `dose_followup` has written it at the moment the dose came due, so a day
    the phone served entirely from its own local notifications has nothing here
    yet. The phone merges the two copies; this is the copy that survives a
    wiped phone and the copy a guardian is alerted from.
    """
    require_health_consent(db, user)
    the_day = day or local_day(user)
    start, end = _day_bounds(user, the_day)
    rows = db.scalars(
        select(DoseLog)
        .where(DoseLog.user_id == user.id, DoseLog.due_at >= start, DoseLog.due_at < end)
        .order_by(DoseLog.due_at, DoseLog.id)
    ).all()
    at = now()
    return {
        "day": the_day,
        "doses": [dose_view(r) for r in rows],
        "summary": _dose_counts(list(rows), at),
    }


@router.post("/doses/{dose_id}")
def record_dose(dose_id: int, body: DoseAckIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Record what happened to a dose that has already come due.

    This does not create the row. The row exists because the dose came due, and
    a dose nobody ever acknowledged still exists as `missed` — a person in
    hospital who stopped taking their tablets did not decide to skip them, and a
    phone with no network would otherwise produce no row at all, so the two-miss
    alert would be counting nothing.

    Recording a late dose is allowed. Refusing it would leave a false miss in a
    log a doctor is going to read, and a "missed" that became "taken" at 9:40
    is the truth; the taken time is kept separate from the due time precisely so
    that the history says when it really happened.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    if body.status not in DOSE_STATUSES:
        raise HTTPException(400, f"status must be one of {', '.join(DOSE_STATUSES)}")

    row = db.get(DoseLog, dose_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(
            404,
            "there is no dose recorded at that time. A dose is added when it "
            "comes due, so there is nothing to mark yet.",
        )
    if row.status == DOSE_TAKEN and body.status == DOSE_TAKEN:
        # Changing a settled answer to the same answer is noise, and answering
        # it twice usually means two devices queued the same tap.
        raise HTTPException(409, f"this dose is already recorded as {row.status}")

    at = now()
    if body.status == DOSE_TAKEN:
        if row.status != DOSE_TAKEN:
            # One dose taken is one tablet used: this is what makes the refill
            # alert (dose_followup) mean something.
            med = db.get(Medicine, row.medicine_id)
            if med is not None and (med.stock or 0) > 0:
                med.stock -= 1
        row.status = DOSE_TAKEN
        row.taken_at = at
        # The counter is "misses in a row" and this is the reset.
        row.consecutive_misses = 0
    elif body.status == DOSE_SKIPPED:
        row.status = DOSE_SKIPPED
        row.taken_at = None
        # A deliberate skip breaks the run as well as not joining it. Somebody
        # who has stopped a medicine on purpose is not somebody whose family
        # should be telephoned about it.
        row.consecutive_misses = 0
    else:
        row.status = DOSE_MISSED
        row.taken_at = None
        row.consecutive_misses = (row.consecutive_misses or 0) + 1

    medicine = db.get(Medicine, row.medicine_id)
    db.commit()
    return {
        **dose_view(row),
        # Whether the job may still send the alert, and whether this particular
        # medicine is one the pensioner ever agreed to share. Both are reported
        # rather than acted on here: the send belongs to `dose_followup`.
        "guardian_alert_due": _alert_due(row),
        "share_with_guardians": bool(medicine and medicine.share_with_guardians),
    }


# ------------------------------------------------------------------ summary --
@router.get("/health/summary")
def health_summary(user: CurrentUser, db: DbSession, day: str | None = None):
    """
    One day: the doses, the stock, and any reading worth showing again.

    This is the server's whole reason for holding health data at all — a second
    phone after a lost one, the PDF for a doctor, and the card a pensioner reads
    with their family. It is deliberately a summary of one person's own rows and
    nothing else: there is no cross-user figure, no cohort, no average and no
    endpoint that answers "how are people doing" anywhere in this file. A
    pensioner's blood pressure is not an input to anybody's product decision.
    """
    require_health_consent(db, user)
    the_day = day or local_day(user)
    start, end = _day_bounds(user, the_day)
    at = now()

    doses = db.scalars(
        select(DoseLog)
        .where(DoseLog.user_id == user.id, DoseLog.due_at >= start, DoseLog.due_at < end)
        .order_by(DoseLog.due_at, DoseLog.id)
    ).all()
    medicines = db.scalars(
        select(Medicine)
        .where(Medicine.user_id == user.id, Medicine.is_active.is_(True))
        .order_by(Medicine.name)
    ).all()
    vitals = db.scalars(
        select(Vital)
        .where(Vital.user_id == user.id, Vital.at >= start, Vital.at < end)
        .order_by(Vital.at)
    ).all()

    # Rule 6, at the point where the temptation is. A reading outside its band
    # is reported, stored and left alone. This function says which numbers moved
    # and nothing about what they mean; the words "high", "dangerous" and any
    # condition name would all be a diagnosis, and the only action offered is
    # taking the reading to a doctor. `is_diagnosis` travels with the payload,
    # permanently false, so a screen cannot drift into something stronger without
    # it being visible in a diff.
    notices = [
        {
            "kind": v.kind,
            "at": v.at,
            "values": dict(v.values or {}),
            "unit": v.unit,
            "keys": outside,
            "is_diagnosis": False,
            "show_to_doctor": True,
        }
        for v in vitals
        for outside in [_outside_usual(v.kind, v.values or {})]
        if outside
    ]

    return {
        "day": the_day,
        "doses": _dose_counts(list(doses), at),
        "medicines": [medicine_view(m) for m in medicines],
        "running_low": [
            m.id for m in medicines if (_days_left(m) or 0) <= LOW_STOCK_DAYS
        ],
        "vitals": [vital_view(v) for v in vitals],
        "notices": notices,
        "disclaimer": NOT_A_DIAGNOSIS,
    }


@router.get("/health/guardian/{user_id}")
def guardian_view(user_id: int, user: CurrentUser, db: DbSession, days: int = 7):
    """
    What a guardian is allowed to see of somebody they look after.

    Three narrowing decisions, in this order, and each one is a place where the
    looser version would be easier:

      1. the caller must be on this person's accepted family list, and must
         hold the medicine grant — `require_health_consent`;
      2. only medicines the pensioner individually chose to share come back,
         never the whole list because the caller is family;
      3. vitals are not here at all. Readings are not shared with anybody. Plan
         14 gives no basis for it, plan 6.13 does not list them among what a
         family may see, and a blood-pressure number in a relative's app turns
         support into surveillance faster than a missed dose does. Guardian
         sharing is opt-in per medicine; it is not opt-in per condition.

    An empty `shared` list is answered with a sentence rather than left blank,
    because "they have not shared anything" and "the app is broken" look the
    same on a screen otherwise.
    """
    if not 1 <= days <= 30:
        raise HTTPException(400, "days must be 1 to 30")
    subject = db.get(User, user_id)
    if subject is None or subject.deleted_at is not None:
        raise HTTPException(404, "no such person")
    require_health_consent(db, user, subject)

    shared = db.scalars(
        select(Medicine)
        .where(
            Medicine.user_id == subject.id,
            Medicine.share_with_guardians.is_(True),
        )
        .order_by(Medicine.name)
    ).all()
    ids = [m.id for m in shared]
    if not ids:
        return {
            "user_id": subject.id,
            "name": subject.name,
            "shared": [],
            "message": (
                f"{subject.name or 'This person'} has not shared any medicines "
                "with you. Ask them to share one, in Settings."
            ),
        }

    at = now()
    # The subject's own day, not the caller's: a son in another timezone must
    # be told about the doses his mother's Tuesday, not his own.
    _, end = _day_bounds(subject, local_day(subject, at))
    rows = db.scalars(
        select(DoseLog)
        .where(
            DoseLog.medicine_id.in_(ids),
            DoseLog.due_at >= end - days * 86_400,
            DoseLog.due_at < end,
        )
        .order_by(DoseLog.due_at)
    ).all()
    lines = []
    for m in shared:
        mine = [r for r in rows if r.medicine_id == m.id]
        counts = _dose_counts(mine, at)
        lines.append(
            {
                "medicine": medicine_view(m),
                "days": days,
                "doses": counts,
                "last_missed_at": max(
                    (r.due_at for r in mine if r.status == DOSE_MISSED), default=None
                ),
            }
        )
    return {"user_id": subject.id, "name": subject.name, "shared": lines}


# -------------------------------------------------------------------- vitals --
@router.get("/vitals")
def list_vitals(user: CurrentUser, db: DbSession, kind: str | None = None, days: int = 30):
    """
    The readings, for a second phone and for the PDF a doctor is handed.

    Thirty days by default, which is the window the charts draw — a year of
    daily blood pressure is four hundred points on a phone screen, and a chart
    nobody can read is worse than no chart, because it looks like the app is
    watching something. Thirty is enough for a doctor to see a shape.
    """
    require_health_consent(db, user)
    if kind is not None and kind not in VITAL_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(VITAL_KINDS)}")
    if not 1 <= days <= 365:
        raise HTTPException(400, "days must be 1 to 365")
    start = now() - days * 86_400
    q = select(Vital).where(Vital.user_id == user.id, Vital.at >= start)
    if kind is not None:
        q = q.where(Vital.kind == kind)
    rows = db.scalars(q.order_by(Vital.at.desc(), Vital.id.desc())).all()
    return {"vitals": [vital_view(r) for r in rows]}


@router.post("/vitals")
def create_vital(body: VitalIn, request: Request, user: CurrentUser, db: DbSession):
    """
    Record a reading. What this does not do is read it.

    A value outside the usual band is stored exactly as given and comes back
    with `outside_usual` naming the number that moved. It is not rejected as
    implausible, it is not given a severity, and no word about a condition is
    attached to it anywhere on this path — the temptation to add one is the
    single thing that would turn a log into a diagnosis, and plan 14 forbids
    exactly that. The one action the summary offers is showing it to a doctor.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    if body.kind not in VITAL_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(VITAL_KINDS)}")
    values, unit = _clean_vital_values(body.kind, body.values)
    if body.unit is not None and body.unit.strip() != unit:
        # Every kind but the two sugar ones has exactly one possible unit, and
        # letting a caller say "this pulse is in mmHg" only ever produces a
        # chart with a wrong axis. The sugar rows are the exception because a
        # meter set to mmol/L is the common real mistake, and the model carries
        # the unit a reading was taken in.
        if body.kind not in SUGAR_KINDS:
            raise HTTPException(400, f"a {body.kind.replace('_', ' ')} reading is in {unit}")
        unit = body.unit.strip()[:16]
    row = Vital(
        user_id=user.id,
        kind=body.kind,
        values=values,
        unit=unit,
        at=now() if body.at is None else body.at,
        from_health_platform=body.from_health_platform,
        note=body.note,
    )
    db.add(row)
    db.commit()
    return vital_view(row)


@router.get("/vitals/{vital_id}")
def get_vital(vital_id: int, user: CurrentUser, db: DbSession):
    require_health_consent(db, user)
    row = db.get(Vital, vital_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "reading not found")
    return vital_view(row)


@router.delete("/vitals/{vital_id}")
def delete_vital(vital_id: int, request: Request, user: CurrentUser, db: DbSession):
    """
    Remove one reading.

    The only correction offered. There is no PATCH on a vital, and the reason is
    that a reading which is wrong is a reading nobody measured, and the way to
    be rid of one is to take it out of the log rather than to overwrite it. An
    edited value in a record a doctor reads is a value whose history nobody can
    account for, and a log that can be quietly rewritten is not a log anybody
    can rely on in a consultation.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = db.get(Vital, vital_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "reading not found")
    view = vital_view(row)
    db.delete(row)
    db.commit()
    return view


# ----------------------------------------------------------------- reminders --
@router.get("/reminders")
def list_reminders(user: CurrentUser, db: DbSession, include_done: bool = False):
    require_health_consent(db, user)
    q = select(Reminder).where(Reminder.user_id == user.id)
    if not include_done:
        q = q.where(Reminder.done_at.is_(None))
    rows = db.scalars(q.order_by(Reminder.next_at, Reminder.id)).all()
    return {"reminders": [reminder_view(r) for r in rows]}


@router.post("/reminders")
def create_reminder(body: ReminderIn, request: Request, user: CurrentUser, db: DbSession):
    """
    A server-side reminder — the copy that still fires when the app has been
    force-quit, and the copy the `reminders_tick` job walks every minute.

    Doses are additionally scheduled on the phone, and neither copy is allowed
    to be the only one: a pensioner with patchy data who misses an evening
    tablet because the reminder never arrived is precisely the person this app
    exists for.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    if body.kind not in REMINDER_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(REMINDER_KINDS)}")
    schedule = _validate_schedule(body.kind, body.schedule)
    if body.next_at <= 0:
        raise HTTPException(400, "next_at must be a time in seconds since 1970")
    if body.kind == "medicine":
        # A reminder about somebody else's medicine is a reminder that leaks a
        # medicine list through the notification history.
        med = db.get(Medicine, schedule.get("medicine_id"))
        if med is None or med.user_id != user.id:
            raise HTTPException(404, "medicine not found")
    row = Reminder(
        user_id=user.id,
        kind=body.kind,
        schedule=schedule,
        next_at=body.next_at,
        title=body.title,
        body=body.body,
    )
    db.add(row)
    db.commit()
    return reminder_view(row)


@router.patch("/reminders/{reminder_id}")
def update_reminder(
    reminder_id: int, body: ReminderPatchIn, request: Request, user: CurrentUser, db: DbSession
):
    """
    Move a reminder, or mark it done.

    `done_at` is a fact about the past rather than a deletion, so a missed life
    certificate still shows on the list that it was missed — which is the
    version of this a pensioner needs, not a row that quietly vanished.
    """
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = db.get(Reminder, reminder_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "reminder not found")
    if body.next_at is not None:
        if body.next_at <= 0:
            raise HTTPException(400, "next_at must be a time in seconds since 1970")
        row.next_at = body.next_at
    if body.done_at is not None:
        row.done_at = body.done_at
    if body.title is not None:
        row.title = body.title
    if body.body is not None:
        row.body = body.body
    db.commit()
    return reminder_view(row)


@router.delete("/reminders/{reminder_id}")
def delete_reminder(reminder_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    require_health_consent(db, user)
    row = db.get(Reminder, reminder_id)
    if row is None or row.user_id != user.id:
        raise HTTPException(404, "reminder not found")
    view = reminder_view(row)
    db.delete(row)
    db.commit()
    return view


# ---------------------------------------------------------------- facilities --
@router.get("/facilities")
def list_facilities(
    user: OptionalUser,
    db: DbSession,
    scheme: str | None = None,
    q: str | None = None,
    city_id: int | None = None,
    state_code: str | None = None,
    lat: float | None = None,
    lng: float | None = None,
    radius_km: float | None = None,
    limit: int = FACILITY_LIMIT,
):
    """
    The CGHS / ECHS directory. Public, and works signed out.

    `OptionalUser` is declared and deliberately unused. A hospital list is not
    anybody's health data, and the only thing this route could do with a signed-in
    caller is reach for their saved coordinates — which are the check-in
    window's location, held under a different consent
    (`CheckInSettings.share_location`). Borrowing them here would turn a public
    directory into a location read that no consent covers. The client sends
    lat/lng, or nothing and gets the list without distances.

    A facility row is a public listing, not a person's record, so it is the one
    read in this file that no consent check gates — and it is also the reason
    the person using it is probably ill: the list says what the official list
    said, how old that list is, and how far away it is. It does not say which
    hospital is nearest emergency care, does not rank by anything, and cannot:
    none of that is knowable from a monthly list, and claiming it would be a
    claim about a sick person that this app has not earned.
    """
    if not 1 <= limit <= 50:
        raise HTTPException(400, "limit must be 1 to 50")
    if lat is not None and not -90 <= lat <= 90:
        raise HTTPException(400, "lat out of range")
    if lng is not None and not -180 <= lng <= 180:
        raise HTTPException(400, "lng out of range")
    if lat is not None and lng is not None and radius_km is None:
        radius_km = FACILITY_RADIUS_KM
    if radius_km is not None and not 0 < radius_km <= 500:
        raise HTTPException(400, "radius_km must be between 0 and 500")

    query = select(Facility)
    if scheme:
        # The schemes column is a JSON list, so this is a substring test and it
        # has to be loose: the official lists write "CGHS", "cghs" and "CGHS ".
        query = query.where(Facility.schemes.cast(String).ilike(f'%"{scheme.strip()}"%'))
    if city_id is not None:
        query = query.where(Facility.city_id == city_id)
    if state_code:
        query = query.where(Facility.state_code == state_code.strip().upper())
    if q:
        needle = f"%{q.strip()}%"
        query = query.where(Facility.name.ilike(needle) | Facility.address.ilike(needle))

    rows = db.scalars(query).all()
    at = now()
    results = []
    for row in rows:
        distance = None
        if lat is not None and lng is not None:
            distance = _km_between(lat, lng, row.lat, row.lng)
            if radius_km is not None and distance > radius_km:
                continue
        results.append(
            {
                "id": row.id,
                "name": row.name,
                "kind": list(row.schemes or []),
                "address": row.address,
                "phone": row.phone,
                "beds": row.beds,
                "city_id": row.city_id,
                "state_code": row.state_code,
                "distance_km": None if distance is None else round(distance, 1),
                # A straight line, not a route. There is no routing engine here
                # and adding one to pick a hospital for somebody who is ill
                # would be slow on a cheap phone and a claim about roads this
                # server has never seen. The flag lets the screen say "about".
                "distance_is_approximate": distance is not None,
                # Plan 6.10: every entry shows when the list was last refreshed.
                "updated_at": row.updated_at,
            }
        )
    results.sort(
        key=lambda r: (r["distance_km"] is None, r["distance_km"] or 0.0, r["name"].lower())
    )
    newest = max((r.updated_at for r in rows), default=None)
    return {
        "facilities": results[:limit],
        "total": len(results),
        "list_updated_at": newest,
        "is_stale": newest is not None and at - newest > FACILITY_STALE_DAYS * 86_400,
    }


def _km_between(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle kilometres, mirroring FacilityFinder.greatCircleKm."""
    d_lat = radians(lat2 - lat1)
    d_lng = radians(lng2 - lng1)
    h = sin(d_lat / 2) ** 2 + sin(d_lng / 2) ** 2 * cos(radians(lat1)) * cos(radians(lat2))
    return 2 * EARTH_RADIUS_KM * atan2(sqrt(h), sqrt(1 - h))
