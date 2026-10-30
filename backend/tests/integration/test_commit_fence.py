import asyncio

import pytest
from servicehub.ai.agent import AgentResult
from servicehub.ai.schemas import Evidence
from servicehub.ai.service import AIService
from servicehub.conversations.models import Message
from servicehub.jobs.models import Job
from servicehub.jobs.service import JobService
from sqlalchemy import func, select

pytestmark = pytest.mark.integration


async def test_expiry_during_evidence_validation_rolls_back_the_formal_answer(
    env, login, monkeypatch
):
    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "commit-fence", "body": "退货政策"},
    )
    async with env.sessions() as session:
        job = await session.scalar(
            select(Job).where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
        )
    lease = await JobService(env.sessions).acquire(job.id, "slow-commit", 1)
    evidence = Evidence(
        chunk_id=1, document_id=1, version_id=1, page=1, text="依据", score=1, title="政策"
    )
    service = AIService(env.sessions, env.redis, env.settings)

    async def slow_revalidate(principal, citations):
        await asyncio.sleep(1.15)
        return citations

    monkeypatch.setattr(service.retriever, "revalidate", slow_revalidate)
    result = AgentResult("迟到回答 [1]", [evidence], [], None)
    assert await service.commit_result(lease, lease.payload["generation_epoch"], result) is False
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(func.count())
                .select_from(Message)
                .where(Message.conversation_id == cid, Message.author_role == "ai")
            )
            == 0
        )
