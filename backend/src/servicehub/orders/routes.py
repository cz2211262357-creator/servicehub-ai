from fastapi import APIRouter, Query, Request

from servicehub.core.dependencies import Authenticated
from servicehub.orders.schemas import OrderDTO, OrderPage
from servicehub.orders.service import OrderService

router = APIRouter(prefix="/api/v1/orders", tags=["orders"])


@router.get("", response_model=OrderPage)
async def orders(
    request: Request,
    principal: Authenticated,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
    customer_id: int | None = Query(default=None, gt=0),
    search: str | None = Query(default=None, max_length=40),
):
    return await OrderService(request.app.state.sessions).list(
        principal, cursor, limit, customer_id, search
    )


@router.get("/{order_number}", response_model=OrderDTO)
async def order(order_number: str, request: Request, principal: Authenticated):
    return await OrderService(request.app.state.sessions).get_order(principal, order_number)
