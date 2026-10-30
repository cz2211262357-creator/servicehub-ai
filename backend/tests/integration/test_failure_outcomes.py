from datetime import timedelta

import pytest
from servicehub.ai.models import AIRun
from servicehub.conversations.models import ConversationEvent
from servicehub.db.base import utcnow
from servicehub.jobs.models import Job
from servicehub.jobs.service import JobService
from servicehub.knowledge.models import DocumentVersion
from sqlalchemy import select, update

pytestmark = pytest.mark.integration


async def queued_ai(env, login):
    customer = await login()
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    await customer.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "failure-case", "body": "请查询订单"},
    )
    async with env.sessions() as session:
        run = await session.scalar(select(AIRun).where(AIRun.conversation_id == cid))
    return customer, cid, run


async def test_exhausted_ai_retries_sync_the_run_event_and_reopened_snapshot(env, login):
    customer, cid, run = await queued_ai(env, login)
    jobs = JobService(env.sessions)
    for attempt in range(4):
        lease = await jobs.acquire(run.job_id, f"worker-{attempt}", 60)
        async with env.sessions() as session, session.begin():
            row = await session.get(AIRun, run.id)
            row.state = "running"
        assert await jobs.fail(lease, "model_capacity_exhausted", retryable=True)
        async with env.sessions() as session, session.begin():
            job = await session.get(Job, run.job_id)
            job.available_at = utcnow() - timedelta(seconds=1)
    async with env.sessions() as session:
        row = await session.get(AIRun, run.id)
        job = await session.get(Job, run.job_id)
        assert job.state == "failed"
        assert row.state == "failed" and row.error_code == "model_capacity_exhausted"
        event = await session.scalar(
            select(ConversationEvent)
            .where(ConversationEvent.conversation_id == cid, ConversationEvent.kind == "ai.failed")
            .order_by(ConversationEvent.seq.desc())
            .limit(1)
        )
        assert event.payload["will_retry"] is False
    reopened = (await customer.get(f"/api/v1/conversations/{cid}")).json()
    assert reopened["ai_reply"]["state"] == "failed"
    assert reopened["ai_reply"]["will_retry"] is False
    admin = await login("admin")
    assert (await admin.post(f"/api/v1/admin/jobs/{run.job_id}/retry")).status_code == 200
    restarted = (await customer.get(f"/api/v1/conversations/{cid}")).json()
    assert restarted["ai_reply"]["state"] == "queued"
    assert restarted["ai_reply"]["error_code"] is None


async def test_crashed_fourth_attempt_also_syncs_terminal_failure_when_reclaimed(env, login):
    _, _, run = await queued_ai(env, login)
    jobs = JobService(env.sessions)
    await jobs.acquire(run.job_id, "crashed", 60)
    async with env.sessions() as session, session.begin():
        await session.execute(
            update(Job)
            .where(Job.id == run.job_id)
            .values(attempts=4, lease_expires_at=utcnow() - timedelta(seconds=1))
        )
        row = await session.get(AIRun, run.id)
        row.state = "running"
    assert await jobs.acquire(run.job_id, "recovery", 60) is None
    async with env.sessions() as session:
        row = await session.get(AIRun, run.id)
        assert row.state == "failed" and row.error_code == "retry_exhausted"


async def test_snapshot_reports_failed_authoritative_job_even_if_the_run_was_interrupted(
    env, login
):
    customer, cid, run = await queued_ai(env, login)
    async with env.sessions() as session, session.begin():
        await session.execute(
            update(Job)
            .where(Job.id == run.job_id)
            .values(state="failed", error_code="retry_exhausted", attempts=4)
        )
        row = await session.get(AIRun, run.id)
        row.state = "running"
    snapshot = (await customer.get(f"/api/v1/conversations/{cid}")).json()
    assert snapshot["ai_reply"]["state"] == "failed"
    assert snapshot["ai_reply"]["error_code"] == "retry_exhausted"


async def test_index_storage_failure_reaches_a_retryable_final_document_state(
    env, login, monkeypatch
):
    from redis.exceptions import ConnectionError
    from servicehub.ai.embedding import HTTPEmbeddingProvider
    from servicehub.jobs.outbox import OutboxDispatcher
    from servicehub.jobs.worker import Worker
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.indexer import KnowledgeIndexer

    admin = await login("admin")
    result = await admin.post(
        "/api/v1/knowledge/documents",
        files={"file": ("policy.txt", "七天内退货请保留完整包装。".encode(), "text/plain")},
    )
    version = result.json()["latest_version"]
    async with env.sessions() as session, session.begin():
        await session.execute(update(Job).where(Job.id == version["job_id"]).values(attempts=3))
    cfg = env.settings.model_copy(update={"model_mode": "online", "embedding_dimensions": 2})

    async def embed(self, texts):
        return [[1.0, 0.0] for _ in texts]

    async def unavailable(self, *args, **kwargs):
        raise ConnectionError("private-provider-detail")

    monkeypatch.setattr(HTTPEmbeddingProvider, "embed", embed)
    monkeypatch.setattr(HybridIndex, "write", unavailable)
    indexer = KnowledgeIndexer(env.sessions, env.redis, cfg)

    async def handle(lease):
        return await indexer.build_version(lease, version["id"])

    await OutboxDispatcher(env.sessions, env.redis).tick()
    await Worker(env.sessions, env.redis, cfg, handlers={"knowledge_index": handle}).tick()
    async with env.sessions() as session:
        row = await session.get(DocumentVersion, version["id"])
        job = await session.get(Job, version["job_id"])
        assert job.state == "failed"
        assert row.status == "failed" and row.error_code == "index_storage_unavailable"
    assert (await admin.post(f"/api/v1/admin/jobs/{version['job_id']}/retry")).status_code == 200
    async with env.sessions() as session:
        assert (await session.get(DocumentVersion, version["id"])).status == "queued"
