from datetime import UTC, datetime, timedelta
from decimal import Decimal
from math import ceil

from pydantic import BaseModel
from sqlalchemy import String, case, cast, func, select, text

from servicehub.ai.models import AIRun, TraceSpan
from servicehub.ai.tracing import normalize_usage
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
    ai_succeeded: int
    ai_failed: int
    ai_cancelled: int
    ai_failure_rate: float | None
    ai_terminal_samples: int
    ai_p95_latency_ms: int | None
    ai_latency_samples: int
    model_calls: int
    tool_calls: int
    retrieval_calls: int
    prompt_tokens: int | None
    completion_tokens: int | None
    usage_coverage: float | None
    usage_complete_records: int
    usage_unknown_records: int
    estimated_cost: Decimal | None
    cost_currency: str | None
    price_version: str | None
    cost_known_records: int


def summarize_ai(runs, spans):
    succeeded = sum(row.state == "succeeded" for row in runs)
    failed = sum(row.state == "failed" for row in runs)
    latencies = []
    for row in runs:
        if row.state not in ("succeeded", "failed"):
            continue
        latency = row.latency_ms
        if (
            latency is None
            and getattr(row, "started_at", None)
            and getattr(row, "finished_at", None)
        ):
            latency = max(0, int((row.finished_at - row.started_at).total_seconds() * 1000))
        if latency is not None:
            latencies.append(latency)
    latencies.sort()
    models = [row for row in spans if row.kind == "model"]
    usage = [normalize_usage(row.usage) for row in models]
    known = sum(row["total_tokens"] is not None for row in usage)
    costs = [row.estimated_cost for row in models if row.estimated_cost is not None]
    currencies = {row.cost_currency for row in models}
    versions = {row.price_version for row in models}
    cost_complete = (
        bool(models) and len(costs) == len(models) and len(currencies) == len(versions) == 1
    )
    return dict(
        ai_succeeded=succeeded,
        ai_failed=failed,
        ai_cancelled=sum(row.state == "cancelled" for row in runs),
        ai_failure_rate=failed / (succeeded + failed) if succeeded + failed else None,
        ai_terminal_samples=succeeded + failed,
        ai_p95_latency_ms=latencies[ceil(len(latencies) * 0.95) - 1] if latencies else None,
        ai_latency_samples=len(latencies),
        model_calls=len(models),
        tool_calls=sum(row.kind == "tool" for row in spans),
        retrieval_calls=sum(row.kind == "retrieval" for row in spans),
        **{
            key: sum(values)
            if (values := [row[key] for row in usage if row[key] is not None])
            else None
            for key in ("prompt_tokens", "completion_tokens", "total_tokens")
        },
        usage_records=known,
        usage_coverage=known / len(models) if models else None,
        usage_complete_records=sum(
            all(value is not None for value in row.values()) for row in usage
        ),
        usage_unknown_records=len(models) - known,
        estimated_cost=sum(costs, Decimal(0)) if cost_complete else None,
        cost_currency=next(iter(currencies)) if cost_complete else None,
        price_version=next(iter(versions)) if cost_complete else None,
        cost_known_records=len(costs),
    )


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
            ai_runs = await session.scalar(
                select(func.count())
                .select_from(AIRun)
                .where(AIRun.tenant_id == tenant, AIRun.created_at >= start, AIRun.created_at < end)
            )
            terminal_runs = list(
                await session.scalars(
                    select(AIRun).where(
                        AIRun.tenant_id == tenant,
                        AIRun.finished_at >= start,
                        AIRun.finished_at < end,
                        AIRun.state.in_(["succeeded", "failed", "cancelled"]),
                    )
                )
            )
            # Include every model attempt, even retries and failed final commits.
            spans = list(
                await session.scalars(
                    select(TraceSpan).where(
                        TraceSpan.tenant_id == tenant,
                        TraceSpan.started_at >= start,
                        TraceSpan.started_at < end,
                    )
                )
            )
            ai = summarize_ai(terminal_runs, spans)
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
            start=start.astimezone(UTC),
            end=end.astimezone(UTC),
            conversations=count,
            waiting=waiting or 0,
            human_active=human or 0,
            handoff_count=handoffs,
            handoff_rate=handoffs / count if count else None,
            first_response_ms=float(delay) if delay is not None else None,
            failed_jobs=states.get("failed", 0),
            job_states=states,
            ai_runs=ai_runs,
            **ai,
            daily=[DailyCount(date=str(day), count=count) for day, count in trend],
        )
