"""Freeze schema and synthetic wire examples; never read .env, live DB or user data."""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/verification/servicehub-python-openapi-2026-10-22.json"
EXPECTED_SHA256 = "1a66dbcdce53dfed0af857486aa1a6f58c01d6c6f1c434b81d473ad57fa37b17"
DEST = ROOT / "contracts/python-v1"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options"}


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def artifacts():
    raw = SOURCE.read_bytes()
    if hashlib.sha256(raw).hexdigest() != EXPECTED_SHA256:
        raise SystemExit(
            "Frozen Python OpenAPI changed: review the difference before updating its digest."
        )
    schema = json.loads(raw)
    operations = []
    for path, item in sorted(schema["paths"].items()):
        for method, operation in sorted(item.items()):
            if method in METHODS:
                operations.append(
                    {
                        "id": f"H{len(operations) + 1:02}",
                        "method": method.upper(),
                        "path": path,
                        "operation": operation,
                    }
                )
    if len(operations) != 39:
        raise SystemExit(f"Expected 39 legacy operations, found {len(operations)}")
    extension = [
        {
            "id": "N01",
            "method": "POST",
            "path": "/api/v1/conversations/{conversation_id}/interrupt",
            "input": {"expected_generation_epoch": "nonnegative integer"},
        },
        {"id": "N02", "method": "GET", "path": "/api/v1/conversations/{conversation_id}/topics"},
        {
            "id": "N03",
            "method": "POST",
            "path": "/api/v1/conversations/{conversation_id}/topics/switch",
            "input": {
                "mode": "NEW or RESUME",
                "topic_id": "optional positive integer",
                "expected_generation_epoch": "nonnegative integer",
                "pending_message_id": "optional positive integer",
            },
        },
    ]
    # Read route declarations rather than guessing that OpenAPI includes WebSocket routes.
    routes = ast.parse(
        (ROOT / "backend/src/servicehub/realtime/routes.py").read_text(encoding="utf-8")
    )
    ws_paths = [
        n.args[0].value
        for n in ast.walk(routes)
        if isinstance(n, ast.Call)
        and isinstance(n.func, ast.Attribute)
        and n.func.attr == "websocket"
    ]
    if ws_paths != ["/api/v1/conversations/{conversation_id}/live"]:
        raise SystemExit("Legacy WebSocket route changed; review the protocol.")
    kinds = set()
    for name in ("conversations/service.py", "ai/service.py"):
        tree = ast.parse((ROOT / "backend/src/servicehub" / name).read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "append_event"
            ):
                if len(node.args) >= 3 and isinstance(node.args[2], ast.Constant):
                    kinds.add(node.args[2].value)
    if kinds != {"conversation.created", "conversation.updated", "message.created"}:
        raise SystemExit("Legacy durable event kinds changed; review event examples.")
    conversation = {
        "id": 101,
        "customer_id": 201,
        "assigned_agent_id": None,
        "title": "合成契约样本",
        "status": "AI_ACTIVE",
        "version": 2,
        "generation_epoch": 1,
        "last_event_seq": 2,
        "created_at": "2026-10-22T00:00:00Z",
        "updated_at": "2026-10-22T00:00:01.123000Z",
        "ai_reply": None,
    }
    message = {
        "id": 301,
        "conversation_id": 101,
        "sender_id": 201,
        "author_role": "customer",
        "client_message_id": "synthetic-message-1",
        "ai_run_id": None,
        "body": "查询售后政策",
        "citations": [],
        "draft_id": None,
        "created_at": "2026-10-22T00:00:01.123000Z",
    }
    examples = []
    for seq, kind in enumerate(sorted(kinds), 1):
        payload = {"conversation": {**conversation, "last_event_seq": seq}}
        if kind == "message.created":
            payload["message"] = message
        examples.append(
            {
                "kind": kind,
                "conversation_id": 101,
                "seq": seq,
                "generation_epoch": 1,
                "payload": payload,
            }
        )
    examples.extend(
        [
            {
                "kind": "ai.delta",
                "conversation_id": 101,
                "seq": None,
                "generation_epoch": 1,
                "payload": {"text": "正在核实", "run_id": 401},
            },
            {
                "kind": "heartbeat",
                "conversation_id": 101,
                "seq": None,
                "generation_epoch": 0,
                "payload": {},
            },
        ]
    )
    ws = {
        "id": "W01",
        "path": ws_paths[0],
        "query": {"after_seq": {"type": "integer", "minimum": 0, "default": 0}},
        "event_schema": schema["components"]["schemas"]["LiveEvent"],
        "durable_kinds": sorted(kinds),
        "transient_kinds": ["ai.delta", "heartbeat"],
        "close_codes": {
            "4401": "unauthenticated or revoked",
            "4403": "origin or object permission denied",
            "1013": "dependency, slow consumer or send timeout",
        },
        "buffer_size": 128,
        "send_timeout_seconds": 5,
        "replay": "Persistent seq is authoritative; Redis notification loss triggers DB catch-up; transient events are not persisted.",
        "examples_kind": "synthetic examples, not captured user traffic",
        "examples": examples,
        "java_additions": ["topic_id", "run_id", "execution_version"],
    }
    http = {
        "source": str(SOURCE.relative_to(ROOT)).replace("\\", "/"),
        "source_sha256": EXPECTED_SHA256,
        "runtime_acceptance": "docs/verification/java-contract-matrix.md",
        "operations": operations,
    }
    return {
        "openapi.json": raw,
        "http-contracts.json": encoded(http),
        "extensions.json": encoded(extension),
        "websocket.json": encoded(ws),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--write", action="store_true", help="Explicitly generate reviewed baseline artifacts"
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Read-only comparison against frozen artifacts (default)",
    )
    args = parser.parse_args()
    if args.write and args.check:
        parser.error("Choose --write or --check")
    files = artifacts()
    if args.write:
        DEST.mkdir(parents=True, exist_ok=True)
        for name, content in files.items():
            (DEST / name).write_bytes(content)
    else:
        for name, content in files.items():
            target = DEST / name
            if not target.exists() or target.read_bytes() != content:
                raise SystemExit(
                    f"Contract mismatch: {target.relative_to(ROOT)}; review before regenerating"
                )
    print(
        "Frozen contract verified: original HTTP 39, Java extensions 3, WebSocket 1; no live data read."
    )


if __name__ == "__main__":
    main()
