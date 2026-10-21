from sqlalchemy import select

from servicehub.jobs.models import Job, OutboxEvent
from servicehub.jobs.schemas import JobDTO


async def enqueue_job(session, kind: str, business_key: str, payload: dict) -> JobDTO:
    tenant_id = payload["tenant_id"]
    existing = await session.scalar(
        select(Job).where(
            Job.tenant_id == tenant_id, Job.kind == kind, Job.business_key == business_key
        )
    )
    if existing:
        return JobDTO.model_validate(existing)
    job = Job(tenant_id=tenant_id, kind=kind, business_key=business_key, payload=payload)
    session.add(job)
    await session.flush()
    session.add(OutboxEvent(tenant_id=tenant_id, kind="job", job_id=job.id, payload={}))
    return JobDTO.model_validate(job)
