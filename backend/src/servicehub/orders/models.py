from decimal import Decimal

from sqlalchemy import (
    JSON,
    BigInteger,
    ForeignKeyConstraint,
    Index,
    Numeric,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, TenantEntity, tenant_constraints


class Order(TenantEntity, Base):
    __tablename__ = "orders"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "order_number"),
        ForeignKeyConstraint(["tenant_id", "customer_id"], ["users.tenant_id", "users.id"]),
        Index("ix_orders_customer_created", "tenant_id", "customer_id", "created_at", "id"),
    )
    customer_id: Mapped[int] = mapped_column(BigInteger)
    order_number: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(24), default="DELIVERED")
    total_amount: Mapped[Decimal] = mapped_column(Numeric(12, 2))
    currency: Mapped[str] = mapped_column(String(3), default="CNY")
    tracking_number: Mapped[str | None] = mapped_column(String(80))
    carrier: Mapped[str | None] = mapped_column(String(80))
    delivery_summary: Mapped[str | None] = mapped_column(String(255))
    # Only trusted merchant/server ingestion writes these facts; customer/tool inputs cannot.
    after_sale_facts: Mapped[dict | None] = mapped_column(JSON, nullable=True)


class OrderItem(TenantEntity, Base):
    __tablename__ = "order_items"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "order_id"], ["orders.tenant_id", "orders.id"]),
    )
    order_id: Mapped[int] = mapped_column(BigInteger)
    product_name: Mapped[str] = mapped_column(String(200))
    sku: Mapped[str] = mapped_column(String(80))
    quantity: Mapped[int] = mapped_column()
    unit_price: Mapped[Decimal] = mapped_column(Numeric(12, 2))
