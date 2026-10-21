from decimal import Decimal

import pytest
from servicehub.orders.models import Order, OrderItem

pytestmark = pytest.mark.integration


@pytest.fixture
async def order(env):
    async with env.sessions() as session, session.begin():
        row = Order(
            tenant_id=env.tenants[0].id,
            customer_id=env.users["customer"].id,
            order_number="SH-20260001",
            total_amount=Decimal("299.90"),
        )
        session.add(row)
        await session.flush()
        session.add(
            OrderItem(
                tenant_id=row.tenant_id,
                order_id=row.id,
                product_name="降噪耳机",
                sku="SH-EAR-01",
                quantity=1,
                unit_price=Decimal("299.90"),
            )
        )
    return row


async def test_owned_order_decimal_and_unauthorized_customers(env, login, order):
    owner = await login()
    result = await owner.get("/api/v1/orders/SH-20260001")
    assert result.status_code == 200, result.text
    assert result.json()["total_amount"] == "299.90"
    assert result.json()["items"][0]["quantity"] == 1
    for user in ("customer2", "outsider"):
        client = await login(user)
        assert (await client.get("/api/v1/orders/SH-20260001")).status_code == 404
    staff = await login("agent")
    assert (await staff.get("/api/v1/orders/SH-20260001")).status_code == 200
