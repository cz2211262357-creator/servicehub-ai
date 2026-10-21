import asyncio

import pytest
from servicehub.auth.models import User
from servicehub.auth.schemas import Principal
from servicehub.conversations.models import Conversation
from servicehub.core.errors import BusinessError
from sqlalchemy import select

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


async def test_twenty_agents_claim_with_one_winner(env, login, password_hash):
    from servicehub.conversations.service import ConversationService

    client = await login()
    created = await client.post("/api/v1/conversations", json={})
    assert created.status_code == 201, created.text
    cid = created.json()["id"]
    queued = await client.post(f"/api/v1/conversations/{cid}/handoff")
    assert queued.status_code == 200
    version = queued.json()["version"]
    async with env.sessions() as session, session.begin():
        agents = [
            User(
                tenant_id=env.tenants[0].id,
                email=f"claim{i}@example.com",
                display_name=f"claim{i}",
                password_hash=password_hash,
                role="agent",
            )
            for i in range(20)
        ]
        session.add_all(agents)
        await session.flush()
    service = ConversationService(env.sessions)

    async def attempt(agent):
        try:
            return await service.claim(Principal(agent.id, agent.tenant_id, "agent"), cid, version)
        except BusinessError as exc:
            assert exc.status_code == 409
            return None

    outcomes = await asyncio.gather(*[attempt(agent) for agent in agents])
    assert sum(result is not None for result in outcomes) == 1
    async with env.sessions() as session:
        row = await session.scalar(select(Conversation).where(Conversation.id == cid))
        assert row.assigned_agent_id in {agent.id for agent in agents}
        assert row.status == "HUMAN_ACTIVE"
