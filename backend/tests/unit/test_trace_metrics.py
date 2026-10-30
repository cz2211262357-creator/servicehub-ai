from decimal import Decimal
from types import SimpleNamespace

import pytest


def test_trace_arguments_never_persist_private_text_or_unlisted_keys():
    from servicehub.ai.tracing import safe_arguments

    raw = {
        "order_number": "SH-CUSTOMER-9234",
        "query": "客户秘密",
        "reason": "私密理由",
        "kind": "RETURN",
        "reason_code": "NO_REASON",
        "api_key": "secret",
        "messages": ["private"],
    }
    safe = safe_arguments(raw)
    assert safe["kind"] == "RETURN" and safe["reason_code"] == "NO_REASON"
    assert safe["query"]["length"] == 4 and len(safe["query"]["sha256"]) == 64
    assert safe["order_number"]["sha256"] and "9234" not in str(safe)
    assert all(
        value not in str(safe)
        for value in ["客户秘密", "私密理由", "secret", "private", "SH-CUSTOMER"]
    )
    assert "api_key" not in safe and "messages" not in safe


def test_usage_keeps_unknowns_and_does_not_derive_total():
    from servicehub.ai.tracing import normalize_usage, sum_usage

    assert normalize_usage({"prompt_tokens": 7}) == {
        "prompt_tokens": 7,
        "completion_tokens": None,
        "total_tokens": None,
    }
    assert normalize_usage({"total_tokens": -1, "completion_tokens": "12"}) == {
        "prompt_tokens": None,
        "completion_tokens": None,
        "total_tokens": None,
    }
    assert sum_usage([{"prompt_tokens": 7}, {"completion_tokens": 3}]) == {
        "prompt_tokens": None,
        "completion_tokens": None,
        "total_tokens": None,
    }
    assert sum_usage([{"prompt_tokens": 7}, {"prompt_tokens": 2}])["prompt_tokens"] == 9


def test_cost_needs_explicit_prices_version_model_and_complete_usage():
    from servicehub.ai.tracing import estimate_cost

    cfg = SimpleNamespace(
        model_name="qwen-plus",
        model_input_price_per_million=Decimal("2"),
        model_output_price_per_million=Decimal("8"),
        model_price_version="2026-10",
        model_price_currency="CNY",
    )
    assert estimate_cost(
        cfg, "qwen-plus", {"prompt_tokens": 100, "completion_tokens": 50}
    ) == Decimal("0.0006")
    assert estimate_cost(cfg, "other", {"prompt_tokens": 100, "completion_tokens": 50}) is None
    assert estimate_cost(cfg, "qwen-plus", {"prompt_tokens": 100}) is None
    cfg.model_price_version = ""
    assert estimate_cost(cfg, "qwen-plus", {"prompt_tokens": 100, "completion_tokens": 50}) is None


def test_metric_failure_denominator_p95_and_partial_usage_are_honest():
    from servicehub.operations.metrics import summarize_ai

    runs = [
        SimpleNamespace(state=state, latency_ms=latency)
        for state, latency in [("succeeded", 10), ("failed", 100), ("cancelled", 9999)]
    ]
    spans = [
        SimpleNamespace(
            kind="model",
            usage={"prompt_tokens": 9, "total_tokens": 12},
            estimated_cost=None,
            cost_currency=None,
            price_version=None,
        ),
        SimpleNamespace(
            kind="model", usage=None, estimated_cost=None, cost_currency=None, price_version=None
        ),
        SimpleNamespace(
            kind="tool", usage=None, estimated_cost=None, cost_currency=None, price_version=None
        ),
    ]
    result = summarize_ai(runs, spans)
    assert result["ai_failure_rate"] == 0.5
    assert result["ai_cancelled"] == 1 and result["ai_terminal_samples"] == 2
    assert result["ai_p95_latency_ms"] == 100 and result["ai_latency_samples"] == 2
    assert result["model_calls"] == 2 and result["tool_calls"] == 1
    assert result["total_tokens"] == 12 and result["usage_coverage"] == 0.5
    assert result["completion_tokens"] is None and result["estimated_cost"] is None
    assert summarize_ai([], [])["ai_failure_rate"] is None


def test_citations_reject_fabricated_or_missing_and_only_attach_cited_evidence():
    from servicehub.ai.tracing import cited_evidence
    from servicehub.core.errors import BusinessError

    evidence = [SimpleNamespace(chunk_id=11), SimpleNamespace(chunk_id=22)]
    assert cited_evidence("按政策 [11]", evidence, required=True) == evidence[:1]
    with pytest.raises(BusinessError) as invalid:
        cited_evidence("可退款 [999]", evidence, required=True)
    assert invalid.value.code == "citation_invalid"
    with pytest.raises(BusinessError) as missing:
        cited_evidence("可以退货", evidence, required=True)
    assert missing.value.code == "citation_missing"


def test_failed_run_latency_is_derived_from_finished_window_without_inventing_zero():
    from datetime import UTC, datetime, timedelta

    from servicehub.operations.metrics import summarize_ai

    started = datetime(2026, 10, 29, tzinfo=UTC)
    run = SimpleNamespace(
        state="failed",
        latency_ms=None,
        started_at=started,
        finished_at=started + timedelta(milliseconds=240),
    )
    assert summarize_ai([run], [])["ai_p95_latency_ms"] == 240
    run.started_at = None
    assert summarize_ai([run], [])["ai_p95_latency_ms"] is None


def test_badly_typed_tool_arguments_do_not_break_redaction():
    from servicehub.ai.tracing import safe_arguments

    assert safe_arguments({"kind": ["secret"], "reason_code": {"private": 1}}) == {}


@pytest.mark.parametrize("usage", ["not-a-usage-object", [12], 42])
def test_malformed_provider_usage_stays_unknown_without_breaking_trace_finalization(usage):
    from servicehub.ai.tracing import normalize_usage

    assert normalize_usage(usage) == {
        "prompt_tokens": None,
        "completion_tokens": None,
        "total_tokens": None,
    }
