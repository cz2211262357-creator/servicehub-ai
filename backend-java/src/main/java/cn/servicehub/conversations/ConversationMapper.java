package cn.servicehub.conversations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {
    record ConversationRow(long id,long tenantId,long customerId,Long assignedAgentId,String title,String status,
        long version,long generationEpoch,long lastEventSeq,Instant createdAt,Instant updatedAt,Long activeTopicId) { }
    record ReplyRow(long runId,String runState,String jobState,String errorCode,long generationEpoch,int attempts,long executionVersion) { }
    record MessageRow(long id,long conversationId,Long senderId,String authorRole,String clientMessageId,Long aiRunId,
        String body,String citationsText,Long draftId,Instant createdAt,Long topicId,String routingState,String contextStatus,
        long acceptedGenerationEpoch,Long triggerRunId,Long triggerJobId) { }
    record EventRow(String kind,long conversationId,long seq,long generationEpoch,String payloadText) { }
    ConversationRow get(@Param("tenantId") long tenantId,@Param("id") long id);
    ConversationRow lock(@Param("tenantId") long tenantId,@Param("id") long id);
    List<ConversationRow> list(@Param("tenantId") long tenantId,@Param("customerId") Long customerId,@Param("cursor") Long cursor,
        @Param("limit") int limit,@Param("status") String status,@Param("search") String search);
    ReplyRow reply(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("epoch") long epoch);
    int insert(Map<String,Object> row);
    int insertTopic(Map<String,Object> row);
    int setTopic(@Param("tenantId") long tenantId,@Param("id") long id,@Param("topicId") long topicId);
    int advance(@Param("tenantId") long tenantId,@Param("id") long id);
    List<Long> lockRuns(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("beforeEpoch") long beforeEpoch);
    int cancelRuns(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("beforeEpoch") long beforeEpoch,@Param("state") String state);
    int excludeCancelledInstructions(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId);
    int insertMessage(Map<String,Object> row);
    MessageRow messageByKey(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("key") String key);
    MessageRow messageById(@Param("tenantId") long tenantId,@Param("id") long id);
    List<MessageRow> messages(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("cursor") Long cursor,@Param("limit") int limit);
    int insertRun(Map<String,Object> row);
    int bindMessage(@Param("tenantId") long tenantId,@Param("messageId") long messageId,@Param("runId") long runId,@Param("jobId") long jobId);
    int nextSeq(@Param("tenantId") long tenantId,@Param("id") long id);
    int insertEvent(Map<String,Object> row);
    int refreshMessageEvent(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("seq") long seq,@Param("payload") String payload);
    int insertNotification(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("seq") long seq);
    List<EventRow> events(@Param("tenantId") long tenantId,@Param("conversationId") long conversationId,@Param("afterSeq") long afterSeq,@Param("limit") int limit);
    int handoff(@Param("tenantId") long tenantId,@Param("id") long id);
    int claim(@Param("tenantId") long tenantId,@Param("id") long id,@Param("agentId") long agentId,@Param("version") long version);
    int close(@Param("tenantId") long tenantId,@Param("id") long id);

}
