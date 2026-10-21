import json

import httpx
import pytest
from pydantic import SecretStr
from servicehub.core.config import Settings
from servicehub.core.errors import BusinessError


def settings():
    return Settings(
        _env_file=None,
        model_mode="online",
        model_base_url="https://model.example.com/v1",
        model_name="contract-model",
        model_api_key=SecretStr("test-only-key"),
    )


async def test_fragmented_sse_text_and_tool_arguments_are_assembled():
    from servicehub.ai.provider import HTTPModelProvider

    records = [
        {"choices": [{"delta": {"content": "你好"}}]},
        {
            "choices": [
                {
                    "delta": {
                        "tool_calls": [
                            {
                                "index": 0,
                                "id": "call-1",
                                "function": {"name": "lookup_order", "arguments": '{"order_'},
                            }
                        ]
                    }
                }
            ]
        },
        {
            "choices": [
                {
                    "delta": {
                        "tool_calls": [{"index": 0, "function": {"arguments": 'number":"SH-1"}'}}]
                    },
                    "finish_reason": "tool_calls",
                }
            ]
        },
        {"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 8, "total_tokens": 18}},
    ]
    raw = (
        "".join("data: " + json.dumps(row, ensure_ascii=False) + "\n\n" for row in records).encode()
        + b"data: [DONE]\n\n"
    )

    class Fragments(httpx.AsyncByteStream):
        async def __aiter__(self):
            for start in range(0, len(raw), 7):
                yield raw[start : start + 7]

    async def respond(request):
        return httpx.Response(200, stream=Fragments())

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        events = [
            event async for event in HTTPModelProvider(settings(), client).stream([], [], 256)
        ]
    assert "".join(event.text for event in events if event.kind == "delta") == "你好"
    tool = next(event.tool_call for event in events if event.kind == "tool_call")
    assert tool.name == "lookup_order" and tool.arguments == {"order_number": "SH-1"}
    assert next(event.usage for event in events if event.kind == "usage")["total_tokens"] == 18


@pytest.mark.parametrize("failure", ["timeout", "429", "invalid_json", "missing_key"])
async def test_provider_failures_are_explicit_without_demo_fallback(failure):
    from servicehub.ai.provider import HTTPModelProvider

    cfg = settings()
    if failure == "missing_key":
        cfg.model_api_key = SecretStr("")

    async def respond(request):
        if failure == "timeout":
            raise httpx.ReadTimeout("private-provider-detail", request=request)
        if failure == "429":
            return httpx.Response(429, json={"error": {"message": "private-provider-detail"}})
        return httpx.Response(200, content=b"data: invalid-json\n\ndata: [DONE]\n\n")

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with pytest.raises(BusinessError) as error:
            _ = [event async for event in HTTPModelProvider(cfg, client).stream([], [], 256)]
    assert "private-provider-detail" not in error.value.message
