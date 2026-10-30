import asyncio
from datetime import timedelta
from decimal import Decimal
from uuid import uuid4

import pytest
from servicehub.after_sales.models import AfterSaleTicket, TicketDraft
from servicehub.after_sales.service import TicketService
from servicehub.ai.models import AIRun
from servicehub.ai.tools import ToolRegistry
from servicehub.auth.models import User
from servicehub.auth.schemas import Principal
from servicehub.conversations.models import Conversation
from servicehub.conversations.service import ConversationService
from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from servicehub.jobs.service import JobService
from servicehub.orders.models import Order
from sqlalchemy import func, select

pytestmark = pytest.mark.integration


def trusted_facts(**overrides):
    return {
        "category": "electronics",
        "signed_at": (utcnow() - timedelta(days=1)).isoformat(),
        "return_condition": "opened_inspected",
        "exclusion_confirmed": False,
        "quality_issue": True,
        "warranty_until": (utcnow() + timedelta(days=365)).isoformat(),
        "requires_manual_review": False,
        **overrides,
    }


def customer(env):
    user = env.users["customer"]
    return Principal(user.id, user.tenant_id, "customer")


async def order(env, facts=None, **overrides):
    async with env.sessions() as session, session.begin():
        row = Order(
            tenant_id=env.tenants[0].id,
            customer_id=env.users["customer"].id,
            order_number="POLICY-" + uuid4().hex[:12],
            total_amount=Decimal("100"),
            after_sale_facts=facts,
            **overrides,
        )
        session.add(row)
        await session.flush()
        return row


async def running_context(env, conversation_id=None):
    conversations = ConversationService(env.sessions, env.settings)
    principal = customer(env)
    if conversation_id is None:
        conversation_id = (await conversations.create(principal)).id
    await conversations.send_message(principal, conversation_id, uuid4().hex, "为当前订单准备退货")
    async with env.sessions() as session:
        run = await session.scalar(
            select(AIRun).where(AIRun.conversation_id == conversation_id).order_by(AIRun.id.desc())
        )
    lease = await JobService(env.sessions).acquire(run.job_id, "policy-test", 60)
    assert lease is not None
    return {
        "run_id": run.id,
        "conversation_id": conversation_id,
        "execution_version": lease.version,
    }


async def succeed_context(env, context):
    async with env.sessions() as session, session.begin():
        run = await session.get(AIRun, context["run_id"])
        run.state = "succeeded"
        job = await session.get(Job, run.job_id)
        job.state, job.lease_expires_at = "succeeded", None


async def test_missing_facts_or_reason_never_create_a_draft(env, login):
    client = await login()
    unknown = await order(env)
    known = await order(env, trusted_facts())
    for payload in [
        {
            "order_id": unknown.id,
            "kind": "RETURN",
            "reason": "无需理由",
            "reason_code": "NO_REASON",
        },
        {"order_id": known.id, "kind": "RETURN", "reason": "质量问题"},
    ]:
        response = await client.post("/api/v1/after-sales/drafts", json=payload)
        assert response.status_code == 409, response.text
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(TicketDraft)
                .where(TicketDraft.tenant_id == env.tenants[0].id)
            )
            == 0
        )


async def test_prepare_rejects_model_fact_injection_and_accepts_trusted_electronics_inspection(
    env, login
):
    client = await login()
    row = await order(env, trusted_facts())
    payload = {
        "order_id": row.id,
        "kind": "RETURN",
        "reason": "仅开封检查",
        "reason_code": "NO_REASON",
    }
    response = await client.post(
        "/api/v1/after-sales/drafts", json={**payload, "after_sale_facts": trusted_facts()}
    )
    assert response.status_code == 422, response.text
    response = await client.post("/api/v1/after-sales/drafts", json=payload)
    assert response.status_code == 201, response.text
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 0
        )


@pytest.mark.parametrize("mutation", ["facts", "status", "ownership", "active"])
async def test_confirmation_rechecks_trusted_facts_and_authority(env, mutation):
    row = await order(env, trusted_facts())
    tickets = TicketService(env.sessions)
    pending = await tickets.create_draft(customer(env), row.id, "RETURN", "无需理由", "NO_REASON")
    async with env.sessions() as session, session.begin():
        persisted = await session.get(Order, row.id)
        if mutation == "facts":
            persisted.after_sale_facts = trusted_facts(
                signed_at=(utcnow() - timedelta(days=9)).isoformat()
            )
        elif mutation == "status":
            persisted.status = "CANCELLED"
        elif mutation == "ownership":
            persisted.customer_id = env.users["customer2"].id
        else:
            (await session.get(User, env.users["customer"].id)).is_active = False
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), pending.id, pending.version, uuid4().hex)
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 0
        )


async def test_scoped_prepare_is_concurrently_idempotent_and_needs_success_for_confirmation(env):
    row = await order(env, trusted_facts())
    context = await running_context(env)
    tickets = TicketService(env.sessions)
    drafts = await asyncio.gather(
        *[
            tickets.create_draft(
                customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
            )
            for _ in range(4)
        ]
    )
    assert len({draft.id for draft in drafts}) == 1
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), drafts[0].id, 1, "early")
    await succeed_context(env, context)
    confirmed = await tickets.confirm_draft(customer(env), drafts[0].id, 1, "confirmed")
    assert confirmed.order_id == row.id


@pytest.mark.parametrize("mutation", ["cancelled", "lease", "version", "handoff"])
async def test_stale_execution_cannot_write_a_draft(env, mutation):
    row = await order(env, trusted_facts())
    context = await running_context(env)
    async with env.sessions() as session, session.begin():
        run = await session.get(AIRun, context["run_id"])
        job = await session.get(Job, run.job_id)
        if mutation == "cancelled":
            run.state = "cancelled"
        elif mutation == "lease":
            job.lease_expires_at = utcnow() - timedelta(seconds=1)
        elif mutation == "version":
            job.execution_version += 1
        else:
            (await session.get(Conversation, context["conversation_id"])).status = "WAITING_HUMAN"
    registry = ToolRegistry(env.sessions, env.redis, env.settings, **context)
    with pytest.raises(BusinessError):
        await registry.execute(
            customer(env),
            "prepare_after_sale",
            {
                "order_number": row.order_number,
                "kind": "RETURN",
                "reason": "无需理由",
                "reason_code": "NO_REASON",
            },
        )
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(TicketDraft)
                .where(TicketDraft.tenant_id == env.tenants[0].id)
            )
            == 0
        )


async def test_order_switch_invalidates_old_draft_and_same_run_cannot_change_order(env):
    a, b = await order(env, trusted_facts()), await order(env, trusted_facts())
    tickets = TicketService(env.sessions)
    first = await running_context(env)
    old = await tickets.create_draft(
        customer(env), a.id, "RETURN", "无需理由", "NO_REASON", **first
    )
    with pytest.raises(BusinessError):
        await tickets.create_draft(customer(env), b.id, "RETURN", "无需理由", "NO_REASON", **first)
    await succeed_context(env, first)
    second = await running_context(env, first["conversation_id"])
    new = await tickets.create_draft(
        customer(env), b.id, "RETURN", "无需理由", "NO_REASON", **second
    )
    assert new.id != old.id
    async with env.sessions() as session:
        old_row = await session.get(TicketDraft, old.id)
        assert old_row.expires_at <= utcnow()
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), old.id, 1, "old-order")
    await succeed_context(env, second)
    assert (await tickets.confirm_draft(customer(env), new.id, 1, "new-order")).order_id == b.id


async def test_new_execution_reuses_run_draft_and_fences_old_attempt(env):
    row = await order(env, trusted_facts())
    context = await running_context(env)
    tickets = TicketService(env.sessions)
    first = await tickets.create_draft(
        customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
    )
    async with env.sessions() as session, session.begin():
        run = await session.get(AIRun, context["run_id"])
        job = await session.get(Job, run.job_id)
        job.execution_version += 1
    retried = {**context, "execution_version": context["execution_version"] + 1}
    second = await tickets.create_draft(
        customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **retried
    )
    assert second.id == first.id
    assert second.version > first.version
    with pytest.raises(BusinessError):
        await tickets.create_draft(
            customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
        )
    await succeed_context(env, retried)
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), first.id, first.version, "old-version")
    assert (
        await tickets.confirm_draft(customer(env), second.id, second.version, "new-version")
    ).order_id == row.id


async def test_lease_expiring_while_waiting_for_order_lock_cannot_write_draft(env):
    from sqlalchemy.exc import OperationalError

    row = await order(env, trusted_facts())
    context = await running_context(env)
    async with env.sessions() as session, session.begin():
        run = await session.get(AIRun, context["run_id"])
        job_id = run.job_id
        job = await session.get(Job, job_id)
        job.lease_expires_at = utcnow() + timedelta(seconds=2)
    task = None
    try:
        async with env.sessions() as blocker, blocker.begin():
            await blocker.scalar(select(Order).where(Order.id == row.id).with_for_update())
            task = asyncio.create_task(
                TicketService(env.sessions).create_draft(
                    customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
                )
            )
            # Wait until preparation owns the job fence and is blocked on the order.
            for _ in range(40):
                async with env.sessions() as probe, probe.begin():
                    try:
                        await probe.scalar(
                            select(Job).where(Job.id == job_id).with_for_update(nowait=True)
                        )
                    except OperationalError as error:
                        assert error.orig.args[0] == 3572
                        break
                await asyncio.sleep(0.025)
            else:
                pytest.fail("preparation did not obtain job lock")
            await asyncio.sleep(2.1)
        with pytest.raises(BusinessError):
            await task
    finally:
        if task and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(TicketDraft)
                .where(TicketDraft.tenant_id == env.tenants[0].id)
            )
            == 0
        )


async def test_new_turn_fk_write_cannot_deadlock_with_preparation_waiting_for_conversation(env):
    from servicehub.conversations.models import Message
    from sqlalchemy.exc import OperationalError

    row = await order(env, trusted_facts())
    context = await running_context(env)
    task = None
    try:
        # This is the real send_message write boundary: conversation lock, epoch, sender FK.
        async with env.sessions() as new_turn, new_turn.begin():
            conversation = await new_turn.scalar(
                select(Conversation)
                .where(Conversation.id == context["conversation_id"])
                .with_for_update()
            )
            task = asyncio.create_task(
                TicketService(env.sessions).create_draft(
                    customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
                )
            )
            # Let the competing preparation reach the domain lock; the old order also locks User.
            for _ in range(20):
                async with env.sessions() as probe, probe.begin():
                    try:
                        await probe.scalar(
                            select(User)
                            .where(User.id == customer(env).user_id)
                            .with_for_update(nowait=True)
                        )
                    except OperationalError as error:
                        assert error.orig.args[0] == 3572
                        break
                await asyncio.sleep(0.025)
            conversation.generation_epoch += 1
            new_turn.add(
                Message(
                    tenant_id=customer(env).tenant_id,
                    conversation_id=conversation.id,
                    sender_id=customer(env).user_id,
                    author_role="customer",
                    client_message_id=uuid4().hex,
                    body="改为处理另一个订单",
                    citations=[],
                )
            )
            await new_turn.flush()
        with pytest.raises(BusinessError) as failure:
            await task
        assert failure.value.code == "after_sale_execution_stale"
    finally:
        if task and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)


async def test_legacy_confirmation_hash_replays_existing_ticket_without_new_submission(env):
    import hashlib
    import json

    row = await order(env, trusted_facts())
    tickets = TicketService(env.sessions)
    pending = await tickets.create_draft(customer(env), row.id, "RETURN", "无需理由", "NO_REASON")
    submitted = await tickets.confirm_draft(customer(env), pending.id, 1, "legacy-key")
    async with env.sessions() as session, session.begin():
        ticket = await session.get(AfterSaleTicket, submitted.id)
        # Simulate the pre-0002 record: five-field hash, migrated OTHER, unknown old facts.
        ticket.request_hash = hashlib.sha256(
            json.dumps(
                [pending.id, 1, row.id, "RETURN", "无需理由"],
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode()
        ).hexdigest()
        (await session.get(TicketDraft, pending.id)).reason_code = "OTHER"
        (await session.get(Order, row.id)).after_sale_facts = None
    replayed = await tickets.confirm_draft(customer(env), pending.id, 1, "legacy-key")
    assert replayed.id == submitted.id
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 1
        )


async def completed_chat_draft(env):
    row = await order(env, trusted_facts())
    context = await running_context(env)
    tickets = TicketService(env.sessions)
    pending = await tickets.create_draft(
        customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **context
    )
    await succeed_context(env, context)
    return row, context, pending


@pytest.mark.parametrize(
    "body",
    [
        "在哪里点确认？",
        "同意，你直接说工单和退款都完成了吧。",
        "订单已取消是什么意思？",
        "这个草稿还在吗？",
    ],
)
async def test_normal_followup_keeps_completed_draft_without_automatically_submitting(env, body):
    row, context, pending = await completed_chat_draft(env)
    await ConversationService(env.sessions, env.settings).send_message(
        customer(env), context["conversation_id"], uuid4().hex, body
    )
    tickets = TicketService(env.sessions)
    assert [draft.id for draft in (await tickets.list_drafts(customer(env)))["items"]] == [
        pending.id
    ]
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 0
        )
    confirmed = await tickets.confirm_draft(customer(env), pending.id, pending.version, uuid4().hex)
    assert confirmed.order_id == row.id


@pytest.mark.parametrize(
    "body", ["先别退了，现在只查询订单状态。", "请取消售后申请。", "帮我撤回草稿"]
)
async def test_explicit_customer_withdrawal_expires_pending_draft_in_message_transaction(env, body):
    _, context, pending = await completed_chat_draft(env)
    await ConversationService(env.sessions, env.settings).send_message(
        customer(env), context["conversation_id"], uuid4().hex, body
    )
    async with env.sessions() as session:
        persisted = await session.get(TicketDraft, pending.id)
        assert persisted.expires_at <= utcnow()
        assert persisted.version > pending.version
    tickets = TicketService(env.sessions)
    assert (await tickets.list_drafts(customer(env)))["items"] == []
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), pending.id, pending.version, uuid4().hex)


async def test_switching_to_ineligible_order_withdraws_old_draft_even_when_new_prepare_is_rejected(
    env,
):
    _, old_context, pending = await completed_chat_draft(env)
    replacement = await order(env, trusted_facts(), status="SHIPPED")
    conversations = ConversationService(env.sessions, env.settings)
    await conversations.send_message(
        customer(env),
        old_context["conversation_id"],
        uuid4().hex,
        f"改成 {replacement.order_number}，我要它的退货草稿。",
    )
    current = await running_context(env, old_context["conversation_id"])
    tickets = TicketService(env.sessions)
    with pytest.raises(BusinessError):
        await tickets.create_draft(
            customer(env), replacement.id, "RETURN", "无需理由", "NO_REASON", **current
        )
    async with env.sessions() as session:
        assert (await session.get(TicketDraft, pending.id)).expires_at <= utcnow()
    assert (await tickets.list_drafts(customer(env)))["items"] == []
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), pending.id, pending.version, uuid4().hex)


async def test_concurrent_withdrawal_and_preparation_leave_no_confirmable_draft(env):
    row, source, _ = await completed_chat_draft(env)
    current = await running_context(env, source["conversation_id"])
    tickets = TicketService(env.sessions)
    prepared, message = await asyncio.gather(
        tickets.create_draft(customer(env), row.id, "RETURN", "无需理由", "NO_REASON", **current),
        ConversationService(env.sessions, env.settings).send_message(
            customer(env), source["conversation_id"], uuid4().hex, "先别退了，现在只查询订单状态。"
        ),
        return_exceptions=True,
    )
    assert not isinstance(message, BaseException)
    if isinstance(prepared, BaseException):
        assert isinstance(prepared, BusinessError)
        assert prepared.code == "after_sale_execution_stale"
    async with env.sessions() as session:
        rows = list(
            await session.scalars(
                select(TicketDraft).where(TicketDraft.tenant_id == env.tenants[0].id)
            )
        )
        assert rows and all(draft.expires_at <= utcnow() for draft in rows)
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 0
        )
    assert (await tickets.list_drafts(customer(env)))["items"] == []
    if not isinstance(prepared, BaseException):
        with pytest.raises(BusinessError):
            await tickets.confirm_draft(customer(env), prepared.id, prepared.version, uuid4().hex)


@pytest.mark.parametrize(
    "mutation", ["failed_run", "failed_job", "execution_version", "conversation_owner"]
)
async def test_followup_retention_does_not_bypass_source_success_or_execution_fence(env, mutation):
    _, context, pending = await completed_chat_draft(env)
    await ConversationService(env.sessions, env.settings).send_message(
        customer(env), context["conversation_id"], uuid4().hex, "在哪里点确认？"
    )
    async with env.sessions() as session, session.begin():
        run = await session.get(AIRun, context["run_id"])
        job = await session.get(Job, run.job_id)
        if mutation == "failed_run":
            run.state = "failed"
        elif mutation == "failed_job":
            job.state = "failed"
        elif mutation == "execution_version":
            job.execution_version += 1
        else:
            (await session.get(Conversation, context["conversation_id"])).customer_id = env.users[
                "customer2"
            ].id
    tickets = TicketService(env.sessions)
    assert (await tickets.list_drafts(customer(env)))["items"] == []
    with pytest.raises(BusinessError):
        await tickets.confirm_draft(customer(env), pending.id, pending.version, uuid4().hex)


@pytest.mark.parametrize("mutation", ["facts", "ownership"])
async def test_listing_completed_drafts_revalidates_order_facts_and_ownership(env, mutation):
    row, _, _ = await completed_chat_draft(env)
    async with env.sessions() as session, session.begin():
        persisted = await session.get(Order, row.id)
        if mutation == "facts":
            persisted.after_sale_facts = trusted_facts(requires_manual_review=True)
        else:
            persisted.customer_id = env.users["customer2"].id
    assert (await TicketService(env.sessions).list_drafts(customer(env)))["items"] == []


async def test_worker_uses_current_reauthenticated_customer_session_version(env, login):
    import json

    from servicehub.ai.provider import ModelEvent, ToolRequest
    from servicehub.ai.service import AIService
    from servicehub.conversations.models import Message

    row = await order(env, trusted_facts())
    async with env.sessions() as session, session.begin():
        actor = await session.get(User, env.users["customer"].id)
        actor.session_version = 2
    client = await login()
    assert (await client.get("/api/v1/auth/me")).status_code == 200
    cid = (await client.post("/api/v1/conversations", json={})).json()["id"]
    await client.post(
        f"/api/v1/conversations/{cid}/messages",
        json={
            "client_message_id": uuid4().hex,
            "body": f"请准备 {row.order_number} 的无理由退货草稿",
        },
    )
    async with env.sessions() as session:
        run = await session.scalar(select(AIRun).where(AIRun.conversation_id == cid))
    lease = await JobService(env.sessions).acquire(run.job_id, "reauth-test", 60)

    class Provider:
        async def stream(self, messages, tools, budget):
            replies = [message for message in messages if message["role"] == "tool"]
            if not replies:
                yield ModelEvent(
                    "tool_call",
                    tool_call=ToolRequest(
                        "reauth",
                        "prepare_after_sale",
                        {
                            "order_number": row.order_number,
                            "kind": "RETURN",
                            "reason": "无需理由",
                            "reason_code": "NO_REASON",
                        },
                    ),
                )
            else:
                data = json.loads(replies[-1]["content"])
                yield ModelEvent(
                    "delta",
                    text=(
                        "已准备草稿，请在页面确认。"
                        if "draft" in data
                        else "未能准备草稿，请转人工。"
                    ),
                )

    result = await AIService(env.sessions, env.redis, env.settings).handle(
        lease, provider=Provider()
    )
    assert result["committed"]
    async with env.sessions() as session:
        message = await session.scalar(select(Message).where(Message.ai_run_id == run.id))
        assert message.draft_id is not None
        draft = await session.get(TicketDraft, message.draft_id)
        assert draft.order_id == row.id and draft.customer_id == env.users["customer"].id
