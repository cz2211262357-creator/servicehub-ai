import asyncio
import contextlib

import pytest
from servicehub.jobs.models import Job
from servicehub.jobs.persistence import enqueue_job
from servicehub.jobs.worker import Worker
from sqlalchemy import update

pytestmark = pytest.mark.integration


async def enqueue(env, key):
    async with env.sessions() as session, session.begin():
        return await enqueue_job(session, "scheduling_probe", key, {"tenant_id": env.tenants[0].id})


async def test_a_slow_job_does_not_block_outbox_delivery_or_the_next_available_worker_slot(env):
    first = await enqueue(env, "slow")
    entered, second_finished, release = asyncio.Event(), asyncio.Event(), asyncio.Event()

    async def handle(lease):
        if lease.job_id == first.id:
            entered.set()
            await release.wait()
        else:
            second_finished.set()
        return {"handled": lease.job_id}

    cfg = env.settings.model_copy(update={"worker_concurrency": 2, "worker_poll_seconds": 0.03})
    worker = Worker(env.sessions, env.redis, cfg, handlers={"scheduling_probe": handle})
    running = asyncio.create_task(worker.run())
    try:
        await asyncio.wait_for(entered.wait(), 3)
        second = await enqueue(env, "arrived-while-first-running")
        await asyncio.wait_for(second_finished.wait(), 3)
        async with asyncio.timeout(3):
            while True:
                async with env.sessions() as session:
                    row = await session.get(Job, second.id)
                    if row.state == "succeeded":
                        break
                await asyncio.sleep(0.03)
        async with env.sessions() as session:
            assert (await session.get(Job, first.id)).state == "running"
    finally:
        release.set()
        running.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await running


async def test_lost_single_job_lease_does_not_cancel_the_entire_worker_tick(env):
    job = await enqueue(env, "lease-cancel")
    from servicehub.jobs.outbox import OutboxDispatcher

    await OutboxDispatcher(env.sessions, env.redis).tick()

    async def handle(lease):
        async with env.sessions() as session, session.begin():
            await session.execute(
                update(Job)
                .where(Job.id == lease.job_id)
                .values(owner="replacement-worker", execution_version=Job.execution_version + 1)
            )
        await asyncio.sleep(30)
        raise AssertionError("The invalidated job must be cancelled before completing")

    cfg = env.settings.model_copy(update={"job_lease_seconds": 15})
    worker = Worker(env.sessions, env.redis, cfg, handlers={"scheduling_probe": handle})
    assert await asyncio.wait_for(worker.tick(), 8) == 1
    async with env.sessions() as session:
        row = await session.get(Job, job.id)
        assert row.state == "running" and row.owner == "replacement-worker"
        assert row.result is None


async def test_cancelling_the_worker_caller_still_cancels_active_jobs(env):
    await enqueue(env, "shutdown")
    from servicehub.jobs.outbox import OutboxDispatcher

    await OutboxDispatcher(env.sessions, env.redis).tick()
    entered = asyncio.Event()

    async def handle(lease):
        entered.set()
        await asyncio.sleep(30)

    worker = Worker(env.sessions, env.redis, env.settings, handlers={"scheduling_probe": handle})
    running = asyncio.create_task(worker.tick())
    await asyncio.wait_for(entered.wait(), 3)
    running.cancel()
    with pytest.raises(asyncio.CancelledError):
        await running
