import asyncio
from logging.config import fileConfig

from alembic import context
from servicehub.core.config import load_settings
from servicehub.db.metadata import metadata
from sqlalchemy import pool
from sqlalchemy.ext.asyncio import create_async_engine

config = context.config
if config.config_file_name:
    fileConfig(config.config_file_name)
url = load_settings().database_url


def migrate(connection):
    context.configure(connection=connection, target_metadata=metadata, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


async def run_online():
    engine = create_async_engine(
        url, poolclass=pool.NullPool, connect_args={"init_command": "SET time_zone = '+00:00'"}
    )
    async with engine.connect() as connection:
        await connection.run_sync(migrate)
    await engine.dispose()


if context.is_offline_mode():
    context.configure(
        url=url, target_metadata=metadata, literal_binds=True, dialect_opts={"paramstyle": "named"}
    )
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(run_online())
