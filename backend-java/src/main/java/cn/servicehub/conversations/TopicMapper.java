package cn.servicehub.conversations;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface TopicMapper {
    record TopicRow(long id,String title,String summary,String factsText,Instant lastActivityAt){}
    record RunRow(long id,long conversationId,Long topicId,long generationEpoch,long jobId,String state,long authVersion,long budgetRevision,String scopeText,Instant inputExpiresAt){}
    record ContextRow(long id,String authorRole,String body,String citationsText){}
    List<TopicRow> list(@Param("tenantId")long tenant,@Param("conversationId")long conversation);
    TopicRow topic(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("id")long id);
    String receipt(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("actorId")long actor,@Param("epoch")long epoch);
    int receiptInsert(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("actorId")long actor,@Param("epoch")long epoch,@Param("resultEpoch")long resultEpoch,@Param("snapshot")String snapshot);
    RunRow run(@Param("tenantId")long tenant,@Param("id")long id);
    RunRow lockRun(@Param("tenantId")long tenant,@Param("jobId")long job);
    Long latestPending(@Param("tenantId")long tenant,@Param("conversationId")long conversation);
    int resolveMessage(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("messageId")long message,@Param("topicId")long topic,@Param("runId")long run);
    int resolveRun(@Param("tenantId")long tenant,@Param("id")long run,@Param("topicId")long topic,@Param("scope")String scope);
    int waiting(@Param("tenantId")long tenant,@Param("id")long run);
    int touch(@Param("tenantId")long tenant,@Param("id")long topic);
    int clearFacts(@Param("tenantId")long tenant,@Param("id")long topic);
    int supersedeContext(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("topicId")long topic,@Param("messageId")long current);
    List<ContextRow> context(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("topicId")long topic,@Param("limit")int limit);
}
