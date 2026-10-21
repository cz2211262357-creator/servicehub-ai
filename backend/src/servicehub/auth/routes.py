from fastapi import APIRouter, Query, Request, Response

from servicehub.auth.schemas import (
    LoginInput,
    MemberInput,
    MemberPage,
    MemberUpdate,
    SessionDTO,
    UserDTO,
)
from servicehub.core.dependencies import Admin, Authenticated, auth_service, check_origin

router = APIRouter(prefix="/api/v1", tags=["auth"])


@router.post("/auth/login", response_model=SessionDTO)
async def login(payload: LoginInput, request: Request, response: Response):
    check_origin(request)
    token, user, csrf = await auth_service(request).login(
        payload, request.client.host if request.client else "unknown"
    )
    settings = request.app.state.settings
    response.set_cookie(
        settings.session_cookie_name,
        token,
        max_age=settings.session_ttl_seconds,
        httponly=True,
        secure=settings.cookie_secure,
        samesite="lax",
        path="/",
    )
    return SessionDTO(user=user, csrf_token=csrf)


@router.get("/auth/me", response_model=SessionDTO)
async def me(request: Request, principal: Authenticated):
    return SessionDTO(
        user=await auth_service(request).current_user(principal), csrf_token=principal.csrf_token
    )


@router.post("/auth/logout", status_code=204)
async def logout(request: Request, response: Response, principal: Authenticated):
    await auth_service(request).logout(principal)
    response.delete_cookie(request.app.state.settings.session_cookie_name, path="/")


@router.get("/admin/members", response_model=MemberPage)
async def members(
    request: Request,
    principal: Admin,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
):
    return await auth_service(request).list_members(principal, cursor, limit)


@router.post("/admin/members", response_model=UserDTO, status_code=201)
async def create_member(payload: MemberInput, request: Request, principal: Admin):
    return await auth_service(request).create_member(principal, payload, request.state.request_id)


@router.patch("/admin/members/{member_id}", response_model=UserDTO)
async def update_member(member_id: int, payload: MemberUpdate, request: Request, principal: Admin):
    return await auth_service(request).update_member(
        principal, member_id, payload, request.state.request_id
    )
