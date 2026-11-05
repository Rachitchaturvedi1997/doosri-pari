# Test setup: a throw-away SQLite database, configured through environment
# variables BEFORE the app is imported. Mirrors the Moholla backend's conftest.
import os
import tempfile

import pytest

_tmp = tempfile.mkdtemp(prefix="doosri-test-")
os.environ.update({
    "ENV": "test",
    "DATABASE_URL": f"sqlite:///{_tmp}/test.db".replace("\\", "/"),
    "SECRET_KEY": "test-secret-key-not-for-production",
    "REDIS_URL": "",
    "LOG_LEVEL": "WARNING",
    # On, so the two tests that specifically exercise the dev header can. The
    # guard in config.py refuses this outside development and test, so setting
    # it here is not the same as shipping it.
    "ALLOW_DEV_HEADER_AUTH": "1",
})

from fastapi.testclient import TestClient  # noqa: E402

from python.deps import SessionLocal, engine  # noqa: E402
from python.models import Base, CheckIn, CheckInSettings, Guardian, User  # noqa: E402
from python.main import app  # noqa: E402
from python.security import issue_access_token  # noqa: E402


@pytest.fixture(scope="session", autouse=True)
def _schema():
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def db():
    s = SessionLocal()
    try:
        yield s
    finally:
        s.close()


@pytest.fixture()
def user(db):
    """A pensioner in Uttar Pradesh, on the 09:00 three-hour default window."""
    u = User(
        phone_e164="+919000000001",
        name="Sharma",
        address_as="Sharma ji",
        lang="hi",
        service_type="central_civil",
        state_code="UP",
        tier="T0",
        time_zone="Asia/Kolkata",
    )
    db.add(u)
    db.flush()
    db.add(CheckInSettings(user_id=u.id))
    db.commit()
    yield u
    # The schema is session-scoped but the users are per-test, so clean up or
    # the unique phone number trips the second test onwards.
    db.query(CheckIn).filter(CheckIn.user_id == u.id).delete()
    db.query(Guardian).filter(Guardian.user_id == u.id).delete()
    db.query(CheckInSettings).filter(CheckInSettings.user_id == u.id).delete()
    db.query(User).filter(User.id == u.id).delete()
    db.commit()


@pytest.fixture()
def auth(user):
    """
    A real bearer token, so the tests exercise the path the app actually uses.

    This replaced an `X-User-Id` header fixture. The header is still available
    as `dev_auth` for the two things that need it — testing that the header is
    ignored when it should be, and testing that it works when it is switched on
    — but a test that means "a signed-in pensioner" should not be reaching for
    a bypass.
    """
    return {"Authorization": f"Bearer {issue_access_token(user.id, user.tier)}"}


@pytest.fixture()
def dev_auth(user):
    """The development-only header, for the tests that are about the header."""
    return {"X-User-Id": str(user.id)}


@pytest.fixture()
def admin(db):
    """A staff account, for the /admin routes."""
    a = User(
        phone_e164="+919000000099",
        name="Admin",
        role="admin",
        tier="T3",
        lang="en",
    )
    db.add(a)
    db.commit()
    yield a
    db.query(User).filter(User.id == a.id).delete()
    db.commit()


@pytest.fixture()
def admin_auth(admin):
    return {"Authorization": f"Bearer {issue_access_token(admin.id, admin.tier)}"}
