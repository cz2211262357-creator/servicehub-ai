from dataclasses import dataclass, field

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from sqlalchemy import select

from servicehub.after_sales.eligibility import ReasonCode
from servicehub.after_sales.service import TicketService
from servicehub.auth.models import User
from servicehub.core.errors import BusinessError
from servicehub.knowledge.retrieval import Retriever
from servicehub.orders.service import OrderService


class ToolArgs(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PolicyArgs(ToolArgs):
    query: str = Field(min_length=1, max_length=2000)


class OrderArgs(ToolArgs):
    order_number: str = Field(min_length=1, max_length=40)


class DraftArgs(OrderArgs):
    kind: str = Field(pattern="^(RETURN|EXCHANGE|REPAIR)$")
    reason: str = Field(min_length=2, max_length=1000)
    reason_code: ReasonCode = "OTHER"


@dataclass(frozen=True)
class ToolResult:
    data: dict
    evidence_ids: list[int] = field(default_factory=list)
    draft_id: int | None = None
    evidence: list = field(default_factory=list)


TOOL_TYPES = {
    "search_policy": PolicyArgs,
    "lookup_order": OrderArgs,
    "prepare_after_sale": DraftArgs,
}
DESCRIPTIONS = {
    "search_policy": "检索本商城售后政策。必须以结果中的真实片段为依据，并引用 chunk_id。",
    "lookup_order": "查询当前客户本人订单及物流，不允许查询他人订单。",
    "prepare_after_sale": "依据受信订单事实校验并准备售后草稿。reason_code必须明确；缺失事实需澄清或人工。客户在页面确认后才生成工单，不执行退款。",
}


class ToolRegistry:
    def __init__(
        self,
        sessions,
        redis,
        settings,
        *,
        run_id=None,
        conversation_id=None,
        execution_version=None,
    ):
        self.sessions = sessions
        self.execution_context = {
            "run_id": run_id,
            "conversation_id": conversation_id,
            "execution_version": execution_version,
        }
        self.retriever = Retriever(sessions, redis, settings)
        self.orders, self.tickets = OrderService(sessions), TicketService(sessions)

    @property
    def schemas(self):
        return [
            {
                "type": "function",
                "function": {
                    "name": name,
                    "description": DESCRIPTIONS[name],
                    "parameters": kind.model_json_schema(),
                },
            }
            for name, kind in TOOL_TYPES.items()
        ]

    async def execute(self, principal, tool_name, arguments):
        kind = TOOL_TYPES.get(tool_name)
        if not kind:
            raise BusinessError("unknown_tool", "该工具不被允许", 403)
        try:
            args = kind.model_validate(arguments)
        except ValidationError:
            raise BusinessError("invalid_tool_arguments", "工具参数不合法", 422) from None
        async with self.sessions() as session:
            user = await session.scalar(
                select(User).where(
                    User.id == principal.user_id,
                    User.tenant_id == principal.tenant_id,
                    User.is_active.is_(True),
                )
            )
        if not user or user.role != "customer" or principal.role != "customer":
            raise BusinessError("tool_forbidden", "工具仅允许为当前有效客户执行", 403)
        if tool_name == "search_policy":
            evidence = await self.retriever.search(principal, args.query)
            return ToolResult(
                {"evidence": [row.model_dump() for row in evidence]},
                [row.chunk_id for row in evidence],
                evidence=evidence,
            )
        order = await self.orders.get_order(principal, args.order_number)
        if tool_name == "lookup_order":
            return ToolResult({"order": order.model_dump(mode="json")})
        draft = await self.tickets.create_draft(
            principal,
            order.id,
            args.kind,
            args.reason,
            args.reason_code,
            **self.execution_context,
        )
        return ToolResult(
            {"draft": draft.model_dump(mode="json"), "requires_customer_confirmation": True},
            draft_id=draft.id,
        )
