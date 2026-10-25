package cn.servicehub.ai;
import cn.servicehub.jobs.JobLease;
/** Internal execution identity; authorization is always rechecked, never cached here. */
public interface JobCancellationGuard extends DeadlineCancellationGuard { JobLease jobLease(); }
