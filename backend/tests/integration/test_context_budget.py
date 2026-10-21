import pytest
from servicehub.ai.agent import SupportAgent
from servicehub.ai.models import AIRun
from servicehub.ai.provider import ModelEvent
from servicehub.auth.schemas import Principal
from sqlalchemy import select

pytestmark = pytest.mark.integration


async def test_history_budget_always_keeps_the_latest_customer_question(env, login):
    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    bodies = ["旧问题A" + "甲" * 7996, "旧问题B" + "乙" * 7996, "最新问题C" + "丙" * 7995]
    for index, body in enumerate(bodies):
        response = await customer.post(
            f"/api/v1/conversations/{cid}/messages",
            json={"client_message_id": f"long-{index}", "body": body},
        )
        assert response.status_code == 200
    async with env.sessions() as session:
        run = await session.scalar(
            select(AIRun).where(AIRun.conversation_id == cid).order_by(AIRun.id.desc()).limit(1)
        )
    captured = []

    class InspectProvider:
        async def stream(self, messages, tools, budget):
            captured.extend(messages)
            yield ModelEvent("delta", text="收到")
            yield ModelEvent("done")

    user = env.users["customer"]
    await SupportAgent(env.sessions, env.redis, env.settings).run(
        Principal(user.id, user.tenant_id, "customer"), cid, run.id, InspectProvider()
    )
    history = [m for m in captured if m["role"] != "system"]
    assert history[-1]["content"] == bodies[-1]
    assert sum(len(m["content"]) for m in history) <= 16000
