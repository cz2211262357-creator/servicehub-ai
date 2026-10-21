import pytest

pytestmark = pytest.mark.integration


async def test_customer_and_agent_cannot_manage_members(env, login):
    for name in ("customer", "agent"):
        client = await login(name)
        response = await client.patch(
            f"/api/v1/admin/members/{env.users['agent2'].id}", json={"role": "admin"}
        )
        assert response.status_code == 403


async def test_member_queries_and_mutations_cannot_cross_tenants(env, login):
    client = await login("admin")
    response = await client.get("/api/v1/admin/members")
    assert response.status_code == 200
    assert env.users["outsider"].id not in [row["id"] for row in response.json()["items"]]
    response = await client.patch(
        f"/api/v1/admin/members/{env.users['outsider'].id}", json={"role": "agent"}
    )
    assert response.status_code == 404


async def test_role_change_revokes_old_session_and_writes_safe_audit(env, login):
    from servicehub.operations.models import AuditLog
    from sqlalchemy import select

    agent, admin = await login("agent"), await login("admin")
    response = await admin.patch(
        f"/api/v1/admin/members/{env.users['agent'].id}", json={"role": "customer"}
    )
    assert response.status_code == 200
    assert (await agent.get("/api/v1/auth/me")).status_code == 401
    async with env.sessions() as session:
        audit = (
            await session.scalars(
                select(AuditLog).where(
                    AuditLog.tenant_id == env.tenants[0].id, AuditLog.action == "member.updated"
                )
            )
        ).one()
    assert audit.actor_id == env.users["admin"].id
    assert "password" not in str(audit.details).lower()
