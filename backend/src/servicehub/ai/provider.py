import json
from dataclasses import dataclass
from typing import AsyncIterator, Protocol

import httpx

from servicehub.core.errors import BusinessError


@dataclass(frozen=True)
class ToolRequest:
    id: str
    name: str
    arguments: dict


@dataclass(frozen=True)
class ModelEvent:
    kind: str
    text: str = ""
    tool_call: ToolRequest | None = None
    usage: dict | None = None


class ModelProvider(Protocol):
    def stream(self, messages, tools, budget) -> AsyncIterator[ModelEvent]: ...


class HTTPModelProvider:
    def __init__(self, settings, client=None):
        self.settings, self.client = settings, client

    async def stream(self, messages, tools, budget):
        cfg = self.settings
        if not cfg.model_base_url or not cfg.model_name or not cfg.model_api_key.get_secret_value():
            raise BusinessError("model_not_configured", "尚未配置在线对话模型", 503)
        payload = {
            "model": cfg.model_name,
            "messages": messages,
            "stream": True,
            "max_tokens": min(budget, cfg.max_output_tokens),
            "stream_options": {"include_usage": True},
        }
        if tools:
            payload["tools"] = tools
        if "aliyuncs.com" in cfg.model_base_url and cfg.model_name.startswith("qwen"):
            payload["enable_thinking"] = False
        client = self.client or httpx.AsyncClient(
            timeout=cfg.model_timeout_seconds,
            limits=httpx.Limits(max_connections=16, max_keepalive_connections=8),
        )
        calls, done = {}, False
        try:
            async with client.stream(
                "POST",
                cfg.model_base_url.rstrip("/") + "/chat/completions",
                headers={"Authorization": "Bearer " + cfg.model_api_key.get_secret_value()},
                json=payload,
            ) as response:
                if response.status_code != 200:
                    code = (
                        "model_rate_limited" if response.status_code == 429 else "model_unavailable"
                    )
                    raise BusinessError(code, "对话模型暂时不可用，请稍后重试或转人工", 503)
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        done = True
                        break
                    if len(data) > 100000:
                        raise ValueError("Oversized model frame")
                    frame = json.loads(data)
                    if frame.get("error"):
                        raise ValueError("Model stream error")
                    if frame.get("usage"):
                        yield ModelEvent("usage", usage=frame["usage"])
                    for choice in frame.get("choices", []):
                        delta = choice.get("delta", {})
                        if isinstance(delta.get("content"), str) and delta["content"]:
                            yield ModelEvent("delta", text=delta["content"])
                        for fragment in delta.get("tool_calls", []):
                            index = fragment["index"]
                            call = calls.setdefault(index, {"id": "", "name": "", "arguments": ""})
                            if fragment.get("id"):
                                call["id"] = fragment["id"]
                            function = fragment.get("function", {})
                            call["name"] += function.get("name", "")
                            call["arguments"] += function.get("arguments", "")
                            if len(call["arguments"]) > 16000 or len(calls) > cfg.max_tool_steps:
                                raise ValueError("Model tool budget exceeded")
            if not done:
                raise BusinessError("model_truncated_stream", "模型响应中断，请重试", 502)
            for _, call in sorted(calls.items()):
                args = json.loads(call["arguments"])
                if not call["id"] or not call["name"] or not isinstance(args, dict):
                    raise ValueError("Invalid tool call")
                yield ModelEvent("tool_call", tool_call=ToolRequest(call["id"], call["name"], args))
            yield ModelEvent("done")
        except httpx.TimeoutException:
            raise BusinessError("model_timeout", "模型响应超时，请重试或转人工", 504) from None
        except httpx.TransportError:
            raise BusinessError("model_unavailable", "模型连接失败，请重试或转人工", 503) from None
        except (ValueError, KeyError, TypeError):
            raise BusinessError(
                "model_invalid_response", "模型返回格式异常，请重试或转人工", 502
            ) from None
        finally:
            if self.client is None:
                await client.aclose()
