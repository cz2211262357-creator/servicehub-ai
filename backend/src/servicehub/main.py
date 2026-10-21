import asyncio
import logging
import re
import time
from contextlib import asynccontextmanager
from contextvars import ContextVar
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from redis.exceptions import RedisError
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from starlette.exceptions import HTTPException

from servicehub.after_sales.routes import router as after_sales_router
from servicehub.auth.routes import router as auth_router
from servicehub.conversations.routes import router as conversations_router
from servicehub.core.config import Settings, load_settings
from servicehub.core.errors import BusinessError, business_error_handler, error_response
from servicehub.core.logging import configure_logging
from servicehub.db.engine import create_database, create_redis
from servicehub.knowledge.routes import router as knowledge_router
from servicehub.operations.routes import metrics_router
from servicehub.operations.routes import router as operations_router
from servicehub.orders.routes import router as orders_router
from servicehub.realtime.routes import router as realtime_router

request_id_context: ContextVar[str] = ContextVar("request_id", default="-")
logger = logging.getLogger("servicehub")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    configure_logging()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.engine, app.state.sessions = create_database(settings)
        app.state.redis = create_redis(settings)
        try:
            yield
        finally:
            await app.state.redis.aclose()
            await app.state.engine.dispose()

    app = FastAPI(
        title="ServiceHub AI",
        version="0.1.0",
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.allowed_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
        allow_headers=["content-type", "x-csrf-token", "x-request-id"],
        expose_headers=["x-request-id", "retry-after"],
    )
    app.add_exception_handler(BusinessError, business_error_handler)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        fields = [
            {"field": ".".join(str(part) for part in item["loc"]), "type": item["type"]}
            for item in exc.errors()
        ]
        return error_response(request, "validation_error", "Request validation failed", 422, fields)

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException):
        code = {401: "unauthorized", 403: "forbidden", 404: "not_found"}.get(
            exc.status_code,
            "http_error",
        )
        return error_response(request, code, str(exc.detail), exc.status_code)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        candidate = request.headers.get("x-request-id", "")
        request_id = candidate if re.fullmatch(r"[A-Za-z0-9_-]{8,96}", candidate) else uuid4().hex
        request.state.request_id = request_id
        token = request_id_context.set(request_id)
        started = time.monotonic()
        try:
            try:
                response = await call_next(request)
            except (SQLAlchemyError, RedisError):
                response = error_response(
                    request, "dependencies_unavailable", "服务暂时不可用，请稍后重试", 503
                )
            except Exception as exc:
                logger.error(
                    "unhandled_error",
                    extra={
                        "request_id": request_id,
                        "exception_type": type(exc).__name__,
                    },
                )
                response = error_response(
                    request, "internal_error", "An unexpected error occurred", 500
                )
            if response.status_code == 429 and "retry-after" not in response.headers:
                response.headers["retry-after"] = str(settings.rate_limit_window_seconds)
            response.headers["x-request-id"] = request_id
            response.headers["x-content-type-options"] = "nosniff"
            logger.info(
                "request_completed",
                extra={
                    "request_id": request_id,
                    "method": request.method,
                    "route": getattr(request.scope.get("route"), "path", "unmatched"),
                    "status": response.status_code,
                    "duration_ms": round((time.monotonic() - started) * 1000, 2),
                },
            )
            return response
        finally:
            request_id_context.reset(token)

    @app.get("/api/v1/health/live", tags=["health"])
    async def liveness():
        return {"status": "ok", "service": "servicehub-ai", "version": "0.1.0"}

    @app.get("/api/v1/health/ready", tags=["health"])
    async def readiness():
        try:
            async with asyncio.timeout(4):
                async with app.state.engine.connect() as connection:
                    await connection.execute(text("SELECT 1"))
                await app.state.redis.ping()
        except Exception:
            raise BusinessError(
                "dependencies_unavailable",
                "Database or cache is unavailable",
                503,
            ) from None
        return {"status": "ready", "database": "ok", "redis": "ok"}

    app.include_router(auth_router)
    app.include_router(operations_router)
    app.include_router(metrics_router)
    app.include_router(knowledge_router)
    app.include_router(realtime_router)
    app.include_router(conversations_router)
    app.include_router(orders_router)
    app.include_router(after_sales_router)
    return app


app = create_app()
