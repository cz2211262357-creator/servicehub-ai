from sqlalchemy import select, update

from servicehub.after_sales.intent import draft_message_intent
from servicehub.after_sales.models import TicketDraft
from servicehub.ai.models import AIRun
from servicehub.conversations.events import append_event, as_live_event
from servicehub.conversations.models import Conversation, ConversationEvent, Message
from servicehub.conversations.schemas import ConversationDTO, MessageDTO
from servicehub.conversations.snapshot import conversation_snapshot
from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from servicehub.jobs.persistence import enqueue_job
from servicehub.jobs.service import database_now
from servicehub.operations.audit import AuditService


async def authorized_conversation(session, principal, conversation_id, *, lock=False):
    query = select(Conversation).where(
        Conversation.id == conversation_id, Conversation.tenant_id == principal.tenant_id
    )
    if principal.role == "customer":
        query = query.where(Conversation.customer_id == principal.user_id)
    if lock:
        query = query.with_for_update()
    row = await session.scalar(query)
    if not row:
        raise BusinessError("not_found", "会话不存在或无权访问", 404)
    return row


class ConversationService:
    def __init__(self, sessions, settings=None):
        self.sessions, self.settings = sessions, settings

    async def create(self, principal, title="售后咨询"):
        if principal.role != "customer":
            raise BusinessError("forbidden", "请从客户入口创建会话", 403)
        async with self.sessions() as session, session.begin():
            row = Conversation(
                tenant_id=principal.tenant_id, customer_id=principal.user_id, title=title
            )
            session.add(row)
            await session.flush()
            await append_event(session, row, "conversation.created")
            return ConversationDTO.model_validate(row)

    async def get(self, principal, conversation_id):
        async with self.sessions() as session:
            return await conversation_snapshot(
                session, await authorized_conversation(session, principal, conversation_id)
            )

    async def list(self, principal, cursor=None, limit=30, status=None, search=None):
        async with self.sessions() as session:
            query = select(Conversation).where(Conversation.tenant_id == principal.tenant_id)
            if principal.role == "customer":
                query = query.where(Conversation.customer_id == principal.user_id)
            if status:
                query = query.where(Conversation.status == status)
            if cursor:
                query = query.where(Conversation.id < cursor)
            if search:
                query = query.where(Conversation.title.contains(search, autoescape=True))
            rows = list(
                await session.scalars(query.order_by(Conversation.id.desc()).limit(limit + 1))
            )
            return {
                "items": [ConversationDTO.model_validate(row) for row in rows[:limit]],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }

    async def list_messages(self, principal, conversation_id, cursor=None, limit=50):
        async with self.sessions() as session:
            await authorized_conversation(session, principal, conversation_id)
            query = select(Message).where(
                Message.tenant_id == principal.tenant_id, Message.conversation_id == conversation_id
            )
            if cursor:
                query = query.where(Message.id < cursor)
            rows = list(await session.scalars(query.order_by(Message.id.desc()).limit(limit + 1)))
            return {
                "items": [MessageDTO.model_validate(row) for row in reversed(rows[:limit])],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }

    async def list_events(self, principal, conversation_id, after_seq=0, limit=200):
        async with self.sessions() as session:
            await authorized_conversation(session, principal, conversation_id)
            rows = list(
                await session.scalars(
                    select(ConversationEvent)
                    .where(
                        ConversationEvent.tenant_id == principal.tenant_id,
                        ConversationEvent.conversation_id == conversation_id,
                        ConversationEvent.seq > after_seq,
                    )
                    .order_by(ConversationEvent.seq)
                    .limit(limit + 1)
                )
            )
            return {
                "items": [as_live_event(row) for row in rows[:limit]],
                "has_more": len(rows) > limit,
            }

    async def send_message(self, principal, conversation_id, client_message_id, body):
        body = body.strip()
        if not body or len(body) > (self.settings.max_input_characters if self.settings else 8000):
            raise BusinessError("invalid_message", "消息为空或超出长度限制", 422)
        async with self.sessions() as session, session.begin():
            row = await authorized_conversation(session, principal, conversation_id, lock=True)
            if principal.role != "customer" and (
                row.status != "HUMAN_ACTIVE" or row.assigned_agent_id != principal.user_id
            ):
                raise BusinessError("forbidden", "请先领取会话，再发送人工回复", 403)
            existing = await session.scalar(
                select(Message).where(
                    Message.tenant_id == principal.tenant_id,
                    Message.conversation_id == row.id,
                    Message.client_message_id == client_message_id,
                )
            )
            if existing:
                if existing.body != body or existing.sender_id != principal.user_id:
                    raise BusinessError("idempotency_conflict", "相同消息标识不能用于不同内容", 409)
                return MessageDTO.model_validate(existing)
            if row.status == "CLOSED":
                raise BusinessError("conversation_closed", "会话已结束，请创建新会话", 409)
            if principal.role == "customer" and draft_message_intent(body) != "KEEP":
                # The conversation lock serializes this withdrawal with Agent preparation
                # and customer confirmation; a retry returned above cannot repeat it.
                now = await database_now(session)
                await session.execute(
                    update(TicketDraft)
                    .where(
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.customer_id == principal.user_id,
                        TicketDraft.conversation_id == row.id,
                        TicketDraft.consumed.is_(False),
                        TicketDraft.expires_at > now,
                    )
                    .values(expires_at=now, version=TicketDraft.version + 1)
                )
            row.version += 1
            row.generation_epoch += 1
            row.updated_at = utcnow()
            message = Message(
                tenant_id=principal.tenant_id,
                conversation_id=row.id,
                sender_id=principal.user_id,
                author_role="customer" if principal.role == "customer" else "agent",
                client_message_id=client_message_id,
                body=body,
            )
            session.add(message)
            await session.flush()
            dto = MessageDTO.model_validate(message)
            event = await append_event(
                session, row, "message.created", {"message": dto.model_dump(mode="json")}
            )
            if row.status == "AI_ACTIVE" and principal.role == "customer":
                job = await enqueue_job(
                    session,
                    "ai_reply",
                    f"conversation:{row.id}:epoch:{row.generation_epoch}",
                    {
                        "tenant_id": principal.tenant_id,
                        "conversation_id": row.id,
                        "generation_epoch": row.generation_epoch,
                        "message_id": message.id,
                        "customer_id": row.customer_id,
                    },
                )
                run = AIRun(
                    tenant_id=principal.tenant_id,
                    conversation_id=row.id,
                    trigger_event_seq=event.seq,
                    generation_epoch=row.generation_epoch,
                    job_id=job.id,
                    provider=self.settings.model_mode if self.settings else "demo",
                )
                session.add(run)
                await session.flush()
                persisted_job = await session.get(Job, job.id)
                persisted_job.payload = {**persisted_job.payload, "ai_run_id": run.id}
            return dto

    async def request_handoff(self, principal, conversation_id):
        async with self.sessions() as session, session.begin():
            row = await authorized_conversation(session, principal, conversation_id, lock=True)
            if row.status == "CLOSED":
                raise BusinessError("conversation_closed", "会话已结束", 409)
            if row.status == "AI_ACTIVE":
                row.status, row.version = "WAITING_HUMAN", row.version + 1
                row.generation_epoch += 1
                row.updated_at = utcnow()
                await append_event(session, row, "conversation.updated")
                AuditService.record(
                    session, principal, "conversation.handoff", "conversation", row.id, "success"
                )
            return ConversationDTO.model_validate(row)

    async def claim(self, principal, conversation_id, expected_version):
        if principal.role not in ("agent", "admin"):
            raise BusinessError("forbidden", "仅客服可领取会话", 403)
        async with self.sessions() as session, session.begin():
            result = await session.execute(
                update(Conversation)
                .where(
                    Conversation.id == conversation_id,
                    Conversation.tenant_id == principal.tenant_id,
                    Conversation.status == "WAITING_HUMAN",
                    Conversation.assigned_agent_id.is_(None),
                    Conversation.version == expected_version,
                )
                .values(
                    status="HUMAN_ACTIVE",
                    assigned_agent_id=principal.user_id,
                    version=Conversation.version + 1,
                    generation_epoch=Conversation.generation_epoch + 1,
                    claimed_at=utcnow(),
                    updated_at=utcnow(),
                )
            )
            row = await authorized_conversation(session, principal, conversation_id, lock=True)
            if result.rowcount != 1:
                if row.status == "HUMAN_ACTIVE" and row.assigned_agent_id == principal.user_id:
                    return ConversationDTO.model_validate(row)
                raise BusinessError("claim_conflict", "该会话已被领取或状态已变化，请刷新", 409)
            await append_event(session, row, "conversation.updated")
            AuditService.record(
                session, principal, "conversation.claimed", "conversation", row.id, "success"
            )
            return ConversationDTO.model_validate(row)

    async def close(self, principal, conversation_id):
        async with self.sessions() as session, session.begin():
            row = await authorized_conversation(session, principal, conversation_id, lock=True)
            if principal.role == "agent" and row.assigned_agent_id != principal.user_id:
                raise BusinessError("forbidden", "仅接待客服可结束该会话", 403)
            if row.status != "CLOSED":
                row.status, row.version = "CLOSED", row.version + 1
                row.generation_epoch += 1
                row.closed_at = row.updated_at = utcnow()
                await append_event(session, row, "conversation.updated")
                AuditService.record(
                    session, principal, "conversation.closed", "conversation", row.id, "success"
                )
            return ConversationDTO.model_validate(row)
