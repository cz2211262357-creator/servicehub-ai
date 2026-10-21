import hashlib

import httpx
import pytest

pytestmark = pytest.mark.integration


async def test_login_cookie_digest_ttl_and_logout_revocation(env, login):
    client = await login()
    session_id = client.cookies.get("servicehub_session")
    assert session_id and len(session_id) >= 43
    key = "servicehub:session:" + hashlib.sha256(session_id.encode()).hexdigest()
    stored = await env.redis.get(key)
    assert stored and session_id not in stored
    assert 0 < await env.redis.ttl(key) <= 28800
    assert (await client.get("/api/v1/auth/me")).json()["user"]["role"] == "customer"
    assert (await client.post("/api/v1/auth/logout")).status_code == 204
    assert (await client.get("/api/v1/auth/me")).status_code == 401
    assert not await env.redis.exists(key)


async def test_login_httponly_and_csrf_protection(env):
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=env.app), base_url="http://testserver"
    ) as client:
        user = env.users["customer"]
        result = await client.post(
            "/api/v1/auth/login",
            headers={"origin": "http://localhost:5173"},
            json={"tenant_id": user.tenant_id, "email": user.email, "password": env.password},
        )
        assert result.status_code == 200
        assert "httponly" in result.headers["set-cookie"].lower()
        assert "samesite=lax" in result.headers["set-cookie"].lower()
        assert (await client.post("/api/v1/auth/logout")).status_code == 403
        result = await client.post(
            "/api/v1/auth/login",
            headers={"origin": "https://evil.test"},
            json={"tenant_id": user.tenant_id, "email": user.email, "password": env.password},
        )
        assert result.status_code == 403


async def test_disabled_account_invalidates_existing_session(env, login):
    client, admin = await login(), await login("admin")
    result = await admin.patch(
        f"/api/v1/admin/members/{env.users['customer'].id}", json={"is_active": False}
    )
    assert result.status_code == 200, result.text
    assert (await client.get("/api/v1/auth/me")).status_code == 401
