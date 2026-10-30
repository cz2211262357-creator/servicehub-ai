import pytest


def test_policy_units_are_deduplicated_before_recall_and_reciprocal_rank_cutoffs():
    from evals.servicehub.retrieval_benchmark import score_ranking

    result = score_ranking(["P01"] * 8 + ["P02", "P03", "P04", "P05", "P06"], ["P02", "P06"])
    assert result["recall_at_5"] == 0.5
    assert result["mrr_at_10"] == 0.5
    assert result["ranked_policy_ids"] == ["P01", "P02", "P03", "P04", "P05", "P06"]
    assert score_ranking(["P01"], ["P02"])["mrr_at_10"] == 0
    assert score_ranking([], ["P02"])["recall_at_5"] == 0
    with pytest.raises(ValueError):
        score_ranking(["P01"], [])


def test_multiple_relevant_policies_are_recall_not_binary_hit_rate():
    from evals.servicehub.retrieval_benchmark import score_ranking

    result = score_ranking(["P01", "P02", "P03"], ["P01", "P03", "P05", "P06"])
    assert result["recall_at_5"] == 0.5
    assert result["mrr_at_10"] == 1.0


def test_paired_bootstrap_keeps_case_pairs_and_reports_regression_truthfully():
    from evals.servicehub.retrieval_benchmark import paired_bootstrap

    result = paired_bootstrap([1, 1, 1], [0, 0, 0], samples=2000, seed=29)
    assert result["mean_delta"] == -1
    assert result["ci95"] == [-1, -1] and result["paired_samples"] == 3
    assert paired_bootstrap([0, 0.5, 1], [1, 0.5, 0], seed=2) == paired_bootstrap(
        [0, 0.5, 1], [1, 0.5, 0], seed=2
    )
    with pytest.raises(ValueError):
        paired_bootstrap([1], [])


def test_failed_queries_are_zero_in_primary_metrics_but_reported_separately():
    from evals.servicehub.retrieval_benchmark import summarize_variants

    records = [
        {
            "case_id": "a",
            "variants": {
                "legacy": {"recall_at_5": 1.0, "mrr_at_10": 1.0, "error_code": None},
                "new": {"recall_at_5": 0.0, "mrr_at_10": 0.0, "error_code": "timeout"},
            },
        }
    ]
    summary = summarize_variants(records, ["legacy", "new"])
    assert summary["variants"]["new"]["recall_at_5"] == 0
    assert summary["variants"]["new"]["error_count"] == 1
    assert summary["new_minus_legacy"]["recall_at_5"]["mean_delta"] == -1
    assert summary["comparison_claim"] == "incomplete_due_to_errors"


async def test_case_orchestration_passes_only_the_frozen_question_and_records_errors():
    from types import SimpleNamespace

    from evals.servicehub.retrieval_benchmark import benchmark_case

    seen = []

    async def retrieve(query, variant):
        seen.append((query, variant))
        if variant == "new":
            raise TimeoutError("private transport detail")
        return [SimpleNamespace(chunk_id=7, document_id=10, score=0.02)]

    case = {
        "id": "policy-a",
        "user_input": "原始问题",
        "relevant_policy_ids": ["P01"],
        "reference_answer": "must never reach retriever",
    }
    row = await benchmark_case(case, ["legacy", "new"], retrieve, {10: "P01"}, timeout_seconds=1)
    assert seen == [("原始问题", "legacy"), ("原始问题", "new")]
    assert row["variants"]["legacy"]["recall_at_5"] == 1
    assert row["variants"]["new"]["error_code"] == "query_timeout"
    assert row["variants"]["new"]["ranked_policy_ids"] == []
    assert "private transport detail" not in str(row)
    assert row["new_minus_legacy"]["recall_at_5"] == -1


def test_retrieval_cli_is_modular_and_defaults_to_all_four_fair_variants(tmp_path):
    from evals.servicehub.cli import build_parser

    args = build_parser().parse_args(
        [
            "retrieval",
            "--report",
            str(tmp_path / "report.json"),
            "--embedding-cache",
            str(tmp_path / "vectors.json"),
        ]
    )
    assert args.backends == ["legacy", "new", "bm25", "vector"]
    assert args.handler.__name__ == "run_retrieval"


def test_error_rows_cannot_contribute_a_nonzero_success_score():
    from evals.servicehub.retrieval_benchmark import summarize_variants

    row = {
        "case_id": "one",
        "variants": {
            "legacy": {"recall_at_5": 1, "mrr_at_10": 1, "error_code": None},
            "new": {"recall_at_5": 1, "mrr_at_10": 1, "error_code": "timeout"},
        },
    }
    summary = summarize_variants([row], ["legacy", "new"])
    assert summary["variants"]["new"]["recall_at_5"] == 0
    assert summary["new_minus_legacy"]["recall_at_5"]["mean_delta"] == -1


async def test_fatal_setup_failure_writes_an_incomplete_report_without_online_calls(
    tmp_path, monkeypatch
):
    import json
    from argparse import Namespace

    from evals.servicehub.retrieval_benchmark import run_retrieval

    # Fail before any service/provider is created: the report must still exist.
    def unavailable(*args):
        raise RuntimeError("private-key-must-not-appear")

    monkeypatch.setattr("evals.servicehub.cli.evaluation_settings", unavailable)
    report = tmp_path / "partial.json"
    args = Namespace(
        report=report,
        embedding_cache=tmp_path / "cache.json",
        env_file=None,
        backends=["legacy", "new", "bm25", "vector"],
        query_timeout_seconds=1,
    )
    assert await run_retrieval(args) == 1
    data = json.loads(report.read_text())
    assert data["status"] == "failed" and data["policy_samples_measured"] == 0
    assert data["summary"]["variants"]["new"]["recall_at_5"] is None
    assert "private-key-must-not-appear" not in report.read_text()
    assert not (tmp_path / "cache.json").exists()


async def test_fairness_gate_rejects_any_vector_difference_between_indices():
    from evals.servicehub.retrieval_benchmark import verify_index_vectors

    class Redis:
        def __init__(self):
            self.values = {"old:1": b"same", "new:1": b"same"}

        async def execute_command(self, command, key, field, **options):
            assert command == "HGET" and field == "embedding" and options == {"NEVER_DECODE": True}
            return self.values.get(key)

    redis = Redis()
    assert (
        await verify_index_vectors(redis, [(1, [1, 2])], "old:", "new:", lambda vector: b"same")
        == 1
    )
    redis.values["new:1"] = b"different"
    with pytest.raises(ValueError, match="identical"):
        await verify_index_vectors(redis, [(1, [1, 2])], "old:", "new:", lambda vector: b"same")
