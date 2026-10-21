from datetime import datetime

from sqlalchemy import JSON, BigInteger, ForeignKeyConstraint, Index, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, TenantEntity, UTCDateTime, tenant_constraints, utcnow


class Job(TenantEntity, Base):
    __tablename__ = "jobs"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "kind", "business_key"),
        Index("ix_jobs_schedule", "state", "available_at", "lease_expires_at", "id"),
    )
    kind: Mapped[str] = mapped_column(String(32))
    business_key: Mapped[str] = mapped_column(String(160))
    payload: Mapped[dict] = mapped_column(JSON)
    state: Mapped[str] = mapped_column(String(20), default="queued")
    execution_version: Mapped[int] = mapped_column(default=0)
    attempts: Mapped[int] = mapped_column(default=0)
    manual_retries: Mapped[int] = mapped_column(default=0)
    owner: Mapped[str | None] = mapped_column(String(80))
    lease_expires_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    available_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=utcnow)
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    error_code: Mapped[str | None] = mapped_column(String(64))
    error_message: Mapped[str | None] = mapped_column(Text)
    result: Mapped[dict | None] = mapped_column(JSON)


class OutboxEvent(TenantEntity, Base):
    __tablename__ = "outbox_events"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "job_id"], ["jobs.tenant_id", "jobs.id"]),
        Index("ix_outbox_schedule", "dispatched_at", "available_at", "lease_expires_at", "id"),
    )
    job_id: Mapped[int | None] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(32))
    payload: Mapped[dict] = mapped_column(JSON, default=dict)
    available_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=utcnow)
    dispatched_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    owner: Mapped[str | None] = mapped_column(String(80))
    lease_version: Mapped[int] = mapped_column(default=0)
    lease_expires_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    attempts: Mapped[int] = mapped_column(default=0)
