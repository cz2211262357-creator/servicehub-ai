import pytest


def test_rrf_merges_rankings_with_stable_order_and_no_duplicate_boost():
    from servicehub.knowledge.retrieval import rrf_rank

    assert [key for key, _ in rrf_rank([[1, 2], [2, 3]])] == [2, 1, 3]
    assert [key for key, _ in rrf_rank([[1, 1, 2], [2]])] == [2, 1]
    assert rrf_rank([]) == []


async def test_embedding_contract_checks_dimensions_and_does_not_fake_missing_credentials():
    import httpx
    from servicehub.ai.embedding import HTTPEmbeddingProvider
    from servicehub.core.config import Settings
    from servicehub.core.errors import BusinessError

    settings = Settings(_env_file=None, embedding_model="model-a", embedding_dimensions=2)
    with pytest.raises(BusinessError):
        await HTTPEmbeddingProvider(settings).embed(["hello"])
    settings.embedding_base_url = "https://embedding.example.com/v1"
    from pydantic import SecretStr

    settings.embedding_api_key = SecretStr("test-token")

    async def respond(request):
        return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0, 0.0, 0.0]}]})

    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    try:
        with pytest.raises(BusinessError) as error:
            await HTTPEmbeddingProvider(settings, client=client).embed(["hello"])
        assert error.value.code == "embedding_dimension_mismatch"
    finally:
        await client.aclose()
