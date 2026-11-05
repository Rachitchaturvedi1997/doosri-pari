"""Alembic environment.

The database URL comes from python.config, not from alembic.ini, so there is one
place it is defined and a migration cannot be pointed at the wrong database by
editing a config file.

Run from the repository root:

    python/.venv/Scripts/alembic upgrade head
"""
from alembic import context
from sqlalchemy import engine_from_config, pool

from python import config
from python.models import Base

target_metadata = Base.metadata


def _url() -> str:
    # alembic.ini leaves sqlalchemy.url blank on purpose; config owns it.
    return context.config.get_main_option("sqlalchemy.url") or config.DATABASE_URL


def run_migrations_offline() -> None:
    """Emit SQL to stdout without connecting, to review before it runs."""
    context.configure(
        url=_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        # SQLite cannot ALTER most columns, so batch mode rewrites the table
        # instead. A no-op on Postgres, and the tests run on SQLite.
        render_as_batch=True,
        compare_type=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    section = context.config.get_section(context.config.config_ini_section) or {}
    section["sqlalchemy.url"] = _url()

    connectable = engine_from_config(
        section, prefix="sqlalchemy.", poolclass=pool.NullPool
    )
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=True,
            compare_type=True,
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
