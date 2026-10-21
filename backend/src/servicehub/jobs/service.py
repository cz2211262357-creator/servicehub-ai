from datetime import UTC, timedelta

from sqlalchemy import func, select

from servicehub.core.errors import BusinessError
from servicehub.jobs.models import Job, OutboxEvent
from servicehub.jobs.schemas import JobDTO, JobLease
from servicehub.operations.audit import AuditService


async def database_now(session):
    return (await session.scalar(select(func.utc_timestamp(6)))).replace(tzinfo=UTC)


class JobService:
    def __init__(self, sessions):
        self.sessions = sessions

    async def lock_domain(self, session, job_id, tenant_id=None):
        # Use the same domain -> job lock order as successful business commits.
        query = select(Job.kind, Job.payload, Job.tenant_id).where(Job.id == job_id)
        if tenant_id is not None:
            query = query.where(Job.tenant_id == tenant_id)
        preview = (await session.execute(query)).one_or_none()
        if not preview:
            return None
        if preview.kind == "ai_reply":
            from servicehub.conversations.models import Conversation

            return await session.scalar(
                select(Conversation)
                .where(
                    Conversation.id == preview.payload.get("conversation_id"),
                    Conversation.tenant_id == preview.tenant_id,
                )
                .with_for_update()
            )
        if preview.kind == "knowledge_index":
            from servicehub.knowledge.models import DocumentVersion, KnowledgeDocument

            document_id = await session.scalar(
                select(DocumentVersion.document_id).where(
                    DocumentVersion.id == preview.payload.get("document_version_id"),
                    DocumentVersion.tenant_id == preview.tenant_id,
                )
            )
            return await session.scalar(
                select(KnowledgeDocument)
                .where(
                    KnowledgeDocument.id == document_id,
                    KnowledgeDocument.tenant_id == preview.tenant_id,
                )
                .with_for_update()
            )
        return None

    async def sync_domain(self, session, job, domain, now):
        if job.kind == "ai_reply":
            from servicehub.ai.models import AIRun
            from servicehub.conversations.events import append_event

            run = await session.scalar(
                select(AIRun).where(
                    AIRun.id == job.payload.get("ai_run_id"),
                    AIRun.tenant_id == job.tenant_id,
                )
            )
            if not run:
                return
            current = (
                domain is not None
                and domain.status == "AI_ACTIVE"
                and domain.generation_epoch == run.generation_epoch
            )
            run.state = job.state if current else "cancelled"
            run.error_code = job.error_code if current else None
            run.finished_at = now if run.state in ("failed", "cancelled") else None
            if current:
                failed = job.state in ("failed", "retrying")
                await append_event(
                    session,
                    domain,
                    "ai.failed" if failed else "ai.state",
                    {
                        "code": job.error_code,
                        "state": job.state,
                        "will_retry": job.state == "retrying",
                        "can_handoff": True,
                    },
                )
        elif job.kind == "knowledge_index":
            from servicehub.knowledge.models import DocumentVersion

            version = await session.scalar(
                select(DocumentVersion).where(
                    DocumentVersion.id == job.payload.get("document_version_id"),
                    DocumentVersion.tenant_id == job.tenant_id,
                )
            )
            if version:
                version.status = "processing" if job.state == "running" else job.state
                version.error_code = job.error_code

    async def acquire(self, job_id, owner, lease_seconds) -> JobLease | None:
        async with self.sessions() as session, session.begin():
            domain = await self.lock_domain(session, job_id)
            job = await session.scalar(select(Job).where(Job.id == job_id).with_for_update())
            if not job:
                return None
            now = await database_now(session)
            eligible = job.state in ("queued", "retrying") and job.available_at <= now
            expired = (
                job.state == "running" and job.lease_expires_at and job.lease_expires_at <= now
            )
            if not (eligible or expired):
                return None
            if job.attempts >= 4:
                job.state, job.finished_at = "failed", now
                job.error_code, job.error_message = "retry_exhausted", "自动重试次数已用完"
                job.owner = job.lease_expires_at = None
                await self.sync_domain(session, job, domain, now)
                return None
            job.state, job.owner = "running", owner
            job.error_code = job.error_message = job.finished_at = None
            job.execution_version += 1
            job.attempts += 1
            job.lease_expires_at = now + timedelta(seconds=lease_seconds)
            await self.sync_domain(session, job, domain, now)
            await session.flush()
            return JobLease(
                job.id,
                owner,
                job.execution_version,
                job.lease_expires_at,
                job.tenant_id,
                job.kind,
                dict(job.payload),
            )

    async def lock_valid(self, session, lease):
        job = await session.scalar(select(Job).where(Job.id == lease.job_id).with_for_update())
        now = await database_now(session)
        if (
            not job
            or job.state != "running"
            or job.owner != lease.owner
            or (
                job.execution_version != lease.version
                or not job.lease_expires_at
                or job.lease_expires_at <= now
            )
        ):
            return None
        return job

    async def renew(self, lease, seconds):
        async with self.sessions() as session, session.begin():
            job = await self.lock_valid(session, lease)
            if not job:
                return False
            job.lease_expires_at = await database_now(session) + timedelta(seconds=seconds)
            return True

    async def finish(self, lease, result, *, session=None):
        if session is None:
            async with self.sessions() as owned, owned.begin():
                return await self.finish(lease, result, session=owned)
        job = await self.lock_valid(session, lease)
        if not job:
            return False
        job.state, job.result = "succeeded", result
        job.finished_at = await database_now(session)
        job.error_code = job.error_message = None
        job.owner = job.lease_expires_at = None
        return True

    async def fail(self, lease, code, *, retryable=True):
        async with self.sessions() as session, session.begin():
            domain = await self.lock_domain(session, lease.job_id, lease.tenant_id)
            job = await self.lock_valid(session, lease)
            if not job:
                return False
            now = await database_now(session)
            job.error_code = code[:64]
            job.error_message = "任务执行失败，可查看错误代码并重试"
            job.owner = job.lease_expires_at = None
            if retryable and job.attempts < 4:
                job.state = "retrying"
                job.available_at = now + timedelta(seconds=2 ** (job.attempts - 1))
                session.add(
                    OutboxEvent(
                        tenant_id=job.tenant_id,
                        kind="job",
                        job_id=job.id,
                        payload={},
                        available_at=job.available_at,
                    )
                )
            else:
                job.state, job.finished_at = "failed", now
            await self.sync_domain(session, job, domain, now)
            return True

    async def retry(self, principal, job_id, request_id="-"):
        if principal.role != "admin":
            raise BusinessError("forbidden", "仅管理员可重试失败任务", 403)
        async with self.sessions() as session, session.begin():
            domain = await self.lock_domain(session, job_id, principal.tenant_id)
            job = await session.scalar(
                select(Job)
                .where(Job.id == job_id, Job.tenant_id == principal.tenant_id)
                .with_for_update()
            )
            if not job:
                raise BusinessError("not_found", "任务不存在", 404)
            if job.state != "failed":
                raise BusinessError("job_conflict", "仅最终失败的任务可手动重试", 409)
            job.state, job.attempts, job.finished_at = "queued", 0, None
            job.owner = job.lease_expires_at = None
            job.error_code = job.error_message = None
            job.manual_retries += 1
            job.available_at = await database_now(session)
            await self.sync_domain(session, job, domain, job.available_at)
            session.add(OutboxEvent(tenant_id=job.tenant_id, kind="job", job_id=job.id, payload={}))
            AuditService.record(
                session,
                principal,
                "job.retried",
                "job",
                job.id,
                "success",
                request_id,
                {"manual_retries": job.manual_retries},
            )
            await session.flush()
            return JobDTO.model_validate(job)
