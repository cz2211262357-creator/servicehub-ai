"""Test-only driver boundary: the real MySQL commit finishes before task cancellation."""
import asyncio
import importlib.util
import sys
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("transfer_under_test", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.path.insert(0, str(Path(sys.argv[1]).parent))
spec.loader.exec_module(module)
original = module.database


@asynccontextmanager
async def cancelling_database(options):
    async with original(options) as connection:
        class CommitBoundary:
            calls = 0

            def __getattr__(self, name):
                return getattr(connection, name)

            async def commit(self):
                self.calls += 1
                await connection.commit()
                if self.calls == 2:
                    raise asyncio.CancelledError("Synthetic cancellation after real COMMIT")

        yield CommitBoundary()


module.database = cancelling_database


async def run():
    try:
        await module.import_data(
            SimpleNamespace(allow_empty_target=True, input=sys.argv[3], target_env=sys.argv[2])
        )
    except asyncio.CancelledError:
        print("cancelled_after_real_commit")
    else:
        raise AssertionError("Required commit cancellation was not injected")


asyncio.run(run())
