package cn.servicehub.operations;
import java.time.Instant;
import java.util.*;
public final class OperationsDtos {
 private OperationsDtos(){}
 public record JobDto(long id,long tenantId,String kind,String state,Map<String,Object> payload,long executionVersion,int attempts,String errorCode,Instant availableAt,Instant finishedAt,int manualRetries,int lifetimeAttempts,long budgetRevision,Instant deadlineAt){}
 public record JobPage(List<JobDto> items,Long nextCursor){}
 public record AuditDto(long id,long tenantId,Long actorId,String action,String targetType,String targetId,String outcome,String requestId,Map<String,Object> details,Instant createdAt){}
 public record AuditPage(List<AuditDto> items,Long nextCursor){}
 public record RuntimeStatusDto(String modelMode,String modelName,boolean modelConfigured,String embeddingModel,int embeddingDimensions,boolean embeddingConfigured,int globalModelSlots,int maxOutputTokens,int maxUploadBytes,int automaticRetries,Map<String,String> dependencies){}
 public record DailyCount(String date,long count){}
 public record MetricsSummary(Instant start,Instant end,long conversations,long waiting,long humanActive,long handoffCount,Double handoffRate,Double firstResponseMs,long failedJobs,Map<String,Long> jobStates,long aiRuns,long usageRecords,Long totalTokens,List<DailyCount> daily){}
}
