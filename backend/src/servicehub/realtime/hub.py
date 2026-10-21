import asyncio
import contextlib
import json

from servicehub.auth.service import AuthService
from servicehub.conversations.schemas import LiveEvent
from servicehub.conversations.service import ConversationService
from servicehub.core.errors import BusinessError


class RealtimeHub:
    def __init__(self, sessions, redis, settings, tenant_id=None):
        self.sessions, self.redis, self.settings = sessions, redis, settings
        self.tenant_id = tenant_id
        self.conversations = ConversationService(sessions, settings)
        self.auth = AuthService(sessions, redis, settings)

    async def publish(self, event: LiveEvent):
        if self.tenant_id is None:
            raise ValueError("A publisher must be bound to a tenant")
        await self.redis.publish(
            f"servicehub:events:{self.tenant_id}:{event.conversation_id}", event.model_dump_json()
        )

    async def subscribe(self, principal, conversation_id, after_seq=0):
        await self.conversations.get(principal, conversation_id)
        channel = f"servicehub:events:{principal.tenant_id}:{conversation_id}"
        queue = asyncio.Queue(maxsize=self.settings.live_buffer_size)
        overflow = asyncio.Event()
        failures = []
        pubsub = self.redis.pubsub()
        await pubsub.subscribe(channel)

        async def pump():
            try:
                while True:
                    item = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1)
                    if item is None:
                        await asyncio.sleep(0.01)
                        continue
                    try:
                        payload = json.loads(item["data"])
                    except (ValueError, TypeError):
                        continue
                    try:
                        queue.put_nowait(payload)
                    except asyncio.QueueFull:
                        overflow.set()
                        return
            except Exception as exc:
                failures.append(exc)

        task = asyncio.create_task(pump())
        cursor = after_seq
        try:
            while True:
                await self.auth.revalidate(principal)
                await self.conversations.get(principal, conversation_id)
                if overflow.is_set():
                    raise BusinessError("slow_consumer", "接收速度过慢，请重新连接补齐消息", 503)
                if failures:
                    raise failures[0]
                page = await self.conversations.list_events(principal, conversation_id, cursor)
                for event in page["items"]:
                    await self.auth.revalidate(principal)
                    if overflow.is_set():
                        raise BusinessError(
                            "slow_consumer", "接收速度过慢，请重新连接补齐消息", 503
                        )
                    if event.seq > cursor:
                        cursor = event.seq
                        yield event
                await self.auth.revalidate(principal)
                if overflow.is_set():
                    raise BusinessError("slow_consumer", "接收速度过慢，请重新连接补齐消息", 503)
                if page["has_more"]:
                    continue
                try:
                    notification = await asyncio.wait_for(
                        queue.get(), timeout=self.settings.live_auth_interval_seconds
                    )
                except TimeoutError:
                    await self.auth.revalidate(principal)
                    yield LiveEvent(
                        kind="heartbeat",
                        conversation_id=conversation_id,
                        seq=None,
                        generation_epoch=0,
                        payload={},
                    )
                    continue
                # Durable notifications trigger a database catch-up on the next iteration.
                if notification.get("seq") is None and notification.get("kind") == "ai.delta":
                    await self.auth.revalidate(principal)
                    yield LiveEvent.model_validate(notification)
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            await pubsub.aclose()
