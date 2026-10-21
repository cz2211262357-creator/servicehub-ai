from datetime import datetime

from fastapi import APIRouter, Query, Request
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, Gauge, generate_latest
from pydantic import BaseModel, ConfigDict
from sqlalchemy import select
from starlette.responses import Response

from servicehub.core.dependencies import Admin, Staff
from servicehub.jobs.models import Job
from servicehub.jobs.schemas import JobDTO
from servicehub.jobs.service import JobService
from servicehub.operations.metrics import MetricsService, MetricsSummary
from servicehub.operations.models import AuditLog

router = APIRouter(prefix="/api/v1/admin", tags=["operations"])


class JobPage(BaseModel):
    items: list[JobDTO]
    next_cursor: int | None = None


@router.get("/jobs", response_model=JobPage)
async def jobs(
    request: Request,
    principal: Admin,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
    state: str | None = None,
):
    async with request.app.state.sessions() as session:
        query = select(Job).where(Job.tenant_id == principal.tenant_id)
        if cursor:
            query = query.where(Job.id < cursor)
        if state:
            query = query.where(Job.state == state)
        rows = list(await session.scalars(query.order_by(Job.id.desc()).limit(limit + 1)))
        return {
            "items": [JobDTO.model_validate(row) for row in rows[:limit]],
            "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
        }


@router.post("/jobs/{job_id}/retry", response_model=JobDTO)
async def retry(job_id: int, request: Request, principal: Admin):
    return await JobService(request.app.state.sessions).retry(
        principal, job_id, request.state.request_id
    )


class AuditDTO(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    tenant_id: int
    actor_id: int | None
    action: str
    target_type: str
    target_id: str
    outcome: str
    request_id: str
    details: dict
    created_at: datetime


class AuditPage(BaseModel):
    items: list[AuditDTO]
    next_cursor: int | None = None


class RuntimeStatus(BaseModel):
    model_mode: str
    model_name: str
    model_configured: bool
    embedding_model: str
    embedding_dimensions: int
    embedding_configured: bool
    global_model_slots: int
    max_output_tokens: int
    max_upload_bytes: int
    automatic_retries: int


@router.get("/metrics/summary", response_model=MetricsSummary)
async def summary(
    request: Request, principal: Staff, start: datetime | None = None, end: datetime | None = None
):
    return await MetricsService(request.app.state.sessions).summary(principal, start, end)


@router.get("/audits", response_model=AuditPage)
async def audits(
    request: Request,
    principal: Admin,
    cursor: int | None = Query(default=None, gt=0),
    limit: int = Query(default=30, ge=1, le=100),
    action: str | None = None,
    outcome: str | None = None,
):
    async with request.app.state.sessions() as session:
        query = select(AuditLog).where(AuditLog.tenant_id == principal.tenant_id)
        if cursor:
            query = query.where(AuditLog.id < cursor)
        if action:
            query = query.where(AuditLog.action == action)
        if outcome:
            query = query.where(AuditLog.outcome == outcome)
        rows = list(await session.scalars(query.order_by(AuditLog.id.desc()).limit(limit + 1)))
        return {
            "items": [AuditDTO.model_validate(row) for row in rows[:limit]],
            "next_cursor": rows[limit - 1].id if len(rows) > limit else None,
        }


@router.get("/runtime", response_model=RuntimeStatus)
async def runtime(request: Request, principal: Staff):
    cfg = request.app.state.settings
    return RuntimeStatus(
        model_mode=cfg.model_mode,
        model_name=cfg.model_name,
        model_configured=bool(
            cfg.model_name and cfg.model_base_url and cfg.model_api_key.get_secret_value()
        ),
        embedding_model=cfg.embedding_model,
        embedding_dimensions=cfg.embedding_dimensions,
        embedding_configured=bool(
            cfg.embedding_model
            and cfg.embedding_base_url
            and cfg.embedding_api_key.get_secret_value()
        ),
        global_model_slots=cfg.global_model_slots,
        max_output_tokens=cfg.max_output_tokens,
        max_upload_bytes=cfg.max_upload_bytes,
        automatic_retries=3,
    )


metrics_router = APIRouter(tags=["operations"])


@metrics_router.get("/api/v1/metrics")
async def prometheus(request: Request, principal: Admin):
    data = await MetricsService(request.app.state.sessions).summary(principal)
    registry = CollectorRegistry()
    for name, value in [
        ("conversations", data.conversations),
        ("waiting", data.waiting),
        ("failed_jobs", data.failed_jobs),
    ]:
        Gauge("servicehub_" + name, "Tenant scoped " + name, registry=registry).set(value)
    return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)
