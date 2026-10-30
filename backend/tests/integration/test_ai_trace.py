from datetime import timedelta
from uuid import uuid4

import pytest
from servicehub.ai.models import AIRun, TraceSpan
from servicehub.ai.provider import ModelEvent
from servicehub.ai.service import AIService
from servicehub.ai.tracing import RunTracer
from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow
from servicehub.jobs.handlers import RetryableJobError
from servicehub.jobs.service import JobService
from sqlalchemy import select

pytestmark = pytest.mark.integration


async def queued(env, login):
    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": uuid4().hex, "body": "请查询订单"},
    )
    async with env.sessions() as session:
        run = await session.scalar(select(AIRun).where(AIRun.conversation_id == cid))
    return run


class FailedProvider:
    async def stream(self, messages, tools, budget):
        yield ModelEvent("usage", usage={"prompt_tokens": 13})
        raise BusinessError("model_timeout", "private raw prompt must not be logged", 504)


async def test_failed_model_usage_is_durable_and_trace_is_admin_tenant_scoped(env, login):
    run = await queued(env, login)
    jobs = JobService(env.sessions)
    lease = await jobs.acquire(run.job_id, "trace-test", 60)
    with pytest.raises(RetryableJobError):
        await AIService(env.sessions, env.redis, env.settings).handle(
            lease, provider=FailedProvider()
        )
    await jobs.fail(lease, "model_timeout", retryable=False)
    admin, customer, agent = await login("admin"), await login(), await login("agent")
    path = f"/api/v1/admin/ai-runs/{run.id}/trace"
    response = await admin.get(path)
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["state"] == "failed" and data["run_id"] == run.id
    assert [row["kind"] for row in data["spans"]] == ["retrieval", "model"]
    model = data["spans"][1]
    assert model["outcome"] == "failed" and model["error_code"] == "model_timeout"
    assert model["usage"] == {"prompt_tokens": 13, "completion_tokens": None, "total_tokens": None}
    assert model["started_at"].endswith("Z") and model["finished_at"].endswith("Z")
    assert "private raw prompt" not in response.text
    assert (await customer.get(path)).status_code == 403
    assert (await agent.get(path)).status_code == 403
    # Use the same HTTP authentication path for an administrator of tenant two.
    from servicehub.auth.models import User

    async with env.sessions() as session, session.begin():
        otheradmin = User(
            tenant_id=env.tenants[1].id,
            email="other-admin@example.com",
            display_name="other admin",
            role="admin",
            password_hash=env.users["admin"].password_hash,
        )
        session.add(otheradmin)
        await session.flush()
    env.users["otheradmin"] = otheradmin
    other = await login("otheradmin")
    hidden = await other.get(path)
    missing = await other.get(f"/api/v1/admin/ai-runs/{run.id + 999999}/trace")
    assert hidden.status_code == missing.status_code == 404
    assert hidden.json()["error"]["code"] == missing.json()["error"]["code"]


async def test_retries_and_tool_failure_spans_keep_safe_arguments_and_known_tokens(env, login):
    run = await queued(env, login)
    tracer = RunTracer(env.sessions, env.settings, run.tenant_id, run.id, 1)
    with pytest.raises(ValueError):
        async with tracer.span(
            "tool", "lookup_order", arguments={"order_number": "PRIVATE-ORDER", "api_key": "secret"}
        ):
            raise ValueError("private customer contents")
    async with tracer.span("model", "chat.completions", model=env.settings.model_name) as state:
        state["usage"] = {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
    retry_tracer = RunTracer(env.sessions, env.settings, run.tenant_id, run.id, 2)
    async with retry_tracer.span("model", "chat.completions", model=env.settings.model_name):
        pass
    now = utcnow()
    async with env.sessions() as session, session.begin():
        saved = await session.get(AIRun, run.id)
        saved.state, saved.finished_at, saved.latency_ms = "failed", now, 105
    admin = await login("admin")
    response = await admin.get(
        "/api/v1/admin/metrics/summary",
        params={
            "start": (now - timedelta(days=1)).isoformat(),
            "end": (now + timedelta(days=1)).isoformat(),
        },
    )
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["ai_failed"] == 1 and data["ai_failure_rate"] == 1
    assert data["ai_p95_latency_ms"] == 105 and data["model_calls"] == 2
    assert data["tool_calls"] == 1 and data["total_tokens"] == 13
    assert data["usage_coverage"] == 0.5 and data["estimated_cost"] is None
    async with env.sessions() as session:
        spans = list(
            await session.scalars(
                select(TraceSpan).where(TraceSpan.ai_run_id == run.id).order_by(TraceSpan.id)
            )
        )
    assert spans[0].outcome == "failed" and spans[0].error_code == "unexpected_error"
    assert "PRIVATE-ORDER" not in str(spans[0].arguments_summary) and "secret" not in str(
        spans[0].arguments_summary
    )
    assert [row.execution_version for row in spans] == [1, 1, 2]


@pytest.mark.parametrize(
    "text,code", [("可退货 [999999]", "citation_invalid"), ("可退货", "citation_missing")]
)
async def test_commit_rejects_invalid_or_missing_citation_even_with_valid_attached_evidence(
    env, login, monkeypatch, text, code
):
    from servicehub.ai.agent import AgentResult
    from servicehub.ai.schemas import Evidence

    run = await queued(env, login)
    lease = await JobService(env.sessions).acquire(run.job_id, "citation-check", 60)
    service = AIService(env.sessions, env.redis, env.settings)
    evidence = Evidence(chunk_id=11, document_id=1, version_id=1, page=1, text="可信资料", score=1)

    async def revalidate(principal, rows):
        return rows

    monkeypatch.setattr(service.retriever, "revalidate", revalidate)
    with pytest.raises(BusinessError) as failure:
        await service.commit_result(
            lease, run.generation_epoch, AgentResult(text, [evidence], [], None)
        )
    assert failure.value.code == code
