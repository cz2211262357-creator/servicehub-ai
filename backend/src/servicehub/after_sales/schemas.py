from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

TicketKind = Literal["RETURN", "EXCHANGE", "REPAIR"]
TicketStatus = Literal["SUBMITTED", "IN_REVIEW", "APPROVED", "REJECTED", "RESOLVED", "CANCELLED"]


class DraftInput(BaseModel):
    order_id: int = Field(gt=0)
    kind: TicketKind
    reason: str = Field(min_length=2, max_length=1000)


class ConfirmInput(BaseModel):
    version: int = Field(ge=1)
    idempotency_key: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")


class TransitionInput(BaseModel):
    version: int = Field(ge=1)
    status: TicketStatus
    note: str = Field(default="", max_length=500)


class TicketDraftDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    order_id: int
    customer_id: int
    kind: TicketKind
    reason: str
    version: int
    expires_at: datetime
    consumed: bool


class TicketEventDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    actor_id: int
    from_status: str | None
    to_status: str
    note: str
    created_at: datetime


class TicketDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    draft_id: int
    order_id: int
    customer_id: int
    kind: TicketKind
    reason: str
    status: TicketStatus
    version: int
    created_at: datetime
    events: list[TicketEventDTO] = Field(default_factory=list)


class TicketPage(BaseModel):
    items: list[TicketDTO]
    next_cursor: int | None = None


class DraftPage(BaseModel):
    items: list[TicketDraftDTO]
