from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from servicehub.after_sales.eligibility import AfterSaleFacts


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
    after_sale_facts: AfterSaleFacts | None = None

    @field_validator("after_sale_facts", mode="before")
    @classmethod
    def safe_trusted_facts(cls, value):
        # Legacy or malformed facts are unknown to callers, never silently authorized.
        try:
            return AfterSaleFacts.model_validate(value) if value is not None else None
        except (ValidationError, TypeError, ValueError):
            return None


class OrderPage(BaseModel):
    items: list[OrderDTO]
    next_cursor: int | None = None
