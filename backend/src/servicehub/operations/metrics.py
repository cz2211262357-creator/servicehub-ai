from datetime import UTC, datetime, timedelta

from pydantic import BaseModel
from sqlalchemy import BigInteger, String, case, cast, func, select, text

from servicehub.ai.models import AIRun
from servicehub.conversations.models import Conversation, Message
from servicehub.core.errors import BusinessError
from servicehub.jobs.models import Job
from servicehub.operations.models import AuditLog


class DailyCount(BaseModel):
    date: str
    count: int


class MetricsSummary(BaseModel):
    start: datetime
    end: datetime
    conversations: int
    waiting: int
    human_active: int
    handoff_count: int
    handoff_rate: float | None
    first_response_ms: float | None
    failed_jobs: int
    job_states: dict[str, int]
    ai_runs: int
    usage_records: int
    total_tokens: int | None
    daily: list[DailyCount]


class MetricsService:
    def __init__(self, sessions):
        self.sessions = sessions

    async def summary(self, principal, start=None, end=None):
        end = end or datetime.now(UTC)
        start = start or end - timedelta(days=7)
        if (
            start.tzinfo is None
            or end.tzinfo is None
            or start >= end
            or end - start > timedelta(days=366)
        ):
            raise BusinessError("invalid_time_window", "请选择有效的带时区时间窗口，最长一年", 422)
        tenant = principal.tenant_id
        window = (
            Conversation.tenant_id == tenant,
            Conversation.created_at >= start,
            Conversation.created_at < end,
        )
        async with self.sessions() as session:
            count, waiting, human = (
                await session.execute(
                    select(
                        func.count(),
                        func.sum(case((Conversation.status == "WAITING_HUMAN", 1), else_=0)),
                        func.sum(case((Conversation.status == "HUMAN_ACTIVE", 1), else_=0)),
                    )
                    .select_from(Conversation)
                    .where(*window)
                )
            ).one()
            handoffs = await session.scalar(
                select(func.count(func.distinct(Conversation.id)))
                .join(
                    AuditLog,
                    (AuditLog.tenant_id == Conversation.tenant_id)
                    & (AuditLog.target_id == cast(Conversation.id, String))
                    & (AuditLog.action == "conversation.handoff")
                    & (AuditLog.created_at < end),
                )
                .where(*window)
            )
            states = dict(
                (
                    await session.execute(
                        select(Job.state, func.count())
                        .where(
                            Job.tenant_id == tenant, Job.created_at >= start, Job.created_at < end
                        )
                        .group_by(Job.state)
                    )
                ).all()
            )
            token_value = cast(
                func.json_unquote(func.json_extract(AIRun.usage, "$.total_tokens")), BigInteger
            )
            ai_runs, known, tokens = (
                await session.execute(
                    select(func.count(), func.count(token_value), func.sum(token_value))
                    .select_from(AIRun)
                    .where(
                        AIRun.tenant_id == tenant, AIRun.created_at >= start, AIRun.created_at < end
                    )
                )
            ).one()
            first = (
                select(
                    Message.conversation_id.label("cid"), func.min(Message.created_at).label("sent")
                )
                .where(Message.tenant_id == tenant, Message.author_role == "customer")
                .group_by(Message.conversation_id)
                .subquery()
            )
            reply = (
                select(
                    Message.conversation_id.label("cid"), func.min(Message.created_at).label("at")
                )
                .join(first, first.c.cid == Message.conversation_id)
                .where(
                    Message.tenant_id == tenant,
                    Message.author_role.in_(["ai", "agent"]),
                    Message.created_at >= first.c.sent,
                )
                .group_by(Message.conversation_id)
                .subquery()
            )
            delay = await session.scalar(
                select(
                    func.avg(func.timestampdiff(text("MICROSECOND"), first.c.sent, reply.c.at))
                    / 1000
                )
                .select_from(Conversation)
                .join(first, first.c.cid == Conversation.id)
                .join(reply, reply.c.cid == Conversation.id)
                .where(*window)
            )
            trend = (
                await session.execute(
                    select(func.date(Conversation.created_at), func.count())
                    .where(*window)
                    .group_by(func.date(Conversation.created_at))
                    .order_by(func.date(Conversation.created_at))
                )
            ).all()
        return MetricsSummary(
            start=start,
            end=end,
            conversations=count,
            waiting=waiting or 0,
            human_active=human or 0,
            handoff_count=handoffs,
            handoff_rate=handoffs / count if count else None,
            first_response_ms=float(delay) if delay is not None else None,
            failed_jobs=states.get("failed", 0),
            job_states=states,
            ai_runs=ai_runs,
            usage_records=known,
            total_tokens=int(tokens) if known else None,
            daily=[DailyCount(date=str(day), count=count) for day, count in trend],
        )
