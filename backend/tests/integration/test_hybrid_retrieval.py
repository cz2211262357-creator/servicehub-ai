import struct

import pytest
from redis.exceptions import ResponseError
from servicehub.auth.schemas import Principal
from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)
from servicehub.knowledge.retrieval import Retriever, VectorIndex

pytestmark = pytest.mark.integration


def hybrid(redis, model="hybrid-test", dimensions=2):
    from servicehub.knowledge.hybrid_index import HybridIndex

    return HybridIndex(redis, model, dimensions)


async def add_policy(env, text, *, tenant_index=0, enabled=True, model=None, dimensions=None):
    tenant = env.tenants[tenant_index].id
    async with env.sessions() as session, session.begin():
        doc = KnowledgeDocument(
            tenant_id=tenant,
            uploaded_by=env.users["admin" if tenant_index == 0 else "outsider"].id,
            title="政策",
            enabled=enabled,
        )
        session.add(doc)
        await session.flush()
        version = DocumentVersion(
            tenant_id=tenant,
            document_id=doc.id,
            number=1,
            storage_key="test-only",
            filename="policy.txt",
            media_type="text/plain",
            size_bytes=1,
            sha256="0" * 64,
            status="ready",
            embedding_model=model,
            embedding_dimensions=dimensions,
        )
        session.add(version)
        await session.flush()
        chunk = KnowledgeChunk(
            tenant_id=tenant,
            document_id=doc.id,
            version_id=version.id,
            ordinal=0,
            page=1,
            text=text,
        )
        session.add_all(
            [
                chunk,
                KnowledgeActiveVersion(tenant_id=tenant, document_id=doc.id, version_id=version.id),
            ]
        )
        await session.flush()
        return chunk.id, tenant, version.id


async def test_real_redis_chinese_bm25_scores_filters_and_literal_syntax(env):
    index = hybrid(env.redis)
    await index.write(101, 1, "七天无理由退货需要保留完整包装。", [1, 0], version_id=10)
    await index.write(102, 1, "运输与保修流程。", [1, 0], version_id=10)
    await index.write(103, 2, "七天无理由退货。", [1, 0], version_id=10)
    await index.write(104, 1, "七天无理由退货。", [1, 0], version_id=11)
    rows = await index.search_keyword(
        1, "七天无理由退货条件是什么", 30, version_ids=[10], with_scores=True
    )
    assert rows and rows[0][0] == 101 and rows[0][1] > 0
    assert {row[0] for row in rows} <= {101, 102}
    assert (
        await index.search_keyword(1, "@tenant:{2} | * =>[KNN 9 @embedding $x]", version_ids=[10])
        == []
    )
    assert await index.search_keyword(1, '"退货" \\ @version:{11}|*', version_ids=[10]) == [101]
    assert await index.search_vector(1, [1, 0], 30, version_ids=[10]) == [101, 102]
    assert await index.search_keyword(1, "退货", version_ids=[]) == []


async def test_existing_wrong_schema_is_rejected_without_altering_old_index(env):
    index = hybrid(env.redis)
    await env.redis.execute_command(
        "FT.CREATE", index.name, "ON", "HASH", "PREFIX", 1, index.prefix, "SCHEMA", "tenant", "TAG"
    )
    with pytest.raises(ValueError, match="schema"):
        await index.ensure()


async def test_demo_indexes_real_text_without_fake_vectors_and_revalidates(env):
    chunk, tenant, version = await add_policy(env, "七天内未使用商品可申请退货，请保留包装。")
    user = env.users["customer"]
    principal = Principal(user.id, tenant, "customer")
    cfg = env.settings.model_copy(
        update={
            "retrieval_backend": "redis_hybrid",
            "embedding_model": "hybrid-test",
            "embedding_dimensions": 2,
        }
    )
    rows = await Retriever(env.sessions, env.redis, cfg).search(principal, "七天退货条件")
    assert [row.chunk_id for row in rows] == [chunk]
    index = hybrid(env.redis)
    assert (
        await env.redis.hget(index.prefix + str(chunk), "content")
        == "七天内未使用商品可申请退货，请保留包装。"
    )
    assert not await env.redis.hexists(index.prefix + str(chunk), "embedding")
    async with env.sessions() as session, session.begin():
        obj = await session.get(DocumentVersion, version)
        obj.status = "failed"
    assert await Retriever(env.sessions, env.redis, cfg).revalidate(principal, rows) == []


@pytest.mark.parametrize(
    "old_model,old_dimensions", [("another-model", 2), ("hybrid-test", 3), (None, None)]
)
async def test_backfill_reuses_only_trusted_old_vectors_and_resumes(env, old_model, old_dimensions):
    first, tenant, version = await add_policy(
        env, "旧版已就绪退货政策", model="hybrid-test", dimensions=2
    )
    second, _, _ = await add_policy(
        env, "另一政策需要真实重新向量化", model=old_model, dimensions=old_dimensions
    )
    old = VectorIndex(env.redis, "hybrid-test", 2)
    for chunk in (first, second):
        await old.write(chunk, tenant, [0.5, 0.5])

    class Embedding:
        calls = []

        async def embed(self, texts):
            self.calls.extend(texts)
            return [[0.25, 0.75] for _ in texts]

    provider = Embedding()
    index = hybrid(env.redis)
    await index.backfill_active(env.sessions, embedding=provider, require_vectors=True)
    assert provider.calls == ["另一政策需要真实重新向量化"]
    assert await env.redis.execute_command(
        "HGET", index.prefix + str(first), "embedding", NEVER_DECODE=True
    ) == struct.pack("<2f", 0.5, 0.5)
    await index.backfill_active(env.sessions, embedding=provider, require_vectors=True)
    assert provider.calls == ["另一政策需要真实重新向量化"]
    async with env.sessions() as session:
        current = await session.get(DocumentVersion, version)
        assert current.status == "ready"
        from sqlalchemy import select

        active = await session.scalar(
            select(KnowledgeActiveVersion.version_id).where(
                KnowledgeActiveVersion.tenant_id == tenant,
                KnowledgeActiveVersion.version_id == version,
            )
        )
        assert active == version


async def test_disabled_versions_do_not_crowd_hybrid_topk(env):
    target, tenant, current = await add_policy(env, "退货 当前有效条件")
    _, _, stale = await add_policy(env, "退货 旧版条件", enabled=False)
    index = hybrid(env.redis)
    for chunk in range(100001, 100040):
        await index.write(chunk, tenant, "退货 旧版条件", [1, 0], version_id=stale)

    class Embedding:
        async def embed(self, texts):
            return [[1, 0] for _ in texts]

    cfg = env.settings.model_copy(
        update={
            "retrieval_backend": "redis_hybrid",
            "model_mode": "online",
            "embedding_model": "hybrid-test",
            "embedding_dimensions": 2,
        }
    )
    user = env.users["customer"]
    rows = await Retriever(env.sessions, env.redis, cfg, embedding=Embedding()).search(
        Principal(user.id, tenant, "customer"), "退货"
    )
    assert [row.chunk_id for row in rows] == [target]


async def test_redis_index_failure_prevents_new_version_activation(env, login, monkeypatch):
    from servicehub.jobs.handlers import RetryableJobError
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from sqlalchemy import select

    admin = await login("admin")
    document = (
        await admin.post(
            "/api/v1/knowledge/documents",
            files={"file": ("policy.txt", "七天退货政策".encode(), "text/plain")},
        )
    ).json()
    version = document["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "broken-hybrid", 60)

    async def fail(*args, **kwargs):
        raise ResponseError("simulated index storage failure")

    monkeypatch.setattr(HybridIndex, "write", fail)
    with pytest.raises(RetryableJobError):
        await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
            lease, version["id"]
        )
    async with env.sessions() as session:
        current = await session.get(DocumentVersion, version["id"])
        assert current.status == "failed"
        assert (
            await session.scalar(
                select(KnowledgeActiveVersion.id).where(
                    KnowledgeActiveVersion.version_id == current.id
                )
            )
            is None
        )


async def test_backend_override_keeps_legacy_keyword_and_switches_to_redis(env, monkeypatch):
    chunk, tenant, _ = await add_policy(env, "七天退货需要完整包装")
    principal = Principal(env.users["customer"].id, tenant, "customer")
    calls = []

    async def legacy_keyword(self, principal, query, limit=30):
        calls.append(query)
        return [chunk]

    monkeypatch.setattr(Retriever, "keyword", legacy_keyword)
    old = Retriever(env.sessions, env.redis, env.settings, retrieval_backend="mysql_legacy")
    assert [row.chunk_id for row in await old.search(principal, "legacy")] == [chunk]
    assert calls == ["legacy"]
    new = Retriever(env.sessions, env.redis, env.settings, retrieval_backend="redis_hybrid")
    assert [row.chunk_id for row in await new.search(principal, "退货")] == [chunk]
    assert calls == ["legacy"]


async def test_real_resp3_scores_and_vectors_match_resp2(env):
    from redis.asyncio import Redis

    client = Redis(host="127.0.0.1", port=6380, protocol=3, decode_responses=True)
    try:
        index = hybrid(client, model="hybrid-resp3")
        await index.write(101, 1, "七天无理由退货需要完整包装", [1, 0], version_id=10)
        await index.write(102, 1, "七天无理由退货需要完整包装", [1, 0], version_id=10)
        scored = await index.search_keyword(1, "七天退货", version_ids=[10], with_scores=True)
        assert [row[0] for row in scored] == [101, 102]
        assert scored[0][1] > 0
        assert await index.search_vector(1, [1, 0], version_ids=[10]) == [101, 102]
    finally:
        await client.aclose()


async def test_failed_reindex_preserves_prior_active_version(env, login, monkeypatch):
    from servicehub.jobs.handlers import RetryableJobError
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from sqlalchemy import select

    admin = await login("admin")
    first = (
        await admin.post(
            "/api/v1/knowledge/documents",
            files={"file": ("policy.txt", "旧版退货政策".encode(), "text/plain")},
        )
    ).json()
    old = first["latest_version"]
    first_lease = await JobService(env.sessions).acquire(old["job_id"], "old-ready", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        first_lease, old["id"]
    )
    latest = (
        await admin.post(
            f"/api/v1/knowledge/documents/{first['id']}/versions",
            files={"file": ("new-policy.txt", "新版退货政策".encode(), "text/plain")},
        )
    ).json()["latest_version"]
    lease = await JobService(env.sessions).acquire(latest["job_id"], "reindex-fails", 60)

    async def fail(*args, **kwargs):
        raise ResponseError("simulated failure")

    monkeypatch.setattr(HybridIndex, "write", fail)
    with pytest.raises(RetryableJobError):
        await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
            lease, latest["id"]
        )
    async with env.sessions() as session:
        active = await session.scalar(
            select(KnowledgeActiveVersion.version_id).where(
                KnowledgeActiveVersion.document_id == first["id"]
            )
        )
        assert active == old["id"]
        assert (await session.get(DocumentVersion, latest["id"])).status == "failed"


async def test_hybrid_retry_reuses_staged_chunk_ids_to_avoid_orphan_candidate_crowding(
    env, login, monkeypatch
):
    from servicehub.jobs.handlers import RetryableJobError
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.hybrid_index import HybridIndex
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from sqlalchemy import select

    admin = await login("admin")
    document = (
        await admin.post(
            "/api/v1/knowledge/documents",
            files={"file": ("policy.txt", "七天内退货请保留完整包装。".encode(), "text/plain")},
        )
    ).json()
    version = document["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "retry-hybrid", 60)
    original = HybridIndex.write

    async def partial_failure(self, *args, **kwargs):
        await original(self, *args, **kwargs)
        raise ResponseError("failed after hash write")

    monkeypatch.setattr(HybridIndex, "write", partial_failure)
    indexer = KnowledgeIndexer(env.sessions, env.redis, env.settings)
    with pytest.raises(RetryableJobError):
        await indexer.build_version(lease, version["id"])
    async with env.sessions() as session:
        before = list(
            await session.scalars(
                select(KnowledgeChunk.id).where(KnowledgeChunk.version_id == version["id"])
            )
        )
    monkeypatch.setattr(HybridIndex, "write", original)
    await indexer.build_version(lease, version["id"])
    async with env.sessions() as session:
        after = list(
            await session.scalars(
                select(KnowledgeChunk.id).where(KnowledgeChunk.version_id == version["id"])
            )
        )
    assert after == before


async def test_explicit_legacy_rebuild_restores_vector_channel_after_v2_only_ingestion(env):
    from sqlalchemy import select

    from scripts.reindex_legacy import rebuild_legacy

    chunk, tenant, version = await add_policy(
        env, "稀有售后术语", model="hybrid-test", dimensions=2
    )
    index = hybrid(env.redis)
    await index.write(chunk, tenant, "稀有售后术语", [1, 0], version_id=version)
    legacy = VectorIndex(env.redis, "hybrid-test", 2)
    assert not await env.redis.exists(legacy.prefix + str(chunk))
    cfg = env.settings.model_copy(
        update={"embedding_model": "hybrid-test", "embedding_dimensions": 2}
    )

    class UnexpectedNetwork:
        async def embed(self, texts):
            raise AssertionError("matching real-vector bytes must be reused")

    stats = await rebuild_legacy(
        env.sessions, env.redis, cfg, embedding=UnexpectedNetwork(), tenant_id=tenant
    )
    assert stats["reused_vectors"] == stats["written"] == stats["verified"] == 1
    assert await legacy.search(tenant, [1, 0], version_ids=[version]) == [chunk]
    async with env.sessions() as session:
        assert (
            await session.scalar(
                select(KnowledgeChunk.id).where(KnowledgeChunk.version_id == version)
            )
            == chunk
        )


async def test_legacy_rebuild_rejects_mismatched_v2_metadata_and_reembeds_current_text(env):
    from scripts.reindex_legacy import rebuild_legacy

    chunk, tenant, version = await add_policy(
        env, "当前有效政策", model="hybrid-test", dimensions=2
    )
    index = hybrid(env.redis)
    await index.write(chunk, tenant + 999, "当前有效政策", [1, 0], version_id=version)
    cfg = env.settings.model_copy(
        update={"model_mode": "online", "embedding_model": "hybrid-test", "embedding_dimensions": 2}
    )

    class ActualBoundaryReplacement:
        async def embed(self, texts):
            assert texts == ["当前有效政策"]
            return [[0, 1]]

    stats = await rebuild_legacy(
        env.sessions, env.redis, cfg, embedding=ActualBoundaryReplacement(), tenant_id=tenant
    )
    assert stats["reused_vectors"] == 0 and stats["embedded_vectors"] == 1
    legacy = VectorIndex(env.redis, "hybrid-test", 2)
    raw = await env.redis.execute_command(
        "HMGET", legacy.prefix + str(chunk), "tenant", "version", "embedding", NEVER_DECODE=True
    )
    assert raw == [str(tenant).encode(), str(version).encode(), struct.pack("<2f", 0, 1)]


async def test_incomplete_legacy_rebuild_fails_before_advertising_rollback_ready(env, monkeypatch):
    from scripts.reindex_legacy import rebuild_legacy

    chunk, tenant, version = await add_policy(env, "完整性检查", model="hybrid-test", dimensions=2)
    await hybrid(env.redis).write(chunk, tenant, "完整性检查", [1, 0], version_id=version)
    cfg = env.settings.model_copy(
        update={"embedding_model": "hybrid-test", "embedding_dimensions": 2}
    )

    async def lost_write(*args, **kwargs):
        pass

    monkeypatch.setattr(VectorIndex, "write", lost_write)
    with pytest.raises((ValueError, TimeoutError)):
        await rebuild_legacy(env.sessions, env.redis, cfg, tenant_id=tenant)
