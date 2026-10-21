from sqlalchemy import Boolean, CheckConstraint, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, Identity, TenantEntity, tenant_constraints


class Tenant(Identity, Base):
    __tablename__ = "tenants"
    name: Mapped[str] = mapped_column(String(120))


class User(TenantEntity, Base):
    __tablename__ = "users"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "email"),
        CheckConstraint("role IN ('admin','agent','customer')", name="role"),
    )
    email: Mapped[str] = mapped_column(String(254))
    display_name: Mapped[str] = mapped_column(String(80))
    password_hash: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(20))
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    session_version: Mapped[int] = mapped_column(default=1)
