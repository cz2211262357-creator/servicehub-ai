from fastapi import APIRouter, Query, Request

from servicehub.after_sales.schemas import (
    ConfirmInput,
    DraftInput,
    DraftPage,
    TicketDraftDTO,
    TicketDTO,
    TicketPage,
    TransitionInput,
)
from servicehub.after_sales.service import TicketService
from servicehub.core.dependencies import Authenticated

router = APIRouter(prefix="/api/v1/after-sales", tags=["after-sales"])


def service(request):
    return TicketService(request.app.state.sessions)


@router.get("/drafts", response_model=DraftPage)
async def drafts(request: Request, principal: Authenticated):
    return await service(request).list_drafts(principal)


@router.post("/drafts", response_model=TicketDraftDTO, status_code=201)
async def prepare(payload: DraftInput, request: Request, principal: Authenticated):
    return await service(request).create_draft(
        principal, payload.order_id, payload.kind, payload.reason, payload.reason_code
    )


@router.post("/drafts/{draft_id}/confirm", response_model=TicketDTO)
async def confirm(draft_id: int, payload: ConfirmInput, request: Request, principal: Authenticated):
    return await service(request).confirm_draft(
        principal, draft_id, payload.version, payload.idempotency_key
    )


@router.get("", response_model=TicketPage)
async def tickets(
    request: Request,
    principal: Authenticated,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
    status: str | None = None,
):
    return await service(request).list(principal, cursor, limit, status)


@router.get("/{ticket_id}", response_model=TicketDTO)
async def detail(ticket_id: int, request: Request, principal: Authenticated):
    return await service(request).get(principal, ticket_id)


@router.patch("/{ticket_id}/status", response_model=TicketDTO)
async def transition(
    ticket_id: int, payload: TransitionInput, request: Request, principal: Authenticated
):
    return await service(request).transition(
        principal,
        ticket_id,
        payload.status,
        payload.version,
        payload.note,
        request.state.request_id,
    )
