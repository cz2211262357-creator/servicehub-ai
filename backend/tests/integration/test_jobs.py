from datetime import timedelta

import pytest
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from servicehub.jobs.persistence import enqueue_job
from sqlalchemy import update

pytestmark = pytest.mark.integration


async def new_job(env, key="job-1"):
    async with env.sessions() as session, session.begin():
        return await enqueue_job(session, "test", key, {"tenant_id": env.tenants[0].id})


async def test_only_current_lease_can_finish_and_expired_work_is_recoverable(env):
    from servicehub.jobs.service import JobService

    job = await new_job(env)
    service = JobService(env.sessions)
    old = await service.acquire(job.id, "old-worker", 30)
    assert old is not None
    assert await service.acquire(job.id, "second-worker", 30) is None
    async with env.sessions() as session, session.begin():
        await session.execute(
            update(Job)
            .where(Job.id == job.id)
            .values(lease_expires_at=utcnow() - timedelta(seconds=1))
        )
    new = await service.acquire(job.id, "new-worker", 30)
    assert new.version > old.version
    assert await service.finish(old, {"answer": "obsolete"}) is False
    assert await service.finish(new, {"answer": "current"}) is True
    assert await service.acquire(job.id, "duplicate", 30) is None


async def test_retry_budget_is_three_after_the_initial_attempt(env):
    from servicehub.jobs.service import JobService

    job = await new_job(env)
    service = JobService(env.sessions)
    for attempt in range(4):
        lease = await service.acquire(job.id, f"worker-{attempt}", 30)
        assert lease is not None
        assert await service.fail(lease, "provider_timeout", retryable=True)
        async with env.sessions() as session, session.begin():
            row = await session.get(Job, job.id)
            assert row.attempts == attempt + 1
            assert row.state == ("failed" if attempt == 3 else "retrying")
            row.available_at = utcnow() - timedelta(seconds=1)
    assert await service.acquire(job.id, "fifth-attempt", 30) is None
