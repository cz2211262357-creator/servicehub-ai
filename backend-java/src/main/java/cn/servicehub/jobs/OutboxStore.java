package cn.servicehub.jobs;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OutboxStore {
    private final OutboxMapper outbox;private final JobMapper jobs;
    public OutboxStore(OutboxMapper outbox,JobMapper jobs){this.outbox=outbox;this.jobs=jobs;}
    public Long initial(long jobId){return outbox.initial(jobId);}
    public List<Long> pending(){return outbox.pending();}
    public JobMapper.JobRow job(long id){return jobs.peek(id);}
    @Transactional public Optional<OutboxMapper.Row> claim(long id,String owner){
        if(owner==null||owner.isBlank()||owner.length()>80)throw new IllegalArgumentException("Invalid outbox owner");
        if(outbox.eligible(id)==null||outbox.claim(id,owner)!=1)return Optional.empty();
        return Optional.of(outbox.owned(id));
    }
    @Transactional public boolean confirm(OutboxMapper.Row lease){return outbox.confirm(lease)==1;}
    @Transactional public void release(OutboxMapper.Row lease){outbox.release(lease);}
}
