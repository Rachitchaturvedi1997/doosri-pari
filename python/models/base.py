# =============================================================================
# Doosri Pari — schema foundations.
#
# Conventions carried over from the Moholla backend so the two read alike:
#   * SQLAlchemy 2.0 ORM (Mapped[] / mapped_column)
#   * timestamps as UNIX epoch floats, which is what the escalation job and the
#     IVR webhook both compare against
#   * every change needs a matching Alembic migration
#
# The schema is split across files by plan section rather than kept in one, but
# everything is re-exported from `python.models` so no caller ever has to know
# which file a table lives in.
# =============================================================================
from __future__ import annotations

import time

from sqlalchemy import JSON
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import DeclarativeBase

# JSONB on Postgres, plain JSON on SQLite, so tests run without a server.
JsonType = JSON().with_variant(JSONB(), "postgresql")


def now() -> float:
    """Epoch seconds. The single clock for the whole backend.

    Everything that needs the current time calls this rather than
    `time.time()` directly, so a test can pin the clock in one place.
    """
    return time.time()


class Base(DeclarativeBase):
    pass


# --------------------------------------------------------------- identity --

# Verification tiers, plan section 6.1. Check-in needs to know whether somebody
# is allowed to host a voice room or vouch for others.
TIERS = ("T0", "T1", "T2", "T3")
TIER_RANK = {t: i for i, t in enumerate(TIERS)}

# Service types, plan section 10. These filter every Sahayak answer (6.4):
# an arrears calculation for a railway pensioner is not the CCS rule.
SERVICE_TYPES = (
    "central_civil",
    "defence",
    "state_civil",
    "psu",
    "bank",
    "railway",
    "police",
    "teaching",
    "other",
)

LANGUAGES = ("en", "hi", "mr", "kn", "ta", "te", "bn", "gu", "ml", "pa")

# ---------------------------------------------------------------- check-in --

# Check-in stages, mirroring shared/feature-checkin CheckInStage. The strings are
# the wire format the apps see, so they must not change casually.
STAGE_WAITING = "Waiting"
STAGE_REMINDED = "Reminded"
STAGE_CALLED = "Called"
STAGE_GUARDIANS_ALERTED = "GuardiansAlerted"
STAGE_HELPERS_ALERTED = "HelpersAlerted"
STAGE_CHECKED_IN = "CheckedIn"
STAGE_RESOLVED = "Resolved"

TERMINAL_STAGES = (STAGE_CHECKED_IN, STAGE_RESOLVED)

# How a check-in was recorded. Passive signals only ever produce CheckedIn
# (plan section 6.3).
METHOD_TAPPED = "tapped"
METHOD_PASSIVE = "passive"
METHOD_IVR = "ivr"
METHOD_IN_PERSON = "in_person"
CHECKIN_METHODS = (METHOD_TAPPED, METHOD_PASSIVE, METHOD_IVR, METHOD_IN_PERSON)

# Plan section 21 risk 2: any one channel can fail, so each is tracked apart.
DELIVERY_CHANNELS = ("push", "sms", "whatsapp", "ivr_call")

# ----------------------------------------------------------------- circles --

CIRCLE_TYPES = ("batch", "department", "city", "interest", "voice_only", "association", "private")
JOIN_RULES = ("open", "verified", "document_verified", "same_department", "approval", "invite")
CIRCLE_ROLES = ("member", "moderator", "owner")
POST_KINDS = ("text", "image", "voice", "poll", "news")
REPORT_TARGETS = ("post", "comment", "user", "listing", "voice_room")
REPORT_REASONS = ("abuse", "hate_speech", "scam", "spam", "misinformation", "personal_info")
REPORT_STATUSES = ("open", "actioned", "dismissed")
RSVP_STATUSES = ("going", "maybe", "not_going")

# Reports that a human must see immediately rather than sit in a queue. The
# remaining reasons are real but not urgent, and a moderator who cannot get to
# them stops getting to any of them.
URGENT_REPORT_REASONS = ("abuse", "hate_speech", "scam", "personal_info")

# ------------------------------------------------------------------- seva --

LISTING_TYPES = ("help", "skill", "mentor", "space", "work")
LISTING_MODES = ("free", "barter", "paid")
LISTING_STATUSES = ("open", "filled", "withdrawn", "removed")
BOOKING_STATUSES = ("requested", "confirmed", "completed", "cancelled", "disputed")

# Plan section 12, `payouts`: money is released two days after completion so a
# payer who was let down has a window in which to say so.
PAYOUT_HOLD_HOURS = 48

# ---------------------------------------------------------------- sahayak --

DOC_STATUSES = ("draft", "current", "superseded")
SAHAYAK_INTENTS = (
    "arrears",
    "dearness_allowance",
    "commutation",
    "pension_slip",
    "health_cover",
    "family_pension",
    "tax",
    "nps",
    "eligibility",
    "unknown",
)
FEEDBACK_CODES = ("helpful", "not_helpful", "ask_volunteer")
TICKET_STATUSES = ("open", "assigned", "answered", "closed")

# Mirrors SahayakAnswer.MIN_CONFIDENCE in core-model. An answer below this is
# never published, and the sahayak_index job refuses to promote a corpus whose
# scores do not hold.
SAHAYAK_MIN_CONFIDENCE = 0.55

# ----------------------------------------------------------------- health --

DOSE_STATUSES = ("taken", "missed", "skipped")
REMINDER_KINDS = (
    "medicine",
    "check_in",
    "life_certificate",
    "appointment",
    "circle_event",
    "yoga_session",
    "digest",
)

# Plan section 6.15: the app may interrupt for these and for nothing else. A
# circle post that rings the phone is how an app gets its notifications turned
# off, and once off the medicine reminder goes with them.
URGENT_REMINDER_KINDS = ("medicine", "check_in", "life_certificate", "appointment")

# Plan section 12, `dose_followup`: guardians are told after this many misses.
DOSE_MISSES_BEFORE_ALERT = 2

VITAL_KINDS = (
    "blood_pressure",
    "pulse",
    "blood_sugar_fasting",
    "blood_sugar_random",
    "weight",
    "temperature",
)

# ----------------------------------------------------------------- kendra --

PROGRAMME_KINDS = ("chair_yoga", "mobility", "pranayama", "safety_check", "meditation")
INSTRUCTOR_STYLES = ("dadi", "dada")
# Plan section 7.2.1 names Hindu, Muslim, Sikh, Christian, Buddhist, Jain and
# Jewish. Two things were missing here and both were the same mistake, so it is
# worth naming the mistake rather than the fix: this tuple was written from the
# Hindu sub-traditions the content happens to be filed under, and then read as
# though it were the list of paths a person may say they follow. The two are not
# the same list. A Muslim, Christian or Jewish pensioner was refused outright,
# and a Hindu who answered "Hindu" — the word the plan itself uses, and the word
# most people would say — was refused for not having picked a sub-tradition.
#
# "several" is deliberately NOT a value. §7.2.1 says a person may follow several
# paths, and that is expressed by several entries in the list, which is why
# KendraProfile.paths is a list and not a single choice. A sentinel for "more
# than one" would destroy the information it is standing in for.
SPIRITUAL_PATHS = (
    "none",
    "general",
    "hindu",
    "shaiva",
    "vaishnava",
    "shakta",
    "muslim",
    "sikh",
    "christian",
    "buddhist",
    "jain",
    "jewish",
)
FESTIVAL_KINDS = ("festival", "vrat", "ekadashi", "fasting_day")

# The severity ladder behind KendraProfile's `mostRestrictive`. Ordered rather
# than derived so a new flag cannot accidentally become the most permissive.
SAFETY_FLAG_SEVERITY = {
    "none": 0,
    "knee_or_back_issue": 1,
    "limited_mobility": 1,
    "pregnancy": 1,
    # Plan 7.1.1's health check, one flag per question. Appended, never renamed:
    # the older names above stay valid so a profile saved before this list grew
    # still loads.
    "back_pain": 1,
    "hernia": 1,
    "high_bp": 2,
    "knee_replacement": 2,
    "hip_replacement": 2,
    "vertigo": 2,
    "osteoporosis": 2,
    "glaucoma": 2,
    "recent_surgery": 2,
    "heart_condition": 2,
    "fall_risk": 3,
}

# A flag that can be worked around with a modified movement. Anything not in
# here is a hard exclusion, and the UI must say why rather than silently
# shortening somebody's session.
MODIFIABLE_SAFETY_FLAGS = (
    "knee_or_back_issue", "limited_mobility", "pregnancy",
    "back_pain", "hernia", "knee_replacement", "hip_replacement", "vertigo", "osteoporosis",
)

SAFETY_FLAGS = tuple(SAFETY_FLAG_SEVERITY)

# ---------------------------------------------------------------- billing --

BILLING_STORES = ("play", "app_store")
SUBSCRIPTION_STATUSES = ("none", "active", "grace_period", "cancelled", "expired", "refunded")

# Plan section 17: the family plan covers up to six pensioners on one payment.
MAX_PLAN_SEATS = 6

# -------------------------------------------------------------- retention --

# Plan section 14.
RETENTION_QUESTION_DAYS = 180
RETENTION_AUDIT_DAYS = 730
# Verification documents are deleted within 7 days. The record that a check
# happened survives; the document does not.
RETENTION_VERIFICATION_DOCUMENT_DAYS = 7
# Account deletion is honoured after this grace period (plan section 12).
ACCOUNT_DELETION_GRACE_DAYS = 30
