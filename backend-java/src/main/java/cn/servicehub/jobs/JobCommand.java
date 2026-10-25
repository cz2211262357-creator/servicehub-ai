package cn.servicehub.jobs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record JobCommand(long tenantId,String kind,ExecutionClass executionClass,String businessKey,Map<String,Object> payload) {
    public JobCommand {
        Objects.requireNonNull(kind); Objects.requireNonNull(executionClass); Objects.requireNonNull(businessKey);
        payload=Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
