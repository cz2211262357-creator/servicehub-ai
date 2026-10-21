from datetime import timedelta

import pytest
from servicehub.db.base import utcnow
from servicehub.jobs.models import OutboxEvent
from servicehub.jobs.persistence import enqueue_job
from sqlalchemy import update

pytestmark = pytest.mark.integration


async def test_crash_after_stream_send_redelivers_but_cannot_repeat_completed_job(env):
    from servicehub.jobs.outbox import STREAM, OutboxDispatcher
    from servicehub.jobs.service import JobService

    async with env.sessions() as session, session.begin():
        job = await enqueue_job(session, "test", "outbox-crash", {"tenant_id": env.tenants[0].id})

    class InterruptedDispatcher(OutboxDispatcher):
        async def mark_sent(self, delivery):
            raise RuntimeError("simulated process exit after Redis accepted XADD")

    broken = InterruptedDispatcher(env.sessions, env.redis)
    with pytest.raises(RuntimeError):
        await broken.tick()
    assert await env.redis.xlen(STREAM) == 1
    async with env.sessions() as session, session.begin():
        await session.execute(
            update(OutboxEvent)
            .where(OutboxEvent.tenant_id == env.tenants[0].id)
            .values(lease_expires_at=utcnow() - timedelta(seconds=1))
        )
    assert await OutboxDispatcher(env.sessions, env.redis).tick() == 1
    assert await env.redis.xlen(STREAM) == 2
    service = JobService(env.sessions)
    lease = await service.acquire(job.id, "worker", 30)
    assert await service.finish(lease, {"saved": True})
    assert await service.acquire(job.id, "duplicate-worker", 30) is None


async def test_redis_loss_rebuilds_pending_scheduling_from_mysql(env):
    from servicehub.jobs.outbox import STREAM, OutboxDispatcher

    async with env.sessions() as session, session.begin():
        await enqueue_job(session, "test", "reconcile", {"tenant_id": env.tenants[0].id})
    dispatcher = OutboxDispatcher(env.sessions, env.redis)
    await dispatcher.tick()
    await env.redis.flushdb()
    assert await dispatcher.reconcile() == 1
    assert await dispatcher.tick() == 1
    assert await env.redis.xlen(STREAM) == 1
