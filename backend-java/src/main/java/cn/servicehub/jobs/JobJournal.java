package cn.servicehub.jobs;

import cn.servicehub.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobJournal {
    private final JobMapper jobs;
    private final JsonMapper json;
    public JobJournal(JobMapper jobs,JsonMapper json){this.jobs=jobs;this.json=json;}
    @Transactional(propagation=Propagation.MANDATORY)
    public JobRef enqueue(JobCommand command){
        var payload=new LinkedHashMap<>(command.payload());
        if(payload.containsKey("tenant_id") && number(payload.get("tenant_id"))!=command.tenantId()){
            throw new IllegalArgumentException("Job tenant context differs from authoritative tenant");
        }
        payload.put("tenant_id",command.tenantId());
        Long conversationId=payload.containsKey("conversation_id")?number(payload.get("conversation_id")):null;
        if(command.executionClass()==ExecutionClass.INTERACTIVE && conversationId==null){
            throw new IllegalArgumentException("Interactive job requires a conversation");
        }
        String encoded=json.writeValueAsString(payload);
        var existing=jobs.findByKey(command.tenantId(),command.kind(),command.businessKey());
        if(existing!=null)return existing(command,encoded,existing);
        var values=new LinkedHashMap<String,Object>();
        values.put("tenantId",command.tenantId());values.put("kind",command.kind());values.put("businessKey",command.businessKey());
        values.put("executionClass",command.executionClass().name());values.put("conversationId",conversationId);values.put("payload",encoded);
        jobs.insertOrGetId(values);
        long id=((Number)values.get("id")).longValue();
        var saved=jobs.findLocked(command.tenantId(),id);
        JobRef result=existing(command,encoded,saved);
        jobs.ensureInitialOutbox(command.tenantId(),id);
        return result;
    }
    private long number(Object value){return Long.parseLong(String.valueOf(value));}
    private JobRef existing(JobCommand command,String encoded,Map<String,Object> saved){
        if(saved==null || !command.executionClass().name().equals(saved.get("executionClass"))
            || !json.readTree(encoded).equals(json.readTree(String.valueOf(saved.get("payloadText"))))){
            throw new BusinessException("job_conflict","同一任务标识不能用于不同内容",409);
        }
        return new JobRef(((Number)saved.get("id")).longValue(),String.valueOf(saved.get("state")));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public int cancelForConversation(long tenantId,long conversationId,long beforeEpoch){
        return jobs.cancelForConversation(tenantId,conversationId,beforeEpoch);
    }
}
