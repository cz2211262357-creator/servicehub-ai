import asyncio
from datetime import timedelta

import pytest
from servicehub.ai.provider import ModelEvent
from servicehub.conversations.models import Message
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from sqlalchemy import func, select, update

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


@pytest.mark.parametrize("invalidate", ["handoff", "lease_expired"])
async def test_late_model_reply_cannot_cross_handoff_or_lost_lease(env, login, invalidate):
    from servicehub.ai.service import AIService
    from servicehub.jobs.service import JobService

    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "late", "body": "我要查订单"},
    )
    async with env.sessions() as session:
        job = await session.scalar(
            select(Job).where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
        )
    lease = await JobService(env.sessions).acquire(job.id, "old-worker", 60)
    entered, release = asyncio.Event(), asyncio.Event()

    class Delayed:
        async def stream(self, messages, tools, budget):
            entered.set()
            await release.wait()
            yield ModelEvent("delta", text="迟到回答")
            yield ModelEvent("done")

    task = asyncio.create_task(
        AIService(env.sessions, env.redis, env.settings).handle(lease, provider=Delayed())
    )
    await asyncio.wait_for(entered.wait(), 3)
    if invalidate == "handoff":
        assert (await customer.post(f"/api/v1/conversations/{cid}/handoff")).status_code == 200
    else:
        async with env.sessions() as session, session.begin():
            await session.execute(
                update(Job)
                .where(Job.id == job.id)
                .values(lease_expires_at=utcnow() - timedelta(seconds=1))
            )
    release.set()
    await asyncio.wait_for(task, 3)
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(Message)
                .where(Message.conversation_id == cid, Message.author_role == "ai")
            )
            == 0
        )
