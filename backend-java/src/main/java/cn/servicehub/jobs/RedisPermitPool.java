package cn.servicehub.jobs;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Redis TIME and atomic Lua enforce a global total and a separate background ceiling. */
public final class RedisPermitPool implements AutoCloseable {
    private static final String CLOCK="local t=redis.call('TIME');local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000);";
    private static final DefaultRedisScript<Long> ACQUIRE=new DefaultRedisScript<>(CLOCK+
        "redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now);redis.call('ZREMRANGEBYSCORE',KEYS[2],'-inf',now);"+
        "if redis.call('EXISTS',KEYS[3])==1 or redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end;"+
        "if ARGV[4]=='1' and redis.call('ZCARD',KEYS[2])>=tonumber(ARGV[2]) then return 0 end;"+
        "local untilAt=now+tonumber(ARGV[3]);redis.call('SET',KEYS[3],ARGV[5],'PX',ARGV[3]);redis.call('ZADD',KEYS[1],untilAt,ARGV[5]);"+
        "if ARGV[4]=='1' then redis.call('ZADD',KEYS[2],untilAt,ARGV[5]) end;return 1",Long.class);
    private static final DefaultRedisScript<Long> VALID=new DefaultRedisScript<>(CLOCK+
        "if redis.call('GET',KEYS[3])~=ARGV[1] then return 0 end;local s=redis.call('ZSCORE',KEYS[1],ARGV[1]);"+
        "if not s or tonumber(s)<=now then return 0 end;if ARGV[2]=='1' then local b=redis.call('ZSCORE',KEYS[2],ARGV[1]);if not b or tonumber(b)<=now then return 0 end end;return 1",Long.class);
    private static final DefaultRedisScript<Long> RENEW=new DefaultRedisScript<>(CLOCK+
        "if redis.call('GET',KEYS[3])~=ARGV[1] then return 0 end;local s=redis.call('ZSCORE',KEYS[1],ARGV[1]);if not s or tonumber(s)<=now then return 0 end;"+
        "local untilAt=now+tonumber(ARGV[3]);redis.call('PEXPIRE',KEYS[3],ARGV[3]);redis.call('ZADD',KEYS[1],untilAt,ARGV[1]);"+
        "if ARGV[2]=='1' then redis.call('ZADD',KEYS[2],untilAt,ARGV[1]) end;return 1",Long.class);
    private static final DefaultRedisScript<Long> RELEASE=new DefaultRedisScript<>(
        "redis.call('ZREM',KEYS[1],ARGV[1]);redis.call('ZREM',KEYS[2],ARGV[1]);if redis.call('GET',KEYS[3])==ARGV[1] then redis.call('DEL',KEYS[3]) end;return 1",Long.class);
    private final StringRedisTemplate redis;private final String prefix;private final int total,background,ttlMillis,renewSeconds;
    private final ScheduledExecutorService scheduler;private final ConcurrentMap<String,ExecutionPermit> live=new ConcurrentHashMap<>();
    public RedisPermitPool(StringRedisTemplate redis,String scope,int total,int background,int ttlSeconds,int renewSeconds){
        if(total<1||background<0||background>=total||ttlSeconds<=renewSeconds||renewSeconds<1)throw new IllegalArgumentException("Invalid resource admission limits");
        this.redis=redis;prefix="shj:{slots:"+scope+"}:";this.total=total;this.background=background;ttlMillis=Math.multiplyExact(ttlSeconds,1000);this.renewSeconds=renewSeconds;
        var renewals=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon(true).name("slots-"+scope+"-",0).factory());renewals.setRemoveOnCancelPolicy(true);scheduler=renewals;
    }
    public Optional<ExecutionPermit> tryAcquire(ExecutionClass type,String owner){
        if(owner==null||owner.isBlank()||owner.length()>80)throw new IllegalArgumentException("Execution owner must be 1..80 characters");
        String token=UUID.randomUUID().toString(),bg=type==ExecutionClass.BACKGROUND?"1":"0";var keys=keys(owner);
        if(!one(ACQUIRE,keys,Integer.toString(total),Integer.toString(background),Integer.toString(ttlMillis),bg,token))return Optional.empty();
        var permit=new ExecutionPermit(owner,()->one(VALID,keys,token,bg),()->one(RENEW,keys,token,bg,Integer.toString(ttlMillis)),()->{try{one(RELEASE,keys,token);}finally{live.remove(token);}});
        live.put(token,permit);
        permit.schedule(scheduler.scheduleAtFixedRate(()->{if(!permit.renew()){permit.close();}},renewSeconds,renewSeconds,TimeUnit.SECONDS));
        return Optional.of(permit);
    }
    public boolean isHeld(ExecutionClass type,String owner){
        if(owner==null)return false;String token;
        try{token=redis.opsForValue().get(prefix+"owner:"+owner);}catch(DataAccessException e){return false;}
        return token!=null&&one(VALID,keys(owner),token,type==ExecutionClass.BACKGROUND?"1":"0");
    }
    private List<String> keys(String owner){return List.of(prefix+"all",prefix+"background",prefix+"owner:"+owner);}
    private boolean one(DefaultRedisScript<Long> script,List<String> keys,String...args){
        try{return Long.valueOf(1).equals(redis.execute(script,keys,(Object[])args));}catch(DataAccessException e){return false;}
    }
    @Override public void close(){for(var permit:live.values())permit.close();live.clear();scheduler.shutdownNow();}
}
