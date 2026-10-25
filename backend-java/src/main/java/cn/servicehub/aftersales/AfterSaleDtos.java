package cn.servicehub.aftersales;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class AfterSaleDtos {
    private AfterSaleDtos() { }
    public enum Kind {RETURN,EXCHANGE,REPAIR}
    public enum Status {SUBMITTED,IN_REVIEW,APPROVED,REJECTED,RESOLVED,CANCELLED}
    public record DraftInput(@Positive long orderId,@NotNull Kind kind,@NotNull @Size(min=2,max=1000) String reason) { }
    public record ConfirmInput(@Min(1) long version,@NotNull @Size(min=1,max=80) @Pattern(regexp="^[A-Za-z0-9_-]+$") String idempotencyKey) { }
    public static final class TransitionInput {
        @Min(1) public long version;
        @NotNull public Status status;
        @NotNull @Size(max=500) public String note="";
    }
    public record DraftDto(long id,long orderId,long customerId,Kind kind,String reason,long version,Instant expiresAt,boolean consumed) { }
    public record DraftPage(List<DraftDto> items) { }
    public record TicketEventDto(long id,long actorId,String fromStatus,String toStatus,String note,Instant createdAt) { }
    public record TicketDto(long id,long draftId,long orderId,long customerId,Kind kind,String reason,Status status,long version,
        Instant createdAt,List<TicketEventDto> events) { }
    public record TicketPage(List<TicketDto> items,Long nextCursor) { }
}
