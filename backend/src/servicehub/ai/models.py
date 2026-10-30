from datetime import datetime
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

from servicehub.db.base import Base, TenantEntity, UTCDateTime, tenant_constraints


class AIRun(TenantEntity, Base):
    __tablename__ = "ai_runs"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "conversation_id", "generation_epoch"),
        ForeignKeyConstraint(
            ["tenant_id", "conversation_id", "trigger_event_seq"],
            [
                "conversation_events.tenant_id",
                "conversation_events.conversation_id",
                "conversation_events.seq",
            ],
        ),
        ForeignKeyConstraint(["tenant_id", "job_id"], ["jobs.tenant_id", "jobs.id"]),
    )
    conversation_id: Mapped[int] = mapped_column(BigInteger)
    trigger_event_seq: Mapped[int] = mapped_column(BigInteger)
    generation_epoch: Mapped[int] = mapped_column()
    job_id: Mapped[int] = mapped_column(BigInteger)
    state: Mapped[str] = mapped_column(String(24), default="queued")
    provider: Mapped[str] = mapped_column(String(80), default="demo")
    model: Mapped[str | None] = mapped_column(String(160))
    usage: Mapped[dict | None] = mapped_column(JSON)
    started_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    latency_ms: Mapped[int | None] = mapped_column()
    error_code: Mapped[str | None] = mapped_column(String(64))


class ToolCall(TenantEntity, Base):
    __tablename__ = "tool_calls"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "ai_run_id"], ["ai_runs.tenant_id", "ai_runs.id"]),
        UniqueConstraint("tenant_id", "ai_run_id", "execution_version", "step"),
    )
    ai_run_id: Mapped[int] = mapped_column(BigInteger)
    execution_version: Mapped[int] = mapped_column()
    step: Mapped[int] = mapped_column()
    tool_name: Mapped[str] = mapped_column(String(80))
    arguments_summary: Mapped[dict] = mapped_column(JSON, default=dict)
    outcome: Mapped[str] = mapped_column(String(24))
    latency_ms: Mapped[int] = mapped_column(default=0)
    error_code: Mapped[str | None] = mapped_column(String(64))


class TraceSpan(TenantEntity, Base):
    __tablename__ = "ai_trace_spans"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "ai_run_id"], ["ai_runs.tenant_id", "ai_runs.id"]),
        Index("ix_ai_trace_spans_run", "tenant_id", "ai_run_id", "execution_version", "id"),
        Index("ix_ai_trace_spans_window", "tenant_id", "started_at"),
    )
    ai_run_id: Mapped[int] = mapped_column(BigInteger)
    execution_version: Mapped[int] = mapped_column()
    kind: Mapped[str] = mapped_column(String(24))
    name: Mapped[str] = mapped_column(String(80))
    started_at: Mapped[datetime] = mapped_column(UTCDateTime())
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    latency_ms: Mapped[int | None] = mapped_column()
    outcome: Mapped[str] = mapped_column(String(24))
    error_code: Mapped[str | None] = mapped_column(String(64))
    arguments_summary: Mapped[dict] = mapped_column(JSON, default=dict)
    model: Mapped[str | None] = mapped_column(String(160))
    usage: Mapped[dict | None] = mapped_column(JSON)
    estimated_cost: Mapped[Decimal | None] = mapped_column(Numeric(20, 10))
    cost_currency: Mapped[str | None] = mapped_column(String(12))
    price_version: Mapped[str | None] = mapped_column(String(80))
