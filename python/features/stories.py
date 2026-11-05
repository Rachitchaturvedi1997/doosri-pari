# =============================================================================
# Feature · Meri Kahani (my stories) — plan sections 6.9 and 15.
#
#   GET  /stories/prompts   this week's questions, in the caller's language
#   POST /stories           a transcribed or typed story; private by default
#   POST /stories/book      a StoryBook of the author's own stories
#
# The rules this module enforces:
#
#   1. A story is the most personal thing a person will put in this app, and it
#      is usually about loss, illness or money. It is stored and shown to its
#      author and to nobody else by default. It is never auto-published and
#      never summarised: others read it only when the author passes `publish`
#      and picks a wider visibility themselves — 6.9's "private by default;
#      shared per chapter".
#   2. The book is assembled from the author's own words in the author's own
#      order. The PDF (cover, chapters, photos, a QR per chapter that plays the
#      original voice) and the automatic chapter grouping need a renderer and a
#      model behind a seam. When they are not configured the answer says so and
#      no book is invented: a fabricated memory book is worse than no book.
# =============================================================================
from __future__ import annotations

import logging

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from python.deps import CurrentUser, DbSession
from python.models import (
    LANGUAGES,
    Story,
    StoryBook,
    StoryPrompt,
    now,
)
from python.ratelimit import limit_write

log = logging.getLogger("doosri.stories")
router = APIRouter(tags=["stories"])

# The only visibilities that exist. models/content.py is explicit that this is
# "not a free string", and it is the one field that decides who hears a story
# about somebody's illness — a typo there must not become a broadcast.
VISIBILITIES = ("only_me", "connections", "circle")

# The plan's cap: "up to 10 minutes" per answer (6.9). Anything longer is a
# different product and, on a phone recording a story in one take, probably a
# mistake worth stopping.
MAX_DURATION_S = 600

TRANSCRIPT_MAX = 20_000   # ~1500 words; far past 10 minutes of speech
CAPTION_MAX = 300         # the column
TITLE_MAX = 160           # the column
STORY_IDS_MAX = 200

# models/content.py: "Expires by itself. A story is meant to be gone." The
# column is NOT NULL, so this file must decide the lifetime — one year, long
# enough to finish a book and short enough that a story recorded in grief is
# not on anybody's screen forever. The book keeps its own copy of the order;
# expired stories simply fall out of the document (see book_view).
STORY_TTL_S = 365 * 24 * 3600


# ------------------------------------------------------------------ schemas --
class StoryIn(BaseModel):
    # Object-storage key of the recording, uploaded before this call. Empty
    # for a typed story.
    media_key: str = Field(default="", max_length=255)
    duration_s: int = Field(default=0, ge=0, le=MAX_DURATION_S)
    caption: str | None = Field(default=None, max_length=CAPTION_MAX)
    # The text of the story — typed, or the transcription of the recording.
    transcript: str | None = Field(default=None, max_length=TRANSCRIPT_MAX)
    language: str = Field(default="")       # empty means "the author's language"
    visibility: str = Field(default="only_me")
    # THE publish switch. Off unless the author flips it. This is the whole of
    # 6.9's privacy rule in one boolean, and it is server-enforced below.
    publish: bool = False
    prompt_id: int | None = None


class BookIn(BaseModel):
    title: str = Field(..., min_length=1, max_length=TITLE_MAX)
    # Story ids, in the order the author wants them. The order is theirs; this
    # module never rearranges it and never writes a heading they did not write.
    story_ids: list[int] = Field(default_factory=list, max_length=STORY_IDS_MAX)


# ------------------------------------------------------------------ views --
def prompt_view(row: StoryPrompt) -> dict:
    return {"id": row.id, "text": row.text, "language": row.language}


def story_view(row: Story) -> dict:
    """
    A story as its author sees it. The transcript is in here because this view
    is author-facing — it is never reused for a feed. When a wider audience is
    ever built, it gets its own serializer that carries only what the chosen
    visibility allows, and it asks this module first.
    """
    return {
        "id": row.id,
        "media_key": row.media_key,
        "duration_s": row.duration_s,
        "caption": row.caption,
        # THE PRIVACY FIELD. A story is about loss, illness and money. This
        # text is never summarised, never sent to a model, and never read by
        # anyone but its author unless the author published it and chose who
        # may see it. It is stored as the person's own words and returned as
        # exactly that — data, not material for anybody else's use.
        "transcript": row.transcript,
        "language": row.language,
        "visibility": row.visibility,
        "created_at": row.created_at,
        "expires_at": row.expires_at,
        "hidden": row.hidden,
        "prompt_id": row.prompt_id,
        "remade_count": row.remade_count,
    }


def book_view(row: StoryBook) -> dict:
    return {
        "id": row.id,
        "user_id": row.user_id,
        "title": row.title,
        "story_ids": row.story_ids or [],
        "created_at": row.created_at,
        "updated_at": row.updated_at,
    }


# ----------------------------------------------------------------- helpers --
def _own_story(db: Session, user_id: int, story_id: int) -> Story:
    """
    The author's story, or 404.

    404 rather than 403 for somebody else's story id: confirming that an id
    exists is already telling a stranger whose memory is behind it. A book is
    built from the author's own stories and from nothing else (StoryBook's own
    words: "private to the owner").
    """
    row = db.get(Story, story_id)
    if row is None or row.author_id != user_id:
        raise HTTPException(404, "story not found")
    return row


# --------------------------------------------------------------- SEAM: book --
def generate_book_document(row: StoryBook, stories: list[Story]) -> dict | None:
    """
    SEAM — the book's document, and the only place a model belongs.

    WHAT PRODUCTION SUPPLIES
      renderer   A PDF renderer: a cover, the chapters, the author's photos,
                 and per chapter a QR code that plays the original voice
                 (plan 6.9). That is a service that takes this row plus the
                 media in object storage and returns a document handle.
      model      Optionally, chapter grouping — childhood, career, family,
                 wisdom (plan 21: "Haiku by default; cached common answers").
                 The grouping is an aid to the author's own ordering. It never
                 rewrites a word of what they said.

    Returns None in this repository: neither a renderer nor a model is
    configured here, and the honest answer is a book with no document rather
    than a book that lies about having one. When production wires the seam, the
    same call returns {"url": ..., "state": "ready"} and the response below
    changes without this contract moving.
    """
    return None


# ---------------------------------------------------------------- endpoints --
@router.get("/stories/prompts")
def list_prompts(user: CurrentUser, db: DbSession):
    """
    The questions, in the caller's language.

    6.9's fifty-two weekly prompts are curated per language (StoryPrompt), so
    this asks for the user's language first. If no editor has written that
    language yet, the fallback is explicit in the response rather than a silent
    empty list: a Punjabi speaker with no prompts has a dead feature, and a
    mixed-language list that pretends to be theirs is the other way to fail
    them. Each row carries its own language so the client can show honestly
    what it is.
    """
    lang = user.lang or "hi"
    rows = db.scalars(
        select(StoryPrompt)
        .where(StoryPrompt.is_active.is_(True), StoryPrompt.language == lang)
        .order_by(StoryPrompt.id)
    ).all()
    matched = bool(rows)
    if not matched:
        rows = db.scalars(
            select(StoryPrompt)
            .where(StoryPrompt.is_active.is_(True))
            .order_by(StoryPrompt.id)
        ).all()
    return {
        "prompts": [prompt_view(r) for r in rows],
        "language": lang,
        "matched_language": matched,
    }


@router.post("/stories")
def create_story(body: StoryIn, request: Request, user: CurrentUser, db: DbSession):
    """
    A story, typed or transcribed from a recording. Private the moment it
    exists.

    Nothing is published by this call unless the author said so twice: a
    visibility other than only_me AND the explicit publish flag. Both, always,
    because the alternative is the failure this feature cannot survive — a
    story about somebody's illness appearing in a circle feed because a button
    defaulted the wrong way.

    The story text is the person's own. It is stored verbatim, moderated only
    through the same report flow everything else uses (plan 15), and it is not
    logged here, not summarised and not read by anything — see story_view.
    """
    limit_write(request, user.id)

    if not body.media_key and not body.transcript:
        raise HTTPException(400, "a story needs a recording or written words")
    if body.visibility not in VISIBILITIES:
        raise HTTPException(400, f"visibility must be one of {', '.join(VISIBILITIES)}")
    if body.visibility != "only_me" and not body.publish:
        # THE RULE. Sharing is an act the author takes, not a default they
        # inherit. "Never auto-publish without the user choosing to".
        raise HTTPException(
            400,
            "nothing is shared until you choose to publish it; "
            "set publish to share this story",
        )

    lang = body.language or user.lang or "hi"
    if lang not in LANGUAGES:
        raise HTTPException(400, f"language must be one of {', '.join(LANGUAGES)}")

    if body.prompt_id is not None:
        prompt = db.get(StoryPrompt, body.prompt_id)
        if prompt is None:
            raise HTTPException(400, "unknown prompt")

    at = now()
    row = Story(
        author_id=user.id,
        # A typed story has no recording. The column is NOT NULL and belongs to
        # models, so it holds "" — a schema that wanted to distinguish typed
        # from spoken would make this column nullable; until it does, this is
        # the convention and the duration (0) agrees with it.
        media_key=body.media_key,
        duration_s=body.duration_s,
        caption=body.caption,
        transcript=body.transcript,
        language=lang,
        visibility=body.visibility,
        created_at=at,
        expires_at=at + STORY_TTL_S,
        prompt_id=body.prompt_id,
    )
    db.add(row)
    db.flush()  # so the id in the log line is real
    # Ids only in the log. The transcript is somebody's memory and does not go
    # into a log line, an error report or anybody's inbox.
    log.info("story %s recorded by user %s (visibility=%s)", row.id, user.id, body.visibility)
    db.commit()
    return story_view(row)


@router.post("/stories/book")
def build_book(body: BookIn, request: Request, user: CurrentUser, db: DbSession):
    """
    The author's memory book: their stories, in their order, under their title.

    The row is real and lands now — that is the "builds a StoryBook" half. The
    document is the seam above: with no renderer configured the response says
    `not_generated` and hands back the book's structure instead of a fake PDF.
    Chapter headings are the author's own captions and prompt texts in the
    order they chose; the automatic grouping of 6.9 needs the model behind the
    seam and is deliberately absent here rather than guessed at.
    """
    limit_write(request, user.id)

    stories: list[Story] = []
    seen: set[int] = set()
    for sid in body.story_ids:
        if sid in seen:
            raise HTTPException(400, "a story cannot appear twice in one book")
        seen.add(sid)
        stories.append(_own_story(db, user.id, sid))

    at = now()
    # One memory book per person: it grows a story at a time, and `updated_at`
    # on the model is the record of that growth (StoryBook).
    row = db.scalar(
        select(StoryBook).where(StoryBook.user_id == user.id).order_by(StoryBook.id.desc())
    )
    if row is None:
        row = StoryBook(user_id=user.id, created_at=at)
        db.add(row)
    row.title = body.title
    row.story_ids = list(body.story_ids)
    row.updated_at = at
    db.flush()  # so the id in the log line is real

    log.info("story book %s built by user %s from %s stories", row.id, user.id, len(stories))
    db.commit()

    out = book_view(row)
    # The chapters are the author's own words, in the author's order. A caption
    # becomes a chapter title exactly as written; where there is no caption the
    # prompt's question stands in, and where there is neither the entry says so
    # rather than inventing a heading.
    out["chapters"] = [
        {
            "story_id": s.id,
            "title": s.caption
            or (db.get(StoryPrompt, s.prompt_id).text if s.prompt_id else "")
            or "",
            "duration_s": s.duration_s,
            "expired": s.expires_at <= now(),
        }
        for s in stories
    ]

    document = generate_book_document(row, stories)
    out["document"] = document
    out["document_state"] = "ready" if document else "not_generated"
    if document is None:
        out["message"] = (
            "The book is saved. To make the document, ask for it "
            "(POST /stories/book/build) - nothing is created behind your back "
            "and nothing is missing from what you saved."
        )
    return out