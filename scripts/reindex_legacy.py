"""Rebuild and verify legacy vectors BEFORE switching to mysql_legacy.

Does not change chunk IDs, SQL facts/activation or selected retrieval backend.
Reuses only matching real v2 vectors; otherwise requires real online embeddings.
Run during an indexing maintenance window; concurrent active-set changes fail.
"""

import argparse
import asyncio
import hashlib
import json

from servicehub.ai.embedding import HTTPEmbeddingProvider
from servicehub.core.config import Settings
from servicehub.db.engine import create_database, create_redis
from servicehub.knowledge.hybrid_index import HybridIndex, _mapping, _string
from servicehub.knowledge.models import (
    DocumentVersion,
    KnowledgeActiveVersion,
    KnowledgeChunk,
    KnowledgeDocument,
)
from servicehub.knowledge.retrieval import VectorIndex
from sqlalchemy import select


def eligible_chunks(tenant_id=None):
    query = (
        select(
            KnowledgeChunk, DocumentVersion.embedding_model, DocumentVersion.embedding_dimensions
        )
        .join(
            DocumentVersion,
            (DocumentVersion.id == KnowledgeChunk.version_id)
            & (DocumentVersion.tenant_id == KnowledgeChunk.tenant_id),
        )
        .join(
            KnowledgeActiveVersion,
            (KnowledgeActiveVersion.tenant_id == KnowledgeChunk.tenant_id)
            & (KnowledgeActiveVersion.document_id == KnowledgeChunk.document_id)
            & (KnowledgeActiveVersion.version_id == KnowledgeChunk.version_id),
        )
        .join(
            KnowledgeDocument,
            (KnowledgeDocument.id == KnowledgeChunk.document_id)
            & (KnowledgeDocument.tenant_id == KnowledgeChunk.tenant_id),
        )
        .where(
            DocumentVersion.status == "ready",
            KnowledgeDocument.enabled.is_(True),
            KnowledgeDocument.deleted.is_(False),
        )
        .order_by(KnowledgeChunk.id)
    )
    return query.where(KnowledgeChunk.tenant_id == tenant_id) if tenant_id is not None else query


def fingerprint(row):
    chunk, model, dimensions = row
    return (
        chunk.id,
        chunk.tenant_id,
        chunk.version_id,
        hashlib.sha256(chunk.text.encode()).hexdigest(),
        model,
        dimensions,
    )


async def rebuild_legacy(sessions, redis, settings, *, embedding=None, tenant_id=None):
    legacy = VectorIndex(redis, settings.embedding_model, settings.embedding_dimensions)
    source = HybridIndex(redis, settings.embedding_model, settings.embedding_dimensions)
    await legacy.ensure()
    info = _mapping(await redis.execute_command("FT.INFO", legacy.name))
    fields = {_string(f["attribute"]): f for f in map(_mapping, info["attributes"])}
    vector_field = fields.get("embedding", {})
    if (
        any(
            _string(fields.get(name, {}).get("type", "")).upper() != kind
            for name, kind in (
                ("tenant", "TAG"),
                ("version", "TAG"),
                ("chunk", "NUMERIC"),
                ("embedding", "VECTOR"),
            )
        )
        or int(vector_field.get("dim", 0)) != settings.embedding_dimensions
        or _string(vector_field.get("algorithm", "")).upper() != "HNSW"
        or _string(vector_field.get("data_type", "")).upper() != "FLOAT32"
        or _string(vector_field.get("distance_metric", "")).upper() != "COSINE"
    ):
        raise ValueError("Legacy vector schema mismatch; no backend switch is safe")
    stats = {"written": 0, "verified": 0, "reused_vectors": 0, "embedded_vectors": 0}
    snapshots = []
    after = 0
    provider = embedding or HTTPEmbeddingProvider(settings)
    while True:
        async with sessions() as session:
            rows = (
                await session.execute(
                    eligible_chunks(tenant_id).where(KnowledgeChunk.id > after).limit(500)
                )
            ).all()
        if not rows:
            break
        for row in rows:
            chunk, model, dimensions = row
            snapshots.append(fingerprint(row))
            raw = await source._read(source.prefix + str(chunk.id))
            expected = [
                str(chunk.tenant_id).encode(),
                str(chunk.version_id).encode(),
                str(chunk.id).encode(),
                chunk.text.encode(),
            ]
            vector = source._decode_vector(raw[4]) if raw[:4] == expected else None
            if (
                vector is not None
                and model == settings.embedding_model
                and dimensions == settings.embedding_dimensions
            ):
                stats["reused_vectors"] += 1
            else:
                if settings.model_mode != "online":
                    raise ValueError("Missing matching real vector; online embedding is required")
                vectors = await provider.embed([chunk.text])
                if len(vectors) != 1:
                    raise ValueError("Incomplete real embedding response")
                vector = vectors[0]
                stats["embedded_vectors"] += 1
            binary = legacy.encode(vector)
            await legacy.write(chunk.id, chunk.tenant_id, vector, version_id=chunk.version_id)
            stats["written"] += 1
            stored = await redis.execute_command(
                "HMGET",
                legacy.prefix + str(chunk.id),
                "tenant",
                "version",
                "chunk",
                "embedding",
                NEVER_DECODE=True,
            )
            if stored != [*expected[:3], binary]:
                raise ValueError("Legacy vector write verification failed")
            query = f"(@tenant:{{{chunk.tenant_id}}} @version:{{{chunk.version_id}}} @chunk:[{chunk.id} {chunk.id}])=>[KNN 1 @embedding $v AS distance]"
            for attempt in range(50):
                hits = await redis.execute_command(
                    "FT.SEARCH",
                    legacy.name,
                    query,
                    "PARAMS",
                    2,
                    "v",
                    binary,
                    "RETURN",
                    1,
                    "chunk",
                    "LIMIT",
                    0,
                    1,
                    "DIALECT",
                    2,
                )
                count = (
                    int(_mapping(hits).get("total_results", 0))
                    if isinstance(hits, dict)
                    else int(hits[0])
                )
                if count == 1:
                    break
                await asyncio.sleep(0.02)
            else:
                raise TimeoutError("Legacy vector is not searchable; do not switch backend")
            stats["verified"] += 1
        after = rows[-1][0].id
    async with sessions() as session:
        current = [
            fingerprint(row) for row in (await session.execute(eligible_chunks(tenant_id))).all()
        ]
    if current != snapshots:
        raise ValueError("Authorized active corpus changed; rerun before switching backend")
    return {"index": legacy.name, "rollback_ready": True, **stats}


async def run(args):
    settings = Settings(_env_file=args.env_file)
    engine, sessions = create_database(settings)
    redis = create_redis(settings)
    try:
        return await rebuild_legacy(sessions, redis, settings, tenant_id=args.tenant_id)
    finally:
        await redis.aclose()
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--tenant-id", type=int)
    args = parser.parse_args()
    try:
        print(json.dumps(asyncio.run(run(args))))
    except Exception as exc:
        print(json.dumps({"rollback_ready": False, "error": type(exc).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
