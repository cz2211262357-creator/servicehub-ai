import json
from types import SimpleNamespace

import pytest


class Provider:
    def __init__(self, model="real-model", dimensions=2):
        self.settings = SimpleNamespace(embedding_model=model, embedding_dimensions=dimensions)
        self.batches = []
        self.failure = None

    async def embed(self, texts):
        self.batches.append(list(texts))
        if self.failure:
            raise self.failure
        return [[float(len(text)), 1.0] for text in texts]


async def test_cache_deduplicates_batches_and_reuses_vectors_without_raw_text(tmp_path):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    provider, path = Provider(), tmp_path / "vectors.json"
    cache = CachedEmbeddingProvider(provider, path)
    texts = ["private query " + str(i) for i in range(23)]
    vectors = await cache.embed(texts + texts[:2])
    assert len(vectors) == 25 and vectors[0] == vectors[-2]
    assert list(map(len, provider.batches)) == [10, 10, 3]
    assert "private query" not in path.read_text()
    assert cache.stats["provider_calls"] == 3 and cache.stats["provider_texts"] == 23
    reloaded = CachedEmbeddingProvider(provider, path)
    assert await reloaded.embed(texts[:2]) == vectors[:2]
    assert len(provider.batches) == 3 and reloaded.stats["cache_hits"] == 2
    assert len(reloaded.fingerprint) == 64
    if __import__("os").name != "nt":
        assert path.stat().st_mode & 0o777 == 0o600


async def test_cache_rejects_tampering_and_model_or_dimension_mismatch(tmp_path):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    path = tmp_path / "vectors.json"
    await CachedEmbeddingProvider(Provider(), path).embed(["one"])
    with pytest.raises(ValueError, match="identity"):
        CachedEmbeddingProvider(Provider(model="other-model"), path)
    with pytest.raises(ValueError, match="identity"):
        CachedEmbeddingProvider(Provider(dimensions=3), path)
    data = json.loads(path.read_text())
    next(iter(data["vectors"].values()))[0] = 99.0
    path.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="checksum"):
        CachedEmbeddingProvider(Provider(), path)


@pytest.mark.parametrize("bad", [[[float("nan"), 1]], [[float("inf"), 1]], [[True, 1]], [[1]], []])
async def test_bad_provider_vectors_never_poison_the_cache(tmp_path, bad):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    provider = Provider()

    async def invalid(texts):
        return bad

    provider.embed = invalid
    path = tmp_path / "vectors.json"
    with pytest.raises(ValueError):
        await CachedEmbeddingProvider(provider, path).embed(["one"])
    assert not path.exists()


async def test_provider_failure_keeps_previously_saved_cache_unchanged(tmp_path):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    provider, path = Provider(), tmp_path / "vectors.json"
    cache = CachedEmbeddingProvider(provider, path)
    await cache.embed(["known"])
    before = path.read_bytes()
    provider.failure = RuntimeError("provider private details")
    with pytest.raises(RuntimeError):
        await cache.embed(["missing"])
    assert path.read_bytes() == before
    assert cache.stats["provider_failures"] == 1


@pytest.mark.parametrize("field,value", [("schema_version", True), ("embedding_dimensions", 2.0)])
async def test_cache_rejects_invalid_metadata_types_even_with_recomputed_checksum(
    tmp_path, field, value
):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider, digest

    path = tmp_path / "vectors.json"
    await CachedEmbeddingProvider(Provider(), path).embed(["one"])
    payload = json.loads(path.read_text())
    payload.pop("checksum")
    payload[field] = value
    payload["checksum"] = digest(payload)
    path.write_text(json.dumps(payload))
    with pytest.raises(ValueError):
        CachedEmbeddingProvider(Provider(), path)


async def test_provider_identity_cannot_change_during_cache_lifetime(tmp_path):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    provider = Provider()
    cache = CachedEmbeddingProvider(provider, tmp_path / "cache.json")
    provider.settings.embedding_model = "changed-model"
    with pytest.raises(ValueError, match="identity"):
        await cache.embed(["one"])
    assert provider.batches == []


@pytest.mark.skipif(__import__("os").name != "nt", reason="Windows ACL contract")
async def test_windows_cache_acl_has_only_one_current_user_grant(tmp_path):
    import subprocess

    from evals.servicehub.embedding_cache import CachedEmbeddingProvider

    target = tmp_path / "private.json"
    await CachedEmbeddingProvider(Provider(), target).embed(["private"])
    acl_file = tmp_path / "acl.txt"
    subprocess.run(
        ["icacls", str(target), "/save", str(acl_file), "/q"], check=True, capture_output=True
    )
    sddl = acl_file.read_text(encoding="utf-16-le")
    assert sddl.count("(A;") == 1
    assert "(A;;FA;;;" in sddl
    assert json.loads(target.read_text())["embedding_model"] == "real-model"


async def test_corpus_prewarm_matches_exact_runtime_parser_inputs_and_batches_them(tmp_path):
    import hashlib

    from servicehub.knowledge.parser import parse_document, split_pages

    from evals.servicehub.embedding_cache import CachedEmbeddingProvider, prewarm_corpus
    from evals.servicehub.schema import DATA_ROOT

    manifest = json.loads((DATA_ROOT / "manifest.json").read_text(encoding="utf-8"))
    expected_by_document = [
        [
            text
            for _, text in split_pages(parse_document(DATA_ROOT / document["path"], "text/plain"))
        ]
        for document in manifest["documents"]
    ]
    expected = [text for chunks in expected_by_document for text in chunks]
    provider = Provider()
    cache = CachedEmbeddingProvider(provider, tmp_path / "cache.json")
    result = await prewarm_corpus(cache, manifest)
    assert result["document_count"] == 30
    assert result["chunk_count"] == len(expected)
    assert [text for batch in provider.batches for text in batch] == list(dict.fromkeys(expected))
    assert all(len(batch) <= 10 for batch in provider.batches)
    hashes = set(json.loads((tmp_path / "cache.json").read_text())["vectors"])
    assert hashes == {hashlib.sha256(text.encode()).hexdigest() for text in expected}
    calls = len(provider.batches)
    # These are exactly the per-document text batches passed by KnowledgeIndexer.
    for chunks in expected_by_document:
        await cache.embed(chunks)
    assert len(provider.batches) == calls


async def test_corpus_integrity_failure_happens_before_any_provider_call(tmp_path):
    from evals.servicehub.embedding_cache import CachedEmbeddingProvider, prewarm_corpus

    (tmp_path / "doc.txt").write_text("tampered", encoding="utf-8")
    manifest = {"documents": [{"policy_id": "P01", "path": "doc.txt", "sha256": "0" * 64}]}
    provider = Provider()
    with pytest.raises(ValueError, match="corpus"):
        await prewarm_corpus(
            CachedEmbeddingProvider(provider, tmp_path / "cache.json"), manifest, root=tmp_path
        )
    assert provider.batches == []
    assert not (tmp_path / "cache.json").exists()
