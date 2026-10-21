import pytest
from servicehub.knowledge.models import DocumentVersion, KnowledgeActiveVersion
from sqlalchemy import select

pytestmark = pytest.mark.integration


async def upload(client, text=b"Seven day return policy."):
    response = await client.post(
        "/api/v1/knowledge/documents",
        files={"file": ("policy.md", text, "text/markdown")},
        data={"title": "退货政策"},
    )
    assert response.status_code == 201, response.text
    return response.json()


async def index(env, document):
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer

    version = document["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "test-indexer", 60)
    await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
        lease, version["id"]
    )


async def test_upload_limit_tenant_download_and_path_boundaries(env, login):
    from servicehub.knowledge.storage import PrivateStorage

    admin, outsider = await login("admin"), await login("outsider")
    large = await admin.post(
        "/api/v1/knowledge/documents",
        files={"file": ("too-big.txt", b"x" * (10 * 1024 * 1024 + 1), "text/plain")},
    )
    assert large.status_code == 413
    document = await upload(admin)
    assert (
        await outsider.get(f"/api/v1/knowledge/documents/{document['id']}/download")
    ).status_code == 404
    with pytest.raises(ValueError):
        PrivateStorage(env.settings).resolve("../../outside.txt")


async def test_new_version_switches_only_after_ready_and_deleted_document_is_private(env, login):
    admin = await login("admin")
    document = await upload(admin)
    await index(env, document)
    response = await admin.post(
        f"/api/v1/knowledge/documents/{document['id']}/versions",
        files={"file": ("policy.md", b"Replacement policy with a new condition.", "text/markdown")},
    )
    assert response.status_code == 201, response.text
    replacement = response.json()
    async with env.sessions() as session:
        active = await session.scalar(
            select(KnowledgeActiveVersion).where(
                KnowledgeActiveVersion.tenant_id == env.tenants[0].id
            )
        )
        assert active.version_id == document["latest_version"]["id"]
    await index(env, replacement)
    async with env.sessions() as session:
        active = await session.scalar(
            select(KnowledgeActiveVersion).where(
                KnowledgeActiveVersion.tenant_id == env.tenants[0].id
            )
        )
        assert active.version_id == replacement["latest_version"]["id"]
    assert (await admin.delete(f"/api/v1/knowledge/documents/{document['id']}")).status_code == 204
    assert (
        await admin.get(f"/api/v1/knowledge/documents/{document['id']}/download")
    ).status_code == 404


async def test_invalid_pdf_job_has_traceable_failure_without_active_index(env, login):
    from servicehub.jobs.handlers import PermanentJobError
    from servicehub.jobs.service import JobService
    from servicehub.knowledge.indexer import KnowledgeIndexer

    admin = await login("admin")
    response = await admin.post(
        "/api/v1/knowledge/documents",
        files={"file": ("fake.pdf", b"this is not a pdf", "application/pdf")},
    )
    assert response.status_code == 201
    document = response.json()
    version = document["latest_version"]
    lease = await JobService(env.sessions).acquire(version["job_id"], "invalid-parser", 60)
    with pytest.raises(PermanentJobError):
        await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
            lease, version["id"]
        )
    async with env.sessions() as session:
        row = await session.get(DocumentVersion, version["id"])
        assert row.status == "failed" and row.error_code
        assert (
            await session.scalar(
                select(KnowledgeActiveVersion).where(
                    KnowledgeActiveVersion.tenant_id == env.tenants[0].id
                )
            )
            is None
        )
