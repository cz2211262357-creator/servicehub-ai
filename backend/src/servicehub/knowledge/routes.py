from fastapi import APIRouter, File, Form, Query, Request, UploadFile
from starlette.responses import FileResponse

from servicehub.core.dependencies import Admin, Authenticated
from servicehub.knowledge.schemas import DocumentDTO, DocumentPage, EnableInput
from servicehub.knowledge.service import DocumentService

router = APIRouter(prefix="/api/v1/knowledge/documents", tags=["knowledge"])


def service(request):
    return DocumentService(request.app.state.sessions, request.app.state.settings)


@router.post("", response_model=DocumentDTO, status_code=201)
async def upload(
    request: Request,
    principal: Admin,
    file: UploadFile = File(),
    title: str = Form(default="", max_length=200),
):
    return await service(request).upload(
        principal, file, title, request_id=request.state.request_id
    )


@router.post("/{document_id}/versions", response_model=DocumentDTO, status_code=201)
async def version(document_id: int, request: Request, principal: Admin, file: UploadFile = File()):
    return await service(request).upload(
        principal, file, document_id=document_id, request_id=request.state.request_id
    )


@router.get("", response_model=DocumentPage)
async def documents(
    request: Request,
    principal: Authenticated,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
):
    return await service(request).list(principal, cursor, limit)


@router.get("/{document_id}", response_model=DocumentDTO)
async def detail(document_id: int, request: Request, principal: Authenticated):
    return await service(request).get(principal, document_id)


@router.get("/{document_id}/download")
async def download(
    document_id: int,
    request: Request,
    principal: Authenticated,
    version_id: int | None = Query(default=None, ge=1),
):
    path, filename, media_type = await service(request).download(principal, document_id, version_id)
    return FileResponse(
        path, filename=filename, media_type=media_type, content_disposition_type="attachment"
    )


@router.patch("/{document_id}", response_model=DocumentDTO)
async def enable(document_id: int, payload: EnableInput, request: Request, principal: Admin):
    return await service(request).set_enabled(
        principal, document_id, payload.enabled, payload.version, request.state.request_id
    )


@router.delete("/{document_id}", status_code=204)
async def remove(document_id: int, request: Request, principal: Admin):
    await service(request).delete(principal, document_id, request.state.request_id)
