package cn.servicehub.auth;

import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SessionStore {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final DefaultRedisScript<Long> CREATE=new DefaultRedisScript<>("redis.call('SET',KEYS[1],ARGV[1],'EX',ARGV[2]);redis.call('SADD',KEYS[2],ARGV[3]);redis.call('EXPIRE',KEYS[2],ARGV[2]);return 1",Long.class);
    private static final DefaultRedisScript<Long> REVOKE=new DefaultRedisScript<>("local ids=redis.call('SMEMBERS',KEYS[1]);for _,id in ipairs(ids) do redis.call('DEL','shj:session:'..id) end;redis.call('DEL',KEYS[1]);return #ids",Long.class);
    private static final DefaultRedisScript<Long> RATE=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]);if n==1 then redis.call('EXPIRE',KEYS[1],60) end;return n",Long.class);
    public record StoredSession(Actor actor,String csrfToken) {
        @Override public String toString(){return "StoredSession[REDACTED]";}
    }
    public record SessionDto(String sessionId,String csrfToken,String sessionDigest,Actor actor) {
        @Override public String toString(){return "SessionDto[REDACTED]";}
    }
    private final StringRedisTemplate redis;private final JsonMapper json;private final int ttl;
    public SessionStore(StringRedisTemplate redis,JsonMapper json,ServiceHubProperties properties){this.redis=redis;this.json=json;ttl=properties.sessionTtlSeconds();}
    public SessionDto create(Actor actor){
        String token=newToken(),csrf=newToken(),digest=digest(token);
        try{redis.execute(CREATE,List.of("shj:session:"+digest,index(actor.tenantId(),actor.userId())),json.writeValueAsString(new StoredSession(actor,csrf)),Integer.toString(ttl),digest);}
        catch(DataAccessException e){throw dependencyFailure();}
        return new SessionDto(token,csrf,digest,actor);
    }
    public SessionDto load(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw unauthorized("请先登录");
        String digest=digest(token),raw;
        try{raw=redis.opsForValue().get("shj:session:"+digest);}catch(DataAccessException e){throw dependencyFailure();}
        if(raw==null)throw unauthorized("登录已过期，请重新登录");
        try{var data=json.readValue(raw,StoredSession.class);return new SessionDto(token,data.csrfToken(),digest,data.actor());}
        catch(Exception e){throw unauthorized("登录已过期，请重新登录");}
    }
    public void revokeDigest(String digest){try{redis.delete("shj:session:"+digest);}catch(DataAccessException e){throw dependencyFailure();}}
    public void revokeUser(long tenantId,long userId){try{redis.execute(REVOKE,List.of(index(tenantId,userId)));}catch(DataAccessException e){throw dependencyFailure();}}
    public void checkLoginRate(long tenantId,String email,String ip){
        Long attempts;
        try{attempts=redis.execute(RATE,List.of("shj:login:"+digest(tenantId+":"+email+":"+ip)));}catch(DataAccessException e){throw dependencyFailure();}
        if(attempts==null)throw dependencyFailure();
        if(attempts>10)throw new BusinessException("login_rate_limited","登录尝试过于频繁，请稍后重试",429);
    }
    public static boolean safeEquals(String supplied,String expected){return supplied!=null&&expected!=null&&MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8));}
    public static String newToken(){byte[] value=new byte[32];RANDOM.nextBytes(value);return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    public static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String index(long tenant,long user){return "shj:user-sessions:"+tenant+":"+user;}
    public static BusinessException unauthorized(String message){return new BusinessException("unauthorized",message,401);}
    public static BusinessException dependencyFailure(){return new BusinessException("dependencies_unavailable","Database or cache is unavailable",503);}
}
