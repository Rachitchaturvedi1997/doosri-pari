# =============================================================================
# Load the safe-to-ship seed content into the database.
#
#     python -m python.tools.seed_content                  # everything below
#     python -m python.tools.seed_content --only lessons,prompts
#     python -m python.tools.seed_content --activate-programmes
#
# Idempotent: re-running adds what is missing and touches nothing that exists, so
# an editor's changes in the admin console are never overwritten.
#
# YOGA PROGRAMMES ARE LOADED INACTIVE. Plan section 20 requires a certified yoga
# therapist and a physiotherapist to review every programme before release. Run
# with --activate-programmes only after that has happened; until then the Yog
# Kendra shows nothing rather than an unreviewed exercise routine.
# =============================================================================
from __future__ import annotations

import argparse
import logging

from sqlalchemy import select

from python.data import seed_content as sc
from python.data import seed_kendra as sk
from python.deps import SessionLocal, engine
from python.models import (
    Base,
    DailyThought,
    Department,
    KendraProgramme,
    Lesson,
    Pose,
    ScamAlert,
    StoryPrompt,
    YatraPlace,
)

log = logging.getLogger("doosri.seed")

# Places for the yatra planner: name, state, path. Accessibility, booking links and
# summaries are deliberately EMPTY - they must come from an editor with a source
# (models/extras.py YatraPlace), and the app shows "not added yet" rather than a
# guess about whether a temple has a lift.
PLACES = [
    ("tirupati-balaji", "Tirumala Tirupati", "AP", "hindu"),
    ("vaishno-devi", "Mata Vaishno Devi", "JK", "hindu"),
    ("kashi-vishwanath", "Kashi Vishwanath, Varanasi", "UP", "hindu"),
    ("somnath", "Somnath", "GJ", "hindu"),
    ("rameswaram", "Rameswaram", "TN", "hindu"),
    ("puri-jagannath", "Jagannath Puri", "OD", "hindu"),
    ("dwarka", "Dwarka", "GJ", "hindu"),
    ("kedarnath", "Kedarnath", "UK", "hindu"),
    ("badrinath", "Badrinath", "UK", "hindu"),
    ("golden-temple", "Harmandir Sahib, Amritsar", "PB", "sikh"),
    ("ajmer-sharif", "Ajmer Sharif Dargah", "RJ", "muslim"),
    ("bodh-gaya", "Bodh Gaya", "BR", "buddhist"),
    ("sarnath", "Sarnath", "UP", "buddhist"),
    ("shravanabelagola", "Shravanabelagola", "KA", "jain"),
    ("velankanni", "Velankanni", "TN", "christian"),
]


def _slug_exists(db, model, slug: str) -> bool:
    return db.scalar(select(model.id).where(model.slug == slug)) is not None


def seed_departments(db) -> int:
    n = 0
    for name, service, aliases in sc.DEPARTMENTS:
        if db.scalar(select(Department.id).where(Department.name == name)) is None:
            db.add(Department(name=name, service_type=service, aliases=aliases))
            n += 1
    return n


def seed_kendra(db, activate: bool) -> dict:
    poses = programmes = 0
    for slug, name, instr, hold, reps, stand, floor, contra, modif, breath in sk.POSES:
        if not _slug_exists(db, Pose, slug):
            db.add(Pose(slug=slug, name=name, instruction=instr, hold_seconds=hold, reps=reps,
                        requires_standing=stand, requires_floor=floor, contraindications=contra,
                        modification=modif, breathing_cue=breath, illustration_key=slug))
            poses += 1
    db.flush()
    by_slug = {p.slug: p.id for p in db.scalars(select(Pose)).all()}
    for slug, title, desc, kind, minutes, pose_slugs, targets in sk.PROGRAMMES:
        row = db.scalar(select(KendraProgramme).where(KendraProgramme.slug == slug))
        if row is None:
            db.add(KendraProgramme(
                slug=slug, title=title, description=desc, kind=kind, total_minutes=minutes,
                pose_ids=[by_slug[s] for s in pose_slugs if s in by_slug], targets=targets,
                languages=["en", "hi"], is_active=activate,
            ))
            programmes += 1
        elif activate:
            row.is_active = True
    return {"poses": poses, "programmes": programmes, "activated": activate}


def seed_prompts(db) -> int:
    n = 0
    for lang, items in (("en", sc.PROMPTS_EN), ("hi", sc.PROMPTS_HI)):
        for text in items:
            if db.scalar(select(StoryPrompt.id).where(StoryPrompt.text == text)) is None:
                db.add(StoryPrompt(text=text, language=lang))
                n += 1
    return n


def seed_lessons(db) -> int:
    n = 0
    for pos, (cat, title, summary, minutes, steps, quiz, badge) in enumerate(sc.LESSONS, start=1):
        slug = title.lower().replace(" ", "-").replace(":", "").replace(",", "")[:80]
        if _slug_exists(db, Lesson, slug):
            continue
        db.add(Lesson(
            slug=slug, title=title, summary=summary, category=cat, minutes=minutes, language="en",
            steps=[{"title": t, "text": x} for t, x in steps],
            quiz=[{"q": q, "options": o, "answer": a, "why": w} for q, o, a, w in quiz],
            badge=badge, position=pos,
        ))
        n += 1
    return n


def seed_scam_alerts(db) -> int:
    n = 0
    for cat, title, body, lang, markers, domains, sev in sc.SCAM_ALERTS:
        if db.scalar(select(ScamAlert.id).where(ScamAlert.title == title, ScamAlert.language == lang)) is None:
            db.add(ScamAlert(category=cat, title=title, body=body, language=lang, markers=markers,
                             domains=domains, severity=sev))
            n += 1
    return n


def seed_thoughts(db) -> int:
    n = 0
    for text, author, source, lang in sc.THOUGHTS:
        if db.scalar(select(DailyThought.id).where(DailyThought.text == text)) is None:
            db.add(DailyThought(text=text, author=author, source_ref=source, language=lang))
            n += 1
    return n


def seed_places(db) -> int:
    n = 0
    for slug, name, state, path in PLACES:
        if not _slug_exists(db, YatraPlace, slug):
            db.add(YatraPlace(slug=slug, name=name, state_code=state, path=path, accessibility={},
                              source_ref="details to be added by an editor, with a source"))
            n += 1
    return n


SEEDS = {
    "departments": seed_departments,
    "prompts": seed_prompts,
    "lessons": seed_lessons,
    "scam_alerts": seed_scam_alerts,
    "thoughts": seed_thoughts,
    "places": seed_places,
}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="comma list of: " + ", ".join(list(SEEDS) + ["kendra"]))
    ap.add_argument("--activate-programmes", action="store_true",
                    help="switch the yoga programmes ON (only after therapist review)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO)

    Base.metadata.create_all(bind=engine)
    want = set(args.only.split(",")) if args.only else set(SEEDS) | {"kendra"}
    unknown = want - set(SEEDS) - {"kendra"}
    if unknown:
        ap.error(f"unknown: {', '.join(sorted(unknown))}")

    with SessionLocal() as db:
        out = {}
        for name, fn in SEEDS.items():
            if name in want:
                out[name] = fn(db)
        if "kendra" in want:
            out["kendra"] = seed_kendra(db, args.activate_programmes)
        db.commit()
    for k, v in out.items():
        print(f"{k}: {v}")
    if "kendra" in want and not args.activate_programmes:
        print("note: yoga programmes are INACTIVE until reviewed; then run with --activate-programmes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
