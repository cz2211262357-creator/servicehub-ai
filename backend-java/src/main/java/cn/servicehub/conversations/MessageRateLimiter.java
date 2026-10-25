package cn.servicehub.conversations;

import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class MessageRateLimiter {
    private static final DefaultRedisScript<List> SCRIPT=new DefaultRedisScript<>("local u=tonumber(redis.call('GET',KEYS[1]) or '0');local t=tonumber(redis.call('GET',KEYS[2]) or '0');if u>=tonumber(ARGV[1]) or t>=tonumber(ARGV[2]) then return {0,math.max(redis.call('PTTL',KEYS[1]),redis.call('PTTL',KEYS[2]),1)} end;for i=1,2 do local n=redis.call('INCR',KEYS[i]);if n==1 then redis.call('PEXPIRE',KEYS[i],ARGV[3]) end end;return {1,0}",List.class);
    private final StringRedisTemplate redis;private final ServiceHubProperties properties;
    public MessageRateLimiter(StringRedisTemplate redis,ServiceHubProperties properties){this.redis=redis;this.properties=properties;}
    public void check(Actor actor){
        List<?> result;var limits=properties.limits();
        try{result=redis.execute(SCRIPT,List.of("shj:rate:"+actor.tenantId()+":message:u:"+actor.userId(),"shj:rate:"+actor.tenantId()+":message:tenant"),Integer.toString(limits.rateLimitRequests()),Integer.toString(limits.rateLimitRequests()*10),Integer.toString(limits.rateLimitWindowSeconds()*1000));}
        catch(DataAccessException e){throw new BusinessException("rate_service_unavailable","限流服务不可用，请稍后重试",503);}
        if(result==null)throw new BusinessException("rate_service_unavailable","限流服务不可用，请稍后重试",503);
        if(((Number)result.get(0)).intValue()==0){long retry=Math.max(1,(((Number)result.get(1)).longValue()+999)/1000);throw new BusinessException("rate_limited","发送过于频繁，请稍后重试",429,Map.of("retry_after",retry));}
    }
}
