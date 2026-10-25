package cn.servicehub.aftersales;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AfterSaleMapper {
    record DraftRow(long id,long orderId,long customerId,String kind,String reason,long version,Instant expiresAt,boolean consumed) {
        public AfterSaleDtos.DraftDto dto(){return new AfterSaleDtos.DraftDto(id,orderId,customerId,AfterSaleDtos.Kind.valueOf(kind),reason,version,expiresAt,consumed);}
    }
    record TicketRow(long id,long draftId,long orderId,long customerId,String kind,String reason,String status,long version,
        Instant createdAt,String requestHash) {
        public AfterSaleDtos.TicketDto dto(List<AfterSaleDtos.TicketEventDto> events){return new AfterSaleDtos.TicketDto(id,draftId,orderId,customerId,AfterSaleDtos.Kind.valueOf(kind),reason,AfterSaleDtos.Status.valueOf(status),version,createdAt,events);}
    }
    int insertDraft(Map<String,Object> values);
    int insertAgentDraft(Map<String,Object> values);
    DraftRow agentDraft(@Param("tenantId")long tenant,@Param("runId")long run,@Param("budgetRevision")long revision,@Param("hash")String hash);
    DraftRow lockDraft(@Param("tenantId") long tenantId,@Param("customerId") long customerId,@Param("id") long id);
    DraftRow draftById(@Param("tenantId") long tenantId,@Param("id") long id);
    List<DraftRow> drafts(@Param("tenantId") long tenantId,@Param("customerId") long customerId);
    TicketRow byKey(@Param("tenantId") long tenantId,@Param("key") String key);
    TicketRow ticket(@Param("tenantId") long tenantId,@Param("customerId") Long customerId,@Param("id") long id);
    TicketRow lockTicket(@Param("tenantId") long tenantId,@Param("customerId") Long customerId,@Param("id") long id);
    List<TicketRow> tickets(@Param("tenantId") long tenantId,@Param("customerId") Long customerId,@Param("cursor") Long cursor,@Param("limit") int limit,@Param("status") String status);
    int insertTicket(Map<String,Object> values);
    int consume(@Param("tenantId") long tenantId,@Param("id") long id,@Param("version") long version);
    int transition(@Param("tenantId") long tenantId,@Param("id") long id,@Param("version") long version,@Param("status") String status);
    int event(@Param("tenantId") long tenantId,@Param("ticketId") long ticketId,@Param("actorId") long actorId,@Param("from") String from,@Param("to") String to,@Param("note") String note);
    List<AfterSaleDtos.TicketEventDto> events(@Param("tenantId") long tenantId,@Param("ticketId") long ticketId);
    Instant now();
}
