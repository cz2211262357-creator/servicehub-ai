import hmac
from typing import Annotated

from fastapi import Depends, Request

from servicehub.auth.schemas import Principal
from servicehub.auth.service import AuthService
from servicehub.core.errors import BusinessError


def auth_service(request: Request) -> AuthService:
    return AuthService(
        request.app.state.sessions, request.app.state.redis, request.app.state.settings
    )


def check_origin(request: Request):
    origin = request.headers.get("origin")
    if origin and origin not in request.app.state.settings.allowed_origins:
        raise BusinessError("origin_denied", "请求来源不被允许", 403)


async def require_principal(request: Request) -> Principal:
    settings = request.app.state.settings
    principal = await auth_service(request).authenticate(
        request.cookies.get(settings.session_cookie_name)
    )
    if request.method not in ("GET", "HEAD", "OPTIONS"):
        check_origin(request)
        if not hmac.compare_digest(request.headers.get("x-csrf-token", ""), principal.csrf_token):
            raise BusinessError("csrf_rejected", "请求校验失败，请刷新后重试", 403)
    request.state.principal = principal
    return principal


def require_roles(*roles):
    async def dependency(principal: Annotated[Principal, Depends(require_principal)]) -> Principal:
        if principal.role not in roles:
            raise BusinessError("forbidden", "当前账号无权执行此操作", 403)
        return principal

    return dependency


Authenticated = Annotated[Principal, Depends(require_principal)]
Staff = Annotated[Principal, Depends(require_roles("admin", "agent"))]
Admin = Annotated[Principal, Depends(require_roles("admin"))]
