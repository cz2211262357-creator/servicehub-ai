import json
import logging
from datetime import timedelta

import pytest
from servicehub.conversations.models import Conversation
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job

pytestmark = pytest.mark.integration


async def test_summary_uses_real_tenant_rows_and_explicit_unknown_usage(env, login):
    staff = await login("agent")
    now = utcnow()
    async with env.sessions() as session, session.begin():
        session.add_all(
            [
                Conversation(
                    tenant_id=env.tenants[0].id,
                    customer_id=env.users["customer"].id,
                    status="WAITING_HUMAN",
                ),
                Conversation(
                    tenant_id=env.tenants[1].id,
                    customer_id=env.users["outsider"].id,
                    status="AI_ACTIVE",
                ),
                Conversation(
                    tenant_id=env.tenants[0].id,
                    customer_id=env.users["customer"].id,
                    created_at=now - timedelta(days=40),
                ),
                Job(
                    tenant_id=env.tenants[0].id,
                    kind="test",
                    business_key="metrics",
                    payload={},
                    state="failed",
                ),
            ]
        )
    result = await staff.get(
        "/api/v1/admin/metrics/summary",
        params={
            "start": (now - timedelta(days=1)).isoformat(),
            "end": (now + timedelta(hours=1)).isoformat(),
        },
    )
    assert result.status_code == 200, result.text
    data = result.json()
    assert data["conversations"] == 1 and data["waiting"] == 1 and data["failed_jobs"] == 1
    assert data["total_tokens"] is None and data["first_response_ms"] is None
    assert sum(day["count"] for day in data["daily"]) == 1


async def test_audits_metrics_and_runtime_do_not_cross_roles_or_tenants(env, login):
    admin, customer, agent = await login("admin"), await login(), await login("agent")
    await admin.patch(
        f"/api/v1/admin/members/{env.users['agent2'].id}", json={"display_name": "updated"}
    )
    data = (await admin.get("/api/v1/admin/audits")).json()
    assert data["items"] and {row["tenant_id"] for row in data["items"]} == {env.tenants[0].id}
    assert (await customer.get("/api/v1/admin/audits")).status_code == 403
    assert (await agent.get("/api/v1/metrics")).status_code == 403
    assert (await admin.get("/api/v1/metrics")).status_code == 200
    runtime = await admin.get("/api/v1/admin/runtime")
    assert runtime.status_code == 200
    assert "api_key" not in runtime.text.lower()


def test_structured_logger_omits_credentials():
    from servicehub.core.logging import JSONFormatter

    record = logging.LogRecord("servicehub", "INFO", __file__, 1, "login_ok", (), None)
    record.password = "private-password"
    record.api_key = "private-model-token"
    record.request_id = "trace-1"
    result = JSONFormatter().format(record)
    assert "private-password" not in result and "private-model-token" not in result
    assert json.loads(result)["request_id"] == "trace-1"
