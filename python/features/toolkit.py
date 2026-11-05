# =============================================================================
# Feature · Pension toolkit, the server's part — plan section 6.5.
#
#   GET  /toolkit/commutation-table         the official factor table, versioned
#   GET  /toolkit/reminders/plan            which standard reminders apply to me
#   POST /toolkit/reminders/bootstrap       create them, once each
#
# The CALCULATORS are on the phone (shared/feature-toolkit: pure Kotlin, unit
# tested against rule-based expected values), because a pensioner's basic pension
# is theirs and never leaves the device (plan 10, 14). This module serves the two
# things that ARE shared facts - the rate tables (features/sahayak.py /dr-rates
# and the commutation table below) - and creates the reminders that keep a
# pensioner from missing a date.
#
# The dates are a person's own, so they are asked for and used, not stored: a
# birth date is turned into "the day two months before the 80th birthday" and only
# that reminder survives (plan 14: data minimisation). Every reminder names what
# it is for and is off unless the person asked for it.
#
# Plan 6.5's reminder table, implemented:
#   life certificate      from 1 Nov (1 Oct for 80+), on days 1, 7, 15, 25 until done
#   income tax filing     1 June, 1 July, 20 July (opt-in)
#   CGHS / ECHS renewal   30 days before the date the person enters
#   commutation restored  3 months before the 15-year date
#   additional pension    2 months before each band (80, 85, 90, 95, 100)
# =============================================================================
from __future__ import annotations

import calendar
import logging
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select

from python.deps import CurrentUser, DbSession
from python.models import CommutationTable, Reminder, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.toolkit")
router = APIRouter(tags=["toolkit"])

WHICH = ("life_certificate", "tax", "cghs_renewal", "commutation_restoration", "additional_pension")
ADDITIONAL_BANDS = (80, 85, 90, 95, 100)
# Additional pension for older pensioners is a Central Government provision
# (plan 6.5); others are not shown it, rather than shown it wrongly.
ADDITIONAL_SERVICES = ("central_civil", "defence")
REMINDER_HOUR = 9


class BootstrapIn(BaseModel):
    which: list[str] = Field(min_length=1, max_length=len(WHICH))
    birth_date: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    cghs_renewal_on: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    commutation_on: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")


# ------------------------------------------------------------------ helpers --
def _tz(user) -> ZoneInfo:  # noqa: ANN001
    try:
        return ZoneInfo(user.time_zone or "UTC")
    except Exception:
        return ZoneInfo("UTC")


def _at(user, d: date) -> float:
    return datetime(d.year, d.month, d.day, REMINDER_HOUR, 0, tzinfo=_tz(user)).timestamp()


def _add_months(d: date, months: int) -> date:
    m = d.month - 1 + months
    y, m = d.year + m // 12, m % 12 + 1
    return date(y, m, min(d.day, calendar.monthrange(y, m)[1]))


def _parse(v: str | None) -> date | None:
    try:
        return date.fromisoformat(v) if v else None
    except ValueError:
        raise HTTPException(400, f"{v!r} is not a date like 2026-11-30") from None


def age_on(birth: date, on: date) -> int:
    return on.year - birth.year - ((on.month, on.day) < (birth.month, birth.day))


def life_certificate_dates(today: date, birth: date | None) -> list[date]:
    """Days 1, 7, 15, 25 from the season start; the 80+ season starts on 1 October."""
    aged_80 = birth is not None and age_on(birth, date(today.year, 11, 1)) >= 80
    start_month = 10 if aged_80 else 11
    season_year = today.year if today.month >= start_month or today.month <= 1 else today.year
    out = []
    for offset in (0, 6, 14, 24):
        out.append(date(season_year, start_month, 1) + timedelta(days=offset))
    upcoming = [d for d in out if d >= today]
    if not upcoming:                       # this season is over; plan next year's
        return [d.replace(year=d.year + 1) for d in out]
    return upcoming


def tax_dates(today: date) -> list[date]:
    y = today.year
    cand = [date(y, 6, 1), date(y, 7, 1), date(y, 7, 20)]
    up = [d for d in cand if d >= today]
    return up or [d.replace(year=y + 1) for d in cand]


def additional_pension_dates(today: date, birth: date) -> list[tuple[int, date]]:
    """(band, reminder date) for bands not yet reached: two months before each birthday."""
    out = []
    for band in ADDITIONAL_BANDS:
        birthday = date(birth.year + band, birth.month, min(birth.day, calendar.monthrange(birth.year + band, birth.month)[1]))
        remind = _add_months(birthday, -2)
        if remind >= today:
            out.append((band, remind))
    return out


def plan_for(user, today: date, body: BootstrapIn | None = None) -> list[dict]:
    """What each requested reminder would be. Pure: no database, so it is checkable by hand."""
    birth = _parse(body.birth_date) if body else None
    plan: list[dict] = []
    want = set(body.which) if body else set(WHICH)

    if "life_certificate" in want:
        dates = life_certificate_dates(today, birth)
        plan.append({
            "subkind": "life_certificate", "kind": "life_certificate",
            "title": "Life certificate (Jeevan Pramaan)",
            "body": "Submit your life certificate so your pension is not stopped. Doosri Pari can guide you step by step.",
            "when": dates, "key": f"lc-{dates[0].year}",
            "note": "Reminders on days 1, 7, 15 and 25 of the season until you mark it done.",
        })
    if "tax" in want:
        dates = tax_dates(today)
        plan.append({
            "subkind": "tax", "kind": "appointment", "title": "Income tax return", "body":
            "It is time to file your income tax return. Sahayak can answer basic questions about pensioners' returns.",
            "when": dates, "key": f"tax-{dates[0].year}", "note": "1 June, 1 July and 20 July.",
        })
    if "cghs_renewal" in want:
        on = _parse(body.cghs_renewal_on) if body else None
        if on is None:
            raise HTTPException(400, "cghs_renewal_on is needed for the CGHS / ECHS reminder")
        remind = on - timedelta(days=30)
        if remind < today:
            remind = today
        plan.append({
            "subkind": "cghs_renewal", "kind": "appointment", "title": "CGHS / ECHS card renewal",
            "body": f"Your card is due for renewal on {on.strftime('%d %B %Y')}.",
            "when": [remind], "key": f"cghs-{on.isoformat()}", "note": "30 days before the date you gave.",
        })
    if "commutation_restoration" in want:
        on = _parse(body.commutation_on) if body else None
        if on is None:
            raise HTTPException(400, "commutation_on is needed for the restoration reminder")
        restored = _add_months(on, 15 * 12)
        remind = _add_months(restored, -3)
        if remind < today:
            raise HTTPException(400, "that date has already passed; check your pension slip for the restoration")
        plan.append({
            "subkind": "commutation_restoration", "kind": "appointment", "title": "Commuted pension restoration",
            "body": f"Your commuted pension is due to be restored around {restored.strftime('%B %Y')}. Check your slip after that month.",
            "when": [remind], "key": f"comm-{on.isoformat()}", "note": "3 months before the 15-year date.",
        })
    if "additional_pension" in want:
        if user.service_type not in ADDITIONAL_SERVICES:
            raise HTTPException(400, "the additional pension for older pensioners is a Central Government provision")
        if birth is None:
            raise HTTPException(400, "birth_date is needed for the additional pension reminder")
        for band, remind in additional_pension_dates(today, birth):
            plan.append({
                "subkind": "additional_pension", "kind": "appointment",
                "title": f"Additional pension at {band}",
                "body": f"You turn {band} in two months. Ask your pension office about the additional pension.",
                "when": [remind], "key": f"addl-{band}", "note": f"2 months before your {band}th birthday.",
            })
    return plan


# ---------------------------------------------------------------- endpoints --
@router.get("/toolkit/commutation-table")
def commutation_table(user: CurrentUser, db: DbSession, service_type: str | None = None):
    """
    The factor table in force. `age_next_birthday` is stored in the
    `service_years` column (models/sahayak.py names it for an older draft of the
    rule); the newest `effective_from` per age wins, and a superseded row stays so
    an old figure still reproduces.
    """
    st = service_type or user.service_type or "central_civil"
    rows = db.scalars(
        select(CommutationTable).where(CommutationTable.service_type == st, CommutationTable.effective_from <= now())
        .order_by(CommutationTable.effective_from.desc())
    ).all()
    latest: dict[float, CommutationTable] = {}
    for r in rows:
        latest.setdefault(r.service_years, r)
    out = [{"age_next_birthday": int(k), "factor": v.factor, "effective_from": v.effective_from,
            "source_doc_id": v.source_doc_id} for k, v in sorted(latest.items())]
    return {
        "service_type": st, "table": out,
        "note": "Estimate only. Confirm with your Pay and Accounts Office before you decide.",
    }


@router.get("/toolkit/reminders/plan")
def reminders_plan(
    user: CurrentUser, birth_date: str | None = None, cghs_renewal_on: str | None = None,
    commutation_on: str | None = None,
):
    today = datetime.fromtimestamp(now(), _tz(user)).date()
    which = [w for w in WHICH if w != "additional_pension" or user.service_type in ADDITIONAL_SERVICES]
    out = []
    for w in which:
        try:
            body = BootstrapIn(which=[w], birth_date=birth_date, cghs_renewal_on=cghs_renewal_on, commutation_on=commutation_on)
            for item in plan_for(user, today, body):
                out.append({"which": w, "title": item["title"], "on": [d.isoformat() for d in item["when"]], "note": item["note"]})
        except HTTPException as exc:
            out.append({"which": w, "needs": exc.detail})
    return {"reminders": out}


@router.post("/toolkit/reminders/bootstrap")
def bootstrap(body: BootstrapIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    for w in body.which:
        if w not in WHICH:
            raise HTTPException(400, f"which must be drawn from {', '.join(WHICH)}")
    today = datetime.fromtimestamp(now(), _tz(user)).date()
    plan = plan_for(user, today, body)

    existing = {
        (r.schedule or {}).get("key")
        for r in db.scalars(select(Reminder).where(Reminder.user_id == user.id, Reminder.done_at.is_(None))).all()
    }
    created, skipped = [], []
    for item in plan:
        if item["key"] in existing:
            skipped.append(item["key"])
            continue
        times = [_at(user, d) for d in item["when"]]
        r = Reminder(
            user_id=user.id, kind=item["kind"], next_at=times[0], title=item["title"], body=item["body"],
            schedule={"at": times[0], "due_on": item["when"][-1].isoformat(), "subkind": item["subkind"],
                      "key": item["key"], "remaining": times[1:]},
        )
        db.add(r)
        created.append(item["key"])
    db.commit()
    return {"created": created, "already_set": skipped}
