from fastapi import APIRouter, Query, Request

from servicehub.conversations.schemas import (
    ClaimInput,
    ConversationContextDTO,
    ConversationDTO,
    ConversationInput,
    ConversationPage,
    ConversationStatus,
    EventPage,
    MessageDTO,
    MessageInput,
    MessagePage,
)
from servicehub.conversations.service import ConversationService
from servicehub.core.dependencies import Authenticated, Staff
from servicehub.core.errors import BusinessError
from servicehub.limits.rate import RateLimiter

router = APIRouter(prefix="/api/v1/conversations", tags=["conversations"])


@router.get("/{conversation_id}/context", response_model=ConversationContextDTO)
async def context(conversation_id: int, request: Request, principal: Staff):
    from sqlalchemy import select

    from servicehub.auth.models import User
    from servicehub.conversations.service import authorized_conversation
    from servicehub.orders.service import OrderService

    async with request.app.state.sessions() as session:
        conversation = await authorized_conversation(session, principal, conversation_id)
        user = await session.scalar(
            select(User).where(
                User.id == conversation.customer_id, User.tenant_id == principal.tenant_id
            )
        )
    orders = await OrderService(request.app.state.sessions).list(
        principal, limit=3, customer_id=user.id
    )
    name, _, domain = user.email.partition("@")
    return {
        "customer_id": user.id,
        "display_name": user.display_name,
        "masked_email": name[:2] + "***@" + domain,
        "is_active": user.is_active,
        "orders": [row.model_dump(mode="json") for row in orders["items"]],
    }


def service(request):
    return ConversationService(request.app.state.sessions, request.app.state.settings)


@router.post("", response_model=ConversationDTO, status_code=201)
async def create(payload: ConversationInput, request: Request, principal: Authenticated):
    return await service(request).create(principal, payload.title)


@router.get("", response_model=ConversationPage)
async def conversations(
    request: Request,
    principal: Authenticated,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
    status: ConversationStatus | None = None,
    search: str | None = Query(default=None, max_length=100),
):
    return await service(request).list(principal, cursor, limit, status, search)


@router.get("/{conversation_id}", response_model=ConversationDTO)
async def detail(conversation_id: int, request: Request, principal: Authenticated):
    return await service(request).get(principal, conversation_id)


@router.get("/{conversation_id}/messages", response_model=MessagePage)
async def messages(
    conversation_id: int,
    request: Request,
    principal: Authenticated,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=50, ge=1, le=100),
):
    return await service(request).list_messages(principal, conversation_id, cursor, limit)


@router.post("/{conversation_id}/messages", response_model=MessageDTO)
async def send(
    conversation_id: int, payload: MessageInput, request: Request, principal: Authenticated
):
    decision = await RateLimiter(request.app.state.redis, request.app.state.settings).check(
        principal.tenant_id, principal.user_id, "message"
    )
    if not decision.allowed:
        raise BusinessError(
            "rate_limited", "发送过于频繁，请稍后重试", 429, {"retry_after": decision.retry_after}
        )
    return await service(request).send_message(
        principal, conversation_id, payload.client_message_id, payload.body
    )


@router.get("/{conversation_id}/events", response_model=EventPage)
async def events(
    conversation_id: int,
    request: Request,
    principal: Authenticated,
    after_seq: int = Query(default=0, ge=0),
    limit: int = Query(default=200, ge=1, le=500),
):
    return await service(request).list_events(principal, conversation_id, after_seq, limit)


@router.post("/{conversation_id}/handoff", response_model=ConversationDTO)
async def handoff(conversation_id: int, request: Request, principal: Authenticated):
    return await service(request).request_handoff(principal, conversation_id)


@router.post("/{conversation_id}/claim", response_model=ConversationDTO)
async def claim(conversation_id: int, payload: ClaimInput, request: Request, principal: Staff):
    return await service(request).claim(principal, conversation_id, payload.version)


@router.post("/{conversation_id}/close", response_model=ConversationDTO)
async def close(conversation_id: int, request: Request, principal: Authenticated):
    return await service(request).close(principal, conversation_id)
