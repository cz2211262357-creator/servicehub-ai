"""Opt-in calls to the user's configured API. Never included in the default suite."""

import pytest
from servicehub.auth.schemas import Principal
from servicehub.core.config import load_settings

pytestmark = pytest.mark.online


async def test_real_embedding_indexes_and_recalls_authorized_evidence(env, login):
    from servicehub.ai.embedding import HTTPEmbeddingProvider
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer
    from servicehub.knowledge.retrieval import Retriever, VectorIndex

    cfg = load_settings()
    assert cfg.embedding_api_key.get_secret_value(), "Configure the local .env first"
    for field in (
        "embedding_api_key",
        "embedding_model",
        "embedding_base_url",
        "embedding_dimensions",
    ):
        setattr(env.settings, field, getattr(cfg, field))
    env.settings.model_mode = "online"
    admin = await login("admin")
    source = "本商城支持七天内未使用商品退货。客户应保留完整包装，并在售后页面确认申请。"
    response = await admin.post(
        "/api/v1/knowledge/documents",
        files={"file": ("return-policy.md", source.encode(), "text/markdown")},
    )
    assert response.status_code == 201
    version = response.json()["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "live-index-test", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        lease, version["id"]
    )
    user = env.users["customer"]
    principal = Principal(user.id, user.tenant_id, user.role)
    evidence = await Retriever(env.sessions, env.redis, env.settings).search(
        principal, "商品不想要了怎么退回"
    )
    assert evidence and "完整包装" in evidence[0].text
    vector = (await HTTPEmbeddingProvider(env.settings).embed(["如何办理售后退货"]))[0]
    assert len(vector) == 1024
    hits = await VectorIndex(env.redis, cfg.embedding_model, cfg.embedding_dimensions).search(
        principal.tenant_id, vector
    )
    assert evidence[0].chunk_id in hits
