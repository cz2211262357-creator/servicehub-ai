import asyncio

import pytest
from servicehub.auth.models import User
from servicehub.auth.service import AuthService
from servicehub.conversations.schemas import LiveEvent
from servicehub.core.errors import BusinessError
from sqlalchemy import update

pytestmark = pytest.mark.integration


async def test_reconnect_replays_only_missing_durable_events(env, login):
    from servicehub.realtime.hub import RealtimeHub

    client = await login()
    conversation = (await client.post("/api/v1/conversations", json={})).json()
    cid = conversation["id"]
    await client.post(
        f"/api/v1/conversations/{cid}/messages",
        json={"client_message_id": "replay", "body": "退货政策"},
    )
    principal = await AuthService(env.sessions, env.redis, env.settings).authenticate(
        client.cookies.get("servicehub_session")
    )
    hub = RealtimeHub(env.sessions, env.redis, env.settings)
    stream = hub.subscribe(principal, cid, 1)
    event = await asyncio.wait_for(anext(stream), 3)
    assert event.seq == 2
    assert event.kind == "message.created"
    await stream.aclose()


async def test_permissions_are_revalidated_after_subscription(env, login):
    from servicehub.realtime.hub import RealtimeHub

    client = await login()
    cid = (await client.post("/api/v1/conversations", json={})).json()["id"]
    principal = await AuthService(env.sessions, env.redis, env.settings).authenticate(
        client.cookies.get("servicehub_session")
    )
    settings = env.settings.model_copy(update={"live_auth_interval_seconds": 0.1})
    stream = RealtimeHub(env.sessions, env.redis, settings).subscribe(principal, cid, 0)
    assert (await anext(stream)).seq == 1
    async with env.sessions() as session, session.begin():
        await session.execute(
            update(User).where(User.id == principal.user_id).values(is_active=False)
        )
    with pytest.raises(BusinessError) as error:
        await asyncio.wait_for(anext(stream), 3)
    assert error.value.status_code == 401


async def test_slow_subscriber_is_bounded_and_can_recover_from_mysql(env, login):
    from servicehub.realtime.hub import RealtimeHub

    client = await login()
    cid = (await client.post("/api/v1/conversations", json={})).json()["id"]
    principal = await AuthService(env.sessions, env.redis, env.settings).authenticate(
        client.cookies.get("servicehub_session")
    )
    hub = RealtimeHub(
        env.sessions,
        env.redis,
        env.settings.model_copy(update={"live_buffer_size": 2}),
        tenant_id=principal.tenant_id,
    )
    stream = hub.subscribe(principal, cid, 0)
    assert (await anext(stream)).seq == 1
    for _ in range(30):
        await hub.publish(
            LiveEvent(
                kind="ai.delta",
                conversation_id=cid,
                seq=None,
                generation_epoch=0,
                payload={"text": "fragment"},
            )
        )
    with pytest.raises(BusinessError) as error:
        await asyncio.wait_for(anext(stream), 3)
    assert error.value.code == "slow_consumer"
    recovered = hub.subscribe(principal, cid, 0)
    assert (await anext(recovered)).seq == 1
    await recovered.aclose()
