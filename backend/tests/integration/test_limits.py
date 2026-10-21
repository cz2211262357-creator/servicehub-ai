import asyncio

import pytest
from redis.asyncio import Redis
from servicehub.core.errors import BusinessError

pytestmark = pytest.mark.integration


async def test_rate_limit_is_atomic_under_twenty_concurrent_requests(env):
    from servicehub.limits.rate import RateLimiter

    limiter = RateLimiter(env.redis, env.settings.model_copy(update={"rate_limit_requests": 5}))
    results = await asyncio.gather(
        *[limiter.check(env.tenants[0].id, 1, "message") for _ in range(20)]
    )
    assert sum(item.allowed for item in results) == 5
    assert all(item.retry_after > 0 for item in results if not item.allowed)


async def test_unavailable_redis_never_silently_allows_requests(env):
    from servicehub.limits.rate import RateLimiter

    redis = Redis.from_url(
        "redis://127.0.0.1:1", socket_connect_timeout=0.1, socket_timeout=0.1, decode_responses=True
    )
    try:
        with pytest.raises(BusinessError) as error:
            await RateLimiter(redis, env.settings).check(1, 1, "message")
        assert error.value.status_code == 503
    finally:
        await redis.aclose()
