"""Run actual worker process termination / dedicated test Redis outage experiments."""

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.load_test import ROOT, execute_checked  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["worker", "redis", "all"], default="all")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/fault-report.json")
    args = parser.parse_args()
    selection = {
        "worker": "worker_process",
        "redis": "redis_outage",
        "all": "worker_process or redis_outage",
    }[args.scenario]
    return execute_checked(
        [
            sys.executable,
            "-m",
            "pytest",
            "backend/tests/concurrency/test_scenarios.py",
            "-q",
            "-k",
            selection,
        ],
        args.output,
        {"scenario": args.scenario, "model_mode": "not_invoked"},
        environment=dict(os.environ, SERVICEHUB_RUN_FAULTS="1"),
    )


if __name__ == "__main__":
    raise SystemExit(main())
