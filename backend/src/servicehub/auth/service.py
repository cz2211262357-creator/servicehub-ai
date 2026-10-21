import asyncio
import json

from sqlalchemy import select

from servicehub.auth.models import Tenant, User
from servicehub.auth.schemas import LoginInput, MemberInput, MemberUpdate, Principal, UserDTO
from servicehub.auth.security import digest, hash_password, new_token, verify_password
from servicehub.core.errors import BusinessError
from servicehub.operations.audit import AuditService

LOGIN_RATE = """
local n = redis.call('INCR', KEYS[1])
if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end
return n
"""


class AuthService:
    def __init__(self, sessions, redis, settings):
        self.sessions, self.redis, self.settings = sessions, redis, settings

    async def login(self, payload: LoginInput, client_ip: str):
        identity = f"{payload.tenant_id}:{str(payload.email).lower()}:{client_ip}"
        attempts = await self.redis.eval(LOGIN_RATE, 1, "servicehub:login:" + digest(identity))
        if attempts > 10:
            raise BusinessError("login_rate_limited", "登录尝试过于频繁，请稍后重试", 429)
        async with self.sessions() as session:
            user = await session.scalar(
                select(User).where(
                    User.tenant_id == payload.tenant_id, User.email == str(payload.email).lower()
                )
            )
        valid = await asyncio.to_thread(
            verify_password,
            payload.password.get_secret_value(),
            user.password_hash if user else None,
        )
        if not user or not valid or not user.is_active:
            raise BusinessError("invalid_credentials", "企业编号、账号或密码不正确", 401)
        token, csrf = new_token(), new_token()
        value = {
            "user_id": user.id,
            "tenant_id": user.tenant_id,
            "session_version": user.session_version,
            "csrf_token": csrf,
        }
        await self.redis.set(
            "servicehub:session:" + digest(token),
            json.dumps(value),
            ex=self.settings.session_ttl_seconds,
        )
        return token, UserDTO.model_validate(user), csrf

    async def authenticate(self, token: str | None) -> Principal:
        if not token or len(token) > 128:
            raise BusinessError("unauthorized", "请先登录", 401)
        session_digest = digest(token)
        key = "servicehub:session:" + session_digest
        raw = await self.redis.get(key)
        if not raw:
            raise BusinessError("unauthorized", "登录已过期，请重新登录", 401)
        stored = json.loads(raw)
        async with self.sessions() as session:
            user = await session.scalar(
                select(User).where(
                    User.id == stored["user_id"], User.tenant_id == stored["tenant_id"]
                )
            )
        if not user or not user.is_active or user.session_version != stored["session_version"]:
            await self.redis.delete(key)
            raise BusinessError("unauthorized", "账号权限已变更，请重新登录", 401)
        return Principal(
            user.id,
            user.tenant_id,
            user.role,
            user.session_version,
            session_digest,
            stored["csrf_token"],
            user.email,
            user.display_name,
        )

    async def current_user(self, principal: Principal):
        async with self.sessions() as session:
            user = await session.get(User, principal.user_id)
            return UserDTO.model_validate(user)

    async def revalidate(self, principal):
        raw = await self.redis.get("servicehub:session:" + principal.session_digest)
        if not raw:
            raise BusinessError("unauthorized", "登录已过期，请重新登录", 401)
        stored = json.loads(raw)
        async with self.sessions() as session:
            user = await session.scalar(
                select(User).where(
                    User.id == principal.user_id, User.tenant_id == principal.tenant_id
                )
            )
        if (
            not user
            or not user.is_active
            or user.session_version != principal.session_version
            or stored["session_version"] != principal.session_version
        ):
            raise BusinessError("unauthorized", "账号权限已变更，请重新登录", 401)

    async def logout(self, principal):
        await self.redis.delete("servicehub:session:" + principal.session_digest)

    async def list_members(self, principal, cursor: int | None, limit: int):
        async with self.sessions() as session:
            query = select(User).where(User.tenant_id == principal.tenant_id)
            if cursor:
                query = query.where(User.id < cursor)
            rows = list(await session.scalars(query.order_by(User.id.desc()).limit(limit + 1)))
            return {
                "items": [UserDTO.model_validate(row) for row in rows[:limit]],
                "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
            }

    async def update_member(
        self, principal, member_id: int, payload: MemberUpdate, request_id: str
    ):
        async with self.sessions() as session, session.begin():
            await session.scalar(
                select(Tenant).where(Tenant.id == principal.tenant_id).with_for_update()
            )
            actor = await session.get(User, principal.user_id, populate_existing=True)
            if not actor or not actor.is_active or actor.role != "admin":
                raise BusinessError("forbidden", "仅管理员可管理成员", 403)
            user = await session.scalar(
                select(User)
                .where(User.id == member_id, User.tenant_id == principal.tenant_id)
                .with_for_update()
            )
            if not user:
                raise BusinessError("not_found", "成员不存在", 404)
            if member_id == principal.user_id and (
                payload.role not in (None, "admin") or payload.is_active is False
            ):
                raise BusinessError("self_admin_change", "不能停用或降级当前管理员账号", 409)
            changed = payload.model_dump(exclude_none=True)
            for key, value in changed.items():
                setattr(user, key, value)
            if "role" in changed or "is_active" in changed:
                user.session_version += 1
            AuditService.record(
                session,
                principal,
                "member.updated",
                "user",
                user.id,
                "success",
                request_id,
                changed,
            )
            await session.flush()
            return UserDTO.model_validate(user)

    async def create_member(self, principal, payload: MemberInput, request_id: str):
        hashed = await asyncio.to_thread(hash_password, payload.password.get_secret_value())
        async with self.sessions() as session, session.begin():
            existing = await session.scalar(
                select(User.id).where(
                    User.tenant_id == principal.tenant_id, User.email == str(payload.email).lower()
                )
            )
            if existing:
                raise BusinessError("member_exists", "该账号已存在", 409)
            user = User(
                tenant_id=principal.tenant_id,
                email=str(payload.email).lower(),
                display_name=payload.display_name,
                role=payload.role,
                password_hash=hashed,
            )
            session.add(user)
            await session.flush()
            AuditService.record(
                session,
                principal,
                "member.created",
                "user",
                user.id,
                "success",
                request_id,
                {"role": user.role},
            )
            return UserDTO.model_validate(user)
