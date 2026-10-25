package cn.servicehub.jobs;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
@Service
public class OutboxDispatcher {
    private final OutboxStore store;private final JobPublisher publisher;private final StringRedisTemplate redis;private final JsonMapper json;
    public OutboxDispatcher(OutboxStore store,JobPublisher publisher,StringRedisTemplate redis,JsonMapper json){this.store=store;this.publisher=publisher;this.redis=redis;this.json=json;}
    public boolean dispatchJob(long jobId,String owner){var id=store.initial(jobId);return id!=null&&dispatch(id,owner);}
    public int dispatchOnce(String owner){int count=0;for(long id:store.pending())if(dispatch(id,owner))count++;return count;}
    public boolean dispatch(long id,String owner){
        var claimed=store.claim(id,owner);if(claimed.isEmpty())return false;var lease=claimed.orElseThrow();
        try{
            if("job".equals(lease.kind())){
                var job=lease.jobId()==null?null:store.job(lease.jobId());
                if(job!=null)publisher.publish(job.id(),"INTERACTIVE".equals(job.executionClass())?"ai":"ingest");
            }else if("realtime".equals(lease.kind())){
                var hint=json.readTree(lease.payloadText());long conversation=hint.path("conversation_id").asLong();
                if(conversation<=0||hint.path("seq").asLong()<=0)throw new IllegalStateException("Invalid event hint");
                // Pub/sub wakes replay; durable database events remain the authority.
                redis.convertAndSend("shj:conversation:"+lease.tenantId()+":"+conversation,lease.payloadText());
            }else throw new IllegalStateException("Unknown outbox kind");
            return store.confirm(lease);
        }catch(RuntimeException e){store.release(lease);return false;}
    }
}
