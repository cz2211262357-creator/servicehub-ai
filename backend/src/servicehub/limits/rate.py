from dataclasses import dataclass

from redis.exceptions import RedisError

from servicehub.core.errors import BusinessError

SCRIPT = """
local u = tonumber(redis.call('GET', KEYS[1]) or '0')
local t = tonumber(redis.call('GET', KEYS[2]) or '0')
if u >= tonumber(ARGV[1]) or t >= tonumber(ARGV[2]) then
 return {0, math.max(redis.call('PTTL', KEYS[1]), redis.call('PTTL', KEYS[2]), 1)}
end
for i=1,2 do
 local n = redis.call('INCR', KEYS[i])
 if n == 1 then redis.call('PEXPIRE', KEYS[i], ARGV[3]) end
end
return {1,0}
"""


@dataclass(frozen=True)
class RateDecision:
    allowed: bool
    retry_after: int


class RateLimiter:
    def __init__(self, redis, settings):
        self.redis, self.settings = redis, settings

    async def check(self, tenant_id, user_id, scope):
        try:
            result = await self.redis.eval(
                SCRIPT,
                2,
                f"servicehub:rate:{tenant_id}:{scope}:u:{user_id}",
                f"servicehub:rate:{tenant_id}:{scope}:tenant",
                self.settings.rate_limit_requests,
                self.settings.rate_limit_requests * 10,
                self.settings.rate_limit_window_seconds * 1000,
            )
        except RedisError:
            raise BusinessError(
                "rate_service_unavailable", "限流服务不可用，请稍后重试", 503
            ) from None
        return RateDecision(
            bool(result[0]), max(1, (int(result[1]) + 999) // 1000) if not result[0] else 0
        )
