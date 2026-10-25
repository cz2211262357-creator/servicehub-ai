package cn.servicehub.jobs;

import cn.servicehub.ai.ExecutionCancelledException;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.auth.Role;
import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.conversations.EventStore;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobCoordinator {
    private final JobMapper jobs;private final AuthorizationService authorization;private final AdmissionController admission;
    private final ServiceHubProperties properties;private final JsonMapper json;private final EventStore events;
    public JobCoordinator(JobMapper jobs,AuthorizationService authorization,AdmissionController admission,ServiceHubProperties properties,JsonMapper json,EventStore events){
        this.jobs=jobs;this.authorization=authorization;this.admission=admission;this.properties=properties;this.json=json;this.events=events;
    }
    private record Locked(JobMapper.JobRow job,Actor actor,Map<String,Object> payload,boolean permitted,String reason,boolean conversationPresent) { }
    @Transactional
    public Optional<JobLease> claim(long jobId,String owner){
        var hint=jobs.peek(jobId);
        if(hint==null||!Set.of("QUEUED","RETRY_WAIT").contains(state(hint)))return Optional.empty();
        var type=ExecutionClass.valueOf(hint.executionClass());
        // Redis I/O precedes acquisition of authorization and domain row locks.
        if(!admission.isHeld(type,owner))return Optional.empty();
        var locked=lockScope(hint);var row=locked.job();var now=jobs.now();
        if(!Set.of("QUEUED","RETRY_WAIT").contains(state(row)))return Optional.empty();
        if(!locked.permitted()){retire(locked,"CANCELLED",locked.reason());return Optional.empty();}
        if(expired(row,now)){retire(locked,"TIMED_OUT",row.startedAt()==null?"queue_timeout":"deadline_exceeded");return Optional.empty();}
        if(row.attempts()>=properties.worker().maxAttempts()){retire(locked,"FAILED","attempts_exhausted");return Optional.empty();}
        if(row.availableAt().isAfter(now))return Optional.empty();
        int seconds=type==ExecutionClass.INTERACTIVE?properties.limits().foregroundDeadlineSeconds():properties.limits().backgroundDeadlineSeconds();
        if(jobs.start(row.tenantId(),row.id(),owner,properties.worker().leaseSeconds(),seconds)!=1)return Optional.empty();
        jobs.runState(row.tenantId(),row.id(),"RUNNING",null);notify(locked);
        var current=jobs.lockJob(row.tenantId(),row.id());
        return Optional.of(new JobLease(current.tenantId(),current.id(),owner,current.executionVersion(),current.budgetRevision(),
            current.kind(),type,locked.actor(),locked.payload(),current.startedAt(),current.deadlineAt(),current.leaseExpiresAt(),current.attempts()));
    }
    @Transactional public boolean renew(JobLease lease){
        var hint=jobs.peek(lease.jobId());if(hint==null)return false;
        if(!admission.isHeld(lease.executionClass(),lease.owner()))return false;
        var locked=lockScope(hint);
        return locked.permitted()&&valid(locked.job(),lease,jobs.now())&&jobs.renew(lease,properties.worker().leaseSeconds())==1;
    }
    @Transactional public void manualRetry(Actor admin,long id){
        if(admin.role()!=Role.ADMIN)throw new BusinessException("forbidden","仅管理员可重试失败任务",403);
        var hint=jobs.peek(id);if(hint==null||hint.tenantId()!=admin.tenantId())throw new BusinessException("not_found","任务不存在",404);
        long actorId=number(payload(hint.payloadText()),"actor_id");authorization.lockActorAndTarget(admin,actorId);
        var scope=lockScope(hint,true);var row=scope.job();
        if(!"FAILED".equals(state(row))||!scope.permitted())throw new BusinessException("job_conflict","任务已失效或不是可重试的最终失败任务",409);
        if(jobs.manualRetry(row.tenantId(),id,row.budgetRevision())!=1)throw new BusinessException("job_conflict","任务已变化，请刷新",409);
        if("ai_reply".equals(row.kind())){
            if(jobs.resetRunBudget(row.tenantId(),id,row.budgetRevision())!=1)throw new BusinessException("job_conflict","原执行已失效",409);
        }else if("knowledge_index".equals(row.kind())){
            if(jobs.resetDocumentStatus(row.tenantId(),id,number(scope.payload(),"version_id"))!=1)throw new BusinessException("job_conflict","文档版本不可重试",409);
        }else throw new BusinessException("job_conflict","不支持重试该任务",409);
        jobs.retryOutbox(row.tenantId(),id,"manual:"+(row.budgetRevision()+1));notify(scope);
    }
    @Transactional public void requireValid(JobLease lease){checked(lease);}
    @Transactional public java.time.Duration remaining(JobLease lease){var current=checked(lease);return java.time.Duration.between(jobs.now(),current.job().deadlineAt());}
    @Transactional public JobCommand load(JobLease lease){
        var locked=checked(lease);var row=locked.job();
        return new JobCommand(row.tenantId(),row.kind(),ExecutionClass.valueOf(row.executionClass()),row.businessKey(),locked.payload());
    }
    @Transactional public void finish(JobLease lease,String state,Map<String,Object> result){
        if(!Set.of("SUCCEEDED","FAILED","CANCELLED","WAITING_INPUT","TIMED_OUT").contains(state))throw new IllegalArgumentException("Invalid terminal job state");
        var locked=checked(lease);
        // The database predicate is the final fence, in the caller's business transaction.
        if(jobs.complete(lease,state,json.writeValueAsString(result))!=1)throw lost("lease_lost");
        jobs.runState(lease.tenantId(),lease.jobId(),state,null);notify(locked);
    }
    @Transactional public void fail(JobLease lease,String code,boolean retryable){
        var hint=jobs.peek(lease.jobId());if(hint==null)throw lost("job_missing");
        var locked=lockScope(hint);var row=locked.job();var now=jobs.now();
        if(!sameOwner(row,lease))throw lost("lease_lost");
        if(!locked.permitted()){retire(locked,"CANCELLED",locked.reason());return;}
        if(expired(row,now)){retire(locked,"TIMED_OUT","deadline_exceeded");return;}
        if(row.leaseExpiresAt()==null||!row.leaseExpiresAt().isAfter(now))throw lost("lease_expired");
        if("execution_cancelled".equals(code)&&!retryable){retire(locked,"CANCELLED",code);return;}
        if(retryable&&row.attempts()<properties.worker().maxAttempts())retry(locked,code);else retire(locked,"FAILED",code);
    }
    @Transactional public void recover(long jobId){
        var hint=jobs.peek(jobId);if(hint==null||!Set.of("RUNNING","QUEUED","RETRY_WAIT").contains(state(hint)))return;
        var locked=lockScope(hint);var row=locked.job();var now=jobs.now();
        if(!Set.of("RUNNING","QUEUED","RETRY_WAIT").contains(state(row)))return;
        if(!locked.permitted()){retire(locked,"CANCELLED",locked.reason());return;}
        if(expired(row,now)){retire(locked,"TIMED_OUT",row.startedAt()==null?"queue_timeout":"deadline_exceeded");return;}
        if("RUNNING".equals(state(row))&&(row.leaseExpiresAt()==null||!row.leaseExpiresAt().isAfter(now))){
            if(row.attempts()<properties.worker().maxAttempts())retry(locked,"lease_expired");else retire(locked,"FAILED","attempts_exhausted");
        }
    }
    @Transactional public void expireInput(long jobId){
        var hint=jobs.peek(jobId);if(hint==null||!"WAITING_INPUT".equals(state(hint)))return;
        var locked=lockScope(hint);var row=locked.job();if(!"WAITING_INPUT".equals(state(row)))return;
        if(!locked.permitted()){retire(locked,"CANCELLED",locked.reason());return;}
        var run=jobs.lockRun(row.tenantId(),row.id());
        if(run!=null&&run.inputExpiresAt()!=null&&!run.inputExpiresAt().isAfter(jobs.now())){
            jobs.retire(row.tenantId(),row.id(),"FAILED","input_expired");jobs.runState(row.tenantId(),row.id(),"EXPIRED","input_expired");notify(locked);
        }
    }
    @Transactional public void defer(long jobId){
        var hint=jobs.peek(jobId);if(hint==null)return;var locked=lockScope(hint);var row=locked.job();var now=jobs.now();
        if(!Set.of("QUEUED","RETRY_WAIT").contains(state(row)))return;
        if(!locked.permitted()){retire(locked,"CANCELLED",locked.reason());return;}
        if(expired(row,now)){retire(locked,"TIMED_OUT",row.startedAt()==null?"queue_timeout":"deadline_exceeded");return;}
        if(row.availableAt().isAfter(now))return;
        jobs.retry(row.tenantId(),row.id(),1,"resource_wait");
        jobs.retryOutbox(row.tenantId(),row.id(),"defer:"+UUID.randomUUID());
    }
    private Locked checked(JobLease lease){
        var hint=jobs.peek(lease.jobId());if(hint==null||hint.tenantId()!=lease.tenantId())throw lost("job_missing");
        var locked=lockScope(hint);if(!locked.permitted())throw lost(locked.reason());
        if(!valid(locked.job(),lease,jobs.now()))throw lost("lease_lost");return locked;
    }
    private Locked lockScope(JobMapper.JobRow hint){return lockScope(hint,false);}
    private Locked lockScope(JobMapper.JobRow hint,boolean manual){
        var payload=payload(hint.payloadText());long actorId=number(payload,"actor_id"),authVersion=number(payload,"auth_version");
        var current=authorization.inspectForExecution(hint.tenantId(),actorId,authVersion);
        boolean permitted=current.permitted();String reason=permitted?"":"authorization_changed";
        var actor=current.actor();boolean conversation=false;
        if("ai_reply".equals(hint.kind())&&"INTERACTIVE".equals(hint.executionClass())){
            var domain=hint.conversationId()==null?null:jobs.lockConversation(hint.tenantId(),hint.conversationId());conversation=domain!=null;
            var run=jobs.lockRun(hint.tenantId(),hint.id());
            boolean matching=domain!=null&&run!=null&&actor!=null&&actor.role()==Role.CUSTOMER&&domain.customerId()==actor.userId()
                &&"AI_ACTIVE".equals(domain.status())&&domain.epoch()==number(payload,"generation_epoch")
                &&run.generationEpoch()==domain.epoch()&&run.authVersion()==authVersion&&run.budgetRevision()==hint.budgetRevision()
                &&(run.topicId()==null||Objects.equals(run.topicId(),domain.topicId()))
                &&(Set.of("QUEUED","RUNNING","WAITING_INPUT").contains(run.state().toUpperCase(Locale.ROOT))||manual&&"FAILED".equalsIgnoreCase(run.state()));
            if(!matching){permitted=false;if(reason.isEmpty())reason="superseded";}
        }else if("knowledge_index".equals(hint.kind())&&"BACKGROUND".equals(hint.executionClass())){
            var document=jobs.lockDocument(hint.tenantId(),number(payload,"document_id"),number(payload,"version_id"));
            boolean matching=actor!=null&&Set.of(Role.ADMIN,Role.AGENT).contains(actor.role())&&document!=null
                &&document.enabled()&&!document.deleted()&&document.number()==document.latestNumber()
                &&document.revision()==number(payload,"document_revision")&&Objects.equals(document.jobId(),hint.id());
            if(!matching){permitted=false;if(reason.isEmpty())reason="domain_changed";}
        }else{permitted=false;reason="invalid_job_kind";}
        var row=jobs.lockJob(hint.tenantId(),hint.id());if(row==null)throw lost("job_missing");
        return new Locked(row,actor,payload,permitted,reason,conversation);
    }
    private boolean sameOwner(JobMapper.JobRow row,JobLease lease){
        return row.tenantId()==lease.tenantId()&&row.id()==lease.jobId()&&"RUNNING".equals(state(row))
            &&Objects.equals(row.owner(),lease.owner())&&row.executionVersion()==lease.executionVersion()&&row.budgetRevision()==lease.budgetRevision();
    }
    private boolean valid(JobMapper.JobRow row,JobLease lease,Instant now){
        return sameOwner(row,lease)&&row.leaseExpiresAt()!=null&&row.leaseExpiresAt().isAfter(now)&&row.deadlineAt()!=null&&row.deadlineAt().isAfter(now);
    }
    private boolean expired(JobMapper.JobRow row,Instant now){
        if(row.deadlineAt()!=null&&!row.deadlineAt().isAfter(now))return true;
        int queue="INTERACTIVE".equals(row.executionClass())?properties.limits().foregroundQueueSeconds():properties.limits().backgroundQueueSeconds();
        return row.startedAt()==null&&!row.acceptedAt().plusSeconds(queue).isAfter(now);
    }
    private void retry(Locked locked,String code){
        var row=locked.job();int delay=properties.worker().retryDelaysSeconds().get(row.attempts()-1);
        jobs.retry(row.tenantId(),row.id(),delay,code);
        jobs.retryOutbox(row.tenantId(),row.id(),"retry:"+row.budgetRevision()+":"+row.attempts());
        jobs.runState(row.tenantId(),row.id(),"QUEUED",code);notify(locked);
    }
    private void retire(Locked locked,String state,String code){
        var row=locked.job();jobs.retire(row.tenantId(),row.id(),state,code);jobs.runState(row.tenantId(),row.id(),state,code);notify(locked);
    }
    private void notify(Locked locked){
        if(locked.conversationPresent()&&locked.job().conversationId()!=null)events.append(locked.job().tenantId(),locked.job().conversationId(),"conversation.updated",Map.of());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> payload(String encoded){try{return json.readValue(encoded,Map.class);}catch(RuntimeException e){return Map.of();}}
    private long number(Map<String,Object> payload,String key){try{return Long.parseLong(String.valueOf(payload.get(key)));}catch(NumberFormatException e){return 0;}}
    private String state(JobMapper.JobRow row){return row.state().toUpperCase(Locale.ROOT);}
    private ExecutionCancelledException lost(String code){return new ExecutionCancelledException(code);}
}
