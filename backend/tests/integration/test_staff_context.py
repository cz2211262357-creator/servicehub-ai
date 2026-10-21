import pytest

pytestmark = pytest.mark.integration


async def test_staff_context_is_authorized_and_customer_cannot_read_staff_view(env, login):
    owner, staff, other = await login(), await login("agent"), await login("outsider")
    cid = (await owner.post("/api/v1/conversations", json={})).json()["id"]
    result = await staff.get(f"/api/v1/conversations/{cid}/context")
    assert result.status_code == 200
    assert result.json()["display_name"] == "customer"
    assert (await owner.get(f"/api/v1/conversations/{cid}/context")).status_code == 403
    assert (await other.get(f"/api/v1/conversations/{cid}/context")).status_code == 403


async def test_only_admin_can_retry_a_failed_job_in_their_tenant(env, login):
    from servicehub.jobs.persistence import enqueue_job
    from servicehub.jobs.service import JobService

    async with env.sessions() as session, session.begin():
        job = await enqueue_job(session, "test", "admin-retry", {"tenant_id": env.tenants[0].id})
    lease = await JobService(env.sessions).acquire(job.id, "failed-worker", 30)
    await JobService(env.sessions).fail(lease, "test_permanent", retryable=False)
    admin, agent = await login("admin"), await login("agent")
    assert (await agent.post(f"/api/v1/admin/jobs/{job.id}/retry")).status_code == 403
    result = await admin.post(f"/api/v1/admin/jobs/{job.id}/retry")
    assert result.status_code == 200 and result.json()["state"] == "queued"
