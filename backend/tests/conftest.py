import os
import subprocess
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
import pytest
from pwdlib import PasswordHash
from servicehub.auth.models import Tenant, User
from servicehub.core.config import Settings
from servicehub.db.metadata import metadata
from servicehub.main import create_app
from sqlalchemy import delete

PASSWORD = "Demo-password-2026!"
DATABASE = os.environ.get(
    "SERVICEHUB_TEST_DATABASE_URL",
    "mysql+asyncmy://servicehub:dev-password@127.0.0.1:3306/servicehub_test",
)
REDIS = os.environ.get("SERVICEHUB_TEST_REDIS_URL", "redis://127.0.0.1:6380/0")


@pytest.fixture(scope="session")
def database_ready():
    assert DATABASE.rsplit("/", 1)[-1] == "servicehub_test"
    result = subprocess.run(
        ["uv", "run", "alembic", "-c", "backend/alembic.ini", "upgrade", "head"],
        cwd=Path(__file__).resolve().parents[2],
        env=dict(os.environ, SERVICEHUB_DATABASE_URL=DATABASE),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    assert result.returncode == 0, result.stderr


@pytest.fixture(scope="session")
def password_hash():
    return PasswordHash.recommended().hash(PASSWORD)


@pytest.fixture
async def env(database_ready, password_hash):
    settings = Settings(
        _env_file=None,
        environment="test",
        database_url=DATABASE,
        redis_url=REDIS,
        upload_dir=Path(".cache/test-uploads") / uuid4().hex,
        model_mode="demo",
    )
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        assert REDIS in ("redis://127.0.0.1:6380/0", "redis://localhost:6380/0"), (
            "Only the dedicated test Redis may be cleared"
        )
        await app.state.redis.flushdb()
        async with app.state.sessions() as session, session.begin():
            tenants = [Tenant(name=f"test-{uuid4().hex[:12]}") for _ in range(2)]
            session.add_all(tenants)
            await session.flush()
            users = {}
            for name, role, tenant in [
                ("admin", "admin", tenants[0]),
                ("agent", "agent", tenants[0]),
                ("agent2", "agent", tenants[0]),
                ("customer", "customer", tenants[0]),
                ("customer2", "customer", tenants[0]),
                ("outsider", "customer", tenants[1]),
            ]:
                users[name] = User(
                    tenant_id=tenant.id,
                    email=f"{name}@example.com",
                    display_name=name,
                    password_hash=password_hash,
                    role=role,
                )
            session.add_all(users.values())
            await session.flush()
        clients = []
        bundle = SimpleNamespace(
            app=app,
            settings=settings,
            users=users,
            tenants=tenants,
            sessions=app.state.sessions,
            redis=app.state.redis,
            clients=clients,
            password=PASSWORD,
        )
        try:
            yield bundle
        finally:
            for client in clients:
                await client.aclose()
            ids = [tenant.id for tenant in tenants]
            async with app.state.sessions() as session, session.begin():
                for table in reversed(metadata.sorted_tables):
                    if "tenant_id" in table.c:
                        await session.execute(delete(table).where(table.c.tenant_id.in_(ids)))
                await session.execute(delete(Tenant).where(Tenant.id.in_(ids)))
            await app.state.redis.flushdb()


@pytest.fixture
def login(env):
    async def connect(name="customer"):
        client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=env.app, raise_app_exceptions=False),
            base_url="http://testserver",
            headers={"origin": "http://localhost:5173"},
        )
        env.clients.append(client)
        user = env.users[name]
        result = await client.post(
            "/api/v1/auth/login",
            json={"tenant_id": user.tenant_id, "email": user.email, "password": PASSWORD},
        )
        assert result.status_code == 200, result.text
        client.headers["x-csrf-token"] = result.json()["csrf_token"]
        return client

    return connect
