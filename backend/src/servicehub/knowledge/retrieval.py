import hashlib
import math
import re
import struct

from redis.exceptions import ResponseError
from sqlalchemy import select, text

from servicehub.ai.embedding import HTTPEmbeddingProvider
from servicehub.ai.schemas import Evidence
from servicehub.knowledge.hybrid_index import HybridIndex
from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)


def rrf_rank(rankings, k=60):
    scores = {}
    for ranking in rankings:
        seen = set()
        for rank, key in enumerate(dict.fromkeys(ranking), start=1):
            if key not in seen:
                scores[key] = scores.get(key, 0.0) + 1 / (k + rank)
                seen.add(key)
    return sorted(scores.items(), key=lambda item: (-item[1], item[0]))


class VectorIndex:
    def __init__(self, redis, model, dimensions):
        self.redis, self.model, self.dimensions = redis, model, dimensions
        scope = hashlib.sha256(f"{model}:{dimensions}".encode()).hexdigest()[:24]
        self.name, self.prefix = f"servicehub_idx_{scope}", f"servicehub:vec:{scope}:"
        self._ready = False

    def encode(self, vector):
        if len(vector) != self.dimensions or any(not math.isfinite(float(v)) for v in vector):
            raise ValueError("Vector dimensions or values do not match the model index")
        return struct.pack(f"<{self.dimensions}f", *vector)

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
                "SCHEMA",
                "tenant",
                "TAG",
                "version",
                "TAG",
                "chunk",
                "NUMERIC",
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
            info = await self.redis.execute_command("FT.INFO", self.name)
            data = info if isinstance(info, dict) else dict(zip(info[::2], info[1::2], strict=True))
            fields = [
                field
                if isinstance(field, dict)
                else dict(zip(field[::2], field[1::2], strict=True))
                for field in data["attributes"]
            ]
            if not any(field.get("attribute") == "version" for field in fields):
                try:
                    await self.redis.execute_command(
                        "FT.ALTER", self.name, "SCHEMA", "ADD", "version", "TAG"
                    )
                except ResponseError as altered:
                    if "duplicate" not in str(altered).lower():
                        raise
        self._ready = True

    async def backfill_versions(self, sessions):
        await self.ensure()
        marker = self.prefix + "version-metadata-v1"
        if await self.redis.exists(marker):
            return
        after = 0
        while True:
            async with sessions() as session:
                rows = (
                    await session.execute(
                        select(KnowledgeChunk.id, KnowledgeChunk.version_id)
                        .where(KnowledgeChunk.id > after)
                        .order_by(KnowledgeChunk.id)
                        .limit(500)
                    )
                ).all()
            if not rows:
                break
            pipe = self.redis.pipeline(transaction=False)
            for chunk_id, version_id in rows:
                pipe.eval(
                    "if redis.call('EXISTS', KEYS[1]) == 1 then "
                    "return redis.call('HSET', KEYS[1], 'version', ARGV[1]) end return 0",
                    1,
                    self.prefix + str(chunk_id),
                    str(version_id),
                )
            await pipe.execute()
            after = rows[-1][0]
        await self.redis.set(marker, "1")

    async def write(self, chunk_id, tenant_id, vector, version_id=None):
        binary = self.encode(vector)
        await self.ensure()
        mapping = {"tenant": str(tenant_id), "chunk": chunk_id, "embedding": binary}
        if version_id is not None:
            mapping["version"] = str(version_id)
        await self.redis.hset(self.prefix + str(chunk_id), mapping=mapping)

    async def search(self, tenant_id, vector, limit=20, version_ids=None):
        binary = self.encode(vector)
        await self.ensure()
        filters = f"@tenant:{{{int(tenant_id)}}}"
        if version_ids is not None:
            if not version_ids:
                return []
            filters += " @version:{" + "|".join(str(int(v)) for v in version_ids) + "}"
        query = f"({filters})=>[KNN {int(limit)} @embedding $v AS distance]"
        result = await self.redis.execute_command(
            "FT.SEARCH",
            self.name,
            query,
            "PARAMS",
            2,
            "v",
            binary,
            "SORTBY",
            "distance",
            "RETURN",
            1,
            "chunk",
            "LIMIT",
            0,
            limit,
            "DIALECT",
            2,
        )
        if isinstance(result, dict):
            return [int(row["extra_attributes"]["chunk"]) for row in result["results"]]
        return [
            int(dict(zip(result[i + 1][::2], result[i + 1][1::2], strict=True))["chunk"])
            for i in range(1, len(result), 2)
        ]


class Retriever:
    def __init__(self, sessions, redis, settings, embedding=None, retrieval_backend=None):
        self.sessions, self.redis, self.settings = sessions, redis, settings
        self.embedding = embedding or HTTPEmbeddingProvider(settings)
        self.retrieval_backend = retrieval_backend or getattr(
            settings, "retrieval_backend", "mysql_legacy"
        )
        if self.retrieval_backend not in ("mysql_legacy", "redis_hybrid"):
            raise ValueError("Unknown retrieval backend")

    async def keyword(self, principal, query, limit=30):
        eligible = (
            " FROM knowledge_chunks c "
            "JOIN knowledge_documents d ON d.tenant_id=c.tenant_id AND d.id=c.document_id "
            "JOIN knowledge_active_versions a ON a.tenant_id=c.tenant_id "
            "AND a.document_id=c.document_id AND a.version_id=c.version_id "
            "JOIN document_versions v ON v.tenant_id=c.tenant_id AND v.id=c.version_id "
            "WHERE c.tenant_id=:tenant AND d.enabled=1 AND d.deleted=0 AND v.status='ready' "
        )
        params = {"tenant": principal.tenant_id, "query": query, "limit": limit}
        async with self.sessions() as session:
            rows = list(
                await session.execute(
                    text(
                        "SELECT c.id"
                        + eligible
                        + "AND MATCH(c.text) AGAINST(:query IN NATURAL LANGUAGE MODE) > 0 "
                        "ORDER BY MATCH(c.text) AGAINST(:query IN NATURAL LANGUAGE MODE) DESC, c.id LIMIT :limit"
                    ),
                    params,
                )
            )
            if rows:
                return [row[0] for row in rows]
            # A bounded lexical fallback also handles zero-relevance/empty full-text results.
            tokens = []
            for word in re.findall(r"[\w]+", query[:256], flags=re.UNICODE):
                if re.search(r"[\u4e00-\u9fff]", word):
                    tokens.extend(word[i : i + 2] for i in range(len(word) - 1))
                elif len(word) >= 2:
                    tokens.append(word)
            tokens = list(dict.fromkeys(tokens))[:16]
            if not tokens:
                return []
            predicates = []
            for i, token in enumerate(tokens):
                name = f"term{i}"
                predicates.append(f"c.text LIKE :{name} ESCAPE '/'")
                params[name] = (
                    "%" + token.replace("/", "//").replace("%", "/%").replace("_", "/_") + "%"
                )
            rows = await session.execute(
                text(
                    "SELECT c.id"
                    + eligible
                    + "AND ("
                    + " OR ".join(predicates)
                    + ") ORDER BY c.id LIMIT :limit"
                ),
                params,
            )
            return [row[0] for row in rows]

    async def active_versions(self, principal):
        async with self.sessions() as session:
            return list(
                await session.scalars(
                    select(KnowledgeActiveVersion.version_id)
                    .join(
                        KnowledgeDocument,
                        (KnowledgeDocument.tenant_id == KnowledgeActiveVersion.tenant_id)
                        & (KnowledgeDocument.id == KnowledgeActiveVersion.document_id),
                    )
                    .join(
                        DocumentVersion,
                        (DocumentVersion.tenant_id == KnowledgeActiveVersion.tenant_id)
                        & (DocumentVersion.id == KnowledgeActiveVersion.version_id),
                    )
                    .where(
                        KnowledgeActiveVersion.tenant_id == principal.tenant_id,
                        KnowledgeDocument.enabled.is_(True),
                        KnowledgeDocument.deleted.is_(False),
                        DocumentVersion.status == "ready",
                    )
                )
            )

    async def finalize(self, principal, rankings):
        if not rankings:
            return []
        scores = dict(rankings)
        async with self.sessions() as session:
            rows = (
                await session.execute(
                    select(KnowledgeChunk, KnowledgeDocument.title)
                    .join(
                        KnowledgeDocument,
                        (KnowledgeDocument.tenant_id == KnowledgeChunk.tenant_id)
                        & (KnowledgeDocument.id == KnowledgeChunk.document_id),
                    )
                    .join(
                        KnowledgeActiveVersion,
                        (KnowledgeActiveVersion.tenant_id == KnowledgeChunk.tenant_id)
                        & (KnowledgeActiveVersion.document_id == KnowledgeChunk.document_id)
                        & (KnowledgeActiveVersion.version_id == KnowledgeChunk.version_id),
                    )
                    .join(
                        DocumentVersion,
                        (DocumentVersion.tenant_id == KnowledgeChunk.tenant_id)
                        & (DocumentVersion.id == KnowledgeChunk.version_id),
                    )
                    .where(
                        KnowledgeChunk.tenant_id == principal.tenant_id,
                        KnowledgeChunk.id.in_(list(scores)),
                        KnowledgeDocument.deleted.is_(False),
                        KnowledgeDocument.enabled.is_(True),
                        DocumentVersion.status == "ready",
                    )
                )
            ).all()
            evidence = {
                chunk.id: Evidence(
                    chunk_id=chunk.id,
                    document_id=chunk.document_id,
                    version_id=chunk.version_id,
                    page=chunk.page,
                    text=chunk.text,
                    score=scores[chunk.id],
                    title=title,
                )
                for chunk, title in rows
            }
        return [evidence[key] for key, _ in rankings if key in evidence]

    async def _search_legacy(self, principal, query, limit=5):
        rankings = [await self.keyword(principal, query)]
        if self.settings.model_mode == "online":
            versions = await self.active_versions(principal)
            if not versions:
                return (await self.finalize(principal, rrf_rank(rankings)))[:limit]
            vector = (await self.embedding.embed([query]))[0]
            index = VectorIndex(
                self.redis, self.settings.embedding_model, self.settings.embedding_dimensions
            )
            await index.backfill_versions(self.sessions)
            rankings.append(
                await index.search(principal.tenant_id, vector, 30, version_ids=versions)
            )
        return (await self.finalize(principal, rrf_rank(rankings)))[:limit]

    async def channels(self, principal, query):
        """Return the two 30-candidate channels for the selected backend.

        Lexical/vector ablations can fuse one channel with the same RRF and SQL
        revalidation as search; this exposes no unvalidated Evidence objects.
        """
        if self.retrieval_backend == "mysql_legacy":
            keyword = await self.keyword(principal, query)
            vector_hits = []
            if self.settings.model_mode == "online":
                versions = await self.active_versions(principal)
                if versions:
                    vector = (await self.embedding.embed([query]))[0]
                    index = VectorIndex(
                        self.redis,
                        self.settings.embedding_model,
                        self.settings.embedding_dimensions,
                    )
                    await index.backfill_versions(self.sessions)
                    vector_hits = await index.search(
                        principal.tenant_id, vector, 30, version_ids=versions
                    )
            return [keyword, vector_hits]
        versions = await self.active_versions(principal)
        if not versions:
            return [[], []]
        index = HybridIndex(
            self.redis, self.settings.embedding_model, self.settings.embedding_dimensions
        )
        online = self.settings.model_mode == "online"
        await index.backfill_active(
            self.sessions,
            tenant_id=principal.tenant_id,
            version_ids=versions,
            embedding=self.embedding,
            require_vectors=online,
        )
        keyword = await index.search_keyword(principal.tenant_id, query, 30, version_ids=versions)
        vector_hits = []
        if online:
            vector = (await self.embedding.embed([query]))[0]
            vector_hits = await index.search_vector(
                principal.tenant_id, vector, 30, version_ids=versions
            )
        return [keyword, vector_hits]

    async def search(self, principal, query, limit=5):
        if self.retrieval_backend == "mysql_legacy":
            return await self._search_legacy(principal, query, limit)
        rankings = await self.channels(principal, query)
        return (await self.finalize(principal, rrf_rank(rankings)))[:limit]

    async def revalidate(self, principal, evidence):
        return await self.finalize(principal, [(row.chunk_id, row.score) for row in evidence])
