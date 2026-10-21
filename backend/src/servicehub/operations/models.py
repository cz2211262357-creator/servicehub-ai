from sqlalchemy import JSON, BigInteger, ForeignKeyConstraint, Index, String
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, TenantEntity, tenant_constraints


class AuditLog(TenantEntity, Base):
    __tablename__ = "audit_logs"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "actor_id"], ["users.tenant_id", "users.id"]),
        Index("ix_audit_tenant_created", "tenant_id", "created_at", "id"),
    )
    actor_id: Mapped[int | None] = mapped_column(BigInteger)
    action: Mapped[str] = mapped_column(String(80))
    target_type: Mapped[str] = mapped_column(String(40))
    target_id: Mapped[str] = mapped_column(String(100))
    outcome: Mapped[str] = mapped_column(String(24))
    request_id: Mapped[str] = mapped_column(String(96))
    details: Mapped[dict] = mapped_column(JSON, default=dict)
