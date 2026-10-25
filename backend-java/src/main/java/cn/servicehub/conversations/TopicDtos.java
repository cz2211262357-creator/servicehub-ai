package cn.servicehub.conversations;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class TopicDtos {
    private TopicDtos(){}
    public enum Mode {NEW,RESUME}
    public record InterruptInput(@NotNull @Min(0) Long expectedGenerationEpoch){}
    public record TopicSwitchCommand(@NotNull Mode mode,@Positive Long topicId,@NotNull @Min(0) Long expectedGenerationEpoch,@Positive Long pendingMessageId){}
    public record TopicDto(long id,String title,String status,Instant lastActivityAt){}
    public record TopicPage(List<TopicDto> items,Long activeTopicId){}
}
