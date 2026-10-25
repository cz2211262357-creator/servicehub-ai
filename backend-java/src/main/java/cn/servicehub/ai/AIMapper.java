package cn.servicehub.ai;
import java.time.Instant;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AIMapper {
    record ClassificationRow(boolean attempted,String resultText){}
    ClassificationRow classification(@Param("tenantId")long tenant,@Param("jobId")long job);
    int claimClassification(@Param("tenantId")long tenant,@Param("jobId")long job,@Param("budgetRevision")long revision);
    int saveClassification(@Param("tenantId")long tenant,@Param("jobId")long job,@Param("budgetRevision")long revision,@Param("decision")String decision);
    int insertAnswer(java.util.Map<String,Object> values);
    int result(@Param("tenantId")long tenant,@Param("runId")long run,@Param("model")String model,@Param("usage")String usage);
    int validDraft(@Param("tenantId")long tenant,@Param("customerId")long customer,@Param("runId")long run,@Param("topicId")long topic,@Param("epoch")long epoch,@Param("revision")long revision,@Param("draftId")long draft);
    int insertTool(java.util.Map<String,Object> values);
    int finishTool(@Param("tenantId")long tenant,@Param("runId")long run,@Param("executionVersion")long token,@Param("step")int step,@Param("outcome")String outcome,@Param("latency")int latency,@Param("code")String code);
    record BudgetRow(long runId,int modelRequestsReserved,int toolExecutionsReserved,Instant deadlineAt,Instant databaseNow){}
    BudgetRow budget(@Param("tenantId")long tenant,@Param("jobId")long job);
    int reserveModel(@Param("tenantId")long tenant,@Param("jobId")long job,@Param("budgetRevision")long revision,@Param("limit")int limit);
    int reserveTool(@Param("tenantId")long tenant,@Param("jobId")long job,@Param("budgetRevision")long revision,@Param("limit")int limit);
}
