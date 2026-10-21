import pytest
from servicehub.auth.schemas import Principal

pytestmark = pytest.mark.integration


async def test_vector_index_is_tenant_model_scoped_and_rejects_wrong_dimensions(env):
    from servicehub.knowledge.retrieval import VectorIndex

    index = VectorIndex(env.redis, "test-model-a", 2)
    await index.write(111, env.tenants[0].id, [1.0, 0.0])
    await index.write(222, env.tenants[1].id, [1.0, 0.0])
    assert await index.search(env.tenants[0].id, [1.0, 0.0], 5) == [111]
    with pytest.raises(ValueError):
        await index.write(333, env.tenants[0].id, [1.0, 0.0, 0.0])
    other = VectorIndex(env.redis, "test-model-b", 2)
    assert index.name != other.name
    assert await other.search(env.tenants[0].id, [1.0, 0.0], 5) == []


async def test_keyword_evidence_is_real_and_rechecked_after_document_disable(env, login):
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from servicehub.knowledge.retrieval import Retriever

    admin = await login("admin")
    uploaded = await admin.post(
        "/api/v1/knowledge/documents",
        files={
            "file": (
                "policy.txt",
                "七天内未使用商品可申请退货，请保留完整包装。".encode(),
                "text/plain",
            )
        },
    )
    document = uploaded.json()
    version = document["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "retrieval-indexer", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        lease, version["id"]
    )
    user = env.users["customer"]
    principal = Principal(user.id, user.tenant_id, user.role)
    retriever = Retriever(env.sessions, env.redis, env.settings)
    evidence = await retriever.search(principal, "退货")
    assert evidence and "完整包装" in evidence[0].text and evidence[0].page == 1
    other = env.users["outsider"]
    assert await retriever.search(Principal(other.id, other.tenant_id, other.role), "退货") == []
    current = (await admin.get(f"/api/v1/knowledge/documents/{document['id']}")).json()
    await admin.patch(
        f"/api/v1/knowledge/documents/{document['id']}",
        json={"enabled": False, "version": current["version"]},
    )
    assert await retriever.revalidate(principal, evidence) == []
