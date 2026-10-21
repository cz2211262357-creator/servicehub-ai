from dataclasses import dataclass
from uuid import uuid4

from redis.exceptions import RedisError

from servicehub.core.errors import BusinessError

PREFIX = """
local tm=redis.call('TIME')
local now=tonumber(tm[1])*1000+math.floor(tonumber(tm[2])/1000)
"""
ACQUIRE = (
    PREFIX
    + """
local expired=redis.call('ZRANGEBYSCORE',KEYS[1],'-inf',now)
for _,id in ipairs(expired) do redis.call('HDEL',KEYS[2],id) end
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
redis.call('ZADD',KEYS[1],now+tonumber(ARGV[4]),ARGV[2])
redis.call('HSET',KEYS[2],ARGV[2],ARGV[3])
return 1
"""
)
RENEW = (
    PREFIX
    + """
if redis.call('HGET',KEYS[2],ARGV[1]) ~= ARGV[2] then return 0 end
local score=tonumber(redis.call('ZSCORE',KEYS[1],ARGV[1]) or '0')
if score <= now then return 0 end
redis.call('ZADD',KEYS[1],now+tonumber(ARGV[3]),ARGV[1])
return 1
"""
)
RELEASE = """
if redis.call('HGET',KEYS[2],ARGV[1]) ~= ARGV[2] then return 0 end
redis.call('HDEL',KEYS[2],ARGV[1])
return redis.call('ZREM',KEYS[1],ARGV[1])
"""


@dataclass(frozen=True, slots=True)
class SlotLease:
    id: str
    owner: str
    ttl: float


class ExecutionSlots:
    def __init__(self, redis, settings):
        self.redis, self.settings = redis, settings

    async def command(self, script, *args):
        try:
            return await self.redis.eval(
                script, 2, "servicehub:model:slots", "servicehub:model:owners", *args
            )
        except RedisError:
            raise BusinessError("capacity_service_unavailable", "模型容量服务不可用", 503) from None

    async def acquire(self, owner, ttl):
        if ttl <= 0:
            raise ValueError("Lease duration must be positive")
        lease = SlotLease(uuid4().hex, owner, ttl)
        accepted = await self.command(
            ACQUIRE, self.settings.global_model_slots, lease.id, owner, int(ttl * 1000)
        )
        return lease if accepted else None

    async def renew(self, lease):
        return bool(await self.command(RENEW, lease.id, lease.owner, int(lease.ttl * 1000)))

    async def release(self, lease):
        return bool(await self.command(RELEASE, lease.id, lease.owner))
