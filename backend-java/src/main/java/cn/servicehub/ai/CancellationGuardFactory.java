package cn.servicehub.ai;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Service
public class CancellationGuardFactory {
    private final AdmissionController admission;private final JobCoordinator coordinator;private final TopicService topics;private final TransactionTemplate transactions;
    public CancellationGuardFactory(AdmissionController admission,JobCoordinator coordinator,TopicService topics,PlatformTransactionManager manager){this.admission=admission;this.coordinator=coordinator;this.topics=topics;transactions=new TransactionTemplate(manager);}
    public JobCancellationGuard forRun(ExecutionFence fence,JobLease lease){return new BoundGuard(lease,()->{
        resource(lease);transactions.executeWithoutResult(status->topics.requireCurrentForWrite(lease.actor(),lease,fence));
    });}
    public JobCancellationGuard forJob(JobLease lease){return new BoundGuard(lease,()->{resource(lease);coordinator.requireValid(lease);});}
    private record BoundGuard(JobLease jobLease,Runnable validation) implements JobCancellationGuard {
        @Override public Instant deadlineAt(){return jobLease.deadlineAt();}
        @Override public ExecutionClass executionClass(){return jobLease.executionClass();}
        @Override public void check(){validation.run();}
    }
    private void resource(JobLease lease){
        if(Thread.currentThread().isInterrupted())throw new ExecutionCancelledException("execution_interrupted");
        if(!admission.isHeld(lease.executionClass(),lease.owner()))throw new ExecutionCancelledException("resource_lease_lost");
    }
}
