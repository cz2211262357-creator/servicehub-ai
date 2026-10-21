from sqlalchemy.ext.asyncio import AsyncSession

from servicehub.auth.schemas import Principal
from servicehub.operations.models import AuditLog


class AuditService:
    @staticmethod
    def record(
        session: AsyncSession,
        principal: Principal,
        action: str,
        target_type: str,
        target_id: str | int,
        outcome: str,
        request_id: str = "-",
        details: dict | None = None,
    ) -> None:
        session.add(
            AuditLog(
                tenant_id=principal.tenant_id,
                actor_id=principal.user_id,
                action=action,
                target_type=target_type,
                target_id=str(target_id),
                outcome=outcome,
                request_id=request_id,
                details=details or {},
            )
        )
