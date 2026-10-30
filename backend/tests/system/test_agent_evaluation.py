"""Explicit real-service gates; online paid evaluation remains opt-in CLI only."""

import json
from pathlib import Path

import pytest
from servicehub.core.config import Settings

from evals.servicehub.runner import EvaluationRunner

pytestmark = pytest.mark.integration


async def test_controlled_development_cases_execute_actual_agent(env, tmp_path):

    settings = Settings(
        _env_file=None,
        environment="test",
        database_url=env.settings.database_url,
        redis_url=env.settings.redis_url,
        project_root=Path(__file__).resolve().parents[3],
        model_mode="demo",
        retrieval_backend="redis_hybrid",
    )
    output = tmp_path / "controlled-development.json"
    report = await EvaluationRunner(settings).run(output, dataset="dev")
    assert report["status"] == "completed"
    assert len(report["cases"]) == 5
    assert report["summary"]["model_task_accuracy"] is None
    assert report["summary"]["execution_error_cases"] == 0, json.dumps(
        report["cases"], ensure_ascii=False
    )
    assert all(case["checks"]["task_pass"] for case in report["cases"]), json.dumps(
        report["cases"], ensure_ascii=False
    )
    assert all(case["traces"] for case in report["cases"])
    assert any(
        call["name"] == "prepare_after_sale"
        for case in report["cases"]
        for call in case["tool_calls"]
    )
    assert any(turn["citations"] for case in report["cases"] for turn in case["turns"])


async def test_upstream_failures_record_only_actual_public_http_explanations(env, tmp_path):
    settings = Settings(
        _env_file=None,
        environment="test",
        database_url=env.settings.database_url,
        redis_url=env.settings.redis_url,
        project_root=Path(__file__).resolve().parents[3],
        model_mode="demo",
        retrieval_backend="redis_hybrid",
    )
    report = await EvaluationRunner(settings).run(
        tmp_path / "public-failure-notices.json", case_ids={"M13", "M14"}
    )
    assert report["summary"]["ai_run_state_counts"] == {"failed": 2}
    assert report["summary"]["ai_run_failure_rate"] == 1
    for case in report["cases"]:
        turn = case["turns"][0]
        assert turn["answer"] == ""
        assert turn["run_state"] == "failed"
        public = turn["public_failure_snapshot"]
        assert public["http_status"] == 200
        assert public["run_id"] == turn["run_id"]
        assert public["error_code"] == turn["error"]
        if public.get("error_message"):
            assert turn["response_kind"] == "public_failure_notice"
            assert turn["public_failure_notice"] == public["error_message"]
            assert case["checks"]["task_pass"]
        else:
            assert "public_failure_notice" not in turn
            assert not case["checks"]["task_pass"]
