from datetime import datetime

from sqlalchemy import (
    BigInteger,
    Boolean,
    ForeignKeyConstraint,
    Index,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, TenantEntity, UTCDateTime, tenant_constraints


class TicketDraft(TenantEntity, Base):
    __tablename__ = "ticket_drafts"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "customer_id"], ["users.tenant_id", "users.id"]),
        ForeignKeyConstraint(["tenant_id", "order_id"], ["orders.tenant_id", "orders.id"]),
    )
    customer_id: Mapped[int] = mapped_column(BigInteger)
    order_id: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(24))
    reason: Mapped[str] = mapped_column(Text)
    version: Mapped[int] = mapped_column(default=1)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime())
    consumed: Mapped[bool] = mapped_column(Boolean, default=False)


class AfterSaleTicket(TenantEntity, Base):
    __tablename__ = "after_sale_tickets"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "draft_id"),
        UniqueConstraint("tenant_id", "idempotency_key"),
        ForeignKeyConstraint(
            ["tenant_id", "draft_id"], ["ticket_drafts.tenant_id", "ticket_drafts.id"]
        ),
        ForeignKeyConstraint(["tenant_id", "customer_id"], ["users.tenant_id", "users.id"]),
        ForeignKeyConstraint(["tenant_id", "order_id"], ["orders.tenant_id", "orders.id"]),
        Index("ix_tickets_status_created", "tenant_id", "status", "created_at", "id"),
    )
    draft_id: Mapped[int] = mapped_column(BigInteger)
    customer_id: Mapped[int] = mapped_column(BigInteger)
    order_id: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(24))
    reason: Mapped[str] = mapped_column(Text)
    status: Mapped[str] = mapped_column(String(24), default="SUBMITTED")
    version: Mapped[int] = mapped_column(default=1)
    idempotency_key: Mapped[str] = mapped_column(String(80))
    request_hash: Mapped[str] = mapped_column(String(64))


class TicketEvent(TenantEntity, Base):
    __tablename__ = "ticket_events"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(
            ["tenant_id", "ticket_id"], ["after_sale_tickets.tenant_id", "after_sale_tickets.id"]
        ),
        ForeignKeyConstraint(["tenant_id", "actor_id"], ["users.tenant_id", "users.id"]),
    )
    ticket_id: Mapped[int] = mapped_column(BigInteger)
    actor_id: Mapped[int] = mapped_column(BigInteger)
    from_status: Mapped[str | None] = mapped_column(String(24))
    to_status: Mapped[str] = mapped_column(String(24))
    note: Mapped[str] = mapped_column(String(500), default="")
