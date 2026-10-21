import hashlib
import json
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from servicehub.after_sales.models import AfterSaleTicket, TicketDraft, TicketEvent
from servicehub.after_sales.schemas import TicketDraftDTO, TicketDTO, TicketEventDTO
from servicehub.auth.models import User
from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow
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

    async def create_draft(self, principal, order_id, kind, reason):
        async with self.sessions() as session, session.begin():
            query = select(Order).where(
                Order.tenant_id == principal.tenant_id, Order.id == order_id
            )
            if principal.role == "customer":
                query = query.where(Order.customer_id == principal.user_id)
            order = await session.scalar(query)
            if not order:
                raise BusinessError("not_found", "订单不存在或无权访问", 404)
            if order.status not in ("DELIVERED", "SHIPPED"):
                raise BusinessError("order_not_eligible", "当前订单状态不可发起售后", 409)
            draft = TicketDraft(
                tenant_id=principal.tenant_id,
                customer_id=order.customer_id,
                order_id=order.id,
                kind=kind,
                reason=reason.strip(),
                expires_at=utcnow() + timedelta(minutes=15),
            )
            session.add(draft)
            await session.flush()
            return TicketDraftDTO.model_validate(draft)

    async def confirm_draft(self, principal, draft_id, version, idempotency_key):
        if principal.role != "customer":
            raise BusinessError("customer_confirmation_required", "售后申请必须由客户本人确认", 403)
        try:
            async with self.sessions() as session, session.begin():
                # Serialize one customer's confirmations; unique constraints remain the final guard.
                await session.scalar(
                    select(User)
                    .where(User.id == principal.user_id, User.tenant_id == principal.tenant_id)
                    .with_for_update()
                )
                draft = await session.scalar(
                    select(TicketDraft)
                    .where(
                        TicketDraft.id == draft_id,
                        TicketDraft.tenant_id == principal.tenant_id,
                        TicketDraft.customer_id == principal.user_id,
                    )
                    .with_for_update()
                )
                if not draft:
                    raise BusinessError("not_found", "申请草稿不存在", 404)
                request_hash = hashlib.sha256(
                    json.dumps(
                        [draft.id, version, draft.order_id, draft.kind, draft.reason],
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
                if existing:
                    if (
                        existing.customer_id != principal.user_id
                        or existing.request_hash != request_hash
                    ):
                        raise BusinessError("idempotency_conflict", "该提交标识已用于其他申请", 409)
                    return TicketDTO.model_validate(existing)
                if draft.version != version or draft.consumed:
                    raise BusinessError("draft_conflict", "草稿已变更或已确认，请刷新", 409)
                if draft.expires_at <= utcnow():
                    raise BusinessError("draft_expired", "草稿已过期，请重新准备申请", 410)
                order = await session.scalar(
                    select(Order)
                    .where(
                        Order.id == draft.order_id,
                        Order.tenant_id == principal.tenant_id,
                        Order.customer_id == principal.user_id,
                    )
                    .with_for_update()
                )
                if not order or order.status not in ("DELIVERED", "SHIPPED"):
                    raise BusinessError("order_not_eligible", "当前订单状态不可发起售后", 409)
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
            rows = await session.scalars(
                select(TicketDraft)
                .where(
                    TicketDraft.tenant_id == principal.tenant_id,
                    TicketDraft.customer_id == principal.user_id,
                    TicketDraft.consumed.is_(False),
                    TicketDraft.expires_at > utcnow(),
                )
                .order_by(TicketDraft.id.desc())
                .limit(30)
            )
            return {"items": [TicketDraftDTO.model_validate(row) for row in rows]}

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
