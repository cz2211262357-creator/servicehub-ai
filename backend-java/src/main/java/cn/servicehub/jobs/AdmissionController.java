package cn.servicehub.jobs;

import cn.servicehub.config.ServiceHubProperties;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdmissionController implements AutoCloseable {
    private final RedisPermitPool pool;
    public AdmissionController(StringRedisTemplate redis,ServiceHubProperties properties){
        var limits=properties.limits();var worker=properties.worker();
        pool=new RedisPermitPool(redis,"execution",limits.totalExecutionSlots(),limits.backgroundExecutionSlots(),worker.leaseSeconds(),worker.renewalSeconds());
    }
    public Optional<ExecutionPermit> tryAcquire(ExecutionClass type,String owner){return pool.tryAcquire(type,owner);}
    public boolean isHeld(ExecutionClass type,String owner){return pool.isHeld(type,owner);}
    @PreDestroy @Override public void close(){pool.close();}
}
