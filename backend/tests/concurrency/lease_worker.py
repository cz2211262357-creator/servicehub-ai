"""A real Worker process with a deterministic test job, never an application AI provider."""

import asyncio
import sys

from servicehub.core.config import Settings
from servicehub.core.logging import configure_logging
from servicehub.db.engine import create_database, create_redis
from servicehub.db.metadata import metadata  # noqa: F401 -- register FK targets in isolated process
from servicehub.jobs.worker import Worker


async def main():
    configure_logging()
    settings = Settings()
    assert settings.environment == "test"
    assert settings.database_url.endswith("/servicehub_test")
    engine, sessions = create_database(settings)
    redis = create_redis(settings)

    async def handler(lease):
        if sys.argv[1] == "block":
            while True:
                await asyncio.sleep(1)
        return {"receipt": "recovered", "execution_version": lease.version}

    try:
        worker = Worker(sessions, redis, settings, handlers={"fault_probe": handler})
        await worker.run()
    finally:
        await redis.aclose()
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
