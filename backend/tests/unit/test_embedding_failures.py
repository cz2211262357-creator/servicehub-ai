import httpx
import pytest
from pydantic import SecretStr
from servicehub.ai.embedding import HTTPEmbeddingProvider
from servicehub.core.config import Settings
from servicehub.core.errors import BusinessError


@pytest.mark.parametrize(
    "failure,code",
    [
        ("timeout", "embedding_timeout"),
        ("connect", "embedding_unavailable"),
        ("429", "embedding_rate_limited"),
    ],
)
async def test_embedding_network_failures_are_actionable_and_do_not_expose_provider_details(
    failure, code
):
    cfg = Settings(
        _env_file=None,
        embedding_base_url="https://embedding.example.com/v1",
        embedding_api_key=SecretStr("test-only-key"),
    )

    async def respond(request):
        if failure == "timeout":
            raise httpx.ReadTimeout("private-detail", request=request)
        if failure == "connect":
            raise httpx.ConnectError("private-detail", request=request)
        return httpx.Response(429, json={"error": "private-detail"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
        with pytest.raises(BusinessError) as caught:
            await HTTPEmbeddingProvider(cfg, client).embed(["售后政策"])
    assert caught.value.code == code
    assert "private-detail" not in caught.value.message
