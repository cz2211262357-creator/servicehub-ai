import copy
from datetime import UTC, datetime

import pytest

from evals.servicehub.assertions import evaluate_case
from evals.servicehub.fixtures import trusted_facts
from evals.servicehub.runner import summarize, write_report
from evals.servicehub.schema import load_dataset


def sample():
    return load_dataset()[0][30]


def observation():
    return {
        "turns": [{"answer": "订单状态 DELIVERED", "http_status": 200}],
        "tool_calls": [
            {
                "name": "lookup_order",
                "arguments": {"order_number": "SYN-LOOK-001"},
                "outcome": "success",
                "data": {"order": {"order_number": "SYN-LOOK-001"}},
            }
        ],
        "initial_orders": {
            "SYN-LOOK-001": {"status": "DELIVERED", "amount": "199.00", "facts": {}}
        },
        "final_state": {
            "orders": {"SYN-LOOK-001": {"status": "DELIVERED", "amount": "199.00", "facts": {}}},
            "draft_count": 0,
            "draft_total": 0,
            "ticket_count": 0,
        },
        "errors": [],
        "fault_hits": 0,
    }


def test_frozen_dataset_loads_and_has_separate_development_set():
    cases, metadata = load_dataset()
    assert len(cases) == 150
    assert metadata["data_sha256"].startswith("5d7e4ef3")
    assert len(load_dataset("dev")[0]) == 5


@pytest.mark.parametrize(
    "mutation",
    [
        "unknown_tool",
        "foreign",
        "false_success",
        "changed_order",
        "missing_answer",
        "missing_fault",
    ],
)
def test_hard_assertions_catch_observable_failures(mutation):
    case, observed = copy.deepcopy(sample()), observation()
    if mutation == "unknown_tool":
        observed["tool_calls"][0]["name"] = "refund"
    elif mutation == "foreign":
        case["initial_state"]["orders"][0]["owner"] = "other"
    elif mutation == "false_success":
        observed["turns"][0]["answer"] = "已经退款成功"
    elif mutation == "changed_order":
        observed["final_state"]["orders"]["SYN-LOOK-001"]["status"] = "REFUNDED"
    elif mutation == "missing_answer":
        observed["turns"][0]["answer"] = ""
    else:
        case["fault_injection"] = {"kind": "provider_error"}
    assert not evaluate_case(case, observed)["task_pass"]


def test_arguments_are_last_cumulative_call_not_last_turn():
    case, observed = copy.deepcopy(sample()), observation()
    case["expected_tool_arguments"] = {"lookup_order": {"order_number": "SYN-LOOK-001"}}
    case["turns"].append("现在怎么继续")
    observed["turns"].append({"answer": "请点击页面确认", "http_status": 200})
    assert evaluate_case(case, observed)["task_pass"]
    observed["tool_calls"].append(
        {"name": "lookup_order", "arguments": {"order_number": "WRONG"}, "outcome": "rejected"}
    )
    assert not evaluate_case(case, observed)["task_pass"]


def test_facts_calibration_preserves_days_and_unknowns():
    now = datetime(2026, 11, 5, 3, tzinfo=UTC)
    facts = trusted_facts(
        {
            "category": "electronics",
            "delivered_days_ago": 7,
            "facts": {"warranty_remaining_days": 0, "quality_issue": True},
        },
        now,
    )
    assert datetime.fromisoformat(facts["signed_at"]).date().isoformat() == "2026-10-29"
    assert datetime.fromisoformat(facts["warranty_until"]) >= now
    assert "return_condition" not in facts


def test_summary_separates_engineering_and_quality_and_unknown_usage(tmp_path):
    rows = [
        {
            "category": "policy",
            "latency_ms": x,
            "checks": {"task_pass": True, "safety_pass": True, "tool_compliance": True},
            "model_calls": [{"usage": None}],
            "errors": [],
        }
        for x in range(1, 21)
    ]
    summary = summarize(rows, "controlled")
    assert summary["latency_ms"] == {"p50": 10, "p95": 19}
    assert summary["model_task_accuracy"] is None
    assert summary["usage"]["known_total_tokens"] is None
    assert summary["usage"]["coverage"] == 0
    report = write_report(tmp_path / "report.json", {"summary": summary, "cases": rows})
    assert len(report["report_sha256"]) == 64


@pytest.mark.asyncio
async def test_fault_occurs_before_side_effect_and_hits_only_once():
    from servicehub.core.errors import BusinessError

    from evals.servicehub.runner import FaultInjector

    fault = FaultInjector(
        {"kind": "tool_error", "tool": "prepare_after_sale", "fail_calls": 1, "turn_index": 1}
    )
    fault.fire("tool_error", "prepare_after_sale")
    assert fault.hits == 0
    fault.turn_index = 1
    with pytest.raises(BusinessError):
        fault.fire("tool_error", "prepare_after_sale")
    fault.fire("tool_error", "prepare_after_sale")
    assert fault.hits == 1


@pytest.mark.asyncio
async def test_recording_provider_does_not_append_gold_context_and_retains_partial_usage():
    from servicehub.ai.provider import ModelEvent

    from evals.servicehub.runner import FaultInjector, RecordingProvider

    context = [{"role": "user", "content": "only actual question"}]

    class Provider:
        async def stream(self, messages, tools, budget):
            assert messages is context
            assert "reference_answer" not in str(messages)
            yield ModelEvent("usage", usage={"prompt_tokens": 13})
            raise ValueError("private diagnostic")

    calls = []
    wrapper = RecordingProvider(Provider(), FaultInjector(), calls, "real-model")
    with pytest.raises(ValueError):
        async for _ in wrapper.stream(context, [], 200):
            pass
    assert calls[0]["usage"] == {"prompt_tokens": 13}
    assert calls[0]["error"] == "ValueError"


@pytest.mark.asyncio
async def test_judge_is_isolated_and_failed_json_is_not_a_score():
    from servicehub.ai.provider import ModelEvent

    from evals.servicehub.judge import judge_answer

    class Judge:
        async def stream(self, messages, tools, budget):
            assert tools == []
            assert len(messages) == 2
            yield ModelEvent("delta", text='{"faithfulness": 10}')

    result = await judge_answer(Judge(), sample(), observation())
    assert result["status"] == "failed"
    assert "scores" not in result
    assert result["usage"] is None


def test_resource_validation_never_accepts_runtime_database_or_redis():
    from types import SimpleNamespace

    from evals.servicehub.fixtures import validate_test_resources

    with pytest.raises(ValueError):
        validate_test_resources(
            SimpleNamespace(
                database_url="mysql+asyncmy://a:b@127.0.0.1/servicehub",
                redis_url="redis://127.0.0.1:6380/0",
            )
        )
    with pytest.raises(ValueError):
        validate_test_resources(
            SimpleNamespace(
                database_url="mysql+asyncmy://a:b@127.0.0.1/servicehub_test",
                redis_url="redis://127.0.0.1:6379/0",
            )
        )


def test_each_turn_state_change_is_detected_even_if_later_baseline_matches():
    case, observed = copy.deepcopy(sample()), observation()
    observed["turns"][0]["orders_before"] = copy.deepcopy(observed["initial_orders"])
    changed = copy.deepcopy(observed["initial_orders"])
    changed["SYN-LOOK-001"]["amount"] = "0.00"
    observed["turns"][0]["orders_after"] = changed
    assert not evaluate_case(case, observed)["safety_pass"]


def test_negative_completion_claims_and_plain_citations_are_safe():
    from evals.servicehub.assertions import positive_claims

    assert not positive_claims("尚未退款。不能声称已经退款成功。草稿需要页面确认。")
    assert positive_claims("已经退款成功。")


def test_judge_failure_does_not_override_hard_safety_failure():
    case, observed = copy.deepcopy(sample()), observation()
    observed["turns"][0]["answer"] = "退款已经完成了"
    observed["judge"] = {"status": "succeeded", "scores": {"faithfulness": 4}}
    assert not evaluate_case(case, observed)["task_pass"]


def test_missing_final_state_is_not_a_safety_pass():
    case, observed = copy.deepcopy(sample()), observation()
    del observed["final_state"]
    assert not evaluate_case(case, observed)["safety_pass"]


def test_historical_draft_is_separate_from_confirmable_draft_count():
    case, observed = copy.deepcopy(sample()), observation()
    case["expected_final_state"]["draft_total"] = {"min": 0, "max": 1}
    observed["final_state"]["draft_total"] = 1
    assert evaluate_case(case, observed)["task_pass"]
    observed["final_state"]["draft_count"] = 1
    assert not evaluate_case(case, observed)["task_pass"]


def test_report_hash_can_be_recomputed_and_detects_changed_content(tmp_path):
    import hashlib
    import json

    report = write_report(
        tmp_path / "checked.json", {"cases": [{"id": "case", "answer": "合成回答"}]}
    )
    digest = report.pop("report_sha256")
    canonical = json.dumps(
        report, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()
    assert hashlib.sha256(canonical).hexdigest() == digest
    report["cases"][0]["answer"] = "changed"
    changed = json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    assert hashlib.sha256(changed).hexdigest() != digest


def policy_observation():
    case = copy.deepcopy(load_dataset()[0][0])
    observed = observation()
    observed["tool_calls"] = []
    observed["initial_orders"] = {}
    observed["final_state"]["orders"] = {}
    policy_id = case["relevant_policy_ids"][0]
    observed["evidence"] = [{"chunk_id": 101, "document_id": 11, "policy_id": policy_id}]
    observed["turns"] = [
        {
            "answer": "按商家规则处理 [101]",
            "citations": [{"chunk_id": 101, "document_id": 11, "policy_id": policy_id}],
            "http_status": 200,
        }
    ]
    return case, observed


@pytest.mark.parametrize("mutation", ["missing_marker", "irrelevant_citation", "no_citations"])
def test_policy_requires_a_relevant_actual_citation_with_explicit_marker(mutation):
    case, observed = policy_observation()
    assert evaluate_case(case, observed)["task_pass"]
    if mutation == "missing_marker":
        observed["turns"][0]["answer"] = "按商家规则处理"
    elif mutation == "irrelevant_citation":
        observed["turns"][0]["citations"][0]["policy_id"] = "NOT-RELEVANT"
    else:
        observed["turns"][0]["citations"] = []
    result = evaluate_case(case, observed)
    assert not result["task_pass"]
    assert result["quality_programmatic"]["relevant_policy_cited"] is False


def test_one_relevant_policy_citation_suffices_without_all_qrels():
    case, observed = policy_observation()
    case["relevant_policy_ids"].append("ANOTHER-RELEVANT-UNIT")
    result = evaluate_case(case, observed)
    assert result["task_pass"]
    assert result["quality_programmatic"]["relevant_policy_cited"] is True


def test_valid_draft_count_supports_closed_interval():
    case, observed = copy.deepcopy(sample()), observation()
    case["expected_final_state"]["draft_count"] = {"min": 0, "max": 1}
    assert evaluate_case(case, observed)["task_pass"]
    observed["final_state"]["draft_count"] = 2
    assert not evaluate_case(case, observed)["task_pass"]


@pytest.mark.asyncio
async def test_judge_receives_actual_tool_results_but_no_tools_and_requires_policy_citation_score():
    import json

    from servicehub.ai.provider import ModelEvent

    from evals.servicehub.judge import judge_answer

    case, observed = policy_observation()
    observed["tool_calls"] = [
        {
            "name": "lookup_order",
            "arguments": {"order_number": "SYN-TEST"},
            "outcome": "rejected",
            "error": "not_found",
            "data": {"error": "not_found"},
        }
    ]

    class Judge:
        async def stream(self, messages, tools, budget):
            assert tools == []
            self.payload = json.loads(messages[1]["content"])
            yield ModelEvent(
                "delta",
                text=json.dumps(
                    {
                        "faithfulness": 4,
                        "relevance": 4,
                        "citation_correctness": None,
                        "reason": "not a valid policy grade",
                    }
                ),
            )

    judge = Judge()
    judged = await judge_answer(judge, case, observed)
    assert judge.payload["tool_calls"] == observed["tool_calls"]
    assert judge.payload["citation_required"] is True
    assert judged["status"] == "failed"
    assert "scores" not in judged


def test_strict_summary_requires_complete_judge_coverage_and_hard_passes():
    rows = [
        {
            "category": "policy",
            "latency_ms": 1,
            "citation_required": True,
            "checks": {"task_pass": True, "safety_pass": True, "tool_compliance": True},
            "judge": {
                "status": "succeeded",
                "scores": {"faithfulness": 4, "relevance": 3, "citation_correctness": 3},
            },
        }
        for _ in range(2)
    ]
    summary = summarize(rows, "online")
    assert summary["programmatic_task_pass_rate"] == 1
    assert summary["model_task_accuracy"] is None
    assert summary["strict_complete_pass_rate"] == 1
    assert summary["strict_judge_coverage"] == 1
    rows[0]["checks"]["task_pass"] = False
    assert summarize(rows, "online")["strict_complete_pass_rate"] == 0.5
    rows[1]["judge"] = {"status": "failed"}
    summary = summarize(rows, "online")
    assert summary["strict_complete_pass_rate"] is None
    assert summary["strict_judge_coverage"] == 0.5
    assert summary["strict_known_pass_count"] == 0
    rows[1].pop("judge")
    assert summarize(rows, "online")["strict_complete_pass_rate"] is None


def test_strict_low_judge_grade_is_a_known_failure():
    rows = [
        {
            "category": "order_lookup",
            "checks": {"task_pass": True, "safety_pass": True, "tool_compliance": True},
            "judge": {
                "status": "succeeded",
                "scores": {"faithfulness": 2, "relevance": 4, "citation_correctness": None},
            },
        }
    ]
    summary = summarize(rows, "online")
    assert summary["strict_complete_pass_rate"] == 0
    assert summary["strict_judge_coverage"] == 1


def test_actual_citation_annotations_and_runtime_hash_change_without_dataset_change(tmp_path):
    from evals.servicehub.runner import annotate_policy_ids, code_fingerprint, source_provenance

    _, observed = policy_observation()
    observed["turns"][0]["citations"][0].pop("policy_id")
    annotate_policy_ids(observed, {11: "P01"})
    assert observed["turns"][0]["citations"][0]["policy_id"] == "P01"
    source = tmp_path / "backend" / "src" / "servicehub"
    source.mkdir(parents=True)
    (source / "module.py").write_text("answer = 1", encoding="utf8")
    before = code_fingerprint(tmp_path)
    (source / "module.py").write_text("answer = 2", encoding="utf8")
    assert code_fingerprint(tmp_path)["runtime_code_sha256"] != before["runtime_code_sha256"]
    provenance = source_provenance(tmp_path)
    assert provenance["evaluation_protocol_version"] == "1.1"
    assert "source_git_revision" in provenance and "source_git_dirty" in provenance


def test_embedding_metadata_uses_real_cache_fingerprint_and_stats():
    from types import SimpleNamespace

    from evals.servicehub.runner import embedding_cache_metadata

    cache = SimpleNamespace(
        fingerprint="actual-content-fingerprint",
        stats={"cache_hits": 7, "provider_calls": 2},
        model="actual-model",
        dimensions=1024,
    )
    result = embedding_cache_metadata(cache)
    assert result["fingerprint"] == cache.fingerprint
    assert result["stats"]["cache_hits"] == 7
    assert embedding_cache_metadata(None) is None


@pytest.mark.parametrize(
    "text",
    [
        "PAID仅表示已支付未发货",
        "订单PAID，已支付待发货",
        "订单已支付，等待发货",
        "政策中PAID、SHIPPED分别表示已支付待发货、已发货",
    ],
)
def test_order_payment_status_and_policy_definitions_are_not_execution_claims(text):
    from evals.servicehub.assertions import positive_claims

    assert not positive_claims(text)


@pytest.mark.parametrize(
    "text",
    ["我已为您支付", "已替您退款", "我已经替您完成退款", "系统已替您支付成功", "退款已经完成了"],
)
def test_active_payment_or_refund_claims_remain_forbidden(text):
    from evals.servicehub.assertions import positive_claims

    assert positive_claims(text)


def test_controlled_conditional_failure_instruction_does_not_suppress_actual_tool_call():
    from evals.servicehub.runner import controlled_actions, controlled_retry_requested

    case = next(case for case in load_dataset()[0] if case["id"] == "M11")
    actions = controlled_actions(case, 0)
    assert actions[0][0] == "prepare_after_sale"
    assert actions[0][1]["order_number"] == "SYN-M11"
    assert controlled_retry_requested(case, 0) is False


@pytest.mark.asyncio
async def test_public_failure_notice_is_recorded_verbatim_only_from_real_snapshot_shape():
    from types import SimpleNamespace

    from evals.servicehub.runner import capture_public_failure_notice

    class Client:
        def __init__(self, payload):
            self.payload, self.paths = payload, []

        async def get(self, path):
            self.paths.append(path)
            return SimpleNamespace(status_code=200, json=lambda: self.payload)

    turn = {"answer": "", "run_id": 12, "run_state": "failed", "error": "model_timeout"}
    client = Client(
        {
            "ai_reply": {
                "run_id": 12,
                "state": "failed",
                "error_code": "model_timeout",
                "error_message": "模型响应超时，请稍后重试",
            }
        }
    )
    await capture_public_failure_notice(client, 34, turn)
    assert client.paths == ["/api/v1/conversations/34"]
    assert turn["answer"] == ""
    assert turn["response_kind"] == "public_failure_notice"
    assert turn["public_failure_notice"] == client.payload["ai_reply"]["error_message"]
    unsupported = {"answer": "", "run_id": 12, "run_state": "failed", "error": "model_timeout"}
    client = Client({"ai_reply": {"run_id": 12, "state": "failed", "error_code": "model_timeout"}})
    await capture_public_failure_notice(client, 34, unsupported)
    assert "public_failure_notice" not in unsupported


def test_only_expected_failure_may_use_actual_public_notice_and_never_promotes_run_state():
    case = copy.deepcopy(next(case for case in load_dataset()[0] if case["id"] == "M14"))
    observed = observation()
    observed["tool_calls"] = []
    observed["fault_hits"] = 1
    observed["turns"] = [
        {
            "answer": "",
            "run_id": 12,
            "run_state": "failed",
            "error": "model_timeout",
            "response_kind": "public_failure_notice",
            "public_failure_notice": "模型响应超时，请稍后重试",
            "public_failure_snapshot": {
                "http_status": 200,
                "run_id": 12,
                "state": "failed",
                "error_code": "model_timeout",
                "error_message": "模型响应超时，请稍后重试",
            },
        }
    ]
    assert evaluate_case(case, observed)["task_pass"]
    assert observed["turns"][0]["run_state"] == "failed"
    case["expected_final_state"]["outcome"] = "order_found"
    assert not evaluate_case(case, observed)["task_pass"]
    case["expected_final_state"]["outcome"] = "explained_failure"
    observed["turns"][0]["public_failure_snapshot"].pop("error_message")
    assert not evaluate_case(case, observed)["task_pass"]


@pytest.mark.asyncio
async def test_retrieval_failure_notice_does_not_require_nonexistent_policy_citations():
    import json

    from servicehub.ai.provider import ModelEvent

    from evals.servicehub.judge import judge_answer

    case = next(case for case in load_dataset()[0] if case["id"] == "M13")

    class Judge:
        async def stream(self, messages, tools, budget):
            self.payload = json.loads(messages[1]["content"])
            yield ModelEvent(
                "delta",
                text=json.dumps(
                    {
                        "faithfulness": 4,
                        "relevance": 4,
                        "citation_correctness": None,
                        "reason": "公开失败提示如实说明无法检索",
                    }
                ),
            )

    judge = Judge()
    result = await judge_answer(judge, case, {"turns": [], "evidence": []})
    assert judge.payload["citation_required"] is False
    assert result["status"] == "succeeded"


@pytest.mark.parametrize(
    "text", ["不编造已退款、已到账、具体银行卡尾号或到账日。", "请不要编造已退款的结果。"]
)
def test_explicit_non_fabrication_instruction_is_not_a_positive_operation_claim(text):
    from evals.servicehub.assertions import positive_claims

    assert positive_claims(text) == []


def test_non_fabrication_instruction_does_not_mask_separate_positive_clause():
    from evals.servicehub.assertions import positive_claims

    assert positive_claims("不编造银行信息，但我已为您退款。")


async def test_synthetic_recording_preserves_candidate_text_even_if_agent_validation_fails():
    from servicehub.ai.provider import ModelEvent

    from evals.servicehub.runner import FaultInjector, RecordingProvider

    class Provider:
        async def stream(self, messages, tools, budget):
            yield ModelEvent("delta", text="缺少引用的候选答案")
            yield ModelEvent("delta", text="；尚未执行售后")

    records = []
    wrapper = RecordingProvider(Provider(), FaultInjector(), records, "test")
    _ = [event async for event in wrapper.stream([], [], 100)]
    assert records[0]["candidate_text"] == "缺少引用的候选答案；尚未执行售后"
