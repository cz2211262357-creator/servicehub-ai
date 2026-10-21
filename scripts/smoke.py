"""Exercise the deployed website with actual online RAG, tools, human service and after-sales."""

import argparse
import asyncio
import json
import os
import time
from pathlib import Path
from uuid import uuid4

import httpx


async def request(client, method, path, *, status=200, **kwargs):
    response = await client.request(method, "/api/v1" + path, **kwargs)
    if response.status_code != status:
        raise RuntimeError(
            f"{method} {path}: HTTP {response.status_code}, request={response.headers.get('x-request-id')}"
        )
    return response.json() if response.content else None


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tenant-id", type=int, required=True)
    parser.add_argument("--output", type=Path, default=Path(".cache/deployment-smoke.json"))
    args = parser.parse_args()
    assert args.base_url.startswith(("http://localhost:", "http://127.0.0.1:")), (
        "This script uses local development accounts only"
    )
    clients = []
    report = {
        "status": "running",
        "base_url": args.base_url,
        "tenant_id": args.tenant_id,
        "checks": [],
    }
    started = time.monotonic()
    try:
        for name in ("customer", "agent", "admin"):
            client = httpx.AsyncClient(
                base_url=args.base_url,
                timeout=15,
                trust_env=False,
                headers={"origin": args.base_url},
            )
            clients.append(client)
            result = await request(
                client,
                "POST",
                "/auth/login",
                json={
                    "tenant_id": args.tenant_id,
                    "email": name + "@example.com",
                    "password": os.environ.get("SERVICEHUB_SEED_PASSWORD", "ServiceHub-2026!"),
                },
            )
            client.headers["x-csrf-token"] = result["csrf_token"]
        customer, staff, admin = clients
        await request(customer, "GET", "/health/ready")
        async with asyncio.timeout(150):
            while True:
                documents = (await request(admin, "GET", "/knowledge/documents"))["items"]
                if any(
                    d["latest_version"] and d["latest_version"]["status"] == "ready"
                    for d in documents
                ):
                    break
                if any(
                    d["latest_version"] and d["latest_version"]["status"] == "failed"
                    for d in documents
                ):
                    raise RuntimeError("Knowledge indexing failed; inspect the job error code")
                await asyncio.sleep(0.5)
        cid = (await request(customer, "POST", "/conversations", json={}, status=201))["id"]

        async def ask(body):
            sent = await request(
                customer,
                "POST",
                f"/conversations/{cid}/messages",
                json={"client_message_id": uuid4().hex, "body": body},
            )
            async with asyncio.timeout(150):
                while True:
                    rows = (await request(customer, "GET", f"/conversations/{cid}/messages"))[
                        "items"
                    ]
                    answer = next(
                        (m for m in rows if m["id"] > sent["id"] and m["author_role"] == "ai"), None
                    )
                    if answer:
                        return answer
                    await asyncio.sleep(0.5)

        answer = await ask("签收后七天内，未使用且包装完整的商品可以退货吗？请依据商城政策回答。")
        assert answer["citations"], "A policy answer must contain actual authorized citations"
        report["checks"].append(
            {
                "name": "online_rag",
                "message_id": answer["id"],
                "citations": len(answer["citations"]),
            }
        )
        answer = await ask(
            "请查询我的订单 SH20261021001，商品无法开机，帮我准备 RETURN 退货申请草稿，我会在页面确认。"
        )
        assert answer["draft_id"], "The real model must call the authorized draft tool"
        drafts = (await request(customer, "GET", "/after-sales/drafts"))["items"]
        draft = next(d for d in drafts if d["id"] == answer["draft_id"])
        tickets = (await request(customer, "GET", "/after-sales"))["items"]
        assert all(t["draft_id"] != draft["id"] for t in tickets), (
            "No ticket before customer confirmation"
        )
        key = uuid4().hex
        confirmed = await request(
            customer,
            "POST",
            f"/after-sales/drafts/{draft['id']}/confirm",
            json={"version": draft["version"], "idempotency_key": key},
        )
        repeated = await request(
            customer,
            "POST",
            f"/after-sales/drafts/{draft['id']}/confirm",
            json={"version": draft["version"], "idempotency_key": key},
        )
        assert repeated["id"] == confirmed["id"]
        report["checks"].append(
            {
                "name": "online_tools_and_explicit_confirmation",
                "draft_id": draft["id"],
                "ticket_id": confirmed["id"],
            }
        )
        waiting = await request(customer, "POST", f"/conversations/{cid}/handoff")
        claimed = await request(
            staff, "POST", f"/conversations/{cid}/claim", json={"version": waiting["version"]}
        )
        assert claimed["status"] == "HUMAN_ACTIVE"
        reply = await request(
            staff,
            "POST",
            f"/conversations/{cid}/messages",
            json={
                "client_message_id": uuid4().hex,
                "body": "您好，已核实申请，将按售后流程继续处理。",
            },
        )
        rows = (await request(customer, "GET", f"/conversations/{cid}/messages"))["items"]
        assert any(m["id"] == reply["id"] and m["author_role"] == "agent" for m in rows)
        for state in ("IN_REVIEW", "APPROVED", "RESOLVED"):
            confirmed = await request(
                staff,
                "PATCH",
                f"/after-sales/{confirmed['id']}/status",
                json={"status": state, "version": confirmed["version"], "note": "本地业务验收"},
            )
        report["checks"].append(
            {
                "name": "human_reply_and_ticket_workflow",
                "conversation_id": cid,
                "final_status": confirmed["status"],
            }
        )
        report["status"] = "passed"
    except Exception as exc:
        report["status"] = "failed"
        report["error_type"] = type(exc).__name__
        raise
    finally:
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        for client in clients:
            await client.aclose()
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
