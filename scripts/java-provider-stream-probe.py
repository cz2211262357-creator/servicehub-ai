"""Opt-in small real-provider stream probe; never records credentials or request headers."""

import json
import os
import sys
from pathlib import Path

import httpx


def main():
    workspace = Path(os.environ["SERVICEHUB_WORKSPACE_ROOT"]).resolve()
    report = Path(sys.argv[1]).resolve()
    if not report.is_relative_to(workspace / ".cache" / "java-verification"):
        raise ValueError("Only the private verification directory is allowed")
    tool = {
        "type": "function",
        "function": {
            "name": "lookup_order",
            "description": "Query the order explicitly requested by the customer.",
            "parameters": {
                "type": "object",
                "properties": {"order_number": {"type": "string"}},
                "required": ["order_number"],
                "additionalProperties": False,
            },
        },
    }
    body = {
        "model": os.environ["SERVICEHUB_MODEL_NAME"],
        "messages": [{"role": "user", "content": "请查询订单 ORD-SYSTEM-1000000 的状态。"}],
        "tools": [tool],
        "tool_choice": {"type": "function", "function": {"name": "lookup_order"}},
        "parallel_tool_calls": False,
        "max_tokens": 1600,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    rows = []
    with httpx.Client(timeout=45) as client:
        with client.stream(
            "POST",
            os.environ["SERVICEHUB_MODEL_BASE_URL"].rstrip("/") + "/chat/completions",
            headers={"Authorization": "Bearer " + os.environ["SERVICEHUB_MODEL_API_KEY"]},
            json=body,
        ) as response:
            if response.status_code != 200:
                raise RuntimeError(f"Provider probe status {response.status_code}")
            for line in response.iter_lines():
                if not line.startswith("data:"):
                    continue
                value = line[5:].strip()
                if value == "[DONE]":
                    break
                data = json.loads(value)
                rows.append(
                    {key: data[key] for key in ("model", "choices", "usage") if key in data}
                )
    report.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"frames": len(rows), "report": str(report)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
