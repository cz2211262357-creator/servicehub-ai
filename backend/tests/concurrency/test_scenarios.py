import asyncio
import contextlib
import json
import os
import subprocess
import sys
import time
from contextlib import AsyncExitStack
from datetime import timedelta
from decimal import Decimal
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from servicehub.after_sales.models import AfterSaleTicket
from servicehub.auth.models import User
from servicehub.conversations.models import Conversation, Message
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from servicehub.jobs.persistence import enqueue_job
from servicehub.jobs.service import JobService
from sqlalchemy import func, select
from websockets.asyncio.client import connect

from scripts.load_test import require_ready, summarize

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]
ROOT = Path(__file__).resolve().parents[3]


async def remote(stack, env, port, name="customer", *, user=None):
    client = await stack.enter_async_context(
        httpx.AsyncClient(
            base_url=f"http://127.0.0.1:{port}",
            trust_env=False,
            timeout=15,
            headers={"origin": "http://localhost:5173"},
        )
    )
    user = user or env.users[name]
    result = await client.post(
        "/api/v1/auth/login",
        json={"tenant_id": user.tenant_id, "email": user.email, "password": env.password},
    )
    assert result.status_code == 200, result.text
    client.headers["x-csrf-token"] = result.json()["csrf_token"]
    return client


async def test_http_duplicate_messages_and_ticket_confirmations_have_one_result(env, live_servers):
    from servicehub.orders.models import Order

    async with AsyncExitStack() as stack:
        clients = [await remote(stack, env, port) for port in live_servers]
        cid = (await clients[0].post("/api/v1/conversations", json={})).json()["id"]
        results = await asyncio.gather(
            *[
                clients[i % 2].post(
                    f"/api/v1/conversations/{cid}/messages",
                    json={"client_message_id": "same-http-id", "body": "退货政策是什么"},
                )
                for i in range(20)
            ]
        )
        assert {r.status_code for r in results} == {200}
        assert len({r.json()["id"] for r in results}) == 1
        async with env.sessions() as session, session.begin():
            assert (
                await session.scalar(
                    select(func.count()).select_from(Message).where(Message.conversation_id == cid)
                )
                == 1
            )
            assert (
                await session.scalar(
                    select(func.count())
                    .select_from(Job)
                    .where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
                )
                == 1
            )
            order = Order(
                tenant_id=env.tenants[0].id,
                customer_id=env.users["customer"].id,
                order_number="HTTP-CONFIRM",
                total_amount=Decimal("299.00"),
                after_sale_facts={
                    "category": "electronics",
                    "signed_at": (utcnow() - timedelta(days=1)).isoformat(),
                    "return_condition": "intact",
                    "exclusion_confirmed": False,
                    "quality_issue": True,
                    "warranty_until": (utcnow() + timedelta(days=365)).isoformat(),
                    "requires_manual_review": False,
                },
            )
            session.add(order)
            await session.flush()
        prepared = await clients[0].post(
            "/api/v1/after-sales/drafts",
            json={
                "order_id": order.id,
                "kind": "RETURN",
                "reason": "商品无法启动",
                "reason_code": "QUALITY",
            },
        )
        assert prepared.status_code == 201, prepared.text
        draft = prepared.json()
        results = await asyncio.gather(
            *[
                clients[i % 2].post(
                    f"/api/v1/after-sales/drafts/{draft['id']}/confirm",
                    json={"version": draft["version"], "idempotency_key": "same-http-confirm"},
                )
                for i in range(20)
            ]
        )
        assert {r.status_code for r in results} == {200}
        assert len({r.json()["id"] for r in results}) == 1
        async with env.sessions() as session:
            assert (
                await session.scalar(
                    select(func.count())
                    .select_from(AfterSaleTicket)
                    .where(AfterSaleTicket.tenant_id == env.tenants[0].id)
                )
                == 1
            )


async def test_http_twenty_distinct_agents_claim_with_one_winner(env, live_servers, password_hash):
    async with env.sessions() as session, session.begin():
        agents = [
            User(
                tenant_id=env.tenants[0].id,
                email=f"http-agent-{i}@example.com",
                display_name=f"http-agent-{i}",
                role="agent",
                password_hash=password_hash,
            )
            for i in range(20)
        ]
        session.add_all(agents)
        await session.flush()
    async with AsyncExitStack() as stack:
        customer = await remote(stack, env, live_servers[0])
        cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
        waiting = (await customer.post(f"/api/v1/conversations/{cid}/handoff")).json()
        clients = [
            await remote(stack, env, live_servers[i % 2], user=agent)
            for i, agent in enumerate(agents)
        ]
        results = await asyncio.gather(
            *[
                client.post(
                    f"/api/v1/conversations/{cid}/claim", json={"version": waiting["version"]}
                )
                for client in clients
            ]
        )
        assert sum(r.status_code == 200 for r in results) == 1
        assert sum(r.status_code == 409 for r in results) == 19
        winner = next(r.json() for r in results if r.status_code == 200)
        async with env.sessions() as session:
            row = await session.get(Conversation, cid)
            assert row.assigned_agent_id == winner["assigned_agent_id"]
            assert row.status == "HUMAN_ACTIVE" and row.generation_epoch > 0


def stop_process(process):
    if process.poll() is None:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                capture_output=True,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )
        else:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


@pytest.mark.skipif(
    os.getenv("SERVICEHUB_RUN_FAULTS") != "1", reason="opt-in process fault experiment"
)
async def test_worker_process_killed_mid_job_recovers_under_new_lease(env):
    async with env.sessions() as session, session.begin():
        job = await enqueue_job(
            session, "fault_probe", "killed-worker", {"tenant_id": env.tenants[0].id}
        )
    children = []
    child_env = dict(
        os.environ,
        SERVICEHUB_DATABASE_URL=env.settings.database_url,
        SERVICEHUB_REDIS_URL=env.settings.redis_url,
        SERVICEHUB_ENVIRONMENT="test",
        SERVICEHUB_JOB_LEASE_SECONDS="15",
        SERVICEHUB_WORKER_POLL_SECONDS="0.1",
    )
    logpath = ROOT / ".cache/worker-fault.log"
    with logpath.open("wb") as logfile:

        def launch(mode):
            child = subprocess.Popen(
                [sys.executable, str(Path(__file__).with_name("lease_worker.py")), mode],
                cwd=ROOT,
                env=child_env,
                stdout=logfile,
                stderr=logfile,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
            children.append(child)
            return child

        try:
            first = launch("block")
            async with asyncio.timeout(10):
                while True:
                    async with env.sessions() as session:
                        row = await session.get(Job, job.id)
                        if row.state == "running":
                            old_owner, old_version, expiry = (
                                row.owner,
                                row.execution_version,
                                row.lease_expires_at,
                            )
                            break
                    assert first.poll() is None, logpath.read_text(errors="replace")
                    await asyncio.sleep(0.1)
            await asyncio.to_thread(stop_process, first)
            second = launch("finish")
            async with asyncio.timeout(24):
                while True:
                    async with env.sessions() as session:
                        row = await session.get(Job, job.id)
                        if row.state == "succeeded":
                            assert row.execution_version == old_version + 1
                            assert row.result == {
                                "receipt": "recovered",
                                "execution_version": row.execution_version,
                            }
                            break
                    assert second.poll() is None, logpath.read_text(errors="replace")
                    await asyncio.sleep(0.2)
            from servicehub.jobs.schemas import JobLease

            stale = JobLease(
                job.id, old_owner, old_version, expiry, env.tenants[0].id, "fault_probe", {}
            )
            assert await JobService(env.sessions).finish(stale, {"receipt": "obsolete"}) is False
        finally:
            for child in children:
                await asyncio.to_thread(stop_process, child)


@pytest.mark.skipif(
    os.getenv("SERVICEHUB_RUN_FAULTS") != "1", reason="opt-in dedicated test Redis outage"
)
async def test_redis_outage_fails_closed_then_recovers_without_losing_mysql_data(env, live_servers):
    assert env.settings.redis_url == "redis://127.0.0.1:6380/0"
    async with AsyncExitStack() as stack:
        customer = await remote(stack, env, live_servers[0])
        cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
        message = await customer.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": "before-outage", "body": "请查询售后规则"},
        )
        assert message.status_code == 200

        async def compose(*arguments):
            result = await asyncio.to_thread(
                subprocess.run,
                ["docker", "compose", *arguments, "redis-test"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
            assert result.returncode == 0, result.stderr

        try:
            await compose("stop")
            for port in live_servers:
                async with httpx.AsyncClient(trust_env=False) as client:
                    assert (
                        await client.get(f"http://127.0.0.1:{port}/api/v1/health/ready")
                    ).status_code == 503
            blocked = await customer.post(
                f"/api/v1/conversations/{cid}/messages",
                json={"client_message_id": "during-outage", "body": "这条不能静默写入"},
            )
            assert blocked.status_code == 503
            async with env.sessions() as session:
                assert (
                    await session.scalar(
                        select(func.count())
                        .select_from(Message)
                        .where(Message.conversation_id == cid)
                    )
                    == 1
                )
        finally:
            await compose("start")
            async with asyncio.timeout(15):
                while True:
                    try:
                        await env.redis.ping()
                        break
                    except Exception:
                        await asyncio.sleep(0.2)
        await require_ready(f"http://127.0.0.1:{live_servers[1]}")
        recovered = await remote(stack, env, live_servers[1])
        records = (await recovered.get(f"/api/v1/conversations/{cid}/messages")).json()
        assert [row["id"] for row in records["items"]] == [message.json()["id"]]
        from servicehub.jobs.outbox import OutboxDispatcher
        from servicehub.jobs.worker import Worker

        async def handler(lease):
            return {"receipt": "reconciled-after-redis-outage"}

        dispatcher = OutboxDispatcher(env.sessions, env.redis)
        await dispatcher.reconcile()
        await dispatcher.tick()
        await Worker(env.sessions, env.redis, env.settings, handlers={"ai_reply": handler}).tick()
        async with env.sessions() as session:
            job = await session.scalar(
                select(Job).where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
            )
            assert job.state == "succeeded"


@pytest.mark.skipif(not os.getenv("SERVICEHUB_LOAD_CONFIG"), reason="opt-in measured load")
async def test_measured_workload(env, live_servers, password_hash):
    config = json.loads(os.environ["SERVICEHUB_LOAD_CONFIG"])
    scenario = config["scenario"]
    # All data belongs to disposable test tenants. No application data or Redis is cleared.
    async with env.sessions() as session, session.begin():
        users = [
            User(
                tenant_id=env.tenants[0].id,
                email=f"load-{i}@example.com",
                display_name=f"load-{i}",
                role="customer",
                password_hash=password_hash,
            )
            for i in range(config["users"])
        ]
        session.add_all(users)
        await session.flush()
    samples, statuses = [], {}
    async with AsyncExitStack() as stack:
        clients, conversations, sockets = [], [], []
        for i, user in enumerate(users):
            client = await remote(stack, env, live_servers[i % 2], user=user)
            clients.append(client)
            cid = (await client.post("/api/v1/conversations", json={})).json()["id"]
            conversations.append(cid)
            if scenario != "agent":
                assert (
                    await client.post(f"/api/v1/conversations/{cid}/handoff")
                ).status_code == 200
            if scenario == "live":
                socket = await stack.enter_async_context(
                    connect(
                        f"ws://127.0.0.1:{live_servers[(i + 1) % 2]}/api/v1/conversations/{cid}/live?after_seq=0",
                        origin="http://localhost:5173",
                        proxy=None,
                        additional_headers={
                            "Cookie": f"servicehub_session={client.cookies.get('servicehub_session')}"
                        },
                    )
                )
                while json.loads(await socket.recv()).get("seq", 0) < 2:
                    pass
                sockets.append(socket)
        from servicehub.jobs.outbox import OutboxDispatcher
        from servicehub.jobs.worker import Worker

        dispatcher = OutboxDispatcher(env.sessions, env.redis)
        background = None
        if scenario == "agent":
            from servicehub.core.config import load_settings

            cfg = load_settings()
            assert cfg.model_api_key.get_secret_value() and cfg.embedding_api_key.get_secret_value()
            for field in (
                "model_api_key",
                "model_base_url",
                "model_name",
                "embedding_api_key",
                "embedding_base_url",
                "embedding_model",
                "embedding_dimensions",
            ):
                setattr(env.settings, field, getattr(cfg, field))
            env.settings.model_mode = "online"
            background = asyncio.create_task(Worker(env.sessions, env.redis, env.settings).run())
        elif scenario == "live":

            async def dispatch():
                while True:
                    await dispatcher.tick()
                    await asyncio.sleep(0.03)

            background = asyncio.create_task(dispatch())
        started = time.perf_counter()
        deadline = started + config["duration"]

        async def virtual_user(index):
            client, cid = clients[index], conversations[index]
            while time.perf_counter() < deadline:
                stamp = time.perf_counter()
                response = await client.post(
                    f"/api/v1/conversations/{cid}/messages",
                    json={"client_message_id": uuid4().hex, "body": "请问如何办理售后？"},
                )
                success = response.status_code == 200
                statuses[str(response.status_code)] = statuses.get(str(response.status_code), 0) + 1
                if success and scenario == "live":
                    async with asyncio.timeout(8):
                        while True:
                            event = json.loads(await sockets[index].recv())
                            if (
                                event["kind"] == "message.created"
                                and event["payload"]["message"]["id"] == response.json()["id"]
                            ):
                                break
                elif success and scenario == "agent":
                    async with asyncio.timeout(150):
                        while True:
                            async with env.sessions() as session:
                                job_state = await session.scalar(
                                    select(Job).where(
                                        Job.tenant_id == env.tenants[0].id,
                                        Job.kind == "ai_reply",
                                        Job.payload["message_id"].as_integer()
                                        == response.json()["id"],
                                    )
                                )
                                if job_state and job_state.state in ("succeeded", "failed"):
                                    success = job_state.state == "succeeded"
                                    break
                            await asyncio.sleep(0.2)
                samples.append(((time.perf_counter() - stamp) * 1000, success))
                # Closed-loop clients; this is a workload observation, not a claimed service limit.
                await asyncio.sleep(0.03)

        try:
            await asyncio.gather(*(virtual_user(i) for i in range(config["users"])))
        finally:
            if background:
                background.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await background
        measured = {
            **summarize(samples, time.perf_counter() - started),
            "http_statuses": statuses,
            "latency_scope": {
                "business": "HTTP message persistence",
                "live": "HTTP to WebSocket delivery",
                "agent": "HTTP through real online Agent completion",
            }[scenario],
            "model_mode": "online" if scenario == "agent" else "not_invoked",
        }
        Path(config["output"]).write_text(json.dumps(measured, indent=2), encoding="utf-8")
        assert measured["requests"] > 0
        assert measured["error_rate"] == 0, measured
