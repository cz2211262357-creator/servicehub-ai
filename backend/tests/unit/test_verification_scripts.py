import sys

import pytest

from scripts.load_test import execute_checked, percentile, require_ready, summarize


def test_percentiles_and_error_rates_describe_actual_samples():
    assert percentile([10, 20, 30, 40, 50], 95) == 48
    assert percentile([10], 50) == 10
    stats = summarize([(10, True), (20, False), (30, True)], elapsed=2)
    assert stats["requests"] == 3
    assert stats["p50_ms"] == 20
    assert stats["p95_ms"] == 29
    assert stats["throughput_rps"] == 1.5
    assert stats["error_rate"] == pytest.approx(1 / 3)
    assert summarize([], elapsed=2)["p95_ms"] is None


def test_failed_business_assertion_exits_nonzero_and_cannot_be_reported_passed(tmp_path):
    output = tmp_path / "failure.json"
    output.with_suffix(".measurements.json").write_text('{"requests": 999}')
    code = execute_checked(
        [sys.executable, "-c", "raise AssertionError('business invariant violated')"],
        output,
        {"scenario": "unit"},
    )
    import json

    report = json.loads(output.read_text())
    assert code != 0
    assert "measurements" not in report
    assert report["status"] == "failed"
    assert "business invariant violated" in report["output_tail"]


async def test_missing_dependency_stops_the_run_explicitly():
    with pytest.raises(RuntimeError, match="dependency"):
        await require_ready("http://127.0.0.1:1")
