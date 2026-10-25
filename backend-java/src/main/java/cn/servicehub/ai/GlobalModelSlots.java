package cn.servicehub.ai;

import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.*;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GlobalModelSlots implements AutoCloseable {
    private final cn.servicehub.common.RuntimeMetrics metrics;private final RedisPermitPool pool;private final ExecutorService calls;
    public GlobalModelSlots(StringRedisTemplate redis,ServiceHubProperties properties,cn.servicehub.common.RuntimeMetrics metrics){this.metrics=metrics;
        var limits=properties.limits();var worker=properties.worker();
        pool=new RedisPermitPool(redis,"model",limits.totalModelSlots(),limits.backgroundModelSlots(),worker.leaseSeconds(),worker.renewalSeconds());
        calls=Executors.newFixedThreadPool(limits.totalModelSlots(),Thread.ofPlatform().daemon(true).name("model-call-",0).factory());
    }
    public <T> T withPermit(ExecutionClass type,Duration maxWait,Callable<T> action,CancellationGuard guard){return withPermit(type,maxWait,action,guard,()->{});}
    public <T> T withPermit(ExecutionClass type,Duration maxWait,Callable<T> action,CancellationGuard guard,Runnable cancel){
        long waiting=System.nanoTime();long end=waiting+Math.max(0,maxWait.toNanos());String owner="model-"+UUID.randomUUID();ExecutionPermit permit;
        for(;;){
            guard.check();Optional<ExecutionPermit> acquired=pool.tryAcquire(type,owner);
            if(acquired.isPresent()){permit=acquired.orElseThrow();break;}
            if(System.nanoTime()>=end)throw new ExecutionCancelledException("model_capacity_timeout");
            LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50),Math.max(1,end-System.nanoTime())));
            if(Thread.interrupted()){Thread.currentThread().interrupt();throw new ExecutionCancelledException("model_wait_interrupted");}
        }
        metrics.record("model_wait",type==ExecutionClass.INTERACTIVE?"ai_reply":"knowledge_index","success",Duration.ofNanos(System.nanoTime()-waiting));
        try(permit){
            Future<T> future=calls.submit(()->{guard.check();permit.requireValid();return action.call();});
            try { for(;;){
                guard.check();permit.requireValid();
                try{return future.get(100,TimeUnit.MILLISECONDS);}
                catch(TimeoutException ignored){}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new ExecutionCancelledException("model_interrupted");}
                catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("Model operation failed",e.getCause());}
            }}finally{if(!future.isDone()){try{cancel.run();}finally{future.cancel(true);}}}
        }
    }
    @PreDestroy @Override public void close(){calls.shutdownNow();pool.close();}
}
