from servicehub.conversations.models import ConversationEvent
from servicehub.conversations.schemas import LiveEvent
from servicehub.conversations.snapshot import conversation_snapshot
from servicehub.jobs.models import OutboxEvent


async def append_event(session, conversation, kind: str, payload: dict | None = None) -> LiveEvent:
    """Caller holds the conversation row lock (or owns a newly created row)."""
    conversation.last_event_seq += 1
    data = dict(payload or {})
    await session.flush()
    data["conversation"] = (await conversation_snapshot(session, conversation)).model_dump(
        mode="json"
    )
    event = ConversationEvent(
        tenant_id=conversation.tenant_id,
        conversation_id=conversation.id,
        seq=conversation.last_event_seq,
        kind=kind,
        generation_epoch=conversation.generation_epoch,
        payload=data,
    )
    session.add(event)
    session.add(
        OutboxEvent(
            tenant_id=conversation.tenant_id,
            kind="realtime",
            payload={"conversation_id": conversation.id, "seq": event.seq},
        )
    )
    await session.flush()
    return as_live_event(event)


def as_live_event(event: ConversationEvent) -> LiveEvent:
    return LiveEvent(
        kind=event.kind,
        conversation_id=event.conversation_id,
        seq=event.seq,
        generation_epoch=event.generation_epoch,
        payload=event.payload,
    )
