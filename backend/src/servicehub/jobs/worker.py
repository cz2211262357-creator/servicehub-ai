import asyncio
import contextlib
import logging
import time
from uuid import uuid4

from redis.exceptions import RedisError, ResponseError
from sqlalchemy.exc import SQLAlchemyError

from servicehub.core.config import load_settings
from servicehub.core.logging import configure_logging
from servicehub.db.engine import create_database, create_redis
from servicehub.jobs.handlers import PermanentJobError, RetryableJobError, build_handlers
from servicehub.jobs.outbox import STREAM, OutboxDispatcher
from servicehub.jobs.service import JobService

GROUP = "servicehub-workers"
logger = logging.getLogger("servicehub.worker")


class Worker:
    def __init__(self, sessions, redis, settings, handlers=None):
        self.sessions, self.redis, self.settings = sessions, redis, settings
        self.owner = "worker-" + uuid4().hex
        self.jobs = JobService(sessions)
        self.handlers = (
            handlers if handlers is not None else build_handlers(sessions, redis, settings)
        )
        self.dispatcher = OutboxDispatcher(sessions, redis)
        self.last_reconcile = 0.0
        self.reclaim_cursor = "0-0"

    async def ensure_group(self):
        try:
            await self.redis.xgroup_create(STREAM, GROUP, id="0-0", mkstream=True)
        except ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def _heartbeat(self, lease, work):
        try:
            while True:
                await asyncio.sleep(self.settings.job_lease_seconds / 3)
                if not await self.jobs.renew(lease, self.settings.job_lease_seconds):
                    work.cancel()
                    return
        except (RedisError, SQLAlchemyError):
            work.cancel()

    async def process_message(self, message_id, fields):
        value = fields.get("job_id", "")
        if not value.isdecimal() or len(value) > 18:
            await self.redis.xack(STREAM, GROUP, message_id)
            return
        lease = await self.jobs.acquire(int(value), self.owner, self.settings.job_lease_seconds)
        if lease is None:
            await self.redis.xack(STREAM, GROUP, message_id)
            return

        async def execute():
            handler = self.handlers.get(lease.kind)
            if not handler:
                raise PermanentJobError("unknown_job_kind")
            return await handler(lease)

        work = asyncio.create_task(execute())
        heartbeat = asyncio.create_task(self._heartbeat(lease, work))
        try:
            result = await work
            # Some domain handlers atomically finish with their business result.
            await self.jobs.finish(lease, result or {})
        except PermanentJobError as exc:
            await self.jobs.fail(lease, exc.code, retryable=False)
        except RetryableJobError as exc:
            await self.jobs.fail(lease, exc.code, retryable=True)
        except asyncio.CancelledError:
            # A cancelled child is a lost job lease, while caller cancellation is shutdown.
            if asyncio.current_task().cancelling():
                raise
            return  # Leave this delivery pending for fenced recovery; keep the worker alive.
        except Exception as exc:
            logger.error(
                "job_failed", extra={"job_id": lease.job_id, "error_type": type(exc).__name__}
            )
            await self.jobs.fail(lease, "handler_error", retryable=True)
        finally:
            heartbeat.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await heartbeat
        await self.redis.xack(STREAM, GROUP, message_id)

    async def read_messages(self, capacity):
        await self.ensure_group()
        reclaimed = await self.redis.xautoclaim(
            STREAM,
            GROUP,
            self.owner,
            self.settings.job_lease_seconds * 1000,
            start_id=self.reclaim_cursor,
            count=capacity,
        )
        self.reclaim_cursor = reclaimed[0]
        messages = reclaimed[1]
        if not messages:
            rows = await self.redis.xreadgroup(
                GROUP,
                self.owner,
                {STREAM: ">"},
                count=capacity,
                block=50,
            )
            messages = rows[0][1] if rows else []
        return messages

    async def tick(self):
        """Drain one bounded batch, for explicit single-step operation."""
        messages = await self.read_messages(self.settings.worker_concurrency)
        await asyncio.gather(
            *[self.process_message(message_id, fields) for message_id, fields in messages]
        )
        return len(messages)

    async def dispatch_loop(self):
        while True:
            try:
                if time.monotonic() - self.last_reconcile >= 15:
                    await self.dispatcher.reconcile()
                    self.last_reconcile = time.monotonic()
                await self.dispatcher.tick()
                await asyncio.sleep(self.settings.worker_poll_seconds)
            except (RedisError, SQLAlchemyError) as exc:
                logger.error(
                    "worker_dependency_unavailable", extra={"error_type": type(exc).__name__}
                )
                await asyncio.sleep(2)

    async def consume_loop(self):
        active = set()
        try:
            while True:
                for task in list(active):
                    if task.done():
                        active.remove(task)
                        try:
                            task.result()
                        except asyncio.CancelledError:
                            pass
                        except (RedisError, SQLAlchemyError) as exc:
                            logger.error(
                                "worker_dependency_unavailable",
                                extra={"error_type": type(exc).__name__},
                            )
                capacity = self.settings.worker_concurrency - len(active)
                if not capacity:
                    await asyncio.wait(active, return_when=asyncio.FIRST_COMPLETED)
                    continue
                try:
                    messages = await self.read_messages(capacity)
                except (RedisError, SQLAlchemyError) as exc:
                    logger.error(
                        "worker_dependency_unavailable", extra={"error_type": type(exc).__name__}
                    )
                    await asyncio.sleep(2)
                    continue
                for message_id, fields in messages:
                    active.add(asyncio.create_task(self.process_message(message_id, fields)))
                if not messages:
                    await asyncio.sleep(self.settings.worker_poll_seconds)
        finally:
            for task in active:
                task.cancel()
            await asyncio.gather(*active, return_exceptions=True)

    async def run(self):
        # Notifications/reconciliation must progress even when all execution slots are busy.
        async with asyncio.TaskGroup() as group:
            group.create_task(self.dispatch_loop())
            group.create_task(self.consume_loop())


async def main():
    configure_logging()
    settings = load_settings()
    engine, sessions = create_database(settings)
    redis = create_redis(settings)
    try:
        await Worker(sessions, redis, settings).run()
    finally:
        await redis.aclose()
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
