from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from servicehub.orders.schemas import OrderDTO

ConversationStatus = Literal["AI_ACTIVE", "WAITING_HUMAN", "HUMAN_ACTIVE", "CLOSED"]


class ConversationInput(BaseModel):
    title: str = Field(default="售后咨询", min_length=1, max_length=120)


class MessageInput(BaseModel):
    client_message_id: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    body: str = Field(min_length=1, max_length=8000)


class ClaimInput(BaseModel):
    version: int = Field(ge=1)


class AIReplyDTO(BaseModel):
    run_id: int
    state: str
    error_code: str | None = None
    error_message: str | None = None
    will_retry: bool = False
    generation_epoch: int
    attempts: int


class ConversationDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    customer_id: int
    assigned_agent_id: int | None
    title: str
    status: ConversationStatus
    version: int
    generation_epoch: int
    last_event_seq: int
    created_at: datetime
    updated_at: datetime
    ai_reply: AIReplyDTO | None = None


class MessageDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    conversation_id: int
    sender_id: int | None
    author_role: str
    client_message_id: str | None
    ai_run_id: int | None
    body: str
    citations: list[dict] = Field(default_factory=list)
    draft_id: int | None
    created_at: datetime


class LiveEvent(BaseModel):
    kind: str
    conversation_id: int
    seq: int | None
    generation_epoch: int
    payload: dict


class ConversationPage(BaseModel):
    items: list[ConversationDTO]
    next_cursor: int | None = None


class MessagePage(BaseModel):
    items: list[MessageDTO]
    next_cursor: int | None = None


class ConversationContextDTO(BaseModel):
    customer_id: int
    display_name: str
    masked_email: str
    is_active: bool
    orders: list[OrderDTO]


class EventPage(BaseModel):
    items: list[LiveEvent]
    has_more: bool = False
