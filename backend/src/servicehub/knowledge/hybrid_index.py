"""Independent Redis hybrid-v2 generation; SQL remains the authorization source.

The lexical pipeline deliberately differs from the legacy MySQL baseline: bounded
literal OR terms (Chinese 2-4 character grams), Friso Chinese document tokenization,
STOPWORDS 0, and explicit BM25STD. It performs no model rewrite or reranking.
"""

import asyncio
import hashlib
import math
import re
import struct

from redis.exceptions import ResponseError
from sqlalchemy import select

from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)


def _string(value):
    return value.decode() if isinstance(value, bytes) else value


def _mapping(value):
    if not isinstance(value, dict):
        value = dict(zip(value[::2], value[1::2], strict=True))
    return {_string(key): item for key, item in value.items()}


def keyword_terms(query):
    """Only Unicode letters/numbers survive; punctuation never reaches query syntax."""
    terms = []
    for word in re.findall(r"[^\W_]+", query[:256].casefold(), flags=re.UNICODE):
        for part in re.split(r"([\u3400-\u9fff]+)", word):
            if not part:
                continue
            if re.fullmatch(r"[\u3400-\u9fff]+", part):
                if len(part) == 1:
                    terms.append(part)
                else:
                    terms.extend(
                        part[start : start + width]
                        for start in range(len(part) - 1)
                        for width in (2, 3, 4)
                        if start + width <= len(part)
                    )
            else:
                terms.append(part)
    return list(dict.fromkeys(terms))[:64]


def _search_rows(result, scored=False):
    if isinstance(result, dict):
        result = _mapping(result)
        for row in result.get("results", []):
            row = _mapping(row)
            yield _mapping(row.get("extra_attributes", {})), row.get("score", 0)
    else:
        step = 3 if scored else 2
        for offset in range(1, len(result), step):
            yield _mapping(result[offset + step - 1]), result[offset + 1] if scored else 0


def parse_search_results(result, scored=False):
    """Decode RESP2/RESP3 with numeric scores and deterministic chunk-id ties."""
    if scored:
        rows = [
            (int(fields["chunk"]), float(score)) for fields, score in _search_rows(result, True)
        ]
        return sorted(rows, key=lambda row: (-row[1], row[0]))
    rows = [
        (int(fields["chunk"]), float(fields.get("distance", 0)))
        for fields, _ in _search_rows(result)
    ]
    return [chunk for chunk, _ in sorted(rows, key=lambda row: (row[1], row[0]))]


class HybridIndex:
    def __init__(self, redis, model, dimensions):
        if not model or dimensions < 1:
            raise ValueError("A model and positive vector dimensions are required")
        self.redis, self.model, self.dimensions = redis, model, dimensions
        scope = hashlib.sha256(f"{model}:{dimensions}".encode()).hexdigest()[:24]
        self.name = f"servicehub_hybrid_v2_{scope}"
        self.prefix = f"servicehub:hybrid:v2:{scope}:"
        self.legacy_prefix = f"servicehub:vec:{scope}:"
        self._ready = False

    def encode(self, vector):
        if len(vector) != self.dimensions or any(
            isinstance(value, bool) or not math.isfinite(float(value)) for value in vector
        ):
            raise ValueError("Vector dimensions or values do not match the model index")
        try:
            return struct.pack(f"<{self.dimensions}f", *vector)
        except (OverflowError, struct.error):
            raise ValueError("Vector values do not fit FLOAT32") from None

    def _decode_vector(self, value):
        if not isinstance(value, bytes) or len(value) != self.dimensions * 4:
            return None
        vector = struct.unpack(f"<{self.dimensions}f", value)
        return list(vector) if all(math.isfinite(item) for item in vector) else None

    def _validate_schema(self, raw):
        info = _mapping(raw)
        fields = {_string(field["attribute"]): field for field in map(_mapping, info["attributes"])}
        definition = _mapping(info["index_definition"])
        valid = (
            _string(definition.get("key_type", "")).upper() == "HASH"
            and [_string(prefix) for prefix in definition.get("prefixes", [])] == [self.prefix]
            and _string(definition.get("default_language", "")).lower() == "chinese"
            and all(
                _string(fields.get(name, {}).get("type", "")).upper() == kind
                and _string(fields.get(name, {}).get("identifier", "")) == name
                and "NOINDEX"
                not in {_string(flag).upper() for flag in fields.get(name, {}).get("flags", [])}
                for name, kind in (
                    ("tenant", "TAG"),
                    ("version", "TAG"),
                    ("chunk", "NUMERIC"),
                    ("content", "TEXT"),
                    ("embedding", "VECTOR"),
                )
            )
        )
        vector = fields.get("embedding", {})
        valid = valid and (
            _string(vector.get("algorithm", "")).upper() == "HNSW"
            and _string(vector.get("data_type", "")).upper() == "FLOAT32"
            and int(vector.get("dim", 0)) == self.dimensions
            and _string(vector.get("distance_metric", "")).upper() == "COSINE"
            and not info.get("stopwords_list")
        )
        if not valid:
            raise ValueError(
                "Existing hybrid-v2 schema does not match the model, language or fields"
            )
        return info

    async def ensure(self):
        if self._ready:
            return
        try:
            await self.redis.execute_command(
                "FT.CREATE",
                self.name,
                "ON",
                "HASH",
                "PREFIX",
                1,
                self.prefix,
                "LANGUAGE",
                "chinese",
                "STOPWORDS",
                0,
                "SCHEMA",
                "tenant",
                "TAG",
                "version",
                "TAG",
                "chunk",
                "NUMERIC",
                "content",
                "TEXT",
                "embedding",
                "VECTOR",
                "HNSW",
                6,
                "TYPE",
                "FLOAT32",
                "DIM",
                self.dimensions,
                "DISTANCE_METRIC",
                "COSINE",
            )
        except ResponseError as exc:
            if "already exists" not in str(exc).lower():
                raise
        for _ in range(100):
            info = self._validate_schema(await self.redis.execute_command("FT.INFO", self.name))
            if not int(info.get("indexing", 0)):
                self._ready = True
                return
            await asyncio.sleep(0.02)
        raise TimeoutError("Hybrid index initial scan has not completed")

    async def _read(self, key):
        # The application Redis client decodes text by default; vector bytes must not be decoded.
        return await self.redis.execute_command(
            "HMGET", key, "tenant", "version", "chunk", "content", "embedding", NEVER_DECODE=True
        )

    async def write(self, chunk_id, tenant_id, text, vector=None, version_id=None):
        binary = self.encode(vector) if vector is not None else None
        await self.ensure()
        mapping = {"tenant": str(int(tenant_id)), "chunk": int(chunk_id), "content": text}
        if version_id is not None:
            mapping["version"] = str(int(version_id))
        if binary is not None:
            mapping["embedding"] = binary
        key = self.prefix + str(int(chunk_id))
        pipe = self.redis.pipeline(transaction=True)
        pipe.hset(key, mapping=mapping)
        if binary is None:
            pipe.hdel(key, "embedding")
        await pipe.execute()
        actual = await self._read(key)
        expected = [
            str(int(tenant_id)).encode(),
            str(int(version_id)).encode() if version_id is not None else None,
            str(int(chunk_id)).encode(),
            text.encode(),
            binary,
        ]
        if actual != expected:
            raise ValueError("Hybrid index write verification failed")

    @staticmethod
    def _filters(tenant_id, version_ids):
        filters = f"@tenant:{{{int(tenant_id)}}}"
        if version_ids is not None:
            if not version_ids:
                return None
            filters += " @version:{" + "|".join(str(int(v)) for v in sorted(set(version_ids))) + "}"
        return filters

    async def search_keyword(self, tenant_id, query, limit=30, version_ids=None, with_scores=False):
        terms = keyword_terms(query)
        filters = self._filters(tenant_id, version_ids)
        if not terms or filters is None or limit <= 0:
            return []
        await self.ensure()
        literals = "|".join('"' + term + '"' for term in terms)
        result = await self.redis.execute_command(
            "FT.SEARCH",
            self.name,
            f"({filters}) @content:({literals})",
            "SCORER",
            "BM25STD",
            "WITHSCORES",
            "RETURN",
            1,
            "chunk",
            "LIMIT",
            0,
            int(limit),
            "LANGUAGE",
            "chinese",
            "DIALECT",
            2,
        )
        rows = parse_search_results(result, scored=True)
        return rows if with_scores else [chunk for chunk, _ in rows]

    async def search_vector(self, tenant_id, vector, limit=30, version_ids=None):
        binary = self.encode(vector)
        filters = self._filters(tenant_id, version_ids)
        if filters is None or limit <= 0:
            return []
        await self.ensure()
        result = await self.redis.execute_command(
            "FT.SEARCH",
            self.name,
            f"({filters})=>[KNN {int(limit)} @embedding $v AS distance]",
            "PARAMS",
            2,
            "v",
            binary,
            "SORTBY",
            "distance",
            "RETURN",
            2,
            "chunk",
            "distance",
            "LIMIT",
            0,
            int(limit),
            "DIALECT",
            2,
        )
        return parse_search_results(result)

    async def search(self, tenant_id, vector, limit=30, version_ids=None):
        return await self.search_vector(tenant_id, vector, limit, version_ids)

    async def backfill_active(
        self, sessions, *, tenant_id=None, version_ids=None, embedding=None, require_vectors=False
    ):
        """Resume per chunk, without changing SQL status, activation, or old index hashes.

        A legacy vector is trusted only when SQL records its exact model/dimensions
        and its hash belongs to this tenant/chunk/version. Otherwise embed real text.
        No completion marker can hide a missing, stale, or partially written hash.
        """
        await self.ensure()
        stats = {"written": 0, "reused_vectors": 0, "existing": 0}
        after = 0
        while True:
            statement = (
                select(
                    KnowledgeChunk,
                    DocumentVersion.embedding_model,
                    DocumentVersion.embedding_dimensions,
                )
                .join(
                    DocumentVersion,
                    (DocumentVersion.tenant_id == KnowledgeChunk.tenant_id)
                    & (DocumentVersion.id == KnowledgeChunk.version_id),
                )
                .join(
                    KnowledgeActiveVersion,
                    (KnowledgeActiveVersion.tenant_id == KnowledgeChunk.tenant_id)
                    & (KnowledgeActiveVersion.document_id == KnowledgeChunk.document_id)
                    & (KnowledgeActiveVersion.version_id == KnowledgeChunk.version_id),
                )
                .join(
                    KnowledgeDocument,
                    (KnowledgeDocument.tenant_id == KnowledgeChunk.tenant_id)
                    & (KnowledgeDocument.id == KnowledgeChunk.document_id),
                )
                .where(
                    KnowledgeChunk.id > after,
                    DocumentVersion.status == "ready",
                    KnowledgeDocument.enabled.is_(True),
                    KnowledgeDocument.deleted.is_(False),
                )
                .order_by(KnowledgeChunk.id)
                .limit(500)
            )
            if tenant_id is not None:
                statement = statement.where(KnowledgeChunk.tenant_id == tenant_id)
            if version_ids is not None:
                statement = statement.where(KnowledgeChunk.version_id.in_(version_ids))
            async with sessions() as session:
                rows = (await session.execute(statement)).all()
            if not rows:
                return stats
            pending = []
            for chunk, model, dimensions in rows:
                expected = [
                    str(chunk.tenant_id).encode(),
                    str(chunk.version_id).encode(),
                    str(chunk.id).encode(),
                    chunk.text.encode(),
                ]
                existing = await self._read(self.prefix + str(chunk.id))
                vector = self._decode_vector(existing[4])
                if existing[:4] == expected and (not require_vectors or vector is not None):
                    stats["existing"] += 1
                    continue
                vector = None
                if require_vectors and model == self.model and dimensions == self.dimensions:
                    old = await self._read(self.legacy_prefix + str(chunk.id))
                    if (
                        old[0] == expected[0]
                        and old[2] == expected[2]
                        and old[1] in (None, expected[1])
                    ):
                        vector = self._decode_vector(old[4])
                        if vector is not None:
                            stats["reused_vectors"] += 1
                if require_vectors and vector is None:
                    pending.append(chunk)
                else:
                    await self.write(
                        chunk.id, chunk.tenant_id, chunk.text, vector, version_id=chunk.version_id
                    )
                    stats["written"] += 1
            for start in range(0, len(pending), 10):
                batch = pending[start : start + 10]
                if embedding is None:
                    raise ValueError(
                        "Real embedding provider is required to complete hybrid backfill"
                    )
                vectors = await embedding.embed([chunk.text for chunk in batch])
                if len(vectors) != len(batch):
                    raise ValueError("Incomplete embedding response during hybrid backfill")
                for chunk, vector in zip(batch, vectors, strict=True):
                    await self.write(
                        chunk.id, chunk.tenant_id, chunk.text, vector, version_id=chunk.version_id
                    )
                    stats["written"] += 1
            after = rows[-1][0].id
