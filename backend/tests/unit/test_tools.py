from decimal import Decimal

import pytest
from servicehub.auth.schemas import Principal
from servicehub.core.errors import BusinessError
from servicehub.orders.models import Order

pytestmark = pytest.mark.integration


async def test_customer_tools_reject_arbitrary_sql_refund_and_unowned_orders(env):
    from servicehub.ai.tools import ToolRegistry

    customer = env.users["customer"]
    principal = Principal(customer.id, customer.tenant_id, "customer")
    registry = ToolRegistry(env.sessions, env.redis, env.settings)
    for tool in ("run_sql", "refund", "delete_order", "unknown"):
        with pytest.raises(BusinessError):
            await registry.execute(principal, tool, {"sql": "SELECT * FROM users"})
    async with env.sessions() as session, session.begin():
        session.add(
            Order(
                tenant_id=principal.tenant_id,
                customer_id=env.users["customer2"].id,
                order_number="SOMEONE-ELSE",
                total_amount=Decimal("10.00"),
            )
        )
    with pytest.raises(BusinessError) as error:
        await registry.execute(principal, "lookup_order", {"order_number": "SOMEONE-ELSE"})
    assert error.value.status_code == 404
    with pytest.raises(BusinessError):
        await registry.execute(
            principal, "lookup_order", {"order_number": "SOMEONE-ELSE", "tenant_id": 999}
        )
