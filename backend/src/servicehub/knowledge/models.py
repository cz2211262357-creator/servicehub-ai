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

from servicehub.db.base import Base, TenantEntity, tenant_constraints


class KnowledgeDocument(TenantEntity, Base):
    __tablename__ = "knowledge_documents"
    __table_args__ = tenant_constraints(
        ForeignKeyConstraint(["tenant_id", "uploaded_by"], ["users.tenant_id", "users.id"]),
        Index("ix_knowledge_listing", "tenant_id", "deleted", "created_at", "id"),
    )
    title: Mapped[str] = mapped_column(String(200))
    uploaded_by: Mapped[int] = mapped_column(BigInteger)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)
    version: Mapped[int] = mapped_column(default=1)


class DocumentVersion(TenantEntity, Base):
    __tablename__ = "document_versions"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "document_id", "number"),
        UniqueConstraint("tenant_id", "document_id", "id"),
        ForeignKeyConstraint(
            ["tenant_id", "document_id"],
            ["knowledge_documents.tenant_id", "knowledge_documents.id"],
        ),
        ForeignKeyConstraint(["tenant_id", "job_id"], ["jobs.tenant_id", "jobs.id"]),
    )
    document_id: Mapped[int] = mapped_column(BigInteger)
    number: Mapped[int] = mapped_column()
    storage_key: Mapped[str] = mapped_column(String(100))
    filename: Mapped[str] = mapped_column(String(255))
    media_type: Mapped[str] = mapped_column(String(80))
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(24), default="queued")
    error_code: Mapped[str | None] = mapped_column(String(64))
    job_id: Mapped[int | None] = mapped_column(BigInteger)
    embedding_model: Mapped[str | None] = mapped_column(String(160))
    embedding_dimensions: Mapped[int | None] = mapped_column()


class KnowledgeActiveVersion(TenantEntity, Base):
    __tablename__ = "knowledge_active_versions"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "document_id"),
        ForeignKeyConstraint(
            ["tenant_id", "document_id"],
            ["knowledge_documents.tenant_id", "knowledge_documents.id"],
        ),
        ForeignKeyConstraint(
            ["tenant_id", "document_id", "version_id"],
            [
                "document_versions.tenant_id",
                "document_versions.document_id",
                "document_versions.id",
            ],
        ),
    )
    document_id: Mapped[int] = mapped_column(BigInteger)
    version_id: Mapped[int] = mapped_column(BigInteger)


class KnowledgeChunk(TenantEntity, Base):
    __tablename__ = "knowledge_chunks"
    __table_args__ = tenant_constraints(
        UniqueConstraint("tenant_id", "version_id", "ordinal"),
        ForeignKeyConstraint(
            ["tenant_id", "document_id", "version_id"],
            [
                "document_versions.tenant_id",
                "document_versions.document_id",
                "document_versions.id",
            ],
        ),
        Index("ix_chunks_version", "tenant_id", "version_id"),
        Index("ft_knowledge_text", "text", mysql_prefix="FULLTEXT", mysql_with_parser="ngram"),
    )
    document_id: Mapped[int] = mapped_column(BigInteger)
    version_id: Mapped[int] = mapped_column(BigInteger)
    ordinal: Mapped[int] = mapped_column()
    page: Mapped[int] = mapped_column()
    text: Mapped[str] = mapped_column(Text)
