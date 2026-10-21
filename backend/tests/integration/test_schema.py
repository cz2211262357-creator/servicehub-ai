"""Requires disposable, real MySQL and Redis; never substitutes SQLite/fakeredis."""

import os
import subprocess
from pathlib import Path

import pytest
from redis.asyncio import Redis
from sqlalchemy import inspect

ROOT = Path(__file__).resolve().parents[3]
DATABASE = os.environ.get(
    "SERVICEHUB_TEST_DATABASE_URL",
    "mysql+asyncmy://servicehub:dev-password@127.0.0.1:3306/servicehub_test",
)
REDIS = os.environ.get("SERVICEHUB_TEST_REDIS_URL", "redis://127.0.0.1:6380/0")
pytestmark = pytest.mark.integration


@pytest.fixture(scope="session")
def migrated_schema():
    assert DATABASE.rsplit("/", 1)[-1] == "servicehub_test", (
        "Only the disposable test database is allowed"
    )
    process_env = dict(os.environ, SERVICEHUB_DATABASE_URL=DATABASE)
    for _ in range(2):
        proc = subprocess.run(
            ["uv", "run", "alembic", "-c", "backend/alembic.ini", "upgrade", "head"],
            cwd=ROOT,
            env=process_env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        assert proc.returncode == 0, proc.stderr


@pytest.fixture
async def schema(migrated_schema):
    from sqlalchemy.ext.asyncio import create_async_engine

    engine = create_async_engine(DATABASE, pool_size=4, max_overflow=0)
    try:
        yield engine
    finally:
        await engine.dispose()


async def test_initial_migration_is_repeatable_and_has_tenant_foreign_keys(schema):
    async with schema.connect() as conn:
        tables = await conn.run_sync(lambda sync: inspect(sync).get_table_names())
        assert {"users", "conversations", "messages", "orders", "ai_runs", "jobs"} <= set(tables)
        keys = await conn.run_sync(lambda sync: inspect(sync).get_foreign_keys("messages"))
        assert any(key["constrained_columns"] == ["tenant_id", "conversation_id"] for key in keys)
        indexes = await conn.run_sync(lambda sync: inspect(sync).get_indexes("knowledge_chunks"))
        assert any(index["name"] == "ft_knowledge_text" for index in indexes)


async def test_cross_tenant_reference_is_rejected(schema):
    from servicehub.auth.models import Tenant, User
    from servicehub.conversations.models import Conversation
    from sqlalchemy.exc import IntegrityError
    from sqlalchemy.ext.asyncio import async_sessionmaker

    sessions = async_sessionmaker(schema, expire_on_commit=False)
    async with sessions() as session:
        async with session.begin():
            a, b = Tenant(name="schema-a"), Tenant(name="schema-b")
            session.add_all([a, b])
            await session.flush()
            user = User(
                tenant_id=a.id,
                email="isolation@example.test",
                display_name="Isolation",
                password_hash="not-a-login",
                role="customer",
            )
            session.add(user)
            await session.flush()
            session.add(Conversation(tenant_id=b.id, customer_id=user.id))
            with pytest.raises(IntegrityError):
                await session.flush()
            await session.rollback()


async def test_redis_search_and_streams_commands_use_real_server():
    client = Redis.from_url(REDIS, decode_responses=True, max_connections=8)
    try:
        assert await client.ping()
        assert isinstance(await client.execute_command("FT._LIST"), list)
        key = "servicehub:test:schema-stream"
        message_id = await client.xadd(key, {"job_id": "1"})
        items = await client.xrange(key, min=message_id, max=message_id)
        assert items[0][1]["job_id"] == "1"
        await client.delete(key)
    finally:
        await client.aclose()


@pytest.mark.parametrize(
    "collision", ["client_message", "final_ai_reply", "ticket_key", "ticket_draft", "job_key"]
)
async def test_duplicate_business_results_are_rejected_by_mysql(schema, collision):
    from datetime import UTC, datetime, timedelta
    from decimal import Decimal

    import servicehub.db.metadata  # noqa: F401 -- registers all domain tables
    from servicehub.after_sales.models import AfterSaleTicket, TicketDraft
    from servicehub.ai.models import AIRun
    from servicehub.auth.models import Tenant, User
    from servicehub.conversations.models import Conversation, ConversationEvent, Message
    from servicehub.jobs.models import Job
    from servicehub.orders.models import Order
    from sqlalchemy.exc import IntegrityError
    from sqlalchemy.ext.asyncio import async_sessionmaker

    sessions = async_sessionmaker(schema, expire_on_commit=False)
    async with sessions() as session, session.begin():
        tenant = Tenant(name="uniqueness-test")
        session.add(tenant)
        await session.flush()
        user = User(
            tenant_id=tenant.id,
            email="unique@example.test",
            display_name="Unique",
            password_hash="not-a-login",
            role="customer",
        )
        session.add(user)
        await session.flush()
        order = Order(
            tenant_id=tenant.id,
            customer_id=user.id,
            order_number="UNIQUE-01",
            total_amount=Decimal("199.90"),
        )
        conversation = Conversation(tenant_id=tenant.id, customer_id=user.id)
        job = Job(
            tenant_id=tenant.id, kind="ai_reply", business_key="test-generation-1", payload={}
        )
        session.add_all([order, conversation, job])
        await session.flush()
        event = ConversationEvent(
            tenant_id=tenant.id,
            conversation_id=conversation.id,
            seq=1,
            kind="message.created",
            payload={},
        )
        drafts = [
            TicketDraft(
                tenant_id=tenant.id,
                customer_id=user.id,
                order_id=order.id,
                kind="RETURN",
                reason="商品损坏",
                expires_at=datetime.now(UTC) + timedelta(minutes=15),
            )
            for _ in range(2)
        ]
        session.add_all([event, *drafts])
        await session.flush()
        run = AIRun(
            tenant_id=tenant.id,
            conversation_id=conversation.id,
            trigger_event_seq=1,
            generation_epoch=1,
            job_id=job.id,
        )
        session.add(run)
        await session.flush()

        if collision in ("client_message", "final_ai_reply"):
            args = dict(
                tenant_id=tenant.id,
                conversation_id=conversation.id,
                author_role="ai" if collision == "final_ai_reply" else "customer",
                body="hello",
                client_message_id="client-duplicate" if collision == "client_message" else None,
                ai_run_id=run.id if collision == "final_ai_reply" else None,
            )
            first, second = Message(**args), Message(**args)
        elif collision in ("ticket_key", "ticket_draft"):
            args = dict(
                tenant_id=tenant.id,
                customer_id=user.id,
                order_id=order.id,
                kind="RETURN",
                reason="商品损坏",
                request_hash="a" * 64,
            )
            first = AfterSaleTicket(**args, draft_id=drafts[0].id, idempotency_key="same-key")
            second = AfterSaleTicket(
                **args,
                draft_id=drafts[1].id if collision == "ticket_key" else drafts[0].id,
                idempotency_key="same-key" if collision == "ticket_key" else "another-key",
            )
        else:
            first = Job(tenant_id=tenant.id, kind="document", business_key="same", payload={})
            second = Job(tenant_id=tenant.id, kind="document", business_key="same", payload={})
        session.add(first)
        await session.flush()
        session.add(second)
        with pytest.raises(IntegrityError):
            await session.flush()
        await session.rollback()
