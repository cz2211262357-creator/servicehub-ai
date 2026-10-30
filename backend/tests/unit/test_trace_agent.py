from types import SimpleNamespace

import pytest
from servicehub.ai.agent import SupportAgent
from servicehub.ai.models import AIRun, TraceSpan
from servicehub.ai.provider import ModelEvent, ToolRequest
from servicehub.core.config import Settings
from servicehub.core.errors import BusinessError


class MemorySessions:
    """Minimal persistence boundary for agent attempt instrumentation unit tests."""

    def __init__(self):
        self.rows = []

    def __call__(self):
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    def begin(self):
        return self

    def add(self, row):
        row.id = len(self.rows) + 1
        self.rows.append(row)

    async def flush(self):
        pass

    async def scalars(self, query):
        return []

    async def get(self, model, key):
        if model is AIRun:
            return SimpleNamespace(generation_epoch=1)
        return next(row for row in self.rows if isinstance(row, model) and row.id == key)


async def test_agent_records_every_attempt_including_failure_and_real_fence(monkeypatch):
    contexts = []

    class Registry:
        schemas = []

        def __init__(self, *args, **context):
            contexts.append(context)
            self.retriever = self

        async def search(self, principal, query):
            return []

        async def execute(self, principal, name, arguments):
            raise BusinessError("order_not_found", "不能查询该订单", 404)

    class Provider:
        calls = 0

        async def stream(self, messages, tools, budget):
            self.calls += 1
            if self.calls == 1:
                yield ModelEvent(
                    "tool_call",
                    tool_call=ToolRequest("one", "lookup_order", {"order_number": "PRIVATE-ORDER"}),
                )
                yield ModelEvent(
                    "usage", usage={"prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6}
                )
            else:
                yield ModelEvent("usage", usage={"prompt_tokens": 9})
                raise BusinessError("model_timeout", "private provider details", 504)

    monkeypatch.setattr("servicehub.ai.agent.ToolRegistry", Registry)
    sessions = MemorySessions()
    cfg = Settings(_env_file=None)
    principal = SimpleNamespace(tenant_id=3)
    with pytest.raises(BusinessError) as failure:
        await SupportAgent(sessions, None, cfg, execution_version=8).run(
            principal, 11, 22, Provider()
        )
    assert failure.value.code == "model_timeout"
    spans = [row for row in sessions.rows if isinstance(row, TraceSpan)]
    assert [row.kind for row in spans] == ["retrieval", "model", "tool", "model"]
    assert [row.outcome for row in spans] == ["success", "success", "rejected", "failed"]
    assert spans[-1].usage == {"prompt_tokens": 9, "completion_tokens": None, "total_tokens": None}
    assert all(
        row.ai_run_id == 22 and row.tenant_id == 3 and row.execution_version == 8 for row in spans
    )
    assert contexts[-1] == {"run_id": 22, "conversation_id": 11, "execution_version": 8}
    assert "PRIVATE-ORDER" not in str(spans[2].arguments_summary)


def test_admin_trace_contract_is_registered():
    from servicehub.main import create_app

    schema = create_app(Settings(_env_file=None)).openapi()
    assert "/api/v1/admin/ai-runs/{run_id}/trace" in schema["paths"]
    operation = schema["paths"]["/api/v1/admin/ai-runs/{run_id}/trace"]["get"]
    assert operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"].endswith(
        "/RunTraceDTO"
    )


@pytest.mark.parametrize(
    "answer,expected_error,with_lookup",
    [
        ("请提供您要申请售后的订单号。", None, False),
        ("无法查询其他客户的订单，请提供您本人的订单号或转人工核实。", None, False),
        ("请提供订单号，签收七天内可以无理由退货。", "citation_missing", False),
        ("请提供订单号。[9999]", "citation_invalid", False),
        ("查到本人订单，状态为 DELIVERED。", None, True),
        ("订单已签收，所有商品都支持七天无理由退货。", "citation_missing", True),
        ("订单已签收，所有商品都支持无理由退货。", "citation_missing", True),
    ],
)
async def test_pure_context_requests_survive_irrelevant_search_hits_but_policy_and_fake_cites_do_not(
    monkeypatch, answer, expected_error, with_lookup
):
    from servicehub.ai.schemas import Evidence
    from servicehub.ai.tools import ToolResult

    class Registry:
        schemas = []

        def __init__(self, *args, **context):
            self.retriever = self

        async def search(self, principal, query):
            return [
                Evidence(
                    chunk_id=1,
                    document_id=1,
                    version_id=1,
                    page=1,
                    text="七天退货需符合完好条件",
                    score=1,
                )
            ]

        async def execute(self, principal, name, arguments):
            return ToolResult({"order": {"order_number": "TEST-1", "status": "DELIVERED"}})

    class Hub:
        def __init__(self, *args, **kwargs):
            pass

        async def publish(self, event):
            pass

    class Provider:
        async def stream(self, messages, tools, budget):
            if with_lookup and not any(message["role"] == "tool" for message in messages):
                yield ModelEvent(
                    "tool_call",
                    tool_call=ToolRequest(
                        "lookup-test", "lookup_order", {"order_number": "TEST-1"}
                    ),
                )
                return
            yield ModelEvent("delta", text=answer)
            yield ModelEvent(
                "usage", usage={"prompt_tokens": 3, "completion_tokens": 5, "total_tokens": 8}
            )

    monkeypatch.setattr("servicehub.ai.agent.ToolRegistry", Registry)
    monkeypatch.setattr("servicehub.ai.agent.RealtimeHub", Hub)
    agent = SupportAgent(MemorySessions(), None, Settings(_env_file=None))
    if expected_error:
        with pytest.raises(BusinessError) as error:
            await agent.run(SimpleNamespace(tenant_id=1), 1, 1, Provider())
        assert error.value.code == expected_error
    else:
        result = await agent.run(SimpleNamespace(tenant_id=1), 1, 1, Provider())
        assert result.text == answer
        assert result.citations == []
