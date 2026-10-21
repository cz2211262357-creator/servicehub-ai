import asyncio
import json
from contextlib import AsyncExitStack

import pytest
from websockets.asyncio.client import connect

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


async def test_customer_and_staff_in_two_processes_receive_same_message(env, login, live_servers):
    from servicehub.jobs.outbox import OutboxDispatcher

    customer, staff = await login(), await login("agent")
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    async with AsyncExitStack() as stack:
        sockets = []
        for port, client in zip(live_servers, [customer, staff], strict=True):
            websocket = await stack.enter_async_context(
                connect(
                    f"ws://127.0.0.1:{port}/api/v1/conversations/{cid}/live?after_seq=0",
                    origin="http://localhost:5173",
                    additional_headers={
                        "Cookie": f"servicehub_session={client.cookies.get('servicehub_session')}"
                    },
                    proxy=None,
                )
            )
            assert json.loads(await asyncio.wait_for(websocket.recv(), 3))["seq"] == 1
            sockets.append(websocket)
        response = await customer.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": "cross-process", "body": "两个进程应收到同一条消息"},
        )
        assert response.status_code == 200
        await OutboxDispatcher(env.sessions, env.redis).tick()
        for websocket in sockets:
            async with asyncio.timeout(5):
                while True:
                    event = json.loads(await websocket.recv())
                    if event["kind"] == "message.created":
                        assert event["payload"]["message"]["id"] == response.json()["id"]
                        assert event["seq"] == 2
                        break
