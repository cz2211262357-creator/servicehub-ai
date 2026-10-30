"""Serial actual-Agent execution and transparent reports for frozen synthetic cases."""

import copy
import json
import math
import os
import re
import subprocess
import sys
import time
from contextlib import ExitStack, contextmanager
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from sqlalchemy import select

from evals.servicehub.assertions import evaluate_case, requires_policy_citation
from evals.servicehub.fixtures import evaluation_environment
from evals.servicehub.judge import judge_answer
from evals.servicehub.schema import DATA_ROOT, load_dataset, sha256
from servicehub.ai.models import AIRun, TraceSpan
from servicehub.ai.provider import HTTPModelProvider, ModelEvent, ToolRequest
from servicehub.ai.service import AIService
from servicehub.ai.tools import ToolRegistry
from servicehub.conversations.models import Message
from servicehub.core.errors import BusinessError
from servicehub.jobs.handlers import PermanentJobError, RetryableJobError
from servicehub.jobs.service import JobService
from servicehub.knowledge.retrieval import Retriever

EVALUATION_PROTOCOL_VERSION = "1.1"


def annotate_policy_ids(observed, policy_by_document):
    for evidence in observed.get("evidence", []):
        evidence["policy_id"] = policy_by_document.get(evidence.get("document_id"))
    for turn in observed.get("turns", []):
        turn["citations"] = [
            dict(citation, policy_id=policy_by_document.get(citation.get("document_id")))
            for citation in turn.get("citations", [])
        ]


def code_fingerprint(root):
    root = Path(root).resolve()
    runtime = sorted((root / "backend" / "src").rglob("*.py"))
    evaluator = sorted((root / "evals" / "servicehub").glob("*.py"))

    def digest_files(paths):
        records = [
            {"path": path.relative_to(root).as_posix(), "sha256": sha256(path.read_bytes())}
            for path in paths
        ]
        return sha256(json.dumps(records, sort_keys=True, separators=(",", ":")).encode()), records

    runtime_hash, runtime_files = digest_files(runtime)
    evaluation_hash, evaluation_files = digest_files(evaluator)
    return {
        "runtime_code_sha256": runtime_hash,
        "runtime_files": runtime_files,
        "evaluation_code_sha256": evaluation_hash,
        "evaluation_files": evaluation_files,
    }


def source_provenance(root):
    root = Path(root).resolve()
    result = {
        "evaluation_protocol_version": EVALUATION_PROTOCOL_VERSION,
        "source_git_revision": None,
        "source_git_dirty": None,
        "python_version": sys.version.split()[0],
        **code_fingerprint(root),
    }
    git_reference = root / ".git"
    if not git_reference.exists():
        result["git_metadata_status"] = "unavailable"
        return result
    try:
        if git_reference.is_file():
            reference = git_reference.read_text(encoding="utf8").strip()
            if not reference.startswith("gitdir: "):
                raise ValueError("Invalid Git worktree reference")
            git_dir = (root / reference.removeprefix("gitdir: ")).resolve()
        else:
            git_dir = git_reference
        command = [
            "git",
            "--git-dir=" + str(git_dir),
            "--work-tree=" + str(root),
            "-c",
            "safe.directory=" + str(root),
            "-c",
            "core.bare=false",
        ]
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        revision = subprocess.run(
            [*command, "rev-parse", "HEAD"],
            cwd=root,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf8",
            errors="replace",
            check=False,
            timeout=15,
        )
        dirty = subprocess.run(
            [*command, "status", "--porcelain", "--untracked-files=normal"],
            cwd=root,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf8",
            errors="replace",
            check=False,
            timeout=15,
        )
        if (
            revision.returncode
            or dirty.returncode
            or not re.fullmatch(r"[0-9a-f]{40,64}", revision.stdout.strip())
        ):
            raise ValueError("Git metadata unavailable")
        result.update(
            source_git_revision=revision.stdout.strip(),
            source_git_dirty=bool(dirty.stdout.strip()),
            git_metadata_status="recorded",
        )
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        result["git_metadata_status"] = type(exc).__name__
    return result


def embedding_cache_metadata(cache):
    if cache is None:
        return None
    return {
        "fingerprint": getattr(cache, "fingerprint", None),
        "embedding_model": getattr(cache, "model", None),
        "embedding_dimensions": getattr(cache, "dimensions", None),
        "stats": copy.deepcopy(getattr(cache, "stats", {})),
    }


def strict_case_result(row):
    judge = row.get("judge") or {}
    scores = judge.get("scores") or {}
    required = ["faithfulness", "relevance"]
    if row.get("citation_required", row.get("category") == "policy"):
        required.append("citation_correctness")
    complete = judge.get("status") == "succeeded" and all(
        type(scores.get(key)) is int and 0 <= scores[key] <= 4 for key in required
    )
    hard_pass = (
        row["checks"]["task_pass"]
        and row["checks"]["safety_pass"]
        and row["checks"]["tool_compliance"]
    )
    passed = hard_pass and all(scores[key] >= 3 for key in required) if complete else False
    return {
        "judge_coverage_complete": complete,
        "strict_complete_pass": passed if complete else (False if not hard_pass else None),
    }


def strict_summary(rows):
    outcomes = [strict_case_result(row) for row in rows]
    covered = sum(row["judge_coverage_complete"] for row in outcomes)
    passed = sum(row["strict_complete_pass"] is True for row in outcomes)
    return {
        "strict_complete_pass_rate": passed / len(rows) if rows and covered == len(rows) else None,
        "strict_known_pass_count": passed,
        "strict_judged_case_count": covered,
        "strict_judge_coverage": covered / len(rows) if rows else None,
        "strict_unjudged_or_failed_judge_count": len(rows) - covered,
        "strict_observed_lower_bound_rate": passed / len(rows) if rows else None,
    }


async def capture_public_failure_notice(client, conversation_id, turn):
    """Read public HTTP state; never synthesize UI copy or convert failures into model answers."""
    if turn.get("answer") or turn.get("run_state") != "failed":
        return
    turn["response_kind"] = "no_public_answer"
    try:
        response = await client.get(f"/api/v1/conversations/{conversation_id}")
        snapshot = {
            "http_status": response.status_code,
            "source": f"GET /api/v1/conversations/{conversation_id}",
        }
        turn["public_failure_snapshot"] = snapshot
        if response.status_code != 200:
            return
        reply = response.json().get("ai_reply") or {}
        snapshot.update(
            {key: reply.get(key) for key in ("run_id", "state", "error_code", "error_message")}
        )
        message = reply.get("error_message")
        if (
            reply.get("run_id") == turn.get("run_id")
            and reply.get("state") == "failed"
            and isinstance(message, str)
            and message.strip()
        ):
            turn["response_kind"] = "public_failure_notice"
            turn["public_failure_notice"] = message
    except Exception as exc:
        turn["public_notice_observation_error"] = type(exc).__name__


def controlled_retry_requested(case, turn_index):
    specification = case.get("fault_injection", {})
    return (
        specification.get("fail_calls") == 1
        and specification.get("turn_index", 0) == turn_index
        and not re.search(
            r"失败就先告诉我|失败请如实说|失败就告诉我|失败后先告知", case["turns"][turn_index]
        )
    )


class FaultInjector:
    def __init__(self, specification=None):
        self.specification = specification or {}
        self.turn_index, self.hits = 0, 0

    def fire(self, kind, tool=None):
        spec = self.specification
        if spec.get("kind") != kind or spec.get("turn_index", 0) != self.turn_index:
            return
        if kind == "tool_error" and spec.get("tool") != tool:
            return
        limit = spec.get("fail_calls", 1)
        if limit != -1 and self.hits >= limit:
            return
        self.hits += 1
        raise BusinessError(
            spec.get("code", "evaluation_injected_failure"),
            "评估注入的临时服务故障，请如实告知客户",
            503,
        )


class RecordingProvider:
    """Records metadata and actual model usage without retaining online prompts."""

    def __init__(self, provider, fault, calls, model):
        self.provider, self.fault, self.calls, self.model = provider, fault, calls, model

    async def stream(self, messages, tools, budget):
        started = time.monotonic()
        record = {
            "turn_index": self.fault.turn_index,
            "model": self.model,
            "usage": None,
            "outcome": "running",
            "requested_tools": [],
        }
        self.calls.append(record)
        try:
            record["provider_invoked"] = False
            self.fault.fire("provider_error")
            record["provider_invoked"] = True
            async for event in self.provider.stream(messages, tools, budget):
                if event.kind == "delta":
                    record["candidate_text"] = (record.get("candidate_text", "") + event.text)[
                        :16000
                    ]
                elif event.kind == "usage":
                    record["usage"] = copy.deepcopy(event.usage)
                elif event.kind == "tool_call":
                    record["requested_tools"].append(
                        {
                            "name": event.tool_call.name,
                            "arguments": copy.deepcopy(event.tool_call.arguments),
                        }
                    )
                yield event
            record["outcome"] = "success"
        except Exception as exc:
            record["outcome"] = "failed"
            record["error"] = getattr(exc, "code", type(exc).__name__)
            raise
        finally:
            record["latency_ms"] = round((time.monotonic() - started) * 1000, 2)


@contextmanager
def capture_runtime(observed, fault, tenant_id):
    """Observe the real registry and retriever; injected failures occur before side effects."""
    execute, search = ToolRegistry.execute, Retriever.search

    async def tracked_execute(registry, principal, name, arguments):
        if principal.tenant_id != tenant_id:
            return await execute(registry, principal, name, arguments)
        record = {
            "name": name,
            "arguments": copy.deepcopy(arguments),
            "turn_index": fault.turn_index,
        }
        observed["tool_calls"].append(record)
        started = time.monotonic()
        try:
            fault.fire("tool_error", name)
            result = await execute(registry, principal, name, arguments)
            record.update(
                outcome="success", data=copy.deepcopy(result.data), draft_id=result.draft_id
            )
            return result
        except Exception as exc:
            record.update(outcome="rejected", error=getattr(exc, "code", type(exc).__name__))
            raise
        finally:
            record["latency_ms"] = round((time.monotonic() - started) * 1000, 2)

    async def tracked_search(retriever, principal, query, *args, **kwargs):
        if principal.tenant_id != tenant_id:
            return await search(retriever, principal, query, *args, **kwargs)
        fault.fire("retrieval_error")
        result = await search(retriever, principal, query, *args, **kwargs)
        known = {row["chunk_id"] for row in observed["evidence"]}
        for row in result:
            if row.chunk_id not in known:
                observed["evidence"].append(row.model_dump(mode="json"))
                known.add(row.chunk_id)
        return result

    with (
        patch.object(ToolRegistry, "execute", tracked_execute),
        patch.object(Retriever, "search", tracked_search),
    ):
        yield


def controlled_actions(case, turn_index):
    """Gold-informed scripts are permitted ONLY for the disclosed engineering mode."""
    text = case["turns"][turn_index]
    history = " ".join(case["turns"][: turn_index + 1])
    numbers = re.findall(r"(?:SYN|DEV)-[A-Z0-9-]+", text)
    previous_numbers = re.findall(r"(?:SYN|DEV)-[A-Z0-9-]+", history)
    order_number = numbers[-1] if numbers else (previous_numbers[-1] if previous_numbers else None)
    ambiguous = bool(
        re.search(
            r"还没说|没决定|还没决定|先别决定|暂时没提供|还没给|刚才那个|先问问|先查询|先查询|先解释|先问能不能",
            text,
        )
    )
    final = turn_index == len(case["turns"]) - 1
    expected = case.get("expected_tool_arguments", {})
    actions = []
    if (
        final
        and expected
        and not (turn_index > 0 and not numbers and re.search(r"同意|确认|完成", text))
    ):
        for name, args in expected.items():
            arguments = dict(args)
            if name == "prepare_after_sale":
                arguments["reason"] = "合成评估客户明确申请" + arguments["reason_code"]
            actions.append((name, arguments))
        return actions
    lookup = bool(order_number and re.search(r"查|状态|金额|物流|到哪", text))
    after_sale = bool(
        order_number and re.search(r"申请|准备|我要|帮我退货|我想无理由退货|按无理由退货处理", text)
    )
    if ambiguous:
        return []
    if after_sale and not re.search(r"不需要售后|只查询|先别退|先别决定", text):
        kind = (
            "REPAIR"
            if "维修" in text and "不是维修" not in text
            else "EXCHANGE"
            if "换货" in text
            else "RETURN"
        )
        reason = (
            "NO_REASON"
            if re.search(r"无理由|不想要|不要了|只是反悔|不喜欢", text)
            else "WRONG_ITEM"
            if "发错" in text
            else "LOGISTICS_DAMAGE"
            if "运输" in text and "碎" in text
            else "QUALITY"
            if re.search(r"质量|故障|腐坏|破损|保内|保修", text)
            else "OTHER"
        )
        actions.append(
            (
                "prepare_after_sale",
                {
                    "order_number": order_number,
                    "kind": kind,
                    "reason_code": reason,
                    "reason": "合成评估客户明确申请" + reason,
                },
            )
        )
    elif lookup:
        actions.append(("lookup_order", {"order_number": order_number}))
    spec = case.get("fault_injection", {})
    if (
        not actions
        and spec.get("kind") == "tool_error"
        and spec.get("turn_index", 0) == turn_index
        and order_number
    ):
        name = spec["tool"]
        args = {"order_number": order_number}
        if name == "prepare_after_sale":
            args.update(
                kind="RETURN",
                reason_code="QUALITY" if "质量" in text else "NO_REASON",
                reason="合成评估故障链路验证",
            )
        actions.append((name, args))
    return actions


class ControlledProvider:
    """Deterministic engineering script; final text uses actual evidence and tool data."""

    model_name = "controlled-engineering-script-v1"

    def __init__(self, actions, retry_once=False):
        self.actions = list(actions)
        self.retry_once, self.retried, self.sent = retry_once, False, False

    async def stream(self, messages, tools, budget):
        if self.actions and not self.sent:
            self.sent = True
            name, arguments = self.actions[0]
            yield ModelEvent("tool_call", tool_call=ToolRequest(uuid4().hex, name, arguments))
            yield ModelEvent("done")
            return
        tool_messages = [message for message in messages if message["role"] == "tool"]
        result = json.loads(tool_messages[-1]["content"]) if tool_messages else {}
        if self.actions and result.get("error") and self.retry_once and not self.retried:
            self.retried = True
            name, arguments = self.actions[0]
            yield ModelEvent("tool_call", tool_call=ToolRequest(uuid4().hex, name, arguments))
            yield ModelEvent("done")
            return
        evidence = []
        for message in messages:
            content = message.get("content") or ""
            if message["role"] == "system" and "<untrusted_knowledge>" in content:
                evidence.extend(
                    json.loads(
                        content.split("<untrusted_knowledge>\n", 1)[1].split(
                            "\n</untrusted_knowledge>", 1
                        )[0]
                    )
                )
        evidence.extend(result.get("evidence", []))
        if result.get("draft"):
            draft = result["draft"]
            answer = f"已准备售后草稿，草稿编号{draft['id']}。请在页面点击确认后才会生成正式工单；退款尚未执行。"
        elif result.get("order"):
            answer = "查询到本人订单：" + json.dumps(result["order"], ensure_ascii=False)
        elif result.get("error"):
            answer = "当前操作无法完成，请核实信息或转人工。" + result.get("message", "")
        else:
            answer = "请先明确订单号、申请类型和原因；特殊情形请转人工核实。"
        if evidence:
            answer += "\n政策依据：" + "\n".join(
                f"{row['text']} [{row['chunk_id']}]" for row in evidence[:2]
            )
        yield ModelEvent("delta", text=answer)
        # Unknown synthetic tokens remain unknown; they are not API usage.
        yield ModelEvent("done")


def nearest_rank(values, percentile):
    return sorted(values)[max(0, math.ceil(len(values) * percentile) - 1)] if values else None


def usage_summary(calls):
    known = [
        call.get("usage")
        for call in calls
        if call.get("usage") and isinstance(call["usage"].get("total_tokens"), (int, float))
    ]
    return {
        "calls": len(calls),
        "provider_invocations": sum(call.get("provider_invoked", True) for call in calls),
        "failed_calls": sum(
            call.get("outcome") == "failed" or call.get("status") == "failed" for call in calls
        ),
        "calls_with_total_usage": len(known),
        "coverage": len(known) / len(calls) if calls else None,
        "known_total_tokens": sum(row["total_tokens"] for row in known) if known else None,
        "unknown_usage_calls": len(calls) - len(known),
        "estimated_cost": None,
        "known_prompt_tokens": sum(
            row["prompt_tokens"]
            for row in (call.get("usage") or {} for call in calls)
            if isinstance(row.get("prompt_tokens"), (int, float))
        )
        if any(
            isinstance((call.get("usage") or {}).get("prompt_tokens"), (int, float))
            for call in calls
        )
        else None,
        "known_completion_tokens": sum(
            row["completion_tokens"]
            for row in (call.get("usage") or {} for call in calls)
            if isinstance(row.get("completion_tokens"), (int, float))
        )
        if any(
            isinstance((call.get("usage") or {}).get("completion_tokens"), (int, float))
            for call in calls
        )
        else None,
    }


def summarize(rows, provider):
    count = len(rows)
    passed = sum(row["checks"]["task_pass"] for row in rows)
    latencies = [row["latency_ms"] for row in rows if row.get("latency_ms") is not None]
    categories = {}
    for category in sorted({row["category"] for row in rows}):
        group = [row for row in rows if row["category"] == category]
        categories[category] = {
            "count": len(group),
            "task_pass": sum(row["checks"]["task_pass"] for row in group),
            "programmatic_task_pass_rate": sum(row["checks"]["task_pass"] for row in group)
            / len(group),
            **strict_summary(group),
            "safety_pass_rate": sum(row["checks"]["safety_pass"] for row in group) / len(group),
            "tool_compliance_rate": sum(row["checks"]["tool_compliance"] for row in group)
            / len(group),
            "safety_pass": sum(row["checks"]["safety_pass"] for row in group),
            "tool_compliance": sum(row["checks"]["tool_compliance"] for row in group),
        }
    judges = [row["judge"] for row in rows if row.get("judge")]
    run_states = [
        turn["run_state"] for row in rows for turn in row.get("turns", []) if turn.get("run_state")
    ]
    terminal_count = sum(state in {"succeeded", "failed"} for state in run_states)
    return {
        "count": count,
        "task_pass": passed,
        "programmatic_task_pass_rate": passed / count if count else None,
        **strict_summary(rows),
        "engineering_chain_pass_rate": passed / count
        if count and provider == "controlled"
        else None,
        "model_task_accuracy": None,
        "accuracy_note": "Programmatic completion and judged strict completion are reported separately; no unqualified model accuracy is computed",
        "ai_run_state_counts": {
            state: run_states.count(state) for state in sorted(set(run_states))
        },
        "ai_run_failure_rate": run_states.count("failed") / terminal_count
        if terminal_count
        else None,
        "public_failure_notice_count": sum(
            turn.get("response_kind") == "public_failure_notice"
            for row in rows
            for turn in row.get("turns", [])
        ),
        "safety_hard_pass": sum(row["checks"]["safety_pass"] for row in rows),
        "tool_compliance": sum(row["checks"]["tool_compliance"] for row in rows),
        "execution_error_cases": sum(
            bool(row.get("errors")) or any(turn.get("error") for turn in row.get("turns", []))
            for row in rows
        ),
        "latency_ms": {"p50": nearest_rank(latencies, 0.5), "p95": nearest_rank(latencies, 0.95)},
        "usage": usage_summary([call for row in rows for call in row.get("model_calls", [])]),
        "judge": {
            "calls": len(judges),
            "failures": sum(row["status"] != "succeeded" for row in judges),
            "usage": usage_summary(judges),
            "mean_scores": {
                key: sum(values) / len(values) if values else None
                for key in ("faithfulness", "relevance", "citation_correctness")
                for values in [
                    [
                        j["scores"][key]
                        for j in judges
                        if j["status"] == "succeeded" and j["scores"].get(key) is not None
                    ]
                ]
            },
        },
        "categories": categories,
    }


def write_report(path, report):
    path = Path(path)
    report = dict(report)
    report.pop("report_sha256", None)
    canonical = json.dumps(
        report, ensure_ascii=False, sort_keys=True, separators=(",", ":"), default=str
    ).encode()
    report["report_sha256"] = sha256(canonical)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf8"
    )
    temporary.replace(path)
    return report


class EvaluationRunner:
    def __init__(self, settings, provider="controlled", judge="off", embedding_provider=None):
        if provider not in {"controlled", "online"} or judge not in {"off", "online"}:
            raise ValueError("Invalid evaluation provider/judge")
        self.settings, self.provider_mode, self.judge_mode = settings, provider, judge
        self.embedding_provider = embedding_provider

    async def run_case(self, environment, case):
        started = time.monotonic()
        observed = {
            "id": case["id"],
            "category": case["category"],
            "turns": [],
            "tool_calls": [],
            "model_calls": [],
            "evidence": [],
            "traces": [],
            "errors": [],
            "fault_hits": 0,
        }
        fault = FaultInjector(case.get("fault_injection"))
        try:
            context = await environment.prepare_case(case)
            observed["fixture_calibration"] = {
                "fixed_as_of": environment.manifest["as_of"],
                "server_utc": context["runtime_now"].isoformat(),
                "business_calendar": "Asia/Shanghai",
                "clock_patched": False,
            }
            observed["initial_orders"] = (await environment.snapshot(context))["orders"]
            with capture_runtime(observed, fault, context["actor"].tenant_id):
                for turn_index, text in enumerate(case["turns"]):
                    fault.turn_index = turn_index
                    await environment.before_turn(case, context, turn_index)
                    # Changes made by the trusted fixture are the permitted pre-turn baseline.
                    observed["initial_orders"] = (await environment.snapshot(context))["orders"]
                    turn = {
                        "index": turn_index,
                        "question": text,
                        "answer": "",
                        "citations": [],
                        "orders_before": copy.deepcopy(observed["initial_orders"]),
                    }
                    observed["turns"].append(turn)
                    turn_started = time.monotonic()
                    response = await context["client"].post(
                        f"/api/v1/conversations/{context['conversation_id']}/messages",
                        json={"client_message_id": "eval-" + uuid4().hex, "body": text},
                    )
                    turn["http_status"] = response.status_code
                    if response.status_code not in {200, 201}:
                        turn["error"] = (
                            response.json()
                            .get("error", {})
                            .get("code", f"http_{response.status_code}")
                        )
                        turn["latency_ms"] = round((time.monotonic() - turn_started) * 1000, 2)
                        continue
                    async with environment.sessions() as session:
                        run = await session.scalar(
                            select(AIRun)
                            .where(AIRun.conversation_id == context["conversation_id"])
                            .order_by(AIRun.id.desc())
                        )
                        job_id, run_id = run.job_id, run.id
                    turn["run_id"], turn["job_id"] = run_id, job_id
                    jobs = JobService(environment.sessions)
                    lease = await jobs.acquire(job_id, "eval-agent-" + uuid4().hex, 300)
                    if lease is None:
                        turn["error"] = "job_lease_unavailable"
                        continue
                    provider = (
                        HTTPModelProvider(environment.settings)
                        if self.provider_mode == "online"
                        else ControlledProvider(
                            controlled_actions(case, turn_index),
                            controlled_retry_requested(case, turn_index),
                        )
                    )
                    recorded = RecordingProvider(
                        provider,
                        fault,
                        observed["model_calls"],
                        environment.settings.model_name
                        if self.provider_mode == "online"
                        else ControlledProvider.model_name,
                    )
                    try:
                        await AIService(
                            environment.sessions, environment.redis, environment.settings
                        ).handle(lease, provider=recorded)
                    except (PermanentJobError, RetryableJobError, BusinessError) as exc:
                        code = getattr(exc, "code", str(exc))
                        # Like a worker, mark the failed job terminal; the report retains failure.
                        await jobs.fail(lease, code, retryable=False)
                        turn["error"] = code
                    except Exception as exc:
                        code = type(exc).__name__
                        await jobs.fail(lease, code, retryable=False)
                        turn["error"] = code
                    async with environment.sessions() as session:
                        message = await session.scalar(
                            select(Message).where(
                                Message.ai_run_id == run_id, Message.author_role == "ai"
                            )
                        )
                        if message:
                            turn.update(
                                answer=message.body,
                                response_kind="model_answer",
                                citations=message.citations or [],
                                draft_id=message.draft_id,
                            )
                        run = await session.get(AIRun, run_id)
                        turn["run_state"] = run.state
                        spans = list(
                            await session.scalars(
                                select(TraceSpan)
                                .where(TraceSpan.ai_run_id == run_id)
                                .order_by(TraceSpan.id)
                            )
                        )
                        observed["traces"].extend(
                            {
                                "id": span.id,
                                "run_id": run_id,
                                "execution_version": span.execution_version,
                                "kind": span.kind,
                                "name": span.name,
                                "outcome": span.outcome,
                                "error_code": span.error_code,
                                "latency_ms": span.latency_ms,
                                "model": span.model,
                                "usage": span.usage,
                                "arguments_summary": span.arguments_summary,
                            }
                            for span in spans
                        )
                    await capture_public_failure_notice(
                        context["client"], context["conversation_id"], turn
                    )
                    turn["orders_after"] = (await environment.snapshot(context))["orders"]
                    turn["latency_ms"] = round((time.monotonic() - turn_started) * 1000, 2)
            observed["final_state"] = await environment.snapshot(context)
        except Exception as exc:
            # Never record exception strings containing configuration or provider payloads.
            observed["errors"].append(getattr(exc, "code", type(exc).__name__))
        observed["fault_hits"] = fault.hits
        observed["latency_ms"] = round((time.monotonic() - started) * 1000, 2)
        annotate_policy_ids(observed, environment.policy_by_document)
        observed["citation_required"] = requires_policy_citation(case)
        observed["checks"] = evaluate_case(case, observed)
        observed["evaluation_reference"] = {
            "expected_final_state": case["expected_final_state"],
            "reference_answer": case["reference_answer"],
            "quality_rubric": case.get("quality_rubric", []),
            "relevant_policy_ids": case.get("relevant_policy_ids", []),
        }
        if self.judge_mode == "online":
            observed["judge"] = await judge_answer(HTTPModelProvider(self.settings), case, observed)
        observed["strict_result"] = strict_case_result(observed)
        return observed

    def refresh_reproducibility(self, report):
        ending = code_fingerprint(self.settings.project_root)
        report["source_at_end"] = ending
        report["source_changed_during_run"] = any(
            ending[key] != report["provenance"][key]
            for key in ("runtime_code_sha256", "evaluation_code_sha256")
        )
        report["embedding_cache"] = embedding_cache_metadata(self.embedding_provider)

    async def run(self, report_path, dataset="cases", case_ids=None, categories=None, limit=None):
        cases, data_metadata = load_dataset(dataset)
        selected = [
            case
            for case in cases
            if (not case_ids or case["id"] in case_ids)
            and (not categories or case["category"] in categories)
        ]
        if limit is not None:
            selected = selected[:limit]
        if not selected:
            raise ValueError("No evaluation cases selected")
        manifest = json.loads((DATA_ROOT / "manifest.json").read_text(encoding="utf8"))
        settings = self.settings
        if self.provider_mode == "online" and settings.model_mode != "online":
            settings = settings.model_copy(update={"model_mode": "online"})
        if self.provider_mode == "controlled":
            settings = settings.model_copy(
                update={"model_mode": "demo", "model_name": ControlledProvider.model_name}
            )
        report = {
            "schema_version": "1.1",
            "evaluation_protocol_version": EVALUATION_PROTOCOL_VERSION,
            "criteria_registration_note": "Protocol 1.1 criteria registered before the first online 150-case evaluation; frozen gold answers and corpus unchanged",
            "provenance": source_provenance(self.settings.project_root),
            "data_sha256": data_metadata["data_sha256"],
            "embedding_cache_at_start": embedding_cache_metadata(self.embedding_provider),
            "created_at": datetime.now(UTC).isoformat(),
            "provider": self.provider_mode,
            "model": settings.model_name,
            "runtime_parameters": {
                "model_temperature": settings.model_temperature,
                "max_output_tokens": settings.max_output_tokens,
                "max_tool_steps": settings.max_tool_steps,
                "agent_timeout_seconds": settings.agent_timeout_seconds,
                "model_timeout_seconds": settings.model_timeout_seconds,
                "retrieval_backend": settings.retrieval_backend,
                "embedding_model": settings.embedding_model,
                "embedding_dimensions": settings.embedding_dimensions,
                "model_id_kind": "requested alias; upstream revision not returned by this adapter",
            },
            "judge_mode": self.judge_mode,
            "judge_model": self.settings.model_name if self.judge_mode == "online" else None,
            "quality_scope": "Task success is programmatic tool/state completion; full semantic quality requires optional isolated judge or human review",
            "interpretation": "Gold-informed engineering chain only; not model accuracy"
            if self.provider_mode == "controlled"
            else "Real provider responses; gold is used only after Agent execution",
            "dataset": data_metadata,
            "selected_case_ids": [case["id"] for case in selected],
            "report_hash_method": "SHA256 of sorted compact UTF-8 JSON excluding report_sha256",
            "synthetic_full_arguments": True,
            "cases": [],
            "status": "running",
        }
        with ExitStack() as stack:
            if self.embedding_provider is not None:
                import servicehub.knowledge.indexer as indexer_module
                import servicehub.knowledge.retrieval as retrieval_module

                stack.enter_context(
                    patch.object(
                        indexer_module,
                        "HTTPEmbeddingProvider",
                        lambda _settings: self.embedding_provider,
                    )
                )
                stack.enter_context(
                    patch.object(
                        retrieval_module,
                        "HTTPEmbeddingProvider",
                        lambda _settings: self.embedding_provider,
                    )
                )
            try:
                async with evaluation_environment(settings, manifest) as environment:
                    report["fixture_calibration"] = environment.calibration
                    report["retrieval_backend"] = getattr(
                        settings, "retrieval_backend", "mysql_legacy"
                    )
                    report["retrieval_model_mode"] = settings.model_mode
                    for case in selected:
                        row = await self.run_case(environment, case)
                        report["cases"].append(row)
                        report["summary"] = summarize(report["cases"], self.provider_mode)
                        write_report(report_path, report)
                report["status"] = "completed"
            except Exception as exc:
                report["status"], report["error"] = (
                    "failed",
                    getattr(exc, "code", type(exc).__name__),
                )
                report["summary"] = summarize(report["cases"], self.provider_mode)
                self.refresh_reproducibility(report)
                write_report(report_path, report)
                raise
        report["summary"] = summarize(report["cases"], self.provider_mode)
        self.refresh_reproducibility(report)
        return write_report(report_path, report)
