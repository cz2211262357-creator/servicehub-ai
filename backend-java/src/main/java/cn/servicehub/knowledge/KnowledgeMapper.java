package cn.servicehub.knowledge;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface KnowledgeMapper {
    record DocumentRow(long id,long tenantId,String title,long uploadedBy,boolean enabled,boolean deleted,int version,Instant createdAt){}
    record VersionRow(long id,long tenantId,long documentId,int number,String storageKey,String filename,String mediaType,long sizeBytes,String sha256,String status,String errorCode,Long jobId,String embeddingModel,Integer embeddingDimensions,Long indexExecutionVersion,Instant createdAt){}
    record ChunkRow(long id,long tenantId,long documentId,long versionId,long executionVersion,int ordinal,int page,String text){}
    record ActiveGeneration(long versionId,long executionVersion,String model,int dimensions){}
    record EvidenceRow(long documentId,long versionId,long chunkId,String filename,int page,String text,long executionVersion,String title){}
    List<ActiveGeneration> activeGenerations(@Param("tenantId")long tenant);
    List<Long> keywordFulltext(@Param("tenantId")long tenant,@Param("query")String query,@Param("limit")int limit);
    List<Long> keywordFallback(@Param("tenantId")long tenant,@Param("patterns")List<String> patterns,@Param("limit")int limit);
    default List<Long> keyword(long tenant,String query,int limit){
        var found=keywordFulltext(tenant,query,Math.min(30,limit));if(!found.isEmpty())return found;
        var patterns=KeywordTerms.patterns(query);return patterns.isEmpty()?List.of():keywordFallback(tenant,patterns,Math.min(30,limit));
    }
    List<EvidenceRow> currentEvidence(@Param("tenantId")long tenant,@Param("ids")List<Long> ids);
    VersionRow versionById(@Param("tenantId")long tenant,@Param("id")long version);
    int citedInConversation(@Param("tenantId")long tenant,@Param("conversationId")long conversation,@Param("versionId")long version);
    record JobState(String state,String errorCode){}
    JobState jobState(@Param("tenantId")long tenant,@Param("jobId")long job);
    record GenerationRow(long tenantId,long versionId,long executionVersion,long jobId){}
    int recordGeneration(@Param("tenantId")long tenant,@Param("versionId")long version,@Param("executionVersion")long token,@Param("jobId")long job);
    List<GenerationRow> collectibleGenerations();
    int removeGenerationChunks(GenerationRow generation);
    int cleaned(GenerationRow generation);
    DocumentRow document(@Param("tenantId")long tenant,@Param("id")long id);
    DocumentRow lockDocument(@Param("tenantId")long tenant,@Param("id")long id);
    List<DocumentRow> list(@Param("tenantId")long tenant,@Param("customer")boolean customer,@Param("cursor")Long cursor,@Param("limit")int limit);
    VersionRow latest(@Param("tenantId")long tenant,@Param("documentId")long doc);
    VersionRow version(@Param("tenantId")long tenant,@Param("documentId")long doc,@Param("id")long id);
    Long active(@Param("tenantId")long tenant,@Param("documentId")long doc);
    int insertDocument(Map<String,Object> row);
    int insertVersion(Map<String,Object> row);
    int advance(@Param("tenantId")long tenant,@Param("id")long id,@Param("version")int version);
    int enable(@Param("tenantId")long tenant,@Param("id")long id,@Param("version")int version,@Param("enabled")boolean enabled);
    int remove(@Param("tenantId")long tenant,@Param("id")long id);
    int bindJob(@Param("tenantId")long tenant,@Param("id")long id,@Param("jobId")long job);
    int cited(@Param("tenantId")long tenant,@Param("customerId")long customer,@Param("documentId")long doc,@Param("versionId")long version);
    int clearChunks(@Param("tenantId")long tenant,@Param("versionId")long version);
    int insertChunks(@Param("tenantId")long tenant,@Param("documentId")long doc,@Param("versionId")long version,@Param("executionVersion")long token,@Param("chunks")List<DocumentParser.TextChunk> chunks);
    List<ChunkRow> chunks(@Param("tenantId")long tenant,@Param("versionId")long version,@Param("executionVersion")long token);
    int versionState(@Param("tenantId")long tenant,@Param("id")long version,@Param("state")String state,@Param("code")String code);
    int ready(@Param("tenantId")long tenant,@Param("id")long version,@Param("executionVersion")long token,@Param("model")String model,@Param("dimensions")int dimensions);
    int activate(@Param("tenantId")long tenant,@Param("documentId")long doc,@Param("versionId")long version);
}
