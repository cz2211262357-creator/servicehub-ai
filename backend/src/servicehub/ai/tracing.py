"""Durable attempt traces. Persist only explicitly allowed, redacted inputs."""

import asyncio
import hashlib
import re
import time
from contextlib import asynccontextmanager
from decimal import Decimal

from servicehub.core.errors import BusinessError
from servicehub.db.base import utcnow

TOKEN_FIELDS = ("prompt_tokens", "completion_tokens", "total_tokens")


def safe_arguments(arguments):
    safe = {}
    for key in ("order_number", "query", "reason"):
        value = arguments.get(key)
        if isinstance(value, str):
            safe[key] = {"length": len(value), "sha256": hashlib.sha256(value.encode()).hexdigest()}
    for key, allowed in {
        "kind": {"RETURN", "EXCHANGE", "REPAIR"},
        "reason_code": {"NO_REASON", "QUALITY", "WRONG_ITEM", "LOGISTICS_DAMAGE", "OTHER"},
    }.items():
        if isinstance(arguments.get(key), str) and arguments[key] in allowed:
            safe[key] = arguments[key]
    return safe


def normalize_usage(usage):
    usage = usage if isinstance(usage, dict) else {}
    return {
        key: value if type(value := (usage or {}).get(key)) is int and value >= 0 else None
        for key in TOKEN_FIELDS
    }


def sum_usage(usages):
    rows = [normalize_usage(usage) for usage in usages]
    return {
        key: sum(row[key] for row in rows)
        if rows and all(row[key] is not None for row in rows)
        else None
        for key in TOKEN_FIELDS
    }


def estimate_cost(settings, model, usage):
    incoming = getattr(settings, "model_input_price_per_million", None)
    outgoing = getattr(settings, "model_output_price_per_million", None)
    tokens = normalize_usage(usage)
    if (
        model != settings.model_name
        or not getattr(settings, "model_price_version", "")
        or not getattr(settings, "model_price_currency", "")
        or incoming is None
        or outgoing is None
        or incoming < 0
        or outgoing < 0
        or tokens["prompt_tokens"] is None
        or tokens["completion_tokens"] is None
    ):
        return None
    return (
        Decimal(incoming) * tokens["prompt_tokens"]
        + Decimal(outgoing) * tokens["completion_tokens"]
    ) / Decimal(1000000)


def cited_evidence(text, evidence, *, required=False):
    ids = {int(value) for value in re.findall(r"\[(\d+)\]", text)}
    available = {row.chunk_id: row for row in evidence}
    if ids - available.keys():
        raise BusinessError("citation_invalid", "模型引用了无法核实的资料，请重试或转人工", 502)
    if required and not ids:
        raise BusinessError("citation_missing", "模型未提供可核实的政策引用，请重试或转人工", 502)
    return [available[key] for key in sorted(ids)]


class RunTracer:
    def __init__(self, sessions, settings, tenant_id, run_id, execution_version):
        self.sessions, self.settings = sessions, settings
        self.tenant_id, self.run_id = tenant_id, run_id
        self.execution_version = execution_version

    @asynccontextmanager
    async def span(self, kind, name, *, arguments=None, model=None):
        from servicehub.ai.models import TraceSpan

        row = TraceSpan(
            tenant_id=self.tenant_id,
            ai_run_id=self.run_id,
            execution_version=self.execution_version,
            kind=kind,
            name=name,
            started_at=utcnow(),
            outcome="running",
            model=model,
            arguments_summary=safe_arguments(arguments or {}),
        )
        async with self.sessions() as session, session.begin():
            session.add(row)
            await session.flush()
            span_id = row.id
        started = time.monotonic()
        state = {"usage": None, "outcome": "success", "error_code": None}
        try:
            yield state
        except BaseException as exc:
            state["outcome"] = "cancelled" if isinstance(exc, asyncio.CancelledError) else "failed"
            # Never record exception strings: transport errors may contain prompts or credentials.
            state["error_code"] = (
                exc.code
                if isinstance(exc, BusinessError)
                else (
                    "cancelled" if isinstance(exc, asyncio.CancelledError) else "unexpected_error"
                )
            )
            raise
        finally:

            async def finish():
                async with self.sessions() as session, session.begin():
                    saved = await session.get(TraceSpan, span_id)
                    saved.finished_at = utcnow()
                    saved.latency_ms = max(0, int((time.monotonic() - started) * 1000))
                    saved.outcome, saved.error_code = state["outcome"], state["error_code"]
                    if kind == "model":
                        saved.usage = normalize_usage(state["usage"])
                        saved.estimated_cost = estimate_cost(self.settings, model, saved.usage)
                        if saved.estimated_cost is not None:
                            saved.cost_currency = self.settings.model_price_currency
                            saved.price_version = self.settings.model_price_version

            task = asyncio.create_task(finish())
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                await task
                raise
