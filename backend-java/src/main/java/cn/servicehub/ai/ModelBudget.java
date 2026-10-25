package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.*;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelBudget {
    public record ClassificationClaim(boolean claimed,String resultText){}
    @Transactional public ClassificationClaim beginClassification(JobLease lease){
        coordinator.requireValid(lease);var row=ai.classification(lease.tenantId(),lease.jobId());
        if(row==null)throw new ExecutionCancelledException("run_missing");
        if(row.attempted())return new ClassificationClaim(false,row.resultText());
        if(ai.claimClassification(lease.tenantId(),lease.jobId(),lease.budgetRevision())!=1)throw new ExecutionCancelledException("classification_claim_lost");
        return new ClassificationClaim(true,null);
    }
    @Transactional public void saveClassification(JobLease lease,String decision){
        coordinator.requireValid(lease);
        if(ai.saveClassification(lease.tenantId(),lease.jobId(),lease.budgetRevision(),decision)!=1)throw new ExecutionCancelledException("classification_result_changed");
    }
    public record Snapshot(long runId,Instant deadlineAt,Instant databaseNow,long sampledAtNanos,int modelRequestsReserved,int toolExecutionsReserved,int maxOutputTokens){
        public Duration remaining(){return Duration.between(databaseNow,deadlineAt).minusNanos(Math.max(0,System.nanoTime()-sampledAtNanos));}
        public Duration requestTimeout(){return callTimeout(remaining());}
    }
    private final AIMapper ai;private final JobCoordinator coordinator;private final ServiceHubProperties properties;
    public ModelBudget(AIMapper ai,JobCoordinator coordinator,ServiceHubProperties properties){this.ai=ai;this.coordinator=coordinator;this.properties=properties;}
    @Transactional public Snapshot snapshot(JobLease lease){coordinator.requireValid(lease);return read(lease);}
    @Transactional public Snapshot reserve(JobLease lease,String kind){
        coordinator.requireValid(lease);var previous=read(lease);
        int count="model".equals(kind)?previous.modelRequestsReserved():previous.toolExecutionsReserved();checkReservation(kind,count);
        int limit="model".equals(kind)?Math.min(6,properties.limits().modelRequests()):Math.min(4,properties.limits().toolExecutions());
        if(count>=limit)throw exhausted(kind);
        int updated="model".equals(kind)?ai.reserveModel(lease.tenantId(),lease.jobId(),lease.budgetRevision(),limit):ai.reserveTool(lease.tenantId(),lease.jobId(),lease.budgetRevision(),limit);
        if(updated!=1)throw new ExecutionCancelledException("budget_fence_lost");coordinator.requireValid(lease);return read(lease);
    }
    @Transactional public void startTool(JobLease lease,Snapshot reservation,String name,String summary){
        coordinator.requireValid(lease);var current=read(lease);
        if(reservation.runId()!=current.runId()||reservation.toolExecutionsReserved()<1||reservation.toolExecutionsReserved()>current.toolExecutionsReserved())throw new ExecutionCancelledException("tool_budget_changed");
        ai.insertTool(java.util.Map.of("tenantId",lease.tenantId(),"runId",current.runId(),"executionVersion",lease.executionVersion(),"step",reservation.toolExecutionsReserved(),"name",name,"summary",summary));
    }
    @Transactional public void finishTool(JobLease lease,Snapshot reservation,String outcome,int latency,String code){
        coordinator.requireValid(lease);
        if(ai.finishTool(lease.tenantId(),reservation.runId(),lease.executionVersion(),reservation.toolExecutionsReserved(),outcome,latency,code)!=1)throw new ExecutionCancelledException("tool_trace_changed");
    }
    private Snapshot read(JobLease lease){
        if(lease.executionClass()!=ExecutionClass.INTERACTIVE)throw new IllegalArgumentException("Agent budget requires an interactive run");
        var row=ai.budget(lease.tenantId(),lease.jobId());if(row==null)throw new ExecutionCancelledException("run_missing");
        return new Snapshot(row.runId(),row.deadlineAt(),row.databaseNow(),System.nanoTime(),row.modelRequestsReserved(),row.toolExecutionsReserved(),Math.min(1600,properties.limits().maxOutputTokens()));
    }
    public static void checkReservation(String kind,int reserved){
        if(!"model".equals(kind)&&!"tool".equals(kind)||reserved<0)throw new IllegalArgumentException("Invalid budget reservation");
        if(reserved>=("model".equals(kind)?6:4))throw exhausted(kind);
    }
    public static Duration callTimeout(Duration remaining){
        if(remaining.isZero()||remaining.isNegative())throw new ExecutionCancelledException("deadline_exceeded");
        return remaining.compareTo(Duration.ofSeconds(45))<0?remaining:Duration.ofSeconds(45);
    }
    private static BusinessException exhausted(String kind){return new BusinessException(kind+"_budget_exhausted","本轮执行次数预算已耗尽",422);}
}
