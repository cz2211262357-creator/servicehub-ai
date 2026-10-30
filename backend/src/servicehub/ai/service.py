import asyncio
import contextlib
import time

from sqlalchemy import select

from servicehub.ai.agent import SupportAgent
from servicehub.ai.answer_rules import requires_policy_citation
from servicehub.ai.models import AIRun
from servicehub.ai.provider import HTTPModelProvider
from servicehub.ai.tracing import cited_evidence
from servicehub.auth.models import User
from servicehub.auth.schemas import Principal
from servicehub.conversations.events import append_event
from servicehub.conversations.models import Conversation, Message
from servicehub.conversations.schemas import MessageDTO
from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow
from servicehub.jobs.handlers import PermanentJobError, RetryableJobError
from servicehub.jobs.service import JobService
from servicehub.knowledge.models import KnowledgeDocument
from servicehub.knowledge.retrieval import Retriever
from servicehub.limits.leases import ExecutionSlots


class AIService:
    def __init__(self, sessions, redis, settings):
        self.sessions, self.redis, self.settings = sessions, redis, settings
        self.jobs = JobService(sessions)
        self.slots = ExecutionSlots(redis, settings)
        self.retriever = Retriever(sessions, redis, settings)

    async def commit_result(self, job_lease, generation_epoch, result):
        payload = job_lease.payload
        async with self.sessions() as session, session.begin():
            conversation = await session.scalar(
                select(Conversation)
                .where(
                    Conversation.id == payload["conversation_id"],
                    Conversation.tenant_id == job_lease.tenant_id,
                )
                .with_for_update()
            )
            if not await self.jobs.lock_valid(session, job_lease):
                return False
            run = await session.get(AIRun, payload["ai_run_id"])
            user = await session.get(User, conversation.customer_id)
            if (
                conversation.status != "AI_ACTIVE"
                or conversation.generation_epoch != generation_epoch
                or not user.is_active
                or user.role != "customer"
            ):
                run.state, run.finished_at = "cancelled", utcnow()
                await self.jobs.finish(job_lease, {"discarded": True}, session=session)
                return False
            # Validate text references, not merely the existence of attached evidence.
            evidence = cited_evidence(
                result.text,
                result.citations,
                required=requires_policy_citation(result.text, result.citations, result.tool_calls),
            )
            citations = []
            if evidence:
                await session.execute(
                    select(KnowledgeDocument)
                    .where(
                        KnowledgeDocument.tenant_id == job_lease.tenant_id,
                        KnowledgeDocument.id.in_(sorted({row.document_id for row in evidence})),
                    )
                    .order_by(KnowledgeDocument.id)
                    .with_for_update(read=True)
                )
                principal = Principal(user.id, user.tenant_id, "customer")
                citations = await self.retriever.revalidate(principal, evidence)
                if {row.chunk_id for row in citations} != {row.chunk_id for row in evidence}:
                    raise RetryableJobError("evidence_changed")
            message = Message(
                tenant_id=job_lease.tenant_id,
                conversation_id=conversation.id,
                sender_id=None,
                author_role="ai",
                ai_run_id=run.id,
                client_message_id=None,
                body=result.text,
                citations=[row.model_dump() for row in citations],
                draft_id=result.draft_id,
            )
            session.add(message)
            await session.flush()
            conversation.version += 1
            conversation.updated_at = utcnow()
            run.state, run.finished_at = "succeeded", utcnow()
            run.usage = result.usage
            run.latency_ms = (
                int((run.finished_at - run.started_at).total_seconds() * 1000)
                if run.started_at
                else None
            )
            if not await self.jobs.finish(
                job_lease, {"message_id": message.id, "ai_run_id": run.id}, session=session
            ):
                await session.rollback()
                return False
            await append_event(
                session,
                conversation,
                "message.created",
                {"message": MessageDTO.model_validate(message).model_dump(mode="json")},
            )
            return True

    async def handle(self, lease, provider=None):
        payload = lease.payload
        async with self.sessions() as session, session.begin():
            conversation = await session.scalar(
                select(Conversation)
                .where(
                    Conversation.id == payload["conversation_id"],
                    Conversation.tenant_id == lease.tenant_id,
                )
                .with_for_update()
            )
            if not await self.jobs.lock_valid(session, lease):
                return {"discarded": True}
            run = await session.get(AIRun, payload["ai_run_id"])
            user = await session.get(User, conversation.customer_id)
            if (
                conversation.status != "AI_ACTIVE"
                or conversation.generation_epoch != payload["generation_epoch"]
            ):
                run.state, run.finished_at = "cancelled", utcnow()
                await self.jobs.finish(lease, {"discarded": True}, session=session)
                return {"discarded": True}
            principal = Principal(user.id, user.tenant_id, user.role, user.session_version)
            run.state, run.started_at, run.model = "running", utcnow(), self.settings.model_name
            run.provider = "online"
        slot = None
        work = heartbeat = None
        try:
            async with asyncio.timeout(self.settings.agent_timeout_seconds):
                started = time.monotonic()
                while slot is None and time.monotonic() - started < 30:
                    slot = await self.slots.acquire(
                        f"{lease.owner}:{lease.job_id}:{lease.version}", 30
                    )
                    if slot is None:
                        await asyncio.sleep(0.25)
                if slot is None:
                    raise RetryableJobError("model_capacity_exhausted")
                agent = SupportAgent(self.sessions, self.redis, self.settings, lease.version)
                work = asyncio.create_task(
                    agent.run(
                        principal,
                        conversation.id,
                        run.id,
                        provider or HTTPModelProvider(self.settings),
                    )
                )

                async def renew():
                    while True:
                        await asyncio.sleep(10)
                        try:
                            valid = await self.slots.renew(slot)
                        except BusinessError:
                            valid = False
                        if not valid:
                            work.cancel()
                            return

                heartbeat = asyncio.create_task(renew())
                result = await work
                committed = await self.commit_result(lease, payload["generation_epoch"], result)
                return {"committed": committed}
        except asyncio.CancelledError:
            if asyncio.current_task().cancelling():
                raise
            raise RetryableJobError("model_slot_lost") from None
        except (BusinessError, TimeoutError) as exc:
            code = exc.code if isinstance(exc, BusinessError) else "agent_timeout"
            if isinstance(exc, BusinessError) and exc.status_code < 500:
                raise PermanentJobError(code) from None
            raise RetryableJobError(code) from None
        finally:
            if heartbeat:
                heartbeat.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await heartbeat
            if work and not work.done():
                work.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await work
            if slot:
                with contextlib.suppress(BusinessError):
                    await self.slots.release(slot)
