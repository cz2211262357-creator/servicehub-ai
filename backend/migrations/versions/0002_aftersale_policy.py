"""Trusted after-sale facts and run-scoped draft fences."""

import sqlalchemy as sa
from alembic import op

revision = "0002_aftersale_policy"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("orders", sa.Column("after_sale_facts", sa.JSON(), nullable=True))
    op.add_column(
        "ticket_drafts",
        sa.Column("reason_code", sa.String(24), nullable=False, server_default="OTHER"),
    )
    op.add_column("ticket_drafts", sa.Column("run_id", sa.BigInteger(), nullable=True))
    op.add_column("ticket_drafts", sa.Column("conversation_id", sa.BigInteger(), nullable=True))
    op.add_column("ticket_drafts", sa.Column("execution_version", sa.Integer(), nullable=True))
    op.create_foreign_key(
        "fk_ticket_drafts_run",
        "ticket_drafts",
        "ai_runs",
        ["tenant_id", "run_id"],
        ["tenant_id", "id"],
    )
    op.create_foreign_key(
        "fk_ticket_drafts_conversation",
        "ticket_drafts",
        "conversations",
        ["tenant_id", "conversation_id"],
        ["tenant_id", "id"],
    )
    op.create_unique_constraint(
        "uq_ticket_drafts_tenant_run", "ticket_drafts", ["tenant_id", "run_id"]
    )


def downgrade():
    # MySQL may reuse the unique index for its child foreign-key index.
    op.drop_constraint("fk_ticket_drafts_conversation", "ticket_drafts", type_="foreignkey")
    op.drop_constraint("fk_ticket_drafts_run", "ticket_drafts", type_="foreignkey")
    op.drop_constraint("uq_ticket_drafts_tenant_run", "ticket_drafts", type_="unique")
    op.drop_column("ticket_drafts", "execution_version")
    op.drop_column("ticket_drafts", "conversation_id")
    op.drop_column("ticket_drafts", "run_id")
    op.drop_column("ticket_drafts", "reason_code")
    op.drop_column("orders", "after_sale_facts")
