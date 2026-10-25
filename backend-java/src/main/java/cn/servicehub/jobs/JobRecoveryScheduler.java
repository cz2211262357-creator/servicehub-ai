package cn.servicehub.jobs;

import cn.servicehub.config.ServiceHubProperties;
import org.springframework.stereotype.Service;

@Service
public class JobRecoveryScheduler {
    private final JobMapper jobs;private final JobCoordinator coordinator;private final ServiceHubProperties properties;
    public JobRecoveryScheduler(JobMapper jobs,JobCoordinator coordinator,ServiceHubProperties properties){this.jobs=jobs;this.coordinator=coordinator;this.properties=properties;}
    /** Invoked by the worker's independent recovery scheduler, or explicitly in fault tests. */
    public void recoverOnce(){
        for(long id:jobs.recoverable(properties.limits().foregroundQueueSeconds(),properties.limits().backgroundQueueSeconds()))coordinator.recover(id);
        for(long id:jobs.expiredInputs())coordinator.expireInput(id);
    }
}
