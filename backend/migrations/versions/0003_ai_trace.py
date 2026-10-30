"""Persistent, tenant-scoped AI attempt traces."""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import mysql

revision = "0003_ai_trace"
down_revision = "0002_aftersale_policy"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "ai_trace_spans",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("tenant_id", sa.BigInteger(), sa.ForeignKey("tenants.id"), nullable=False),
        sa.Column("created_at", mysql.DATETIME(fsp=6), nullable=False),
        sa.Column("ai_run_id", sa.BigInteger(), nullable=False),
        sa.Column("execution_version", sa.Integer(), nullable=False),
        sa.Column("kind", sa.String(24), nullable=False),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("started_at", mysql.DATETIME(fsp=6), nullable=False),
        sa.Column("finished_at", mysql.DATETIME(fsp=6)),
        sa.Column("latency_ms", sa.Integer()),
        sa.Column("outcome", sa.String(24), nullable=False),
        sa.Column("error_code", sa.String(64)),
        sa.Column("arguments_summary", sa.JSON(), nullable=False),
        sa.Column("model", sa.String(160)),
        sa.Column("usage", sa.JSON()),
        sa.Column("estimated_cost", sa.Numeric(20, 10)),
        sa.Column("cost_currency", sa.String(12)),
        sa.Column("price_version", sa.String(80)),
        sa.UniqueConstraint("tenant_id", "id"),
        sa.ForeignKeyConstraint(["tenant_id", "ai_run_id"], ["ai_runs.tenant_id", "ai_runs.id"]),
    )
    op.create_index(
        "ix_ai_trace_spans_run",
        "ai_trace_spans",
        ["tenant_id", "ai_run_id", "execution_version", "id"],
    )
    op.create_index("ix_ai_trace_spans_window", "ai_trace_spans", ["tenant_id", "started_at"])


def downgrade():
    op.drop_table("ai_trace_spans")
