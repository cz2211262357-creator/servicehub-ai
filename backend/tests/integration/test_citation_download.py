import pytest
from servicehub.conversations.models import Message
from servicehub.jobs.service import JobService
from servicehub.knowledge.indexer import KnowledgeIndexer

pytestmark = pytest.mark.integration


async def test_historical_citations_download_the_cited_version_with_customer_ownership_checks(
    env, login
):
    admin, customer, other = await login("admin"), await login(), await login("customer2")
    document = (
        await admin.post(
            "/api/v1/knowledge/documents",
            files={"file": ("policy.txt", b"Version one: seven days.", "text/plain")},
        )
    ).json()
    did = document["id"]

    async def activate(doc):
        version = doc["latest_version"]
        lease = await JobService(env.sessions).acquire(version["job_id"], "index-citations", 60)
        await KnowledgeIndexer(env.sessions, env.redis, env.settings).build_version(
            lease, version["id"]
        )
        return version["id"]

    v1 = await activate(document)
    cid = (await customer.post("/api/v1/conversations", json={})).json()["id"]
    async with env.sessions() as session, session.begin():
        session.add(
            Message(
                tenant_id=env.tenants[0].id,
                conversation_id=cid,
                sender_id=None,
                author_role="ai",
                body="依据旧版政策",
                citations=[
                    {
                        "document_id": did,
                        "version_id": v1,
                        "chunk_id": 1,
                        "page": 1,
                        "text": "Version one",
                    }
                ],
            )
        )
    replacement = (
        await admin.post(
            f"/api/v1/knowledge/documents/{did}/versions",
            files={"file": ("policy.txt", b"Version two: an uncited revision.", "text/plain")},
        )
    ).json()
    v2 = await activate(replacement)
    newest = (
        await admin.post(
            f"/api/v1/knowledge/documents/{did}/versions",
            files={"file": ("policy.txt", b"Version three: current policy.", "text/plain")},
        )
    ).json()
    await activate(newest)
    download = f"/api/v1/knowledge/documents/{did}/download"
    cited = await customer.get(download, params={"version_id": v1})
    assert cited.status_code == 200 and cited.content == b"Version one: seven days."
    assert (await customer.get(download)).content == b"Version three: current policy."
    assert (await other.get(download, params={"version_id": v1})).status_code == 404
    assert (await customer.get(download, params={"version_id": v2})).status_code == 404
    assert (await customer.get(download, params={"version_id": 99999999})).status_code == 404
    assert (
        await admin.get(download, params={"version_id": v2})
    ).content == b"Version two: an uncited revision."
    current = (await admin.get(f"/api/v1/knowledge/documents/{did}")).json()
    await admin.patch(
        f"/api/v1/knowledge/documents/{did}", json={"enabled": False, "version": current["version"]}
    )
    assert (await customer.get(download, params={"version_id": v1})).status_code == 404
