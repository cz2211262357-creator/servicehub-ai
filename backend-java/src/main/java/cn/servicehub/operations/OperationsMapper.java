package cn.servicehub.operations;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface OperationsMapper {
 record JobRow(long id,long tenantId,String kind,String state,String payloadText,long executionVersion,int attempts,String errorCode,Instant availableAt,Instant finishedAt,int manualRetries,int lifetimeAttempts,long budgetRevision,Instant deadlineAt){}
 record AuditRow(long id,long tenantId,Long actorId,String action,String targetType,String targetId,String outcome,String requestId,String detailsText,Instant createdAt){}
 record ConversationCounts(long total,long waiting,long human){}
 record UsageCounts(long total,long known,Long tokens){}
 record StateCount(String state,long count){}
 List<JobRow> jobs(@Param("tenantId")long tenant,@Param("cursor")Long cursor,@Param("limit")int limit,@Param("states")List<String> states);
 JobRow job(@Param("tenantId")long tenant,@Param("id")long job);
 List<AuditRow> audits(@Param("tenantId")long tenant,@Param("cursor")Long cursor,@Param("limit")int limit,@Param("action")String action,@Param("outcome")String outcome);
 int audit(Map<String,Object> row);
 ConversationCounts conversationCounts(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
 long handoffs(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
 List<StateCount> jobStates(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
 UsageCounts usage(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
 Double firstResponse(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
 List<OperationsDtos.DailyCount> daily(@Param("tenantId")long tenant,@Param("start")Instant start,@Param("end")Instant end);
}
