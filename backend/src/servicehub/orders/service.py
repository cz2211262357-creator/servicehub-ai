from sqlalchemy import select

from servicehub.core.errors import BusinessError
from servicehub.orders.models import Order, OrderItem
from servicehub.orders.schemas import OrderDTO, OrderItemDTO


def authorized_orders(principal):
    query = select(Order).where(Order.tenant_id == principal.tenant_id)
    if principal.role == "customer":
        query = query.where(Order.customer_id == principal.user_id)
    return query


class OrderService:
    def __init__(self, sessions):
        self.sessions = sessions

    async def get_order(self, principal, order_number):
        async with self.sessions() as session:
            row = await session.scalar(
                authorized_orders(principal).where(Order.order_number == order_number)
            )
            if not row:
                raise BusinessError("not_found", "订单不存在或无权访问", 404)
            items = await session.scalars(
                select(OrderItem)
                .where(OrderItem.tenant_id == principal.tenant_id, OrderItem.order_id == row.id)
                .order_by(OrderItem.id)
            )
            return OrderDTO.model_validate(row).model_copy(
                update={"items": [OrderItemDTO.model_validate(item) for item in items]}
            )

    async def list(self, principal, cursor=None, limit=30, customer_id=None, search=None):
        async with self.sessions() as session:
            query = authorized_orders(principal)
            if customer_id and principal.role in ("admin", "agent"):
                query = query.where(Order.customer_id == customer_id)
            if cursor:
                query = query.where(Order.id < cursor)
            if search:
                query = query.where(Order.order_number == search)
            rows = list(await session.scalars(query.order_by(Order.id.desc()).limit(limit + 1)))
            return {
                "items": [OrderDTO.model_validate(row) for row in rows[:limit]],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }
