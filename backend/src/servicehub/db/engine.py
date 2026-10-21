from redis.asyncio import Redis
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from servicehub.core.config import Settings

SessionFactory = async_sessionmaker[AsyncSession]


def create_database(settings: Settings) -> tuple[AsyncEngine, SessionFactory]:
    engine = create_async_engine(
        settings.database_url,
        pool_size=10,
        max_overflow=5,
        pool_timeout=5,
        pool_pre_ping=True,
        pool_recycle=1800,
        isolation_level="READ COMMITTED",
        connect_args={"connect_timeout": 3, "init_command": "SET time_zone = '+00:00'"},
        echo=False,
    )
    return engine, async_sessionmaker(engine, expire_on_commit=False, autoflush=False)


def create_redis(settings: Settings) -> Redis:
    return Redis.from_url(
        settings.redis_url,
        decode_responses=True,
        max_connections=64,
        socket_connect_timeout=2,
        socket_timeout=2,
        health_check_interval=30,
    )
