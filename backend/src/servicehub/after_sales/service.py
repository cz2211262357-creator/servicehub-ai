import hashlib
import json
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from servicehub.after_sales.eligibility import evaluate_eligibility
from servicehub.after_sales.models import AfterSaleTicket, TicketDraft, TicketEvent
from servicehub.after_sales.schemas import TicketDraftDTO, TicketDTO, TicketEventDTO
from servicehub.ai.models import AIRun
from servicehub.auth.models import User
from servicehub.conversations.models import Conversation
from servicehub.core.errors import BusinessError
from servicehub.jobs.models import Job
from servicehub.jobs.service import database_now
from servicehub.operations.audit import AuditService
from servicehub.orders.models import Order

TRANSITIONS = {
    "SUBMITTED": {"IN_REVIEW", "CANCELLED"},
    "IN_REVIEW": {"APPROVED", "REJECTED"},
    "APPROVED": {"RESOLVED"},
    "REJECTED": set(),
    "RESOLVED": set(),
    "CANCELLED": set(),
}


def tickets_for(principal):
    query = select(AfterSaleTicket).where(AfterSaleTicket.tenant_id == principal.tenant_id)
    return (
        query.where(AfterSaleTicket.customer_id == principal.user_id)
        if principal.role == "customer"
        else query
    )


class TicketService:
    def __init__(self, sessions):
        self.sessions = sessions

    @staticmethod
    async def _lock_actor(session, principal):
        actor = await session.scalar(
            select(User)
            .where(User.id == principal.user_id, User.tenant_id == principal.tenant_id)
            .with_for_update()
        )
        if (
            not actor
            or not actor.is_active
            or actor.role != principal.role
            or actor.session_version != principal.session_version
        ):
            raise BusinessError("after_sale_forbidden", "当前账号已失去此操作权限", 403)
        return actor

    @staticmethod
    async def _lock_execution(
        session, principal, run_id, conversation_id, execution_version, *, confirming=False
    ):
        context = (run_id, conversation_id, execution_version)
        if all(value is None for value in context):
            return
        stale = BusinessError("after_sale_execution_stale", "当前任务已失效，请重新准备申请", 409)
        if any(value is None for value in context) or principal.role != "customer":
            raise stale
        # All business mutations use domain -> job -> run after the customer lock.
        conversation = await session.scalar(
            select(Conversation)
            .where(
                Conversation.id == conversation_id,
                Conversation.tenant_id == principal.tenant_id,
                Conversation.customer_id == principal.user_id,
            )
            .with_for_update()
        )
        if not conversation:
            raise stale
        preview = await session.scalar(
            select(AIRun.job_id).where(
                AIRun.id == run_id,
                AIRun.tenant_id == principal.tenant_id,
                AIRun.conversation_id == conversation_id,
            )
        )
        if preview is None:
            raise stale
        job = await session.scalar(
            select(Job)
            .where(
                Job.id == preview,
                Job.tenant_id == principal.tenant_id,
            )
            .with_for_update()
        )
        run = await session.scalar(
            select(AIRun)
            .where(
                AIRun.id == run_id,
                AIRun.tenant_id == principal.tenant_id,
            )
            .with_for_update()
        )
        now = await database_now(session)
        if (
            not job
            or not run
            or conversation.status != "AI_ACTIVE"
            or (not confirming and conversation.generation_epoch != run.generation_epoch)
            or run.conversation_id != conversation.id
            or run.job_id != job.id
            or job.kind != "ai_reply"
            or job.payload.get("ai_run_id") != run.id
            or job.payload.get("conversation_id") != conversation.id
            or job.execution_version != execution_version
        ):
            raise stale
        if confirming:
            if run.state != "succeeded" or job.state != "succeeded":
                raise stale
        elif (
            run.state != "running"
            or job.state != "running"
            or not job.owner
            or not job.lease_expires_at
            or job.lease_expires_at <= now
        ):
            raise stale
        return job

    @staticmethod
    def _require_eligibility(order, kind, reason_code, now):
        decision = evaluate_eligibility(
            order.status, order.after_sale_facts, kind, reason_code, now
        )
        if not decision.eligible:
            raise BusinessError(decision.code, decision.message, 409, {"action": decision.action})

    async def create_draft(
        self,
        principal,
        order_id,
        kind,
        reason,
        reason_code="OTHER",
        *,
        run_id=None,
        conversation_id=None,
        execution_version=None,
    ):
        if not isinstance(reason, str) or not 2 <= len(reason.strip()) <= 1000:
            raise BusinessError("invalid_after_sale_reason", "申请原因长度应为2至1000字", 422)
        reason = reason.strip()
        async with self.sessions() as session, session.begin():
            await self._lock_actor(session, principal)
            execution_job = await self._lock_execution(
                session, principal, run_id, conversation_id, execution_version
            )
            query = select(Order).where(
                Order.tenant_id == principal.tenant_id, Order.id == order_id
            )
            if principal.role == "customer":
                query = query.where(Order.customer_id == principal.user_id)
            order = await session.scalar(query.with_for_update())
            if not order:
                raise BusinessError("not_found", "订单不存在或无权访问", 404)
            now = await database_now(session)
            # An order lock may have waited past the lease even though the job fence is locked.
            if execution_job is not None and execution_job.lease_expires_at <= now:
                raise BusinessError(
                    "after_sale_execution_stale", "当前任务已失效，请重新准备申请", 409
                )
            self._require_eligibility(order, kind, reason_code, now)
            if run_id is not None:
                existing = await session.scalar(
                    select(TicketDraft)
                    .where(
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.run_id == run_id,
                    )
                    .with_for_update()
                )
                if existing:
                    if (
                        existing.customer_id != order.customer_id
                        or existing.order_id != order.id
                        or existing.kind != kind
                        or existing.reason != reason
                        or existing.reason_code != reason_code
                        or existing.conversation_id != conversation_id
                        or existing.consumed
                    ):
                        raise BusinessError(
                            "draft_conflict", "同一任务已准备不同申请，请发送新消息", 409
                        )
                    if existing.expires_at <= now:
                        raise BusinessError(
                            "draft_expired", "草稿已过期，请发送新消息重新准备", 410
                        )
                    if existing.execution_version != execution_version:
                        existing.execution_version = execution_version
                        existing.version += 1
                    await session.flush()
                    return TicketDraftDTO.model_validate(existing)
                # A new turn supersedes all earlier pending applications in this conversation.
                previous = await session.scalars(
                    select(TicketDraft)
                    .where(
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.customer_id == order.customer_id,
                        TicketDraft.conversation_id == conversation_id,
                        TicketDraft.consumed.is_(False),
                        TicketDraft.expires_at > now,
                    )
                    .order_by(TicketDraft.id)
                    .with_for_update()
                )
                for old in previous:
                    old.expires_at = now
                    old.version += 1
            draft = TicketDraft(
                tenant_id=principal.tenant_id,
                customer_id=order.customer_id,
                order_id=order.id,
                kind=kind,
                reason=reason,
                reason_code=reason_code,
                run_id=run_id,
                conversation_id=conversation_id,
                execution_version=execution_version,
                expires_at=now + timedelta(minutes=15),
            )
            session.add(draft)
            await session.flush()
            return TicketDraftDTO.model_validate(draft)

    async def confirm_draft(self, principal, draft_id, version, idempotency_key):
        if principal.role != "customer":
            raise BusinessError("customer_confirmation_required", "售后申请必须由客户本人确认", 403)
        try:
            async with self.sessions() as session, session.begin():
                await self._lock_actor(session, principal)
                preview = await session.scalar(
                    select(TicketDraft).where(
                        TicketDraft.id == draft_id,
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.customer_id == principal.user_id,
                    )
                )
                if not preview:
                    raise BusinessError("not_found", "申请草稿不存在", 404)
                # Preserve the pre-0002 five-field hash for successful legacy replays.
                # reason_code is immutable and already bound by draft identity and version.
                request_hash = hashlib.sha256(
                    json.dumps(
                        [
                            preview.id,
                            version,
                            preview.order_id,
                            preview.kind,
                            preview.reason,
                        ],
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ).encode()
                ).hexdigest()
                existing = await session.scalar(
                    select(AfterSaleTicket).where(
                        AfterSaleTicket.tenant_id == principal.tenant_id,
                        AfterSaleTicket.idempotency_key == idempotency_key,
                    )
                )
                # A successful response replay is read-only; it never creates a second ticket.
                if existing:
                    if (
                        existing.customer_id != principal.user_id
                        or existing.request_hash != request_hash
                    ):
                        raise BusinessError("idempotency_conflict", "该提交标识已用于其他申请", 409)
                    return TicketDTO.model_validate(existing)
                context = (preview.run_id, preview.conversation_id, preview.execution_version)
                await self._lock_execution(session, principal, *context, confirming=True)
                order = await session.scalar(
                    select(Order)
                    .where(
                        Order.id == preview.order_id,
                        Order.tenant_id == principal.tenant_id,
                        Order.customer_id == principal.user_id,
                    )
                    .with_for_update()
                )
                if not order:
                    raise BusinessError("order_not_eligible", "订单不存在或已失去操作权限", 409)
                draft = await session.scalar(
                    select(TicketDraft)
                    .where(
                        TicketDraft.id == draft_id,
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.customer_id == principal.user_id,
                    )
                    .with_for_update()
                    .execution_options(populate_existing=True)
                )
                if (
                    not draft
                    or draft.version != version
                    or draft.consumed
                    or (draft.run_id, draft.conversation_id, draft.execution_version) != context
                    or draft.order_id != order.id
                ):
                    raise BusinessError("draft_conflict", "草稿已变更或已确认，请刷新", 409)
                now = await database_now(session)
                if draft.expires_at <= now:
                    raise BusinessError("draft_expired", "草稿已过期，请重新准备申请", 410)
                self._require_eligibility(order, draft.kind, draft.reason_code, now)
                ticket = AfterSaleTicket(
                    tenant_id=principal.tenant_id,
                    draft_id=draft.id,
                    customer_id=principal.user_id,
                    order_id=draft.order_id,
                    kind=draft.kind,
                    reason=draft.reason,
                    idempotency_key=idempotency_key,
                    request_hash=request_hash,
                )
                session.add(ticket)
                await session.flush()
                draft.consumed = True
                session.add(
                    TicketEvent(
                        tenant_id=principal.tenant_id,
                        ticket_id=ticket.id,
                        actor_id=principal.user_id,
                        from_status=None,
                        to_status="SUBMITTED",
                        note="客户确认提交",
                    )
                )
                AuditService.record(
                    session, principal, "ticket.confirmed", "ticket", ticket.id, "success"
                )
                return TicketDTO.model_validate(ticket)
        except IntegrityError:
            raise BusinessError(
                "idempotency_conflict", "该提交标识或草稿已被使用，请刷新", 409
            ) from None

    async def list_drafts(self, principal):
        async with self.sessions() as session:
            actor = await session.scalar(
                select(User).where(
                    User.id == principal.user_id,
                    User.tenant_id == principal.tenant_id,
                    User.is_active.is_(True),
                    User.role == principal.role,
                    User.session_version == principal.session_version,
                )
            )
            if not actor:
                raise BusinessError("after_sale_forbidden", "当前账号已失去此操作权限", 403)
            now = await database_now(session)
            # Completed applications survive ordinary follow-ups, but not a failed source,
            # a superseded execution, ownership change, expiry or changed order facts.
            direct = (
                TicketDraft.run_id.is_(None)
                & TicketDraft.conversation_id.is_(None)
                & TicketDraft.execution_version.is_(None)
            )
            completed = (
                (AIRun.state == "succeeded")
                & (Job.state == "succeeded")
                & (Job.kind == "ai_reply")
                & (Job.execution_version == TicketDraft.execution_version)
                & (AIRun.conversation_id == TicketDraft.conversation_id)
                & (Conversation.customer_id == principal.user_id)
                & (Conversation.status == "AI_ACTIVE")
            )
            rows = await session.execute(
                select(TicketDraft, Order, Job)
                .join(
                    Order,
                    (Order.id == TicketDraft.order_id)
                    & (Order.tenant_id == TicketDraft.tenant_id)
                    & (Order.customer_id == TicketDraft.customer_id),
                )
                .outerjoin(
                    AIRun,
                    (AIRun.id == TicketDraft.run_id) & (AIRun.tenant_id == TicketDraft.tenant_id),
                )
                .outerjoin(Job, (Job.id == AIRun.job_id) & (Job.tenant_id == AIRun.tenant_id))
                .outerjoin(
                    Conversation,
                    (Conversation.id == TicketDraft.conversation_id)
                    & (Conversation.tenant_id == TicketDraft.tenant_id),
                )
                .where(
                    TicketDraft.tenant_id == principal.tenant_id,
                    TicketDraft.customer_id == principal.user_id,
                    TicketDraft.consumed.is_(False),
                    TicketDraft.expires_at > now,
                    direct | completed,
                )
                .order_by(TicketDraft.id.desc())
                .limit(30)
            )
            items = []
            for draft, order, job in rows:
                if draft.run_id is not None and (
                    job.payload.get("ai_run_id") != draft.run_id
                    or job.payload.get("conversation_id") != draft.conversation_id
                ):
                    continue
                if evaluate_eligibility(
                    order.status, order.after_sale_facts, draft.kind, draft.reason_code, now
                ).eligible:
                    items.append(TicketDraftDTO.model_validate(draft))
            return {"items": items}

    async def list(self, principal, cursor=None, limit=30, status=None):
        async with self.sessions() as session:
            query = tickets_for(principal)
            if cursor:
                query = query.where(AfterSaleTicket.id < cursor)
            if status:
                query = query.where(AfterSaleTicket.status == status)
            rows = list(
                await session.scalars(query.order_by(AfterSaleTicket.id.desc()).limit(limit + 1))
            )
            return {
                "items": [TicketDTO.model_validate(row) for row in rows[:limit]],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }

    async def get(self, principal, ticket_id):
        async with self.sessions() as session:
            row = await session.scalar(
                tickets_for(principal).where(AfterSaleTicket.id == ticket_id)
            )
            if not row:
                raise BusinessError("not_found", "工单不存在", 404)
            events = await session.scalars(
                select(TicketEvent)
                .where(
                    TicketEvent.tenant_id == principal.tenant_id, TicketEvent.ticket_id == row.id
                )
                .order_by(TicketEvent.id)
            )
            return TicketDTO.model_validate(row).model_copy(
                update={"events": [TicketEventDTO.model_validate(event) for event in events]}
            )

    async def transition(self, principal, ticket_id, status, version, note, request_id="-"):
        failure = None
        async with self.sessions() as session, session.begin():
            row = await session.scalar(
                tickets_for(principal).where(AfterSaleTicket.id == ticket_id).with_for_update()
            )
            if not row:
                raise BusinessError("not_found", "工单不存在", 404)
            allowed = status in TRANSITIONS.get(row.status, set())
            if principal.role == "customer":
                allowed = allowed and status == "CANCELLED"
            if not allowed or row.version != version:
                AuditService.record(
                    session,
                    principal,
                    "ticket.transition",
                    "ticket",
                    row.id,
                    "rejected",
                    request_id,
                    {"requested_status": status},
                )
                failure = BusinessError("ticket_conflict", "工单状态已变化或不允许该流转", 409)
            else:
                previous = row.status
                row.status, row.version = status, row.version + 1
                session.add(
                    TicketEvent(
                        tenant_id=principal.tenant_id,
                        ticket_id=row.id,
                        actor_id=principal.user_id,
                        from_status=previous,
                        to_status=status,
                        note=note,
                    )
                )
                AuditService.record(
                    session,
                    principal,
                    "ticket.transition",
                    "ticket",
                    row.id,
                    "success",
                    request_id,
                    {"from": previous, "to": status},
                )
                await session.flush()
                result = TicketDTO.model_validate(row)
        if failure:
            raise failure
        return result
