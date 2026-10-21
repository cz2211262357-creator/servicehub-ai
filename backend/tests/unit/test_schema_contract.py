from datetime import UTC, datetime
from decimal import Decimal

import pytest
from sqlalchemy.dialects.mysql import dialect
from sqlalchemy.schema import CreateTable


def metadata():
    from servicehub.db.metadata import metadata

    return metadata


def test_all_business_tables_have_tenant_and_composite_identity():
    required = {
        "users",
        "conversations",
        "messages",
        "conversation_events",
        "orders",
        "order_items",
        "ticket_drafts",
        "after_sale_tickets",
        "ticket_events",
        "knowledge_documents",
        "document_versions",
        "knowledge_chunks",
        "knowledge_active_versions",
        "ai_runs",
        "tool_calls",
        "jobs",
        "outbox_events",
        "audit_logs",
    }
    tables = metadata().tables
    assert required <= tables.keys()
    for name in required:
        table = tables[name]
        assert "tenant_id" in table.c
        identities = {tuple(c.name for c in constraint.columns) for constraint in table.constraints}
        assert ("tenant_id", "id") in identities, name


def test_replay_and_final_answer_uniqueness_are_enforced_in_database():
    tables = metadata().tables

    def unique_columns(name):
        return {
            tuple(c.name for c in x.columns)
            for x in tables[name].constraints
            if x.__class__.__name__ == "UniqueConstraint"
        }

    assert ("tenant_id", "conversation_id", "client_message_id") in unique_columns("messages")
    assert ("tenant_id", "ai_run_id") in unique_columns("messages")
    assert ("tenant_id", "conversation_id", "seq") in unique_columns("conversation_events")
    assert ("tenant_id", "idempotency_key") in unique_columns("after_sale_tickets")
    assert ("tenant_id", "draft_id") in unique_columns("after_sale_tickets")
    assert ("tenant_id", "kind", "business_key") in unique_columns("jobs")


def test_all_tables_compile_as_mysql_and_financial_columns_are_decimal():
    for table in metadata().sorted_tables:
        ddl = str(CreateTable(table).compile(dialect=dialect()))
        assert "CREATE TABLE" in ddl
        if "tenant_id" in table.c:
            assert "FOREIGN KEY" in ddl
    assert metadata().tables["orders"].c.total_amount.type.asdecimal
    assert metadata().tables["orders"].c.total_amount.type.python_type is Decimal


def test_utc_type_rejects_ambiguous_naive_values_and_roundtrips_utc():
    from servicehub.db.base import UTCDateTime

    kind = UTCDateTime()
    now = datetime(2026, 10, 20, 15, 0, tzinfo=UTC)
    assert kind.process_bind_param(now, dialect()) == now.replace(tzinfo=None)
    assert kind.process_result_value(now.replace(tzinfo=None), dialect()) == now
    with pytest.raises(ValueError, match="timezone"):
        kind.process_bind_param(now.replace(tzinfo=None), dialect())
