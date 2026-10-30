import pytest
from servicehub.ai.models import AIRun
from servicehub.jobs.models import Job
from sqlalchemy import select, update

pytestmark = pytest.mark.integration


@pytest.mark.parametrize(
    "state,code,expected",
    [
        ("failed", "knowledge_unavailable", "知识检索暂不可用，请稍后重试或转人工。"),
        ("retrying", "model_timeout", "模型响应超时或暂不可用，请稍后重试或转人工。"),
        ("failed", "private-secret-code", "暂时无法完成本次回答，请稍后重试或转人工。"),
        ("running", "model_timeout", None),
    ],
)
async def test_snapshot_exposes_only_safe_notice_and_enforces_customer_access(
    env, login, state, code, expected
):
    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "public-failure", "body": "请查询订单"},
    )
    async with env.sessions() as session, session.begin():
        run = await session.scalar(select(AIRun).where(AIRun.conversation_id == cid))
        await session.execute(
            update(Job)
            .where(Job.id == run.job_id)
            .values(state=state, error_code=code, error_message="private-provider-secret-key")
        )
        run.state = state
    response = await customer.get(f"/api/v1/conversations/{cid}")
    assert response.status_code == 200
    assert response.json()["ai_reply"]["state"] == state
    assert response.json()["ai_reply"]["error_message"] == expected
    assert "private-provider-secret-key" not in response.text
    other = await login("customer2")
    assert (await other.get(f"/api/v1/conversations/{cid}")).status_code in (403, 404)
