"""Opt-in real HTTP load against isolated MySQL/test Redis. No synthetic AI answers."""

import argparse
import json
import math
import os
import platform
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]


def percentile(samples, percent):
    if not samples:
        return None
    ordered = sorted(samples)
    point = (len(ordered) - 1) * percent / 100
    lower, upper = math.floor(point), math.ceil(point)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (point - lower)


def summarize(samples, elapsed):
    times = [latency for latency, _ in samples]
    return {
        "requests": len(samples),
        "elapsed_seconds": round(elapsed, 3),
        "throughput_rps": len(samples) / elapsed if elapsed > 0 else 0,
        "p50_ms": percentile(times, 50),
        "p95_ms": percentile(times, 95),
        "error_rate": sum(not passed for _, passed in samples) / len(samples) if samples else 0,
    }


async def require_ready(base_url):
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=5) as client:
            response = await client.get(base_url + "/api/v1/health/ready")
            response.raise_for_status()
    except httpx.HTTPError as exc:
        raise RuntimeError(f"dependency readiness failed: {type(exc).__name__}") from None


def execute_checked(command, output, metadata, *, environment=None):
    """Preserve the test process' exit status; reports never infer successful checks."""
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    measured = output.with_suffix(".measurements.json")
    measured.unlink(missing_ok=True)
    process = subprocess.run(
        command,
        cwd=ROOT,
        env=environment or os.environ,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    report = {
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "environment": {
            "os": platform.platform(),
            "python": platform.python_version(),
            "mysql": "8.4",
            "redis": "8.4",
            "api_processes": 2,
        },
        **metadata,
        "status": "passed" if process.returncode == 0 else "failed",
        "exit_code": process.returncode,
        "output_tail": process.stdout[-6000:] + process.stderr[-2000:],
    }
    if measured.exists():
        report["measurements"] = json.loads(measured.read_text(encoding="utf-8"))
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(report["output_tail"])
    print(f"Report: {output}; status={report['status']}")
    return process.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--users", type=int, default=8)
    parser.add_argument("--duration", type=float, default=10)
    parser.add_argument("--scenario", choices=["business", "live", "agent"], default="business")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/load-report.json")
    parser.add_argument("--allow-paid-models", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.users <= 100 or not 1 <= args.duration <= 300:
        parser.error("users must be 1..100; duration must be 1..300 seconds")
    if args.scenario == "agent" and not args.allow_paid_models:
        parser.error("agent uses real paid model calls; pass --allow-paid-models explicitly")
    config = {
        "users": args.users,
        "duration": args.duration,
        "scenario": args.scenario,
        "output": str(args.output.resolve().with_suffix(".measurements.json")),
    }
    environment = dict(os.environ, SERVICEHUB_LOAD_CONFIG=json.dumps(config))
    return execute_checked(
        [
            sys.executable,
            "-m",
            "pytest",
            "backend/tests/concurrency/test_scenarios.py::test_measured_workload",
            "-q",
            "-s",
        ],
        args.output,
        {
            "scenario": args.scenario,
            "model_mode": "online" if args.scenario == "agent" else "not_invoked",
            "virtual_users": args.users,
            "requested_duration": args.duration,
        },
        environment=environment,
    )


if __name__ == "__main__":
    raise SystemExit(main())
