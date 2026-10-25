package cn.servicehub.conversations;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ConversationDtos {
    private ConversationDtos() { }
    public enum Status {AI_ACTIVE,WAITING_HUMAN,HUMAN_ACTIVE,CLOSED}
    public static final class ConversationInput {
        @NotNull @Size(min=1,max=120) public String title="售后咨询";
    }
    public record MessageInput(@NotNull @Size(min=1,max=80) @Pattern(regexp="^[A-Za-z0-9_-]+$") String clientMessageId,
        @NotNull @Size(min=1,max=8000) String body) { }
    public record ClaimInput(@Min(1) long version) { }
    public record AIReplyDto(long runId,String state,String errorCode,boolean willRetry,long generationEpoch,int attempts,long executionVersion) { }
    public record ConversationDto(long id,long customerId,Long assignedAgentId,String title,String status,long version,
        long generationEpoch,long lastEventSeq,Instant createdAt,Instant updatedAt,AIReplyDto aiReply,Long activeTopicId) { }
    public record ConversationPage(List<ConversationDto> items,Long nextCursor) { }
    public record MessageDto(long id,long conversationId,Long senderId,String authorRole,String clientMessageId,Long aiRunId,
        String body,List<Map<String,Object>> citations,Long draftId,Instant createdAt,Long topicId,String routingState,
        String contextStatus,long acceptedGenerationEpoch,Long triggerRunId,Long triggerJobId) { }
    public record MessagePage(List<MessageDto> items,Long nextCursor) { }
    public record LiveEvent(String kind,long conversationId,Long seq,long generationEpoch,Map<String,Object> payload) { }
    public record EventPage(List<LiveEvent> items,boolean hasMore) { }
    public record ContextDto(long customerId,String displayName,String maskedEmail,boolean isActive,List<cn.servicehub.orders.OrderDtos.OrderDto> orders) { }
}
