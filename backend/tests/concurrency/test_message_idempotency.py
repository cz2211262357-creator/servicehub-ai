import asyncio

import pytest
from servicehub.conversations.models import Message
from servicehub.jobs.models import Job, OutboxEvent
from sqlalchemy import func, select

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


async def test_twenty_retries_save_one_message_and_one_ai_job(env, login):
    client = await login()
    created = await client.post("/api/v1/conversations", json={})
    assert created.status_code == 201, created.text
    cid = created.json()["id"]
    path = f"/api/v1/conversations/{cid}/messages"
    payload = {"client_message_id": "same-message", "body": "查询七天无理由退货政策"}
    results = await asyncio.gather(*[client.post(path, json=payload) for _ in range(20)])
    assert {row.status_code for row in results} == {200}, [row.text for row in results]
    assert len({row.json()["id"] for row in results}) == 1
    conflict = await client.post(path, json={**payload, "body": "换了正文"})
    assert conflict.status_code == 409
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(Message)
                .where(Message.tenant_id == env.tenants[0].id, Message.conversation_id == cid)
            )
            == 1
        )
        assert (
            await session.scalar(
                select(func.count())
                .select_from(Job)
                .where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
            )
            == 1
        )
        assert (
            await session.scalar(
                select(func.count())
                .select_from(OutboxEvent)
                .where(OutboxEvent.tenant_id == env.tenants[0].id, OutboxEvent.kind == "job")
            )
            == 1
        )
