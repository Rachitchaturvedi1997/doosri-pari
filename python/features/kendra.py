# =============================================================================
# Feature · Yog Kendra and Adhyatm Kendra — plan section 7.
#
#   GET  /kendra/programmes   what this person may practise today, and what was
#                             held back from it, and why
#   GET  /kendra/profile      the safety flags, the doctor's note, the instructor
#   PUT  /kendra/profile
#   POST /kendra/sessions     a session they actually finished
#   POST /kendra/mobility     the monthly Senior Fitness Test self-check
#   POST /kendra/pairs        yoga saathi — a partner, matched rather than chosen
#   GET  /spiritual/state     their own practice record
#   PUT  /spiritual/state
#   GET  /spiritual/festivals festival and vrat dates, by region
#   POST /spiritual/ask      a question about practice
#
# EXERCISE IS SAFETY-CRITICAL. PRACTICE IS NOT.
#
# `kendra_profiles` and everything below it in python/models/kendra.py is the
# exercise half. It carries injuries, joint problems, balance, blood pressure
# and a doctor's note, and every route below the line in that file has to decide
# whether a movement is safe for a particular body before that movement is
# offered. The failure this design exists to prevent is a pensioner with a bad
# knee being handed a programme that squats. It is a fall, or a hip, and it is
# not recoverable and it is not the app's to explain afterwards.
#
# `spiritual_state` is the practice half. It carries no safety flags, no doctor
# note, no mobility gate, and none is wanted here. Nobody falls over a japa
# count, and applying the exercise half's language to it — asking for a doctor,
# gating on a mobility score, filtering on a condition — would treat a person's
# faith as though it were a medical history. It is their record of what they
# already do, and this module's only obligation towards it is to leave it alone.
#
# The two halves share a module because the plan puts them in one app under one
# tab (7). That is the whole reason this banner exists: the temptation is to
# treat the lower half of the model file as more of the same, and the next
# person to open this file will have the exercise routes above these in front of
# them and will reasonably assume the rules carry down. They do not.
# =============================================================================
from __future__ import annotations

import logging
import re
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession, OptionalUser
from python.models import (
    INSTRUCTOR_STYLES,
    LANGUAGES,
    MODIFIABLE_SAFETY_FLAGS,
    PROGRAMME_KINDS,
    SAFETY_FLAGS,
    SAFETY_FLAG_SEVERITY,
    SPIRITUAL_PATHS,
    Festival,
    KendraProfile,
    KendraProgramme,
    KendraSession,
    MobilityCheck,
    Pose,
    SpiritualContent,
    SpiritualState,
    User,
    YogaPair,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.kendra")
router = APIRouter(tags=["kendra"])


# ------------------------------------------------------------- safety text --
# Plan section 7.1.3, quoted rather than paraphrased, because these two lines
# are the plan's own safety instruction and rewording them would be inventing
# a different one. The first is on the screen before a person's first session;
# the second is spoken at the start of every session without exception.
DOCTOR_LINE = "Check with your doctor before starting a new exercise programme."
STOP_IF_PAIN = "Stop if you feel pain or dizziness."

# The plain-language partner of every withheld pose. The client renders this in
# the user's language; the server does not, and shipping an English sentence
# into a Marathi session would be worse than shipping the code and letting the
# app say it in the language the person reads. That is why each reason also
# carries a `code` the client can localise. Wording follows the flag table in
# plan section 7.1, "Answers set flags that filter poses and breathing".
FLAG_REASON = {
    "knee_or_back_issue": (
        "deep knee bends and loaded forward bends are not offered with a knee "
        "or back problem"
    ),
    "limited_mobility": (
        "this movement is only offered in its supported version with limited "
        "mobility"
    ),
    "pregnancy": "this movement is not offered in this form during pregnancy",
    "recent_surgery": (
        "no new exercise programme is offered after recent surgery, until the "
        "doctor has cleared it"
    ),
    "heart_condition": (
        "this movement is not offered with a heart condition, because of what "
        "it asks the heart to do"
    ),
    "fall_risk": (
        "standing unsupported is not offered to somebody at risk of falling, "
        "and a fall is the injury this whole section is built around"
    ),
    "high_bp": (
        "no head-below-heart poses, strong forward folds, kapalbhati or breath "
        "holding are offered with high blood pressure"
    ),
    "knee_replacement": "no deep knee bends, kneeling or squats are offered after a knee replacement",
    "hip_replacement": (
        "no crossing the legs past the midline and no deep hip bends are offered "
        "after a hip replacement"
    ),
    "vertigo": (
        "no quick head movements are offered with vertigo, and standing is only "
        "offered holding a chair or wall"
    ),
    "osteoporosis": "no loaded forward bends or strong twists are offered with osteoporosis",
    "glaucoma": "no poses with the head below the heart are offered with glaucoma",
    "hernia": "no strong abdominal pressure or deep forward bends are offered with a hernia",
    "back_pain": "deep forward bends and strong twists are offered only in their gentle form with back pain",
}

# Plan 7.1.1's health check: nine questions, each of which sets one flag when
# the answer is yes. Served from here so the wording is reviewed once, in one
# place, and translated with the rest of the app; the client renders `text` and
# never decides what a yes means.
HEALTH_CHECK = [
    {"id": "high_bp", "flag": "high_bp", "text": "Do you have high blood pressure?"},
    {"id": "heart", "flag": "heart_condition", "text": "Do you have a heart condition?"},
    {"id": "knee", "flag": "knee_replacement", "text": "Have you had a knee replacement?"},
    {"id": "hip", "flag": "hip_replacement", "text": "Have you had a hip replacement?"},
    {"id": "vertigo", "flag": "vertigo", "text": "Do you get vertigo or dizziness?"},
    {"id": "osteo", "flag": "osteoporosis", "text": "Do you have osteoporosis (weak bones)?"},
    {"id": "surgery", "flag": "recent_surgery", "text": "Have you had surgery in the last few months?"},
    {"id": "glaucoma", "flag": "glaucoma", "text": "Do you have glaucoma?"},
    {"id": "hernia", "flag": "hernia", "text": "Do you have a hernia?"},
    {"id": "back", "flag": "back_pain", "text": "Do you have back pain?"},
    {"id": "falls", "flag": "fall_risk", "text": "Have you had a fall, or feel unsteady on your feet?"},
]

# A mobility check is a self-assessment and is reported as one. It is not a
# screening test, it is not administered by anybody, and the number is not read
# as a diagnosis of anything. It decides one thing only: what we suggest next.
NOT_A_MEDICAL_TEST = (
    "This is what you told us your body could do just now. Nobody here has "
    "examined you and this is not a diagnosis. It is used to decide what to "
    "suggest next, and nothing else."
)

# One constant, used in one place. A Sahayak answer moves money; a line from
# this route moves a person, and somebody who is unwell may take a fast or
# stop taking their tablets on the strength of it. The harm lands on them and
# not on us, which is exactly the argument for telling a pensioner that a
# financial answer is general information — so the same sentence is used here.
# It is not a disclaimer bolted on to be safe of blame; it is the reason the
# route is built to quote and never to advise.
PRACTICE_DISCLAIMER = (
    "This is general information, not medical or spiritual advice, and it "
    "promises nothing. What is right for your practice is between you and the "
    "people who know you. This is here to read, not to decide for you."
)


# ---------------------------------------------------------------- constants --
# The vocabulary a person may use to say what they follow is SPIRITUAL_PATHS and
# nothing else, so validation reads it directly. It used to be the union of two
# lists — the model's, and a local tuple naming the three faiths the model had
# left out — and the model's was the one that was wrong. The fix landed in
# python/models/base.py; this is what the local tuple was always asking for.
VALID_PATHS = tuple(SPIRITUAL_PATHS)

# Tokens, kept to the ones that are noise in both languages the app ships. The
# pattern keeps Devanagari so a question typed in Hindi or Marathi matches
# content typed in Hindi or Marathi — matching on Latin only would mean Ask
# simply never finding anything for half the people who use it.
_QUESTION_WORDS = re.compile(r"[\wऀ-ॿ]+", re.UNICODE)
_STOPWORDS = frozenset(
    {
        "the", "and", "for", "with", "that", "this", "from", "have", "has",
        "was", "were", "what", "which", "who", "how", "why", "when", "where",
        "you", "your", "are", "can", "does", "do", "not", "but", "about",
        "hai", "kya", "kar", "karo", "kaise", "kaisa", "mera", "meri", "mein",
        "ke", "ki", "ko", "se", "par", "aur", "bhi", "toh", "tha", "thi",
        "hota", "hoti", "karna", "karte", "chalana", "chalo", "ji", "जी",
    }
)


# ------------------------------------------------------------------ schemas --
class ProfileIn(BaseModel):
    """The health check from plan section 7.1.1, and who teaches the session."""

    # The answers that set the flags. Every value here is a safety decision, so
    # an unrecognised one is a 400 rather than a silently stored string that
    # no screen would match and that would therefore gate nothing.
    flags: list[str] = Field(default_factory=list, max_length=len(SAFETY_FLAGS))
    # Plan section 7.1.4: whatever the doctor said, in their words or their
    # voice. It is shown on the programme screen. It does NOT clear a flag —
    # see the route.
    doctor_note: str | None = Field(default=None, max_length=2000)
    instructor: str | None = Field(default=None, max_length=8)
    mirror: bool = True
    voice_langs: list[str] = Field(default_factory=list, max_length=len(LANGUAGES))


class SessionIn(BaseModel):
    programme_id: int
    minutes: int = Field(ge=0, le=180)
    completed: bool = False
    mobility_check_id: int | None = None
    # Optional because the app is meant to work offline (plan 3.7) and a
    # session finished on a patch with no signal is still a session. Bounded
    # below in the route: an unbounded date would let a caller write any week
    # of minutes they liked, and "you practised 5 days this week" is shown to
    # families as well as to the person.
    started_at: float | None = None


class MobilityIn(BaseModel):
    # The Senior Fitness Test, plan section 7.1: how many times you can stand
    # up from a chair in 30 seconds, and how long you can hold one leg with
    # support. Both are counts of something the person just did, so both are
    # bounded by what a person can physically do rather than by what looks
    # plausible — zero is a legitimate answer for both.
    chair_stands_30s: int = Field(ge=0, le=50)
    single_leg_secs: int = Field(ge=0, le=120)


class PairIn(BaseModel):
    # The plan's yoga saathi is two people practising the same thing (7.1
    # "Together"), so the programme is the unit of the match and not optional.
    # Pairing with no programme would be a friendship request, and this is not
    # that: it is an arrangement between two people who expect to turn up.
    partner_user_id: int
    programme_id: int


class JapaIn(BaseModel):
    mala_size: int = Field(ge=1, le=1000)
    count: int = Field(ge=0, le=1_000_000)
    goal: int = Field(ge=1, le=10_000_000)


class SankalpIn(BaseModel):
    # A long sankalp, e.g. 1,25,000 chants, with a finish estimate the app
    # works out. Plan 7.2.3.
    text: str = Field(min_length=1, max_length=2000)
    total: int = Field(ge=1, le=10_000_000)
    done: int = Field(ge=0, le=10_000_000)


class SpiritualStateIn(BaseModel):
    paths: list[str] = Field(default_factory=list, max_length=len(VALID_PATHS))
    deity: str | None = Field(default=None, max_length=80)
    japa: JapaIn | None = None
    sankalp: SankalpIn | None = None
    saved: list[str] = Field(default_factory=list, max_length=200)
    festivals_region: str | None = Field(default=None, max_length=64)


class SpiritualAskIn(BaseModel):
    question: str = Field(min_length=2, max_length=400)
    path: str | None = Field(default=None, max_length=24)


# ------------------------------------------------------------------ helpers --
def profile_for(db: Session, user: User) -> KendraProfile:
    """
    The safety profile, created empty on first read.

    Empty is a real state and not a missing one: it means the health check has
    not been answered yet, and every screen has to treat that person as
    unassessed rather than as clear. `most_restrictive` already reads an empty
    list as "none" — which is why the empty list, and not the literal string
    "none", is what gets stored when a person says no to every question.
    """
    p = user.kendra_profile
    if p is None:
        p = KendraProfile(user_id=user.id)
        db.add(p)
        db.flush()
    return p


def state_for(db: Session, user: User) -> SpiritualState:
    """PRACTICE. A record, created empty so the app always has something to save into."""
    s = user.spiritual_state
    if s is None:
        s = SpiritualState(user_id=user.id)
        db.add(s)
        db.flush()
    return s


def latest_mobility(db: Session, user: User) -> MobilityCheck | None:
    return db.scalars(
        select(MobilityCheck)
        .where(MobilityCheck.user_id == user.id)
        .order_by(MobilityCheck.at.desc())
        .limit(1)
    ).first()


def programme_poses(db: Session, programme: KendraProgramme) -> list[tuple[Pose | None, object]]:
    """
    The programme's poses, in the order it stores them.

    `pose_ids` is a JSON list of ids. Slugs are accepted alongside them because
    a catalogue row authored with slugs is a mistake somebody will make, and in
    this screen a pose that fails to resolve cannot be certified safe — it
    comes back as None and the caller withholds it. Returning a shorter list
    instead would quietly drop a movement out of a programme, which is the
    exact failure this feature exists to prevent.
    """
    ids = [p for p in (programme.pose_ids or []) if isinstance(p, int) and not isinstance(p, bool)]
    slugs = [p for p in (programme.pose_ids or []) if isinstance(p, str)]
    clauses = []
    if ids:
        clauses.append(Pose.id.in_(ids))
    if slugs:
        clauses.append(Pose.slug.in_(slugs))
    if not clauses:
        return []
    rows = db.scalars(select(Pose).where(or_(*clauses))).all()
    by_id = {p.id: p for p in rows}
    by_slug = {p.slug: p for p in rows}
    out: list[tuple[Pose | None, object]] = []
    for key in programme.pose_ids or []:
        if isinstance(key, int) and not isinstance(key, bool):
            out.append((by_id.get(key), key))
        else:
            out.append((by_slug.get(str(key)), str(key)))
    return out


def screen_programme(
    db: Session,
    profile: KendraProfile,
    programme: KendraProgramme,
    balance_gate: str | None,
) -> dict:
    """
    What this person may do in this programme today, and what is held back.

    THIS is the filter the whole design exists for. A pose that is unsafe for
    this person's flags is not offered to them, and where that leaves a
    programme with nothing in it, the programme is not offered either. A
    pensioner with a bad knee being handed a programme that squats is the
    failure this exists to prevent, and it is not caught after somebody has
    attempted the movement — it is caught here, before the pose is offered,
    which is what KendraProfile's docstring means by "before it appears on the
    Today screen". A programme that survives is still not silently shortened:
    every removed pose comes back in `withheld` with the reason, because a
    person quietly given six poses instead of nine is being lied to by
    omission.

    Three rules decide a pose, in this order:

      1. `Pose.contraindications` is checked FIRST, before the pose is offered.
         Any of this person's flags in that list is a hit.
      2. A pose that requires standing is a hit for `fall_risk`, whatever its
         contraindications say — KendraProfile's docstring is explicit, and a
         chair-supported version of the pose is a different pose with a
         `modification`, which rule 3 then admits.
      3. A hit that can be worked around with a modified movement is offered
         modified (Pose.modification). A hit that cannot is withheld. A flag
         outside MODIFIABLE_SAFETY_FLAGS — recent surgery, a heart condition,
         fall risk — is never worked around: adapting those is a clinician's
         judgement, and this app has no business making it.

    `balance_gate` is the one place a self-assessment narrows what is offered.
    It is treated as a restriction and never as an all-clear — see the route.
    """
    flags = list(profile.flags or [])
    effective = list(flags)
    if balance_gate and balance_gate not in effective:
        effective.append(balance_gate)

    # `targets` names what the programme is FOR. A programme built for a
    # condition that cannot be worked around with a modified movement is not
    # self-serve, so it is withheld whole rather than patched. Chair yoga
    # targets nothing that needs working around, which is exactly why it still
    # reaches somebody at fall risk: sitting down is the safest thing in the
    # building, and screening them out of it would keep the people who most
    # need to move from moving.
    targeted = [f for f in effective if f in (programme.targets or [])]
    hard_targets = [f for f in targeted if f not in MODIFIABLE_SAFETY_FLAGS]
    if hard_targets:
        return {
            "poses": [],
            "withheld": [],
            "blocked": True,
            "blocked_by": hard_targets,
        }

    poses: list[dict] = []
    withheld: list[dict] = []
    for pose, key in programme_poses(db, programme):
        if pose is None:
            # Cannot be resolved, so its contraindications are unknown, and an
            # unknown is not a certificate. The screen's job is to certify.
            withheld.append(
                {
                    "pose": str(key),
                    "pose_name": None,
                    "code": "unknown_pose",
                    "flags": [],
                    "source": "library",
                    "reason": "this movement could not be found in the pose library, so it cannot be checked for your safety",
                }
            )
            continue

        hits = [f for f in effective if f in (pose.contraindications or [])]
        standing = pose.requires_standing and "fall_risk" in effective
        if standing and "fall_risk" not in hits:
            hits.append("fall_risk")

        if not hits:
            poses.append(
                {
                    "slug": pose.slug,
                    "name": pose.name,
                    "instruction": pose.instruction,
                    "hold_seconds": pose.hold_seconds,
                    "reps": pose.reps,
                    "requires_standing": pose.requires_standing,
                    "requires_floor": pose.requires_floor,
                    "breathing_cue": pose.breathing_cue,
                    "illustration_key": pose.illustration_key,
                    "modified": False,
                    "modification": None,
                }
            )
            continue

        hard = [f for f in hits if f not in MODIFIABLE_SAFETY_FLAGS]
        if hard or not pose.modification:
            # SAFETY_FLAG_SEVERITY is read here for one narrow thing: naming
            # WHICH of several flags caused this pose to be withheld. It is not
            # re-deriving the safety decision — that is screen_programme's and
            # KendraProfile.most_restrictive's job, and re-deciding it here
            # would be the exact sort of second implementation that lets two
            # screens disagree about the same knee.
            worst = max(hits, key=lambda f: SAFETY_FLAG_SEVERITY.get(f, 0))
            withheld.append(
                {
                    "pose": pose.slug,
                    "pose_name": pose.name,
                    "code": "contraindicated",
                    "flags": hits,
                    # Whether this came from what the person told us or from
                    # their own mobility check, because they are told apart on
                    # screen and the two do not carry the same authority.
                    "source": "profile" if any(f in flags for f in hits) else "mobility_check",
                    "reason": FLAG_REASON.get(
                        worst, "this movement is not offered with your current health answers"
                    ),
                }
            )
            continue

        # Offerable in a modified form. Offered, not removed: the plan's whole
        # argument is that a 68-year-old should still be able to do a forward
        # bend in some form, and a modification is the form.
        poses.append(
            {
                "slug": pose.slug,
                "name": pose.name,
                "instruction": pose.instruction,
                "hold_seconds": pose.hold_seconds,
                "reps": pose.reps,
                "requires_standing": pose.requires_standing,
                "requires_floor": pose.requires_floor,
                "breathing_cue": pose.breathing_cue,
                "illustration_key": pose.illustration_key,
                "modified": True,
                "modification": pose.modification,
            }
        )

    # A programme with nothing left in it is not a programme. Withholding the
    # poses one at a time and calling that a session would be how somebody ends
    # up being told they completed a 20-minute practice that had three poses in
    # it.
    if programme.pose_ids and not poses:
        return {"poses": [], "withheld": withheld, "blocked": True, "blocked_by": []}
    return {"poses": poses, "withheld": withheld, "blocked": False, "blocked_by": []}


# --------------------------------------------------------------- serialisers --
def profile_view(p: KendraProfile) -> dict:
    return {
        "flags": p.flags or [],
        # The most restrictive flag across every condition this person has,
        # walked over SAFETY_FLAG_SEVERITY — the worst one wins, so a knee
        # problem and a heart condition do not average out into "fine".
        "most_restrictive": p.most_restrictive,
        # True when any flag is outside MODIFIABLE_SAFETY_FLAGS, which means
        # no modified movement can make that condition safe and a doctor has
        # to say so first. It is a property of the FLAGS, not of the note: a
        # doctor's note the user typed is their own account of what the doctor
        # said, and the app has no way to check it, so it must never be read as
        # clearing a flag. Only a clinician can do that, and only in a room.
        "needs_doctor_note": p.needs_doctor_note,
        "doctor_note": p.doctor_note,
        "instructor": p.instructor,
        "mirror": p.mirror,
        "voice_langs": p.voice_langs or [],
    }


def programme_view(programme: KendraProgramme, screened: dict) -> dict:
    return {
        "slug": programme.slug,
        "title": programme.title,
        "description": programme.description,
        "kind": programme.kind,
        "total_minutes": programme.total_minutes,
        "instructor": programme.instructor,
        "languages": programme.languages or [],
        "poses": screened["poses"],
        # Named, with a reason, every time. The plan is explicit that a
        # withheld movement has to say why rather than silently shorten
        # somebody's session — a person who is quietly given six poses instead
        # of nine is being lied to by omission.
        "withheld": screened["withheld"],
        "blocked": screened["blocked"],
        "blocked_by": screened["blocked_by"],
    }


def session_view(row: KendraSession) -> dict:
    return {
        "id": row.id,
        "programme_id": row.programme_id,
        "started_at": row.started_at,
        "minutes": row.minutes,
        "completed": row.completed,
        "mobility_check_id": row.mobility_check_id,
        # Frozen at the time of the session, so a profile edited tomorrow does
        # not rewrite what was in force today. The record is of the session,
        # not of the profile as it now stands.
        "flags_at_session": row.flags_at_session or [],
    }


def mobility_view(row: MobilityCheck) -> dict:
    return {
        "id": row.id,
        "chair_stands_30s": row.chair_stands_30s,
        "single_leg_secs": row.single_leg_secs,
        "at": row.at,
        "is_at_fall_risk": row.is_at_fall_risk,
        "note": NOT_A_MEDICAL_TEST,
    }


def pair_view(row: YogaPair, partner: User) -> dict:
    """
    What the other person is told about their partner.

    Every field here is one the plan already puts in front of other members.
    Deliberately NOT in this dict, and not in any other pair response:
    phone_e164, lat, lng, the kendra profile's flags, its doctor note, and the
    mobility numbers. Plan section 14 requires a separate consent for health
    data, none of it has been given, and none of it is needed to decide whether
    to turn up on the same morning — it would only be there because it was
    sitting in a row we had already loaded. A full phone number or address is
    the same line: the plan's own answer is a circle, not a directory.
    """
    return {
        "id": row.id,
        "partner": {
            "id": partner.id,
            # How the app should address them — "Sharma ji". Plan 3.5.
            "address_as": partner.address_as,
            "name": partner.name,
            "city_id": partner.city_id,
            "tier": partner.tier,
        },
        "created_at": row.created_at,
    }


def state_view(s: SpiritualState) -> dict:
    return {
        "paths": s.paths or [],
        "deity": s.deity,
        "japa": s.japa,
        "sankalp": s.sankalp,
        "saved": s.saved or [],
        "festivals_region": s.festivals_region,
    }


def festival_view(f: Festival) -> dict:
    return {
        "id": f.id,
        "name": f.name,
        "kind": f.kind,
        "starts_on": f.starts_on,
        "ends_on": f.ends_on,
        "region": f.region,
        "languages": f.languages or [],
        "year": f.year,
        # Carried with the date, not beside it in a docs page. Somebody who
        # kept a fast needs to be able to see where the number came from.
        "source": f.source_key,
    }


def content_view(row: SpiritualContent, asked: set[str]) -> dict:
    return {
        "id": row.id,
        "kind": row.kind,
        "title": row.title,
        "path": row.path,
        "language": row.language,
        # A key into storage we control, never a URL: a sung aarti is licensed
        # and this app does not hand out third-party links it cannot revoke.
        "audio_key": row.audio_key,
        "excerpt": _excerpt(row.body, asked) if row.body else None,
        "source_ref": row.source_ref,
    }


# ------------------------------------------------------------- exercise half --
@router.get("/kendra/programmes")
def list_programmes(
    user: CurrentUser,
    db: DbSession,
    kind: str | None = None,
):
    """
    The programme list, filtered against this person's body.

    Signed in, deliberately. The catalogue on its own is harmless, but this
    endpoint's job is to return what is SAFE FOR THE CALLER, and the moment it
    is served without a caller the filter silently stops applying — and the
    app is the kind of client that caches a list. A signed-out response cached
    and then shown, after sign-in, to somebody with a knee problem, is the
    failure this whole section is built to prevent, and it is easier to prevent
    by never producing the unsigned version than by remembering to invalidate
    it. The signed-out reads the plan allows are the ones that carry nothing
    personal, and festivals is one of them; a per-person safety screen is not.
    """
    if kind is not None and kind not in PROGRAMME_KINDS:
        raise HTTPException(400, f"kind must be one of {', '.join(PROGRAMME_KINDS)}")

    profile = profile_for(db, user)
    programmes = db.scalars(
        select(KendraProgramme).where(KendraProgramme.is_active.is_(True))
    ).all()
    if kind is not None:
        programmes = [p for p in programmes if p.kind == kind]

    # The one self-assessment that narrows what is offered, and only in that
    # direction. A low chair-stand count or a short single-leg hold means the
    # body did not manage those today, and the screen treats that as a
    # restriction. It is never an all-clear: no good morning lifts a
    # restriction that a reported flag put there, because a good morning is not
    # evidence that a hip will not give way. It gates offering and nothing else,
    # it is never written back to the profile as a condition the person did not
    # report, and nothing is inferred from it — that is the line between a
    # self-assessment and a diagnosis, and it is a line this app does not cross.
    check = latest_mobility(db, user)
    balance_gate = "fall_risk" if (check is not None and check.is_at_fall_risk) else None

    # No sort column exists, so this is the order the programmes were authored
    # in, which is the order plan section 7.1 lists them. Stable, and it does
    # not invent a rank.
    programmes = sorted(programmes, key=lambda p: p.id)

    out = []
    for programme in programmes:
        screened = screen_programme(db, profile, programme, balance_gate)
        if screened["blocked"]:
            log.info(
                "kendra: programme %s withheld from user %s (flags=%s)",
                programme.slug,
                user.id,
                screened["blocked_by"] or "all poses",
            )
            continue
        if screened["withheld"]:
            log.info(
                "kendra: %d pose(s) withheld from user %s in %s",
                len(screened["withheld"]),
                user.id,
                programme.slug,
            )
        out.append(programme_view(programme, screened))

    return {
        "programmes": out,
        "gate": {
            "most_restrictive": profile.most_restrictive,
            "needs_doctor_note": profile.needs_doctor_note,
            "flags": profile.flags or [],
            "balance_gate": balance_gate,
            "mobility_checked_at": check.at if check is not None else None,
        },
        # Unassessed (no profile yet) or carrying a flag that cannot be worked
        # around: plan 7.1.3 puts this on the first screen, and the first
        # screen for somebody who has not answered the health check yet is
        # this list.
        "doctor_notice": (
            DOCTOR_LINE if (not profile.flags or profile.needs_doctor_note) else None
        ),
        "stop_if_pain": STOP_IF_PAIN,
    }


@router.get("/kendra/health-check")
def health_check_questions(user: CurrentUser):
    """
    The questions behind the flags (plan 7.1.1). Answers go back through
    PUT /kendra/profile as the list of flags whose question was answered yes.
    """
    return {"questions": HEALTH_CHECK, "doctor_notice": DOCTOR_LINE, "stop_if_pain": STOP_IF_PAIN}


@router.get("/kendra/profile")
def get_profile(user: CurrentUser, db: DbSession):
    """
    EXERCISE. The safety answers, back again.

    Not the same thing as the PUT returning the saved profile, and the
    difference is what a second session is: a phone that has been restarted, a
    reinstalled app, a second handset on the same account. Each of those has to
    be able to read what this person answered before it offers them a squat,
    and a profile that can only be seen by the process that wrote it is a
    safety gate that closes on every fresh session. The PUT already returns the
    same view; this exists so that is not the only way to get it.

    Returns an empty profile rather than a 404 when nothing has been answered,
    because "we have not asked you yet" and "we cannot find you" are different
    things and only one of them should make a pensioner think something is
    wrong. `profile_for` creates the row on first read, so the answer is always
    present and always theirs.
    """
    return profile_view(profile_for(db, user))


@router.put("/kendra/profile")
def put_profile(
    body: ProfileIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    The health check answers, and who teaches the session.

    EXERCISE. These flags decide what movements this person is shown for the
    rest of their time on the app, so an unknown flag is refused rather than
    stored: a string no screen matches gates nothing, and a flag that gates
    nothing is the worst possible outcome — it looks like a safety answer and
    is not one.

    The doctor's note does not clear anything. It is what the doctor said, in
    the user's own words, and it is shown on the programme screen so the
    instructor can see it; the app has no way to verify a note and will not
    pretend to. The flag stays, `needs_doctor_note` stays true, and the doctor
    line stays on the screen until a clinician says otherwise in person.
    """
    limit_write(request, user.id)

    for flag in body.flags:
        if flag not in SAFETY_FLAGS:
            raise HTTPException(400, f"flag must be one of {', '.join(SAFETY_FLAGS)}")
    for lang in body.voice_langs:
        if lang not in LANGUAGES:
            raise HTTPException(400, f"voice_langs must be from {', '.join(LANGUAGES)}")
    if body.instructor is not None and body.instructor not in INSTRUCTOR_STYLES:
        raise HTTPException(400, f"instructor must be one of {', '.join(INSTRUCTOR_STYLES)}")

    # "none" is a rung on the severity ladder so most_restrictive has something
    # to return, and it is in SAFETY_FLAGS so a client may send it. Storing it
    # would be storing the absence of a condition as though it were one, and
    # needs_doctor_note would then read true for a person who answered no to
    # every question. The empty list is what that means; most_restrictive
    # already reports it as "none".
    flags = [f for f in dict.fromkeys(body.flags) if f != "none"]

    profile = profile_for(db, user)
    profile.flags = flags
    profile.doctor_note = body.doctor_note
    profile.instructor = body.instructor
    profile.mirror = body.mirror
    profile.voice_langs = list(dict.fromkeys(body.voice_langs))
    db.commit()
    log.info("kendra: profile updated for user %s, flags=%s", user.id, flags)
    return profile_view(profile)


@router.post("/kendra/sessions")
def record_session(
    body: SessionIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    A session somebody actually finished.

    It is recorded WITH the unsafe poses stripped, rather than refused. The
    reason is that this row is a record of what the body did, and it is written
    after the fact: refusing it would not un-squat anybody's knee, it would
    only lose the data and leave the person with no record of a session they
    genuinely did in a chair instead of on the floor. So the log keeps what was
    true, `flags_at_session` freezes the flags that were in force, and the
    response hands back the withheld poses so the app can say "we left out
    three movements today, and here is why" rather than letting somebody
    believe they did the whole programme. A record that quietly omits half a
    session is how a mobility trend gets read as a decline that never happened.

    The refusal that IS made here is a programme this person's profile does not
    allow at all. There is nothing to record in that case — no session happened
    against a programme they were never offered — and 409 says so plainly
    rather than accepting a completion the safety screen would not have issued.
    """
    limit_write(request, user.id)

    if body.started_at is not None:
        current = now()
        if body.started_at > current + 3600:
            raise HTTPException(400, "started_at is in the future")
        # Seven days, not one: an offline sync of a session from last Tuesday
        # is a real case, and the weekly minutes figure this feeds is read by
        # the person and, with permission, by their family.
        if body.started_at < current - 7 * 86400:
            raise HTTPException(400, "started_at is more than 7 days ago")

    programme = db.get(KendraProgramme, body.programme_id)
    if programme is None or not programme.is_active:
        raise HTTPException(404, "programme not found")

    check = None
    if body.mobility_check_id is not None:
        check = db.get(MobilityCheck, body.mobility_check_id)
        if check is None:
            raise HTTPException(404, "mobility check not found")
        if check.user_id != user.id:
            raise HTTPException(403, "that mobility check is not yours")

    profile = profile_for(db, user)
    # A check attached to this session, or the last one on file — the same gate
    # the programme list was built with, so the programme a person was shown
    # and the session they finished cannot disagree about what was safe.
    latest = check if check is not None else latest_mobility(db, user)
    balance_gate = "fall_risk" if (latest is not None and latest.is_at_fall_risk) else None

    screened = screen_programme(db, profile, programme, balance_gate)
    if screened["blocked"]:
        raise HTTPException(
            409,
            "that programme is not offered for your health answers; nothing was recorded",
        )

    row = KendraSession(
        user_id=user.id,
        programme_id=programme.id,
        minutes=body.minutes,
        completed=body.completed,
        mobility_check_id=check.id if check is not None else None,
        flags_at_session=list(profile.flags or []),
    )
    if body.started_at is not None:
        row.started_at = body.started_at
    db.add(row)
    db.commit()

    if screened["withheld"]:
        log.info(
            "kendra: session %s recorded for user %s with %d pose(s) stripped",
            row.id,
            user.id,
            len(screened["withheld"]),
        )
    out = session_view(row)
    out["withheld"] = screened["withheld"]
    out["done"] = screened["poses"]
    # Spoken at the start of every session, without exception, plan 7.1.3.
    out["spoken_notice"] = STOP_IF_PAIN
    return out


@router.post("/kendra/mobility")
def record_mobility(
    body: MobilityIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    The monthly self-check from plan section 7.1 "Tracking progress".

    Two numbers the person supplies about their own body, which is why the
    response says what they are: not a screening test, not administered by
    anybody, not a diagnosis. It gates what is offered next and is read nowhere
    else — in particular it is not in the family report, which is a separate
    act with its own permission and is not this route's business.

    `is_at_fall_risk` is the model's own threshold and is used as one: a low
    score narrows the programme list and never widens it.
    """
    limit_write(request, user.id)

    row = MobilityCheck(
        user_id=user.id,
        chair_stands_30s=body.chair_stands_30s,
        single_leg_secs=body.single_leg_secs,
    )
    db.add(row)
    db.commit()
    log.info("kendra: mobility check %s for user %s, at_fall_risk=%s", row.id, user.id, row.is_at_fall_risk)
    return mobility_view(row)


@router.post("/kendra/pairs")
def create_pair(
    body: PairIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    Yoga saathi: two people who practise the same programme together.

    Both people have to be in this for it to happen, and the check below is the
    only consent signal the schema currently carries. That is a real gap and it
    is worth saying out loud: the plan wants a yoga partner to be a genuine
    arrangement between two people who expect to turn up, and plan section 14
    wants a separate consent for health data, and neither exists as a column.
    `discoverable` is the closest thing here and it is deliberately the strict
    conjunction of all three conditions rather than any one of them. When the
    schema carries a real yoga-partner consent, this should be REPLACED by it,
    not added to it.

    Ability is matched WITHOUT publishing anybody's ability. The shared
    programme is screened against both profiles on the server, and the outcome
    is a pair or a refusal — never a reason. A refusal says the programme is
    not safe for both of you and stops, because the reason is the other
    person's health, and this caller has not consented to learning it and never
    will by having said yes to a pairing.

    The pair row records the two people and nothing else. There is no column
    for the programme they agreed on, so it is not stored: the response carries
    it, and re-deriving it later would report what is safe now, which is not
    what was agreed then. Storing it needs a migration, not a field default.
    """
    limit_write(request, user.id)

    if body.partner_user_id == user.id:
        raise HTTPException(400, "you cannot pair with yourself")

    programme = db.get(KendraProgramme, body.programme_id)
    if programme is None or not programme.is_active:
        raise HTTPException(404, "programme not found")

    partner = db.get(User, body.partner_user_id)
    # One answer for "no such person" and "not open to pairing", so this cannot
    # be used to enumerate who is on the app.
    if partner is None or partner.deleted_at is not None or not partner.discoverable:
        raise HTTPException(404, "no such member, or they are not open to pairing")
    partner_profile = partner.kendra_profile
    if partner_profile is None:
        # Without a profile there is no health check, and the shared programme
        # cannot be screened for them. An unscreened pairing is the one thing
        # this endpoint must never produce.
        raise HTTPException(404, "no such member, or they are not open to pairing")

    # The row is unordered but its columns are not, so the pair is written
    # low-id-first. Without this the same two people exist twice with the
    # columns swapped, and neither the "am I already paired" check below nor
    # ix_yoga_pairs_b_active finds both.
    a, b = sorted((user.id, partner.id))
    existing = db.scalars(
        select(YogaPair).where(
            or_(YogaPair.user_a == user.id, YogaPair.user_b == user.id),
            YogaPair.ended_at.is_(None),
        )
    ).first()
    if existing is not None:
        raise HTTPException(409, "you are already paired; end that pairing first")
    already = db.scalars(
        select(YogaPair).where(
            YogaPair.user_a == a, YogaPair.user_b == b, YogaPair.ended_at.is_(None)
        )
    ).first()
    if already is not None:
        raise HTTPException(409, "you are already paired with this person")

    # Screen for both, and say nothing about the outcome beyond pass or fail.
    mine = screen_programme(db, profile_for(db, user), programme, None)
    theirs = screen_programme(db, partner_profile, programme, None)
    if mine["blocked"] or theirs["blocked"]:
        raise HTTPException(409, "that programme is not safe for both of you")

    row = YogaPair(user_a=a, user_b=b)
    db.add(row)
    db.commit()
    log.info("kendra: pair %s created (%s, %s) on %s", row.id, a, b, programme.slug)

    out = pair_view(row, partner)
    # There is no GET for pairs in the plan's endpoint list, so this response is
    # the app's only chance to learn who they were paired with and what they
    # agreed to practise. Adding GET /kendra/pairs is a new endpoint, not
    # something to squeeze in here.
    out["programme"] = programme_view(programme, mine)
    out["partner_programme"] = programme_view(programme, theirs)
    return out


# ------------------------------------------------------------- practice half --
# Everything below this line is PRACTICE.
#
# There are no safety flags here, no doctor note, no mobility gate, and none is
# wanted. The exercise routes above are in this same file, in the same order a
# reader meets them, and it is a reasonable — and wrong — assumption that their
# rules carry down. They do not. Nobody falls over a japa count; a person who
# is asked for a doctor's note before setting down their mala is being told
# their practice is a medical condition, and the plan's own position (7.2) is
# that this centre records what somebody already does and instructs nothing.
# If a future edit finds itself adding a check to these routes for the sake of
# consistency, that is the mistake being made.


@router.get("/spiritual/state")
def get_state(user: CurrentUser, db: DbSession):
    """PRACTICE. A record of their own, returned to them and to nobody else."""
    return state_view(state_for(db, user))


@router.put("/spiritual/state")
def put_state(
    body: SpiritualStateIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    PRACTICE. Their paths, their deity, their mala count, their sankalp.

    Stored as given and not interpreted. `paths` is validated so a typo does
    not quietly become a path the Adhyatm Kendra filters on, but nothing is
    ranked, suggested, corrected or added: choosing "none", or several paths,
    or changing them next month, is all legitimate and none of it is this
    app's business. No faith is ranked above another anywhere in this module
    (plan 7.2 content policy), and validating the name of a faith is not
    ranking it.
    """
    limit_write(request, user.id)

    for path in body.paths:
        if path not in VALID_PATHS:
            raise HTTPException(400, f"path must be one of {', '.join(VALID_PATHS)}")
    if body.japa is not None and body.japa.count > body.japa.goal:
        raise HTTPException(400, "japa count is over the goal")
    if body.sankalp is not None and body.sankalp.done > body.sankalp.total:
        raise HTTPException(400, "sankalp done is over the total")

    paths = list(dict.fromkeys(body.paths))
    # "none" alongside a real path is a contradiction the client has not
    # finished resolving; keeping the real path is the reading a person means.
    real = [p for p in paths if p != "none"]
    paths = real or paths or ["none"]

    s = state_for(db, user)
    s.paths = paths
    s.deity = body.deity
    s.japa = body.japa.model_dump() if body.japa is not None else None
    s.sankalp = body.sankalp.model_dump() if body.sankalp is not None else None
    s.saved = list(dict.fromkeys(body.saved))
    s.festivals_region = body.festivals_region
    db.commit()
    return state_view(s)


@router.get("/spiritual/festivals")
def list_festivals(
    user: OptionalUser,
    db: DbSession,
    region: str | None = None,
    year: int | None = None,
):
    """
    Festival and vrat dates for a region, from the licensed source that loaded
    them.

    THERE IS NO FORMULA HERE, and there is not going to be one. A lunar
    calendar, a tithi and a panchang are not something to reimplement from an
    astronomy library whose licence nobody has read, and a calendar that is
    confidently wrong by a day is worse than no calendar at all for somebody
    keeping a fast or booking a journey around it. The dates come from a
    licensed panchang source loaded into `festivals`; this route reads that
    table and returns `source_key` with every row, so the person can see where
    the date came from and check it. The year is asked for rather than assumed,
    because the table is per-year by design and a stale table must not read as
    this year's calendar.

    Signed out is allowed, and this is one of the few reads where it is: the
    data is a published calendar, identical for everybody, and a family member
    looking up Ekadashi on a pensioner's behalf is the ordinary case rather
    than the abuse. A region is never guessed either — without a parameter it
    falls back to the caller's own saved region, and with neither, only the
    nationwide rows come back, which is honest about not knowing.
    """
    this_year = datetime.fromtimestamp(now(), tz=timezone.utc).year
    want_year = this_year if year is None else year
    if not 2000 <= want_year <= this_year + 5:
        raise HTTPException(400, "year out of range")

    chosen = region
    if chosen is None and user is not None:
        state = user.spiritual_state
        if state is not None and state.festivals_region:
            chosen = state.festivals_region

    q = select(Festival).where(Festival.year == want_year)
    if chosen:
        q = q.where(or_(Festival.region == chosen, Festival.region.is_(None)))
    else:
        # No region to filter on, so only what applies everywhere. Returning
        # one region's rows under a blank heading would be worse than saying
        # nothing, because the date would look authoritative and be wrong.
        q = q.where(Festival.region.is_(None))

    rows = db.scalars(q.order_by(Festival.starts_on, Festival.name)).all()
    if not rows:
        # Worth a log line: an empty year is an ops problem (the loader has
        # not run) and it is otherwise indistinguishable from a year with no
        # festivals in it.
        log.warning("kendra: no festivals loaded for year=%s region=%s", want_year, chosen)
    return {
        "year": want_year,
        "region": chosen,
        "source": "licensed panchang source, loaded into festivals; never computed",
        "festivals": [festival_view(f) for f in rows],
    }


def _terms(text: str) -> set[str]:
    return {
        w
        for w in _QUESTION_WORDS.findall(text.lower())
        if len(w) >= 3 and w not in _STOPWORDS
    }


def _excerpt(body: str, terms: set[str], width: int = 300) -> str:
    """A slice of the text around the first matching word. Not a summary."""
    low = body.lower()
    at = -1
    for t in terms:
        i = low.find(t)
        if i != -1 and (at == -1 or i < at):
            at = i
    if at == -1:
        out = body[:width]
        return out + "…" if len(body) > width else out
    start = max(0, at - width // 3)
    if start:
        # Snap back to a word boundary so a cut mid-word does not read as a
        # rendering bug in a text the person is quoting to themselves.
        space = body.find(" ", start)
        start = space + 1 if space != -1 and space - start < 20 else start
    end = min(len(body), start + width)
    out = body[start:end]
    if end < len(body):
        out = out.rsplit(" ", 1)[0] + "…"
    return ("…" if start else "") + out


@router.post("/spiritual/ask")
def ask_spiritual(
    body: SpiritualAskIn,
    request: Request,
    user: CurrentUser,
    db: DbSession,
):
    """
    A question about practice, answered from the Adhyatm Kendra's own texts.

    This is NOT Sahayak, and it imports none of Sahayak. That is deliberate in
    both directions. Sahayak's machinery — documents, chunks, embeddings, a
    confidence floor, a question log, escalation to a volunteer — exists to
    make a pension rule checkable, and borrowing it here would dress a question
    about somebody's devotion in an authority it has not got. And the reverse
    is equally wrong: a spiritual answer is not a financial one that happens
    to be softer, so it gets the same plain line at the end
    (PRACTICE_DISCLAIMER) for the same reason. The user is going to act on it.
    Somebody unwell may keep a fast, or stop a medicine, on the strength of a
    sentence here, and the harm lands on them and not on us.

    So the route only reads. It quotes approved, active content, credited with
    its source, and it NEVER assembles a sentence of its own — a generated
    "traditionally this brings peace and cures sleeplessness" is precisely the
    promise plan 7.2's content policy forbids, and a model that can be asked
    will eventually make one. It never synthesises a voice for an aarti
    either; those are sung recordings, served from `audio_key`.

    If nothing in the person's language matches, the answer is an empty list
    and the disclaimer. Returning a best guess would be a fabrication, and the
    alternative here is a person reading something that is not there.
    """
    limit_write(request, user.id)

    if body.path is not None and body.path not in VALID_PATHS:
        raise HTTPException(400, f"path must be one of {', '.join(VALID_PATHS)}")

    state = state_for(db, user)
    wanted = body.path
    if wanted is None:
        chosen = [p for p in (state.paths or []) if p != "none"]
        wanted = chosen[0] if len(chosen) == 1 else None

    q = select(SpiritualContent).where(SpiritualContent.is_active.is_(True))
    # The person's language first. A line in a script they cannot read is not
    # an answer, however correct it is.
    q = q.where(SpiritualContent.language == (user.lang or "en"))
    if wanted is not None:
        # Content with no path is general and is always fair game; the plan
        # says no faith is ranked above another and this is where that would
        # otherwise leak in as a filter.
        q = q.where(or_(SpiritualContent.path == wanted, SpiritualContent.path.is_(None)))

    rows = db.scalars(q).all()
    asked = _terms(body.question)
    if not asked:
        raise HTTPException(400, "question has no words to search on")

    scored: list[tuple[int, SpiritualContent]] = []
    for row in rows:
        title_terms = _terms(row.title or "")
        # Title matches count for more: the title is what a text is about.
        score = 3 * len(asked & title_terms) + len(
            asked & _terms(f"{row.title or ''} {row.body or ''}")
        )
        if score > 0:
            scored.append((score, row))
    scored.sort(key=lambda s: (-s[0], s[1].title or ""))

    return {
        "question": body.question,
        "path": wanted,
        "results": [content_view(row, asked) for _, row in scored[:3]],
        # One place. Present whether or not anything was found, because the
        # person reading an empty result is the one most likely to go and look
        # it up somewhere less careful.
        "disclaimer": PRACTICE_DISCLAIMER,
    }
