# =============================================================================
# Generate a migration, correctly.
#
# `alembic revision --autogenerate` is right about the schema and wrong about
# three things, every time:
#
#   1. It renders a `.with_variant(JSONB(), "postgresql")` column as
#      `postgresql.JSONB(astext_type=Text())` with `Text` unbound — a NameError
#      the moment upgrade() runs. This is not a rare edge: it is what it does
#      to every JSON column in the project.
#   2. It renders a custom TypeDecorator as a dotted reference to the model
#      module (`python.models.sahayak.Embedding()`), which the migration file
#      does not import. It is also the wrong thing on principle — a migration
#      that imports the model it is migrating stops being a snapshot of the
#      schema as it was, and silently starts describing today's schema instead.
#   3. It emits `create_foreign_key(None, ...)` and the matching
#      `drop_constraint(None, ...)` whenever the target column has no named
#      constraint, and Alembic's own warning says this will fail as rendered.
#
# Rather than remember those three every time, this script runs the
# autogenerate and then applies them. Run it instead of alembic directly:
#
#     python/.venv/Scripts/python -m python.tools.gen_migration -m "add health"
#
# It builds a throw-away SQLite database at the current head first, so
# autogenerate has something to diff against, and removes it afterwards. It
# never touches the real database — passing the wrong DATABASE_URL to a
# migration tool is how people lose data.
# =============================================================================
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
VERSIONS = REPO / "python" / "migrations" / "versions"

# The frozen copy of the embedding type, written into the generated migration.
# Kept in sync with python/models/sahayak.py by hand; a test asserts the two
# agree, because a drift here means the migration and the models describe
# different columns and only Postgres would notice.
EMBEDDING_SHIM = '''

class Embedding(TypeDecorator):
    """
    A frozen copy of python/models/sahayak.py's Embedding.

    Local rather than imported so this migration keeps describing the schema as
    it was when it was written. DIM is the part that matters: a 768-dimension
    embedding model in a 1024 column fails at query time, not migration time.
    """

    impl = sa.JSON
    cache_ok = True
    DIM = 1024

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            from pgvector.sqlalchemy import Vector

            return dialect.type_descriptor(Vector(self.DIM))
        return dialect.type_descriptor(sa.JSON())

'''


def current_head() -> str:
    """The revision alembic would be at, by asking it."""
    out = subprocess.run(
        [sys.executable, "-m", "alembic", "heads"],
        cwd=REPO, capture_output=True, text=True, env=_env(),
    )
    heads = [ln.strip() for ln in out.stdout.splitlines() if "(head)" in ln]
    if not heads:
        raise SystemExit("could not read the current head from alembic")
    return heads[0].split()[0]


def _env(database_url: str | None = None) -> dict:
    env = dict(os.environ)
    if database_url:
        env["DATABASE_URL"] = database_url
    # Autogenerate has to be able to write a migration, and the real secret is
    # not needed to diff a schema.
    env.setdefault("SECRET_KEY", "gen-migration-not-a-real-key")
    return env


def scratch_db_at(revision: str) -> tuple[str, Path]:
    """Build a throw-away database sitting at `revision`."""
    tmp = Path(tempfile.mkdtemp(prefix="doosri-gen-"))
    url = f"sqlite:///{(tmp / 'base.db').as_posix()}"
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", revision],
        cwd=REPO, capture_output=True, text=True, env=_env(url),
    )
    if result.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        raise SystemExit(f"could not build the scratch database:\n{result.stderr}")
    return url, tmp


def fix_generated(path: Path) -> list[str]:
    """Apply the three corrections. Returns what was changed, for the log."""
    text = path.read_text(encoding="utf-8")
    applied: list[str] = []

    # 1. unbound Text()
    fixed = text.replace("postgresql.JSONB(astext_type=Text())",
                         "postgresql.JSONB(astext_type=sa.Text())")
    if fixed != text:
        applied.append("bound Text() in the JSONB variants")
        text = fixed

    # 2. the dotted TypeDecorator reference
    dotted = set(re.findall(r"\b\w+\.models\.\w+\.(\w+)\(\)", text))
    for name in dotted:
        text = re.sub(rf"\b\w+\.models\.\w+\.{name}\(\)", f"{name}()", text)
    if dotted:
        applied.append(f"de-qualified the custom type(s): {', '.join(sorted(dotted))}")
        text = text.replace(
            "from sqlalchemy.dialects import postgresql\n",
            "from sqlalchemy.dialects import postgresql\n"
            "from sqlalchemy.types import TypeDecorator\n",
            1,
        )
        text = text.replace(
            "\n\ndef upgrade() -> None:",
            f"\n{EMBEDDING_SHIM}\ndef upgrade() -> None:",
            1,
        )

    # 3. the unnamed foreign key, and its matching drop.
    text, names = _name_foreign_keys(text)
    if names:
        applied.append(f"named {len(names)} unnamed foreign key(s): {', '.join(names)}")

    path.write_text(text, encoding="utf-8")
    return applied


def _name_foreign_keys(text: str) -> tuple[str, list[str]]:
    """
    Give every `create_foreign_key(None, ...)` a real name, and the matching
    `drop_constraint(None, ...)` the same one.

    Line-based rather than regex-over-the-whole-file, because the constraint
    name has to be derived from the table being ALTERed — which appears in a
    `with op.batch_alter_table(...)` line above, sometimes many lines above, and
    is not on the same line as the call. A whole-file regex would name it after
    the *referenced* table instead, giving `fk_departments_department_id` for a
    foreign key on `users`.
    """
    lines = text.split("\n")
    out: list[str] = []
    current_table: str | None = None
    # constraint name per altered table, so the drop can find it again
    created: dict[str, str] = {}
    names: list[str] = []

    for line in lines:
        if m := re.search(r"(?:op|batch_op)\.(?:batch_)?alter_table\(\s*'(\w+)'", line):
            current_table = m.group(1)

        if m := re.search(r"create_foreign_key\(\s*None,\s*'(\w+)',\s*\[([^\]]*)\]", line):
            ref_table, cols = m.group(1), m.group(2)
            column = re.search(r"'(\w+)'", cols).group(1)
            name = f"fk_{current_table or 'x'}_{column}"
            created[current_table or "x"] = name
            names.append(name)
            indent = line[: len(line) - len(line.lstrip())]
            out.append(
                f"{indent}batch_op.create_foreign_key(\n"
                f"{indent}    '{name}', '{ref_table}', [{cols}], ['id'], ondelete='SET NULL',\n"
                f"{indent})"
            )
            continue

        if "drop_constraint(None" in line:
            table = current_table or "x"
            name = created.get(table)
            if name is None:
                # Nothing in this block created one; the FK predates the
                # migration, so guess the same shape rather than leave a None
                # that is guaranteed to fail.
                name = f"fk_{table}_foreign"
                created[table] = name
                names.append(name)
            out.append(line.replace("drop_constraint(None", f"drop_constraint('{name}'"))
            continue

        out.append(line)

    # Alembic writes a three-line warning above the drop; it is now untrue.
    cleaned: list[str] = []
    skip = 0
    for line in out:
        if skip:
            skip -= 1
            continue
        if "WARNING: constraint name is None" in line:
            skip = 2
            continue
        cleaned.append(line)

    return "\n".join(cleaned), names


def rename(path: Path, revision_id: str, message: str) -> Path:
    """Give it a readable name and the sequential id the project uses."""
    slug = re.sub(r"[^a-z0-9]+", "_", message.lower()).strip("_")[:40]
    target = VERSIONS / f"{revision_id}_{slug}.py"
    shutil.move(str(path), str(target))
    return target


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-m", "--message", required=True, help="what this migration adds")
    ap.add_argument(
        "--from", dest="from_rev", default=None,
        help="revision to diff against (default: the current head)",
    )
    args = ap.parse_args()

    base = args.from_rev or current_head()
    url, tmp = scratch_db_at(base)
    try:
        before = set(VERSIONS.glob("*.py"))
        result = subprocess.run(
            [sys.executable, "-m", "alembic", "revision", "--autogenerate", "-m", args.message],
            cwd=REPO, capture_output=True, text=True, env=_env(url),
        )
        if result.returncode != 0:
            print(result.stderr, file=sys.stderr)
            return 1

        new = [p for p in VERSIONS.glob("*.py") if p not in before]
        if len(new) != 1:
            print(f"expected exactly one new file, got {new}", file=sys.stderr)
            return 1

        for note in fix_generated(new[0]):
            print(f"  fixed: {note}")

        # The next sequential id, which is what the rest of the tree is named in.
        nums = [
            int(m.group(1))
            for p in VERSIONS.glob("*.py")
            if (m := re.match(r"^(\d+)_", p.name))
        ]
        new_id = f"{max(nums, default=0) + 1:04d}"
        final = rename(new[0], new_id, args.message)

        # Point the revision id at the sequential one too, so `alembic history`
        # and the filename agree.
        text = final.read_text(encoding="utf-8")
        old_id = re.search(r"revision: str = '(\w+)'", text).group(1)
        text = text.replace(f"revision: str = '{old_id}'", f"revision: str = '{new_id}'", 1)
        final.write_text(text, encoding="utf-8")

        print(f"  wrote {final.name} (revision {new_id}, from {base})")
        print("\nReview it before committing. The fixes above are mechanical;")
        print("anything autogenerate got wrong about intent, it still has.")
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
