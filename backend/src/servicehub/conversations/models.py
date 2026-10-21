from datetime import datetime

from sqlalchemy import (
    JSON,
    BigInteger,
    CheckConstraint,
    ForeignKeyConstraint,
    Index,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column

from servicehub.db.base import Base, TenantEntity, UTCDateTime, tenant_constraints, utcnow


class Conversation(TenantEntity, Base):
    __tablename__ = "conversations"
    __table_args__ = tenant_constraints(
        CheckConstraint(
            "status IN ('AI_ACTIVE','WAITING_HUMAN','HUMAN_ACTIVE','CLOSED')", name="status"
        ),
        ForeignKeyConstraint(["tenant_id", "customer_id"], ["users.tenant_id", "users.id"]),
        ForeignKeyConstraint(["tenant_id", "assigned_agent_id"], ["users.tenant_id", "users.id"]),
        Index("ix_conversations_queue", "tenant_id", "status", "updated_at", "id"),
        Index("ix_conversations_customer", "tenant_id", "customer_id", "updated_at", "id"),
    )
    customer_id: Mapped[int] = mapped_column(BigInteger)
    assigned_agent_id: Mapped[int | None] = mapped_column(BigInteger)
    title: Mapped[str] = mapped_column(String(120), default="售后咨询")
    status: Mapped[str] = mapped_column(String(24), default="AI_ACTIVE")
    version: Mapped[int] = mapped_column(default=1)
    generation_epoch: Mapped[int] = mapped_column(default=0)
    last_event_seq: Mapped[int] = mapped_column(BigInteger, default=0)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=utcnow)
    claimed_at: Mapped[datetime | None] = mapped_column(UTCDateTime())
    closed_at: Mapped[datetime | None] = mapped_column(UTCDateTime())


class ConversationEvent(TenantEntity, Base):
    __tablename__ = "conversation_events"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "conversation_id", "seq"),
        ForeignKeyConstraint(
            ["tenant_id", "conversation_id"], ["conversations.tenant_id", "conversations.id"]
        ),
    )
    conversation_id: Mapped[int] = mapped_column(BigInteger)
    seq: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(40))
    generation_epoch: Mapped[int] = mapped_column(default=0)
    payload: Mapped[dict] = mapped_column(JSON, default=dict)


class Message(TenantEntity, Base):
    __tablename__ = "messages"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "conversation_id", "client_message_id"),
        UniqueConstraint("tenant_id", "ai_run_id"),
        ForeignKeyConstraint(
            ["tenant_id", "conversation_id"], ["conversations.tenant_id", "conversations.id"]
        ),
        ForeignKeyConstraint(["tenant_id", "sender_id"], ["users.tenant_id", "users.id"]),
        ForeignKeyConstraint(["tenant_id", "ai_run_id"], ["ai_runs.tenant_id", "ai_runs.id"]),
        Index("ix_messages_timeline", "tenant_id", "conversation_id", "created_at", "id"),
    )
    conversation_id: Mapped[int] = mapped_column(BigInteger)
    sender_id: Mapped[int | None] = mapped_column(BigInteger)
    author_role: Mapped[str] = mapped_column(String(20))
    client_message_id: Mapped[str | None] = mapped_column(String(80))
    ai_run_id: Mapped[int | None] = mapped_column(BigInteger)
    body: Mapped[str] = mapped_column(Text)
    citations: Mapped[list] = mapped_column(JSON, default=list)
    draft_id: Mapped[int | None] = mapped_column(BigInteger)
