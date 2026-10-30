"""Resumable backfill of SQL-authorized active versions into hybrid-v2.

Run with ``uv run python scripts/reindex_hybrid.py``. Reads the normal settings;
prints only counts and the generated index name, never connection details. Failed
writes/provider calls abort with a nonzero exit. Old Redis generations and SQL
activation records are never replaced or deleted.
"""

import argparse
import asyncio
import json

from servicehub.ai.embedding import HTTPEmbeddingProvider
from servicehub.core.config import load_settings
from servicehub.db.engine import create_database, create_redis
from servicehub.knowledge.hybrid_index import HybridIndex


async def reindex(*, tenant_id=None, text_only=False):
    settings = load_settings()
    engine, sessions = create_database(settings)
    redis = create_redis(settings)
    try:
        index = HybridIndex(redis, settings.embedding_model, settings.embedding_dimensions)
        stats = await index.backfill_active(
            sessions,
            tenant_id=tenant_id,
            embedding=HTTPEmbeddingProvider(settings),
            require_vectors=not text_only,
        )
        return {"index": index.name, "text_only": text_only, **stats}
    finally:
        await redis.aclose()
        await engine.dispose()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tenant-id", type=int)
    parser.add_argument(
        "--text-only", action="store_true", help="Demo mode: real text only, no synthetic vectors"
    )
    args = parser.parse_args()
    print(json.dumps(asyncio.run(reindex(tenant_id=args.tenant_id, text_only=args.text_only))))


if __name__ == "__main__":
    main()
