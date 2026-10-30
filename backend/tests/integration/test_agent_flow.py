import re

import pytest
from servicehub.ai.provider import ModelEvent
from servicehub.conversations.models import Message
from sqlalchemy import select

pytestmark = pytest.mark.integration


class AnswerProvider:
    async def stream(self, messages, tools, budget):
        matched = re.search(r'"chunk_id":\s*(\d+)', messages[1]["content"])
        assert matched, "Controlled provider requires actual retrieved evidence"
        yield ModelEvent("delta", text=f"可在七天内申请退货，请保留完整包装。[{matched.group(1)}]")
        yield ModelEvent(
            "usage", usage={"prompt_tokens": 30, "completion_tokens": 15, "total_tokens": 45}
        )
        yield ModelEvent("done")


async def test_policy_answer_has_real_evidence_and_single_final_reply(env, login):
    from servicehub.ai.service import AIService
    from servicehub.jobs.models import Job
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer

    admin, customer = await login("admin"), await login()
    response = await admin.post(
        "/api/v1/knowledge/documents",
        files={
            "file": (
                "policy.txt",
                "七天内未使用商品支持退货，请保留完整包装。".encode(),
                "text/plain",
            )
        },
    )
    version = response.json()["latest_version"]
    indexing = await JobService(env.sessions).acquire(version["job_id"], "index", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        indexing, version["id"]
    )
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "policy", "body": "退货政策"},
    )
    async with env.sessions() as session:
        job = await session.scalar(
            select(Job).where(Job.tenant_id == env.tenants[0].id, Job.kind == "ai_reply")
        )
    lease = await JobService(env.sessions).acquire(job.id, "agent", 60)
    service = AIService(env.sessions, env.redis, env.settings)
    await service.handle(lease, provider=AnswerProvider())
    assert await JobService(env.sessions).acquire(job.id, "duplicate", 60) is None
    async with env.sessions() as session:
        rows = list(
            await session.scalars(
                select(Message).where(Message.conversation_id == cid, Message.author_role == "ai")
            )
        )
        assert len(rows) == 1 and rows[0].citations[0]["document_id"] == response.json()["id"]
        assert "完整包装" in rows[0].citations[0]["text"]
