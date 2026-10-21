import asyncio
from dataclasses import replace

import pytest

pytestmark = [pytest.mark.integration, pytest.mark.concurrency]


async def test_global_slots_have_owner_checks_renewal_and_expiry(env):
    from servicehub.limits.leases import ExecutionSlots

    settings = env.settings.model_copy(update={"global_model_slots": 2})
    workers = [ExecutionSlots(env.redis, settings) for _ in range(3)]
    leases = await asyncio.gather(*[workers[i % 3].acquire(f"worker-{i}", 0.3) for i in range(20)])
    live = [lease for lease in leases if lease is not None]
    assert len(live) == 2
    assert await workers[0].release(replace(live[0], owner="intruder")) is False
    assert await workers[0].renew(live[0]) is True
    assert await workers[0].release(live[0]) is True
    assert await workers[1].acquire("replacement", 0.3) is not None
    await asyncio.sleep(0.35)
    assert await workers[2].acquire("after-expiry", 1) is not None
