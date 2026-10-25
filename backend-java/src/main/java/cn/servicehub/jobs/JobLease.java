package cn.servicehub.jobs;

import cn.servicehub.common.Actor;
import java.time.Instant;
import java.util.*;

public record JobLease(long tenantId,long jobId,String owner,long executionVersion,long budgetRevision,String kind,
    ExecutionClass executionClass,Actor actor,Map<String,Object> payload,Instant startedAt,Instant deadlineAt,
    Instant leaseExpiresAt,int attempts) {
    public JobLease {payload=Collections.unmodifiableMap(new LinkedHashMap<>(payload));}
}
