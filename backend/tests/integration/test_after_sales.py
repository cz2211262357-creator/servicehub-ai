import asyncio
from datetime import timedelta
from decimal import Decimal

import pytest
from servicehub.after_sales.models import AfterSaleTicket
from servicehub.db.base import utcnow
from servicehub.operations.models import AuditLog
from servicehub.orders.models import Order
from sqlalchemy import func, select

pytestmark = pytest.mark.integration


async def draft(env, client, reason="商品收到后无法开机"):
    async with env.sessions() as session, session.begin():
        row = Order(
            tenant_id=env.tenants[0].id,
            customer_id=env.users["customer"].id,
            order_number=f"ORDER-{reason}",
            total_amount=Decimal("999.90"),
            after_sale_facts={
                "category": "electronics",
                "signed_at": (utcnow() - timedelta(days=1)).isoformat(),
                "return_condition": "intact",
                "exclusion_confirmed": False,
                "quality_issue": True,
                "warranty_until": (utcnow() + timedelta(days=365)).isoformat(),
                "requires_manual_review": False,
            },
        )
        session.add(row)
        await session.flush()
    result = await client.post(
        "/api/v1/after-sales/drafts",
        json={"order_id": row.id, "kind": "RETURN", "reason": reason, "reason_code": "QUALITY"},
    )
    assert result.status_code == 201, result.text
    return result.json()


async def test_draft_requires_confirmation_and_concurrent_confirmation_is_idempotent(env, login):
    client = await login()
    pending = await draft(env, client)
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 0
        )
    responses = await asyncio.gather(
        *[
            client.post(
                f"/api/v1/after-sales/drafts/{pending['id']}/confirm",
                json={"version": pending["version"], "idempotency_key": "confirm-1"},
            )
            for _ in range(20)
        ]
    )
    assert {row.status_code for row in responses} == {200}, [row.text for row in responses]
    assert len({row.json()["id"] for row in responses}) == 1
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(AfterSaleTicket)
                .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
            )
            == 1
        )


async def test_reusing_confirmation_key_for_other_application_returns_conflict(env, login):
    client = await login()
    a, b = await draft(env, client, "外观损坏"), await draft(env, client, "屏幕故障")
    payload = {"version": 1, "idempotency_key": "same-key"}
    assert (
        await client.post(f"/api/v1/after-sales/drafts/{a['id']}/confirm", json=payload)
    ).status_code == 200
    assert (
        await client.post(f"/api/v1/after-sales/drafts/{b['id']}/confirm", json=payload)
    ).status_code == 409


async def test_illegal_transition_is_rejected_and_audited(env, login):
    client, staff = await login(), await login("agent")
    pending = await draft(env, client)
    created = await client.post(
        f"/api/v1/after-sales/drafts/{pending['id']}/confirm",
        json={"version": 1, "idempotency_key": "transition-key"},
    )
    ticket_id = created.json()["id"]
    result = await staff.patch(
        f"/api/v1/after-sales/{ticket_id}/status",
        json={"status": "RESOLVED", "version": 1, "note": "直接完成"},
    )
    assert result.status_code == 409
    async with env.sessions() as session:
        rejected = await session.scalar(
            select(AuditLog).where(
                AuditLog.tenant_id == env.tenants[0].id,
                AuditLog.action == "ticket.transition",
                AuditLog.outcome == "rejected",
            )
        )
        assert rejected is not None


async def test_another_customer_cannot_confirm_or_read_draft(env, login):
    owner, stranger = await login(), await login("customer2")
    pending = await draft(env, owner)
    result = await stranger.post(
        f"/api/v1/after-sales/drafts/{pending['id']}/confirm",
        json={"version": 1, "idempotency_key": "forbidden-key"},
    )
    assert result.status_code == 404
