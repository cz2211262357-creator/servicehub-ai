from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field


class OrderItemDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    product_name: str
    sku: str
    quantity: int
    unit_price: Decimal


class OrderDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    order_number: str
    customer_id: int
    status: str
    total_amount: Decimal
    currency: str
    tracking_number: str | None
    carrier: str | None
    delivery_summary: str | None
    created_at: datetime
    items: list[OrderItemDTO] = Field(default_factory=list)


class OrderPage(BaseModel):
    items: list[OrderDTO]
    next_cursor: int | None = None
