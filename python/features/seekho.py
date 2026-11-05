# =============================================================================
# Feature · Seekho (learn) — plan section 6.11.
#
#   GET  /lessons                       3-5 minute lessons, with my progress
#   GET  /lessons/{slug}                the big-type steps and the 3-question check
#   POST /lessons/{slug}/complete       submit the check -> score, and a badge at 2/3
#   GET  /badges
#   POST /live-help                     "Teach me live": ask for a 20-minute volunteer call
#   GET  /live-help/open                (volunteers) requests waiting
#   POST /live-help/{id}/accept         a volunteer takes it
#   POST /live-help/{id}/join           tokens for the call
#   POST /live-help/{id}/complete
#
# The correct answers of a quiz are never sent to the client with the question:
# a lesson check that can be passed by reading the network log teaches nothing.
# They are revealed with the result, together with the one-line reason, so a wrong
# answer is a small lesson rather than a small failure. Nobody is graded against
# anybody else and there is no leaderboard.
# =============================================================================
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select

from python.deps import CurrentUser, DbSession
from python.dispatch import notify_user
from python.integrations import livekit
from python.models import TIER_RANK, Lesson, LessonProgress, LiveHelpRequest, User, now
from python.ratelimit import limit_write

log = logging.getLogger("doosri.seekho")
router = APIRouter(tags=["seekho"])

PASS_MARK = 2                      # of 3
HELP_CALL_MINUTES = 20
CATEGORIES = ("phone", "whatsapp", "upi", "digilocker", "jeevan_pramaan", "itr", "maps", "hobby")


class CompleteIn(BaseModel):
    answers: list[int] = Field(default_factory=list, max_length=10)


class HelpIn(BaseModel):
    topic: str = Field(min_length=3, max_length=160)
    preferred_at: float | None = None


def _lesson_view(l: Lesson, progress: LessonProgress | None, full: bool = False) -> dict:
    out = {
        "id": l.id, "slug": l.slug, "title": l.title, "summary": l.summary, "category": l.category,
        "minutes": l.minutes, "language": l.language, "badge": l.badge,
        "completed": bool(progress and progress.completed_at),
        "score": progress.score if progress else None,
    }
    if full:
        out["steps"] = l.steps or []
        # The question and its options - never the answer or the reason.
        out["quiz"] = [{"q": q.get("q"), "options": q.get("options", [])} for q in (l.quiz or [])]
        out["pass_mark"] = min(PASS_MARK, len(l.quiz or []))
    return out


@router.get("/lessons")
def lessons(user: CurrentUser, db: DbSession, category: str | None = None, lang: str | None = None):
    if category is not None and category not in CATEGORIES:
        raise HTTPException(400, f"category must be one of {', '.join(CATEGORIES)}")
    want = lang or user.lang or "en"
    q = select(Lesson).where(Lesson.is_active.is_(True), Lesson.language.in_((want, "en")))
    if category:
        q = q.where(Lesson.category == category)
    rows = db.scalars(q.order_by(Lesson.category, Lesson.position, Lesson.id)).all()
    # One row per slug: the person's language if it exists, otherwise English.
    chosen: dict[str, Lesson] = {}
    for l in rows:
        if l.slug not in chosen or l.language == want:
            chosen[l.slug] = l
    progress = {
        p.lesson_id: p
        for p in db.scalars(select(LessonProgress).where(LessonProgress.user_id == user.id)).all()
    }
    items = [_lesson_view(l, progress.get(l.id)) for l in chosen.values()]
    return {"lessons": items, "completed": sum(1 for i in items if i["completed"]), "total": len(items)}


def _lesson(db, slug: str, user: User) -> Lesson:
    rows = db.scalars(select(Lesson).where(Lesson.slug == slug, Lesson.is_active.is_(True))).all()
    if not rows:
        raise HTTPException(404, "lesson not found")
    return next((l for l in rows if l.language == user.lang), rows[0])


@router.get("/lessons/{slug}")
def lesson(slug: str, user: CurrentUser, db: DbSession):
    l = _lesson(db, slug, user)
    prog = db.scalar(select(LessonProgress).where(LessonProgress.user_id == user.id, LessonProgress.lesson_id == l.id))
    if prog is None:
        prog = LessonProgress(user_id=user.id, lesson_id=l.id)
        db.add(prog)
        db.commit()
    return _lesson_view(l, prog, full=True)


@router.post("/lessons/{slug}/complete")
def complete(slug: str, body: CompleteIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    l = _lesson(db, slug, user)
    quiz = l.quiz or []
    if quiz and len(body.answers) != len(quiz):
        raise HTTPException(400, f"answer all {len(quiz)} questions")
    results = []
    score = 0
    for i, q in enumerate(quiz):
        given = body.answers[i]
        right = int(q.get("answer", -1))
        ok = given == right
        score += int(ok)
        # Revealed now, with the reason: this is the teaching.
        results.append({"correct": ok, "right_option": right, "why": q.get("why")})
    passed = score >= min(PASS_MARK, len(quiz)) if quiz else True

    prog = db.scalar(select(LessonProgress).where(LessonProgress.user_id == user.id, LessonProgress.lesson_id == l.id))
    if prog is None:
        prog = LessonProgress(user_id=user.id, lesson_id=l.id)
        db.add(prog)
    prog.score = max(score, prog.score or 0)
    newly_badged = False
    if passed:
        if prog.completed_at is None:
            prog.completed_at = now()
        if l.badge and not prog.badge_awarded:
            prog.badge_awarded = True
            newly_badged = True
    db.commit()
    return {
        "passed": passed, "score": score, "of": len(quiz), "results": results,
        "badge": l.badge if (passed and l.badge) else None, "new_badge": newly_badged,
        "message": "Well done." if passed else "Almost there. Read it once more and try again, there is no hurry.",
    }


@router.get("/badges")
def badges(user: CurrentUser, db: DbSession):
    rows = db.execute(
        select(LessonProgress, Lesson).join(Lesson, Lesson.id == LessonProgress.lesson_id)
        .where(LessonProgress.user_id == user.id, LessonProgress.badge_awarded.is_(True))
        .order_by(LessonProgress.completed_at)
    ).all()
    return {"badges": [{"badge": l.badge, "lesson": l.title, "earned_at": p.completed_at} for p, l in rows]}


# ------------------------------------------------------------------ live help --
def _help_view(r: LiveHelpRequest) -> dict:
    return {
        "id": r.id, "topic": r.topic, "status": r.status, "preferred_at": r.preferred_at,
        "created_at": r.created_at, "matched_at": r.matched_at, "minutes": HELP_CALL_MINUTES,
    }


@router.post("/live-help")
def ask_live(body: HelpIn, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    open_one = db.scalar(select(LiveHelpRequest).where(LiveHelpRequest.user_id == user.id, LiveHelpRequest.status.in_(("open", "matched"))))
    if open_one is not None:
        return _help_view(open_one)
    r = LiveHelpRequest(user_id=user.id, topic=body.topic.strip(), preferred_at=body.preferred_at)
    db.add(r)
    db.commit()
    return _help_view(r)


@router.get("/live-help/mine")
def my_help(user: CurrentUser, db: DbSession):
    rows = db.scalars(select(LiveHelpRequest).where(LiveHelpRequest.user_id == user.id).order_by(LiveHelpRequest.created_at.desc()).limit(20)).all()
    return {"requests": [_help_view(r) for r in rows]}


def _volunteer(user: User) -> None:
    if user.tier_rank < TIER_RANK["T2"]:
        raise HTTPException(403, "volunteering for live help needs a T2 account")


@router.get("/live-help/open")
def open_requests(user: CurrentUser, db: DbSession):
    _volunteer(user)
    rows = db.scalars(select(LiveHelpRequest).where(LiveHelpRequest.status == "open", LiveHelpRequest.user_id != user.id)
                      .order_by(LiveHelpRequest.created_at).limit(30)).all()
    return {"requests": [_help_view(r) for r in rows]}


@router.post("/live-help/{help_id}/accept")
def accept(help_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    _volunteer(user)
    r = db.get(LiveHelpRequest, help_id)
    if r is None or r.status != "open" or r.user_id == user.id:
        raise HTTPException(409, "someone else has taken this one, or it is closed")
    r.status, r.volunteer_id, r.matched_at = "matched", user.id, now()
    db.commit()
    asker = db.get(User, r.user_id)
    if asker is not None:
        notify_user(db, asker, kind=f"seekho_help{r.id}", urgent=False,
                    title="A volunteer is ready to help",
                    body=f"{user.address_as or user.name or 'A volunteer'} will help you with: {r.topic[:60]}",
                    payload={"open": "live_help", "help_id": r.id})
    return _help_view(r)


@router.post("/live-help/{help_id}/join")
def join(help_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    r = db.get(LiveHelpRequest, help_id)
    if r is None or user.id not in (r.user_id, r.volunteer_id) or r.status != "matched":
        raise HTTPException(404, "call not found")
    room = f"help-{r.id}"
    from python import config  # noqa: PLC0415

    token = livekit.join_token(room=room, identity=f"u{user.id}", name=user.address_as or user.name or "Guest",
                               speaker=True, host=False)
    return {"room": room, "token": token, "url": config.LIVEKIT_URL or None, "minutes": HELP_CALL_MINUTES}


@router.post("/live-help/{help_id}/complete")
def complete_help(help_id: int, request: Request, user: CurrentUser, db: DbSession):
    limit_write(request, user.id)
    r = db.get(LiveHelpRequest, help_id)
    if r is None or user.id not in (r.user_id, r.volunteer_id):
        raise HTTPException(404, "call not found")
    r.status, r.completed_at = "done", now()
    db.commit()
    livekit.end_room(f"help-{r.id}")
    return _help_view(r)
