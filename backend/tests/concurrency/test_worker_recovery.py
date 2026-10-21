import pytest
from servicehub.jobs.models import Job
from servicehub.jobs.persistence import enqueue_job

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


async def test_stream_duplicate_is_acked_without_executing_completed_job_again(env):
    from servicehub.jobs.outbox import STREAM, OutboxDispatcher
    from servicehub.jobs.worker import Worker

    async with env.sessions() as session, session.begin():
        job = await enqueue_job(session, "test", "worker-once", {"tenant_id": env.tenants[0].id})
    await OutboxDispatcher(env.sessions, env.redis).tick()

    async def handler(lease):
        return {"receipt": "durable-result"}

    worker = Worker(env.sessions, env.redis, env.settings, handlers={"test": handler})
    assert await worker.tick() == 1
    await env.redis.xadd(STREAM, {"job_id": str(job.id)})
    await worker.tick()
    async with env.sessions() as session:
        row = await session.get(Job, job.id)
        assert row.result == {"receipt": "durable-result"}
        assert row.execution_version == 1
        assert row.state == "succeeded"
    pending = await env.redis.xpending(STREAM, "servicehub-workers")
    assert pending["pending"] == 0
