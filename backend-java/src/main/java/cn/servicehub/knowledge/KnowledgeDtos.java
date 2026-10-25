package cn.servicehub.knowledge;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class KnowledgeDtos {
    private KnowledgeDtos(){}
    public record VersionDto(long id,int number,String filename,String mediaType,long sizeBytes,String status,String errorCode,Long jobId,Instant createdAt){}
    public record DocumentDto(long id,String title,boolean enabled,int version,Instant createdAt,Long activeVersionId,VersionDto latestVersion){}
    public record DocumentPage(List<DocumentDto> items,Long nextCursor){}
    public record EnableInput(@NotNull Boolean enabled,@Min(1) int version){}
}
