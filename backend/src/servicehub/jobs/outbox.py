import json
from dataclasses import dataclass
from datetime import timedelta
from uuid import uuid4

from redis.exceptions import RedisError
from sqlalchemy import func, or_, select, update

from servicehub.jobs.models import Job, OutboxEvent
from servicehub.jobs.service import database_now

STREAM = "servicehub:jobs"


@dataclass(frozen=True)
class Delivery:
    id: int
    tenant_id: int
    kind: str
    job_id: int | None
    payload: dict
    owner: str
    version: int


class OutboxDispatcher:
    def __init__(self, sessions, redis):
        self.sessions, self.redis = sessions, redis
        self.owner = "dispatcher-" + uuid4().hex

    async def claim(self):
        async with self.sessions() as session, session.begin():
            now = await database_now(session)
            rows = list(
                await session.scalars(
                    select(OutboxEvent)
                    .where(
                        OutboxEvent.dispatched_at.is_(None),
                        OutboxEvent.available_at <= now,
                        or_(
                            OutboxEvent.lease_expires_at.is_(None),
                            OutboxEvent.lease_expires_at <= now,
                        ),
                    )
                    .order_by(OutboxEvent.id)
                    .limit(50)
                    .with_for_update(skip_locked=True)
                )
            )
            deliveries = []
            for row in rows:
                row.owner, row.lease_expires_at = self.owner, now + timedelta(seconds=15)
                row.lease_version += 1
                row.attempts += 1
                deliveries.append(
                    Delivery(
                        row.id,
                        row.tenant_id,
                        row.kind,
                        row.job_id,
                        row.payload,
                        self.owner,
                        row.lease_version,
                    )
                )
            return deliveries

    async def deliver(self, delivery):
        if delivery.kind == "job":
            # Parameters remain in MySQL; Streams only carries the durable job identifier.
            await self.redis.xadd(STREAM, {"job_id": str(delivery.job_id)})
        elif delivery.kind == "realtime":
            await self.redis.publish(
                f"servicehub:events:{delivery.tenant_id}:{delivery.payload['conversation_id']}",
                json.dumps(delivery.payload),
            )

    async def mark_sent(self, delivery):
        async with self.sessions() as session, session.begin():
            result = await session.execute(
                update(OutboxEvent)
                .where(
                    OutboxEvent.id == delivery.id,
                    OutboxEvent.owner == delivery.owner,
                    OutboxEvent.lease_version == delivery.version,
                    OutboxEvent.dispatched_at.is_(None),
                    OutboxEvent.lease_expires_at > func.utc_timestamp(6),
                )
                .values(dispatched_at=func.utc_timestamp(6), owner=None, lease_expires_at=None)
            )
            return result.rowcount == 1

    async def tick(self):
        sent = 0
        for delivery in await self.claim():
            try:
                await self.deliver(delivery)
                sent += int(await self.mark_sent(delivery))
            except RedisError:
                # The short delivery lease expires; subsequent dispatchers can send again.
                continue
        return sent

    async def reconcile(self):
        async with self.sessions() as session, session.begin():
            now = await database_now(session)
            query = (
                select(Job)
                .where(
                    or_(
                        (Job.state.in_(["queued", "retrying"]) & (Job.available_at <= now)),
                        ((Job.state == "running") & (Job.lease_expires_at <= now)),
                    )
                )
                .order_by(Job.id)
                .limit(200)
                .with_for_update(skip_locked=True)
            )
            count = 0
            for job in await session.scalars(query):
                pending = await session.scalar(
                    select(OutboxEvent.id)
                    .where(OutboxEvent.job_id == job.id, OutboxEvent.dispatched_at.is_(None))
                    .limit(1)
                )
                if pending is None:
                    session.add(
                        OutboxEvent(tenant_id=job.tenant_id, kind="job", job_id=job.id, payload={})
                    )
                    count += 1
            return count
