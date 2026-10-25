package cn.servicehub.jobs;

import java.util.Map;
import java.util.List;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface JobMapper {
    Map<String,Object> findByKey(@Param("tenantId") long tenantId,@Param("kind") String kind,@Param("businessKey") String businessKey);
    Map<String,Object> findLocked(@Param("tenantId") long tenantId,@Param("id") long id);
    int insertOrGetId(Map<String,Object> values);
    int ensureInitialOutbox(@Param("tenantId") long tenantId,@Param("jobId") long jobId);
    int cancelForConversation(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("beforeEpoch") long beforeEpoch);
    record JobRow(long id,long tenantId,String kind,String businessKey,String payloadText,String state,String executionClass,
        Long conversationId,long executionVersion,int attempts,int manualRetries,int lifetimeAttempts,String owner,
        Instant leaseExpiresAt,Instant availableAt,Instant acceptedAt,Instant startedAt,Instant deadlineAt,long budgetRevision,String errorCode) { }
    record ConversationScope(long id,long customerId,String status,long epoch,Long topicId) { }
    record RunScope(long id,long conversationId,Long topicId,long generationEpoch,long authVersion,String state,long budgetRevision,Instant inputExpiresAt) { }
    record DocumentScope(long id,boolean enabled,boolean deleted,long revision,long versionId,int number,int latestNumber,Long jobId) { }
    JobRow peek(@Param("id") long id);
    JobRow lockJob(@Param("tenantId") long tenantId,@Param("id") long id);
    ConversationScope lockConversation(@Param("tenantId") long tenantId,@Param("id") long id);
    RunScope lockRun(@Param("tenantId") long tenantId,@Param("jobId") long jobId);
    DocumentScope lockDocument(@Param("tenantId") long tenantId,@Param("documentId") long documentId,@Param("versionId") long versionId);
    int manualRetry(@Param("tenantId")long tenant,@Param("id")long id,@Param("revision")long revision);
    int resetRunBudget(@Param("tenantId")long tenant,@Param("id")long id,@Param("revision")long revision);
    int resetDocumentStatus(@Param("tenantId")long tenant,@Param("id")long job,@Param("versionId")long version);
    List<Long> expiredInputs();
    Instant now();
    int start(@Param("tenantId") long tenantId,@Param("id") long id,@Param("owner") String owner,@Param("leaseSeconds") int leaseSeconds,@Param("deadlineSeconds") int deadlineSeconds);
    int renew(@Param("lease") JobLease lease,@Param("leaseSeconds") int leaseSeconds);
    int complete(@Param("lease") JobLease lease,@Param("state") String state,@Param("result") String result);
    int retire(@Param("tenantId") long tenantId,@Param("id") long id,@Param("state") String state,@Param("code") String code);
    int retry(@Param("tenantId") long tenantId,@Param("id") long id,@Param("delay") int delay,@Param("code") String code);
    int retryOutbox(@Param("tenantId") long tenantId,@Param("jobId") long jobId,@Param("key") String key);
    int runState(@Param("tenantId") long tenantId,@Param("jobId") long jobId,@Param("state") String state,@Param("code") String code);
    List<Long> recoverable(@Param("foregroundQueue") int foregroundQueue,@Param("backgroundQueue") int backgroundQueue);
}
