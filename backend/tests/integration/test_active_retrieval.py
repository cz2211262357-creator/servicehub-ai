import pytest
from servicehub.auth.schemas import Principal
from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)
from servicehub.knowledge.retrieval import Retriever, VectorIndex

pytestmark = pytest.mark.integration


@pytest.mark.parametrize("stale_kind", ["old_version", "disabled_document"])
async def test_stale_chunks_cannot_crowd_active_evidence_out_of_keyword_or_vector_candidates(
    env, stale_kind
):
    tenant = env.tenants[0].id
    async with env.sessions() as session, session.begin():
        good = KnowledgeDocument(
            tenant_id=tenant, uploaded_by=env.users["admin"].id, title="当前政策"
        )
        session.add(good)
        await session.flush()
        stale_doc = good
        if stale_kind == "disabled_document":
            stale_doc = KnowledgeDocument(
                tenant_id=tenant, uploaded_by=env.users["admin"].id, title="停用政策", enabled=False
            )
            session.add(stale_doc)
            await session.flush()
        versions = [
            DocumentVersion(
                tenant_id=tenant,
                document_id=doc.id,
                number=number,
                storage_key="test-only",
                filename="policy.txt",
                media_type="text/plain",
                size_bytes=1,
                sha256="0" * 64,
                status="ready",
            )
            for doc, number in [(stale_doc, 1), (good, 2)]
        ]
        session.add_all(versions)
        await session.flush()
        stale, current = versions
        session.add(
            KnowledgeActiveVersion(tenant_id=tenant, document_id=good.id, version_id=current.id)
        )
        if stale_doc.id != good.id:
            session.add(
                KnowledgeActiveVersion(
                    tenant_id=tenant, document_id=stale_doc.id, version_id=stale.id
                )
            )
        chunks = [
            KnowledgeChunk(
                tenant_id=tenant,
                document_id=stale_doc.id,
                version_id=stale.id,
                ordinal=i,
                page=1,
                text="退货 旧版条件",
            )
            for i in range(32)
        ]
        target = KnowledgeChunk(
            tenant_id=tenant,
            document_id=good.id,
            version_id=current.id,
            ordinal=0,
            page=1,
            text="退货 当前有效条件",
        )
        session.add_all([*chunks, target])
        await session.flush()
        ids = [c.id for c in chunks]
        target_id = target.id
    user = env.users["customer"]
    principal = Principal(user.id, tenant, "customer")
    # Old vector hashes deliberately lack the version metadata added by the fix.
    index = VectorIndex(env.redis, "active-filter-test", 2)
    await env.redis.execute_command(
        "FT.CREATE",
        index.name,
        "ON",
        "HASH",
        "PREFIX",
        1,
        index.prefix,
        "SCHEMA",
        "tenant",
        "TAG",
        "chunk",
        "NUMERIC",
        "embedding",
        "VECTOR",
        "HNSW",
        6,
        "TYPE",
        "FLOAT32",
        "DIM",
        2,
        "DISTANCE_METRIC",
        "COSINE",
    )
    for chunk_id in [*ids, target_id]:
        vector = [0.9, 0.1] if chunk_id == target_id else [1.0, 0.0]
        await env.redis.hset(
            index.prefix + str(chunk_id),
            mapping={
                "tenant": str(tenant),
                "chunk": chunk_id,
                "embedding": index.encode(vector),
            },
        )

    class Embedding:
        async def embed(self, texts):
            return [[1.0, 0.0] for _ in texts]

    cfg = env.settings.model_copy(
        update={
            "model_mode": "online",
            "embedding_model": "active-filter-test",
            "embedding_dimensions": 2,
        }
    )
    vector = await Retriever(env.sessions, env.redis, cfg, embedding=Embedding()).search(
        principal, "unmatched-vector-only"
    )
    assert [row.chunk_id for row in vector] == [target_id]
    keyword = await Retriever(env.sessions, env.redis, env.settings).search(principal, "退货")
    assert [row.chunk_id for row in keyword] == [target_id]


async def test_zero_fulltext_relevance_keeps_a_literal_policy_keyword_retrievable(env, login):
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from sqlalchemy import text

    admin = await login("admin")
    result = (
        await admin.post(
            "/api/v1/knowledge/documents",
            files={"file": ("policy.txt", b"Click the on switch to proceed.", "text/plain")},
        )
    ).json()
    version = result["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "literal-policy", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        lease, version["id"]
    )
    async with env.sessions() as session:
        score = await session.scalar(
            text(
                "SELECT MATCH(text) AGAINST('on' IN NATURAL LANGUAGE MODE) "
                "FROM knowledge_chunks WHERE tenant_id=:tenant LIMIT 1"
            ),
            {"tenant": env.tenants[0].id},
        )
        assert score == 0
    user = env.users["customer"]
    evidence = await Retriever(env.sessions, env.redis, env.settings).search(
        Principal(user.id, user.tenant_id, "customer"), "on"
    )
    assert evidence and evidence[0].version_id == version["id"]
