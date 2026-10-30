"""Cache real embedding results by content hash without persisting source text."""

import asyncio
import csv
import hashlib
import io
import json
import math
import os
import re
import subprocess
import tempfile
from functools import lru_cache
from pathlib import Path


def canonical_bytes(value):
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False
    ).encode("utf-8")


def digest(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


@lru_cache(maxsize=1)
def _windows_sid():
    executable = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "whoami.exe"
    result = subprocess.run(
        [str(executable), "/user", "/fo", "csv", "/nh"], capture_output=True, text=True, check=False
    )
    rows = list(csv.reader(io.StringIO(result.stdout)))
    if (
        result.returncode
        or len(rows) != 1
        or len(rows[0]) != 2
        or not re.fullmatch(r"S-\d+(?:-\d+)+", rows[0][1])
    ):
        raise OSError("Cannot determine current user for private evaluation files")
    return rows[0][1]


def make_private(path):
    """Set POSIX 0600 or a single current-user Windows ACL before writing bytes."""
    os.chmod(path, 0o600)
    if os.name == "nt":
        executable = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "icacls.exe"
        result = subprocess.run(
            [str(executable), str(path), "/inheritance:r", "/grant:r", f"*{_windows_sid()}:(F)"],
            capture_output=True,
            check=False,
        )
        if result.returncode:
            raise OSError("Cannot restrict private evaluation file permissions")


class CachedEmbeddingProvider:
    schema_version = 1

    def __init__(self, provider, path):
        self.provider, self.settings, self.path = provider, provider.settings, Path(path)
        self.model = self.settings.embedding_model
        self.dimensions = self.settings.embedding_dimensions
        if (
            not isinstance(self.model, str)
            or not self.model
            or type(self.dimensions) is not int
            or self.dimensions < 1
        ):
            raise ValueError("Invalid embedding cache identity")
        self._lock = asyncio.Lock()
        self._vectors = {}
        self._stats = dict(
            requested_texts=0,
            cache_hits=0,
            cache_misses=0,
            deduplicated_texts=0,
            provider_calls=0,
            provider_texts=0,
            provider_failures=0,
        )
        if self.path.exists():
            try:
                payload = json.loads(self.path.read_text(encoding="utf-8"))
            except (ValueError, UnicodeError):
                raise ValueError("Invalid embedding cache JSON") from None
            expected = {
                "schema_version",
                "embedding_model",
                "embedding_dimensions",
                "vectors",
                "checksum",
            }
            if not isinstance(payload, dict) or set(payload) != expected:
                raise ValueError("Invalid embedding cache schema")
            if (
                type(payload["schema_version"]) is not int
                or payload["schema_version"] != self.schema_version
            ):
                raise ValueError("Invalid embedding cache schema version")
            if (
                type(payload["embedding_dimensions"]) is not int
                or payload["embedding_model"] != self.model
                or payload["embedding_dimensions"] != self.dimensions
            ):
                raise ValueError("Embedding cache identity mismatch")
            checksum = payload.pop("checksum")
            if checksum != digest(payload):
                raise ValueError("Embedding cache checksum mismatch")
            vectors = payload["vectors"]
            if not isinstance(vectors, dict) or any(
                not re.fullmatch(r"[a-f0-9]{64}", key) for key in vectors
            ):
                raise ValueError("Invalid embedding cache content hashes")
            self._vectors = {key: self._validate_vector(vector) for key, vector in vectors.items()}
            make_private(self.path)

    def _validate_vector(self, vector):
        if not isinstance(vector, list) or len(vector) != self.dimensions:
            raise ValueError("Embedding cache vector dimension mismatch")
        if any(type(value) not in (int, float) or not math.isfinite(value) for value in vector):
            raise ValueError("Embedding cache contains non-finite or invalid vector values")
        return [float(value) for value in vector]

    def _payload(self, vectors=None):
        return dict(
            schema_version=self.schema_version,
            embedding_model=self.model,
            embedding_dimensions=self.dimensions,
            vectors=self._vectors if vectors is None else vectors,
        )

    @property
    def fingerprint(self):
        return digest(self._payload())

    @property
    def stats(self):
        return {
            **self._stats,
            "cache_entries": len(self._vectors),
            "usage": None,
            "usage_note": "Embedding provider interface does not expose token usage",
        }

    def _save(self, vectors):
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        payload = self._payload(vectors)
        payload["checksum"] = digest(payload)
        descriptor, temporary = tempfile.mkstemp(
            prefix=self.path.name + ".", suffix=".tmp", dir=self.path.parent
        )
        try:
            with os.fdopen(descriptor, "wb") as stream:
                make_private(temporary)
                stream.write(canonical_bytes(payload))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    async def embed(self, texts):
        if not isinstance(texts, list) or any(not isinstance(text, str) for text in texts):
            raise ValueError("Embedding input must be a list of strings")
        if not texts:
            return []
        async with self._lock:
            if (
                self.provider.settings.embedding_model != self.model
                or self.provider.settings.embedding_dimensions != self.dimensions
            ):
                raise ValueError("Embedding cache identity changed during execution")
            keys = [hashlib.sha256(text.encode("utf-8")).hexdigest() for text in texts]
            unique = dict(zip(keys, texts, strict=True))
            missing = {key: text for key, text in unique.items() if key not in self._vectors}
            self._stats["requested_texts"] += len(texts)
            self._stats["cache_hits"] += sum(key in self._vectors for key in keys)
            self._stats["cache_misses"] += len(missing)
            self._stats["deduplicated_texts"] += len(texts) - len(unique)
            staged = dict(self._vectors)
            items = list(missing.items())
            for start in range(0, len(items), 10):
                batch = items[start : start + 10]
                self._stats["provider_calls"] += 1
                self._stats["provider_texts"] += len(batch)
                try:
                    vectors = await self.provider.embed([text for _, text in batch])
                    if not isinstance(vectors, list) or len(vectors) != len(batch):
                        raise ValueError("Embedding response count mismatch")
                    checked = [self._validate_vector(vector) for vector in vectors]
                except BaseException:
                    self._stats["provider_failures"] += 1
                    raise
                staged.update(
                    {key: vector for (key, _), vector in zip(batch, checked, strict=True)}
                )
            if missing:
                self._save(staged)
                self._vectors = staged
            return [list(self._vectors[key]) for key in keys]


async def prewarm_corpus(cache, manifest, *, root=None):
    """Batch-cache the exact text/plain parser inputs used by the fixture indexer.

    The fixture still uploads the original frozen bytes and runs KnowledgeIndexer.
    This merely warms its embedding dependency; it never replaces staged SQL text.
    All corpus files are verified before the first provider request.
    """
    from evals.servicehub.schema import DATA_ROOT
    from servicehub.knowledge.parser import parse_document, split_pages

    root = Path(root or DATA_ROOT).resolve()
    texts, chunks = [], []
    documents = manifest["documents"]
    for document in documents:
        path = (root / document["path"]).resolve()
        if not path.is_relative_to(root) or not path.is_file():
            raise ValueError("Frozen corpus path must remain within the dataset root")
        if hashlib.sha256(path.read_bytes()).hexdigest() != document["sha256"]:
            raise ValueError("Frozen corpus SHA256 mismatch during embedding prewarm")
        # FixtureEnvironment uploads each source unchanged as text/plain. These
        # are the actual production parser and default chunker, not a copy.
        for ordinal, (page, text) in enumerate(split_pages(parse_document(path, "text/plain"))):
            texts.append(text)
            chunks.append(
                {
                    "policy_id": document["policy_id"],
                    "ordinal": ordinal,
                    "page": page,
                    "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                }
            )
    before = cache.stats
    await cache.embed(texts)
    return {
        "document_count": len(documents),
        "chunk_count": len(texts),
        "unique_text_count": len(set(texts)),
        "chunk_texts_sha256": digest(chunks),
        "provider_calls_added": cache.stats["provider_calls"] - before["provider_calls"],
        "provider_texts_added": cache.stats["provider_texts"] - before["provider_texts"],
        "parser": "parse_document(text/plain) + split_pages(defaults)",
        "indexer_inputs_unchanged": True,
    }
