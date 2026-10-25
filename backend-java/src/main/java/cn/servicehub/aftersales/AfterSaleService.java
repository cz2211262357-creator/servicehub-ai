package cn.servicehub.aftersales;

import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.auth.Role;
import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import cn.servicehub.orders.OrderDtos;
import cn.servicehub.orders.OrderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AfterSaleService {
    private static final Map<String,Set<String>> TRANSITIONS=Map.of(
        "SUBMITTED",Set.of("IN_REVIEW","CANCELLED"),"IN_REVIEW",Set.of("APPROVED","REJECTED"),
        "APPROVED",Set.of("RESOLVED"),"REJECTED",Set.of(),"RESOLVED",Set.of(),"CANCELLED",Set.of());
    private final cn.servicehub.operations.AuditService audit;private final AfterSaleMapper sales;private final AuthorizationService authorization;private final OrderService orders;
    private final cn.servicehub.conversations.ConversationService conversations;private final cn.servicehub.conversations.TopicService topics;
    private final JsonMapper json;private final TransactionTemplate transaction;
    public AfterSaleService(AfterSaleMapper sales,AuthorizationService authorization,OrderService orders,JsonMapper json,PlatformTransactionManager transactions,cn.servicehub.conversations.ConversationService conversations,cn.servicehub.conversations.TopicService topics,cn.servicehub.operations.AuditService audit){this.audit=audit;this.conversations=conversations;this.topics=topics;
        this.sales=sales;this.authorization=authorization;this.orders=orders;this.json=json;this.transaction=new TransactionTemplate(transactions);
    }
    @Transactional public AfterSaleDtos.DraftDto prepare(Actor actor,long orderId,String kind,String reason){
        if(kind==null||!Set.of("RETURN","EXCHANGE","REPAIR").contains(kind)||reason==null||reason.codePointCount(0,reason.length())<2||reason.codePointCount(0,reason.length())>1000)
            throw new BusinessException("validation_error","Request validation failed",422);
        authorization.requireForWrite(actor);var order=orders.lockById(actor,orderId);eligible(order);
        var row=new LinkedHashMap<String,Object>();row.put("tenantId",actor.tenantId());row.put("orderId",order.id());row.put("customerId",order.customerId());
        row.put("kind",kind);row.put("reason",reason.strip());sales.insertDraft(row);
        return sales.draftById(actor.tenantId(),((Number)row.get("id")).longValue()).dto();
    }
    @Transactional public AfterSaleDtos.DraftDto prepareForRun(Actor actor,cn.servicehub.conversations.ExecutionFence fence,cn.servicehub.jobs.JobLease lease,String orderNumber,String kind,String reason){
        if(kind==null||!Set.of("RETURN","EXCHANGE","REPAIR").contains(kind)||reason==null||reason.strip().codePointCount(0,reason.strip().length())<2||reason.codePointCount(0,reason.length())>1000)
            throw new BusinessException("validation_error","Request validation failed",422);
        authorization.requireForWrite(actor);conversations.lock(actor,fence.conversationId());var order=orders.lockByNumber(actor,orderNumber);eligible(order);
        topics.requireCurrentForWrite(actor,lease,fence);
        String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(List.of(order.id(),kind,reason.strip()))));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        var existing=sales.agentDraft(actor.tenantId(),fence.runId(),lease.budgetRevision(),hash);
        if(existing!=null){
            if(existing.consumed()||!existing.expiresAt().isAfter(sales.now()))throw new BusinessException("draft_conflict","原草稿已确认或过期",409);
            return existing.dto();
        }
        var row=new LinkedHashMap<String,Object>();row.put("tenantId",actor.tenantId());row.put("orderId",order.id());row.put("customerId",order.customerId());row.put("kind",kind);row.put("reason",reason.strip());
        row.put("topicId",fence.topicId());row.put("runId",fence.runId());row.put("epoch",fence.generationEpoch());row.put("budgetRevision",lease.budgetRevision());row.put("hash",hash);sales.insertAgentDraft(row);
        return sales.draftById(actor.tenantId(),((Number)row.get("id")).longValue()).dto();
    }
    @Transactional public AfterSaleDtos.TicketDto confirm(Actor actor,long draftId,long version,String idempotencyKey){
        if(actor.role()!=Role.CUSTOMER)throw new BusinessException("customer_confirmation_required","售后申请必须由客户本人确认",403);
        authorization.requireForWrite(actor);var draft=sales.lockDraft(actor.tenantId(),actor.userId(),draftId);
        if(draft==null)throw new BusinessException("not_found","申请草稿不存在",404);
        String hash=fingerprint(draft,version);var existing=sales.byKey(actor.tenantId(),idempotencyKey);
        if(existing!=null){
            if(existing.customerId()!=actor.userId()||!existing.requestHash().equals(hash))throw new BusinessException("idempotency_conflict","该提交标识已用于其他申请",409);
            return existing.dto(List.of());
        }
        if(draft.version()!=version||draft.consumed())throw new BusinessException("draft_conflict","草稿已变更或已确认，请刷新",409);
        if(!draft.expiresAt().isAfter(sales.now()))throw new BusinessException("draft_expired","草稿已过期，请重新准备申请",410);
        var order=orders.lockById(actor,draft.orderId());eligible(order);
        var row=new LinkedHashMap<String,Object>();row.put("tenantId",actor.tenantId());row.put("draftId",draft.id());row.put("customerId",actor.userId());
        row.put("orderId",draft.orderId());row.put("kind",draft.kind());row.put("reason",draft.reason());row.put("key",idempotencyKey);row.put("hash",hash);
        try{sales.insertTicket(row);}catch(DuplicateKeyException e){throw new BusinessException("idempotency_conflict","该提交标识或草稿已被使用，请刷新",409);}
        long id=((Number)row.get("id")).longValue();
        if(sales.consume(actor.tenantId(),draft.id(),version)!=1)throw new BusinessException("draft_conflict","草稿已变更或已确认，请刷新",409);
        sales.event(actor.tenantId(),id,actor.userId(),null,"SUBMITTED","客户确认提交");
        audit(actor,id,"ticket.confirmed","success",Map.of());
        return sales.ticket(actor.tenantId(),actor.userId(),id).dto(List.of());
    }
    public AfterSaleDtos.DraftPage drafts(Actor actor){
        authorization.requireCurrent(actor);return new AfterSaleDtos.DraftPage(sales.drafts(actor.tenantId(),actor.userId()).stream().map(AfterSaleMapper.DraftRow::dto).toList());
    }
    public AfterSaleDtos.TicketPage list(Actor actor,Long cursor,int limit,String status){
        authorization.requireCurrent(actor);var rows=sales.tickets(actor.tenantId(),customer(actor),cursor,limit+1,status);
        return new AfterSaleDtos.TicketPage(rows.stream().limit(limit).map(r->r.dto(List.of())).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    public AfterSaleDtos.TicketDto get(Actor actor,long id){
        authorization.requireCurrent(actor);var row=sales.ticket(actor.tenantId(),customer(actor),id);
        if(row==null)throw new BusinessException("not_found","工单不存在",404);return row.dto(sales.events(actor.tenantId(),id));
    }
    private record TransitionOutcome(AfterSaleDtos.TicketDto ticket,BusinessException failure) { }
    public AfterSaleDtos.TicketDto transition(Actor actor,long ticketId,long version,String status,String note){
        // A business rejection still commits its audit; unexpected failures roll back the transaction.
        var result=transaction.execute(tx->{
            authorization.requireForWrite(actor);var row=sales.lockTicket(actor.tenantId(),customer(actor),ticketId);
            if(row==null)throw new BusinessException("not_found","工单不存在",404);
            boolean allowed=status!=null&&TRANSITIONS.getOrDefault(row.status(),Set.of()).contains(status);
            if(actor.role()==Role.CUSTOMER)allowed=allowed&&"CANCELLED".equals(status);
            if(!allowed||row.version()!=version){
                audit(actor,row.id(),"ticket.transition","rejected",Map.of("requested_status",Objects.requireNonNullElse(status,"")));
                return new TransitionOutcome(null,new BusinessException("ticket_conflict","工单状态已变化或不允许该流转",409));
            }
            if(sales.transition(actor.tenantId(),row.id(),version,status)!=1)throw new IllegalStateException("Locked ticket version changed");
            sales.event(actor.tenantId(),row.id(),actor.userId(),row.status(),status,note);
            audit(actor,row.id(),"ticket.transition","success",Map.of("from",row.status(),"to",status));
            return new TransitionOutcome(sales.ticket(actor.tenantId(),customer(actor),row.id()).dto(List.of()),null);
        });
        if(result==null)throw new IllegalStateException("Transition transaction returned no result");
        if(result.failure()!=null)throw result.failure();return result.ticket();
    }
    private void eligible(OrderDtos.OrderDto order){if(!Set.of("DELIVERED","SHIPPED").contains(order.status()))throw new BusinessException("order_not_eligible","当前订单状态不可发起售后",409);}
    private Long customer(Actor actor){if(actor.role()==Role.CUSTOMER)return actor.userId();return null;}
    private void audit(Actor actor,long id,String action,String outcome,Map<String,Object> details){
        audit.append(actor,action,"ticket",id,outcome,Objects.requireNonNullElse(MDC.get("request_id"),"-"),details);
    }
    private String fingerprint(AfterSaleMapper.DraftRow draft,long version){
        String input=json.writeValueAsString(List.of(draft.id(),version,draft.orderId(),draft.kind(),draft.reason()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
