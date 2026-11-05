"""
The migration is the real schema. python/models.py is what the application
*believes* the schema is, and a hand-written migration that has never been run
against a database proves nothing.

So this file runs `alembic upgrade head` on a throw-away SQLite file and then
asks Alembic to diff the result against the models. If a column is added to a
model and forgotten in a migration, or vice versa, this fails.

If you change a model, add a migration and let this tell you it worked. If you
change a migration, this tells you it still matches.
"""
import tempfile
from pathlib import Path

import pytest
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect

from python import config
from python.models import Base

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture()
def migrated_db(monkeypatch):
    """A database built by the migrations alone — no create_all anywhere."""
    tmp = tempfile.mkdtemp(prefix="doosri-migrate-")
    url = f"sqlite:///{(Path(tmp) / 'migrated.db').as_posix()}"

    # env.py reads config.DATABASE_URL at call time, so patching it here is
    # enough to point the migration at the temporary file.
    monkeypatch.setattr(config, "DATABASE_URL", url)

    alembic_cfg = Config(str(REPO_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(REPO_ROOT / "python" / "migrations"))

    command.upgrade(alembic_cfg, "head")

    engine = create_engine(url)
    try:
        yield engine
    finally:
        engine.dispose()


def test_the_migration_runs_and_creates_every_table(migrated_db):
    tables = set(inspect(migrated_db).get_table_names())
    expected = set(Base.metadata.tables)
    assert expected <= tables, f"migration did not create: {expected - tables}"


def test_the_migration_matches_the_models(migrated_db):
    """
    Diff the migrated schema against the ORM models.

    Server defaults are excluded: SQLite reports them differently from the way
    Alembic wrote them, which produces differences that mean nothing. A missing
    column or a changed type is a real difference and will fail.
    """
    with migrated_db.connect() as connection:
        diffs = compare_metadata(
            MigrationContext.configure(connection, opts={"compare_type": True}),
            Base.metadata,
        )

    # Drop differences that are only about server defaults.
    meaningful = [
        d for d in diffs
        if not (isinstance(d, dict) and "modify_default" in d)
    ]

    assert not meaningful, "migrated schema differs from the models: " + "\n".join(
        str(d) for d in meaningful
    )


def test_the_migration_can_be_undone(migrated_db):
    """A migration you cannot reverse is a migration you cannot deploy safely."""
    alembic_cfg = Config(str(REPO_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(REPO_ROOT / "python" / "migrations"))

    command.downgrade(alembic_cfg, "base")

    engine = create_engine(migrated_db.url)
    try:
        remaining = {
            t for t in inspect(engine).get_table_names()
            # alembic keeps its own bookkeeping table; that is expected.
            if t != "alembic_version"
        }
        assert remaining == set()
    finally:
        engine.dispose()
