package cn.servicehub.jobs;

import cn.servicehub.ai.ExecutionCancelledException;
import cn.servicehub.config.ServiceHubProperties;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobConsumer implements AutoCloseable {
    private final cn.servicehub.common.RuntimeMetrics metrics;private final JobCoordinator coordinator;private final AdmissionController admission;private final JobMapper jobs;
    private final Map<String,JobHandler> handlers;private final ServiceHubProperties properties;private final JsonMapper json;
    private final ScheduledExecutorService renewal=Executors.newScheduledThreadPool(2,Thread.ofPlatform().daemon(true).name("job-renew-",0).factory());
    private final ExecutorService execution=Executors.newVirtualThreadPerTaskExecutor();
    public JobConsumer(JobCoordinator coordinator,AdmissionController admission,JobMapper jobs,Map<String,JobHandler> handlers,ServiceHubProperties properties,JsonMapper json,cn.servicehub.common.RuntimeMetrics metrics){this.metrics=metrics;
        this.coordinator=coordinator;this.admission=admission;this.jobs=jobs;this.handlers=Map.copyOf(handlers);this.properties=properties;this.json=json;
    }
    /** Acks occur only after the database disposition transaction returns successfully. */
    public void accept(byte[] body,ExecutionClass queueClass,Runnable ack,Consumer<Boolean> reject){
        Long id=decode(body);if(id==null){reject.accept(false);return;}
        var hint=jobs.peek(id);if(hint==null){ack.run();return;}
        if(!queueClass.name().equals(hint.executionClass())){reject.accept(false);return;}
        if(!Set.of("QUEUED","RETRY_WAIT").contains(hint.state().toUpperCase(Locale.ROOT))){ack.run();return;}
        var handler=handlers.get(hint.kind());
        if(handler==null)throw new IllegalStateException("No handler registered for persisted job kind");
        String owner="w-"+UUID.randomUUID();var acquired=admission.tryAcquire(queueClass,owner);
        if(acquired.isEmpty()){coordinator.defer(id);ack.run();return;}
        try(var permit=acquired.orElseThrow()){
            var claimed=coordinator.claim(id,owner);
            if(claimed.isEmpty()){ack.run();return;}
            var lease=claimed.orElseThrow();var lost=new AtomicBoolean();long executionStarted=System.nanoTime();String executionOutcome="failed";
            metrics.record("queue",lease.kind(),"success",java.time.Duration.between(hint.availableAt(),jobs.now()));
            var heartbeat=renewal.scheduleAtFixedRate(()->{
                try{if(!permit.isValid()||!coordinator.renew(lease))lost.set(true);}catch(RuntimeException e){lost.set(true);}
            },properties.worker().renewalSeconds(),properties.worker().renewalSeconds(),TimeUnit.SECONDS);
            Future<?> work=null;
            try{
                permit.requireValid();coordinator.requireValid(lease);
                work=execution.submit(()->handler.handle(lease));
                while(true){
                    if(lost.get())throw new ExecutionCancelledException("lease_lost");
                    if(!Instant.now().isBefore(lease.deadlineAt()))throw new TimeoutException("Execution hard deadline");
                    try{work.get(100,TimeUnit.MILLISECONDS);break;}catch(TimeoutException pending){}
                }
                // Domain handlers must commit their own result and finish in the same transaction.
                var current=jobs.peek(id);
                if("RUNNING".equals(current.state().toUpperCase(Locale.ROOT)))throw new IllegalStateException("Handler returned without durable disposition");
                executionOutcome=switch(current.state().toUpperCase(Locale.ROOT)){case "SUCCEEDED"->"success";case "CANCELLED","SUPERSEDED"->"cancelled";case "WAITING_INPUT"->"waiting_input";default->"failed";};
            }catch(Exception error){
                if(work!=null)work.cancel(true);
                var cause=error instanceof ExecutionException&&error.getCause()!=null?error.getCause():error;
                executionOutcome=cause instanceof ExecutionCancelledException?"cancelled":"failed";
                if(cause instanceof InterruptedException)Thread.currentThread().interrupt();
                String code=cause instanceof TimeoutException?"deadline_exceeded":cause instanceof ExecutionCancelledException?"execution_cancelled":cause instanceof cn.servicehub.common.BusinessException business?business.code():"handler_failed";
                boolean retryable=!(cause instanceof IllegalArgumentException||cause instanceof ExecutionCancelledException);
                if(cause instanceof cn.servicehub.common.BusinessException business)retryable=business.status()==429||business.status()>=500;
                Throwable root=cause;for(int depth=0;root.getCause()!=null&&depth<10;depth++)root=root.getCause();
                var frame=root.getStackTrace().length==0?null:root.getStackTrace()[0];
                // Types and code locations only: exception messages may include SQL values or credentials.
                org.slf4j.LoggerFactory.getLogger(JobConsumer.class).warn("job_execution_failed kind={} code={} exception_type={} root_type={} location={} sql_code={}",
                    hint.kind(),code,cause.getClass().getSimpleName(),root.getClass().getSimpleName(),frame==null?"unknown":frame.getClassName()+"#"+frame.getMethodName()+":"+frame.getLineNumber(),root instanceof java.sql.SQLException sql?sql.getErrorCode():null);
                coordinator.fail(lease,code,retryable);
            }finally{if(work!=null&&!work.isDone())work.cancel(true);heartbeat.cancel(false);metrics.record("execution",lease.kind(),executionOutcome,java.time.Duration.ofNanos(System.nanoTime()-executionStarted));}
            ack.run();
        }
    }
    private Long decode(byte[] body){
        if(body.length>256)return null;
        try{
            var node=json.readTree(body);if(!node.isObject()||node.size()!=2||!node.has("job_id")||!node.has("schema_version"))return null;
            if(!node.path("job_id").isIntegralNumber()||!node.path("schema_version").isIntegralNumber()||node.path("schema_version").asInt()!=1)return null;
            long id=node.path("job_id").asLong();return id>0?id:null;
        }catch(RuntimeException e){return null;}
    }
    @PreDestroy @Override public void close(){renewal.shutdownNow();execution.shutdownNow();}
}
