package cn.servicehub.conversations;

import cn.servicehub.auth.*;
import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import cn.servicehub.jobs.*;
import cn.servicehub.orders.OrderService;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationService {
    private final cn.servicehub.operations.AuditService audit;
    private final ConversationMapper conversations;private final AuthorizationService authorization;private final JobJournal jobs;
    private final EventStore events;private final AuthService auth;private final OrderService orders;private final JsonMapper json;
    public ConversationService(ConversationMapper conversations,AuthorizationService authorization,JobJournal jobs,EventStore events,AuthService auth,OrderService orders,JsonMapper json,cn.servicehub.operations.AuditService audit){this.audit=audit;
        this.conversations=conversations;this.authorization=authorization;this.jobs=jobs;this.events=events;this.auth=auth;this.orders=orders;this.json=json;
    }
    @Transactional public ConversationDtos.ConversationDto create(Actor actor,String title){
        if(actor.role()!=Role.CUSTOMER)throw new BusinessException("forbidden","请从客户入口创建会话",403);
        authorization.requireForWrite(actor);var values=values(actor.tenantId(),0);values.put("customerId",actor.userId());values.put("title",title);
        conversations.insert(values);long id=((Number)values.get("id")).longValue();ensureTopic(conversations.lock(actor.tenantId(),id));
        events.append(actor.tenantId(),id,"conversation.created",Map.of());return events.snapshot(conversations.get(actor.tenantId(),id));
    }
    public ConversationDtos.ConversationDto get(Actor actor,long id){authorization.requireCurrent(actor);return events.snapshot(authorized(actor,id,false));}
    @Transactional(propagation=Propagation.MANDATORY) public ConversationDtos.ConversationDto lock(Actor actor,long id){authorization.requireForWrite(actor);return events.snapshot(authorized(actor,id,true));}
    public ConversationDtos.ConversationPage list(Actor actor,Long cursor,int limit,String status,String search){
        authorization.requireCurrent(actor);var rows=conversations.list(actor.tenantId(),actor.role()==Role.CUSTOMER?actor.userId():null,cursor,limit+1,status,search);
        return new ConversationDtos.ConversationPage(rows.stream().limit(limit).map(events::snapshot).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    @Transactional public ConversationDtos.MessageDto send(Actor actor,long id,String clientMessageId,String body){
        body=body.strip();if(body.isEmpty()||body.codePointCount(0,body.length())>8000)throw new BusinessException("invalid_message","消息为空或超出长度限制",422);
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        if(actor.role()!=Role.CUSTOMER&&(!"HUMAN_ACTIVE".equals(row.status())||!Objects.equals(row.assignedAgentId(),actor.userId())))
            throw new BusinessException("forbidden","请先领取会话，再发送人工回复",403);
        var existing=conversations.messageByKey(actor.tenantId(),id,clientMessageId);
        if(existing!=null){
            if(!existing.body().equals(body)||!Objects.equals(existing.senderId(),actor.userId()))throw new BusinessException("idempotency_conflict","相同消息标识不能用于不同内容",409);
            return message(existing);
        }
        if("CLOSED".equals(row.status()))throw new BusinessException("conversation_closed","会话已结束，请创建新会话",409);
        ensureTopic(row);conversations.advance(actor.tenantId(),id);row=conversations.get(actor.tenantId(),id);cancelFront(row,"SUPERSEDED");
        var values=values(actor.tenantId(),id);values.put("senderId",actor.userId());values.put("authorRole",actor.role()==Role.CUSTOMER?"customer":"agent");
        values.put("key",clientMessageId);values.put("body",body);boolean resolved=actor.role()!=Role.CUSTOMER||TopicRouter.quick(body).map(d->d.kind()==TopicDecision.Kind.CONTINUE).orElse(false);
        values.put("topicId",resolved?row.activeTopicId():null);values.put("routingState",resolved?"RESOLVED":"PENDING");values.put("epoch",row.generationEpoch());
        conversations.insertMessage(values);long messageId=((Number)values.get("id")).longValue();
        var provisional=message(conversations.messageById(actor.tenantId(),messageId));
        // MySQL enforces the trigger_event_seq FK immediately. Finalize references before commit.
        long seq=events.append(actor.tenantId(),id,"message.created",Map.of("message",provisional));
        if("AI_ACTIVE".equals(row.status())&&actor.role()==Role.CUSTOMER){
            startReply(actor,id,messageId,seq,resolved?row.activeTopicId():null,true);
        }
        var result=message(conversations.messageById(actor.tenantId(),messageId));events.completeMessageEvent(actor.tenantId(),id,seq,result);return result;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public long startReply(Actor actor,long id,long messageId,long seq,Long resolvedTopicId,boolean bindOriginal){
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        if(actor.role()!=Role.CUSTOMER||!"AI_ACTIVE".equals(row.status()))throw new BusinessException("forbidden","当前状态不能创建 AI 回答",403);
        var source=conversations.messageById(actor.tenantId(),messageId);
        if(source==null||source.conversationId()!=id||!Objects.equals(source.senderId(),actor.userId()))throw new BusinessException("not_found","消息不存在",404);
        if(resolvedTopicId!=null&&!Objects.equals(resolvedTopicId,row.activeTopicId()))throw new BusinessException("topic_conflict","话题已变化",409);
        var scope=Map.<String,Object>of("conversation_id",id,"topic_id",row.activeTopicId(),"customer_id",row.customerId(),"message_id",messageId);
        var payload=new LinkedHashMap<String,Object>(scope);payload.put("generation_epoch",row.generationEpoch());payload.put("actor_id",actor.userId());payload.put("auth_version",actor.authVersion());payload.put("scope",scope);
        var job=jobs.enqueue(new JobCommand(actor.tenantId(),"ai_reply",ExecutionClass.INTERACTIVE,"conversation:"+id+":epoch:"+row.generationEpoch(),payload));
        var run=values(actor.tenantId(),id);run.put("topicId",resolvedTopicId);run.put("epoch",row.generationEpoch());run.put("jobId",job.id());run.put("eventSeq",seq);run.put("authVersion",actor.authVersion());run.put("scope",json.writeValueAsString(scope));
        conversations.insertRun(run);long runId=((Number)run.get("id")).longValue();
        if(bindOriginal&&conversations.bindMessage(actor.tenantId(),messageId,runId,job.id())!=1)throw new IllegalStateException("Message trigger was already bound");
        return runId;
    }
    public ConversationDtos.MessageDto message(Actor actor,long conversationId,long messageId){
        authorization.requireCurrent(actor);authorized(actor,conversationId,false);var row=conversations.messageById(actor.tenantId(),messageId);
        if(row==null||row.conversationId()!=conversationId)throw new BusinessException("not_found","消息不存在或无权访问",404);return message(row);
    }
    public ConversationDtos.MessagePage messages(Actor actor,long id,Long cursor,int limit){
        authorization.requireCurrent(actor);authorized(actor,id,false);var rows=conversations.messages(actor.tenantId(),id,cursor,limit+1);
        var visible=new ArrayList<>(rows.subList(0,Math.min(limit,rows.size())));Collections.reverse(visible);
        return new ConversationDtos.MessagePage(visible.stream().map(this::message).toList(),rows.size()>limit?rows.get(limit-1).id():null);
    }
    public ConversationDtos.EventPage events(Actor actor,long id,long afterSeq,int limit){
        authorization.requireCurrent(actor);authorized(actor,id,false);var rows=conversations.events(actor.tenantId(),id,afterSeq,limit+1);
        return new ConversationDtos.EventPage(rows.stream().limit(limit).map(r->new ConversationDtos.LiveEvent(r.kind(),r.conversationId(),r.seq(),r.generationEpoch(),object(r.payloadText()))).toList(),rows.size()>limit);
    }
    @Transactional public ConversationDtos.ConversationDto handoff(Actor actor,long id){
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        if("CLOSED".equals(row.status()))throw new BusinessException("conversation_closed","会话已结束",409);
        if("AI_ACTIVE".equals(row.status())){conversations.handoff(actor.tenantId(),id);row=conversations.get(actor.tenantId(),id);cancelFront(row,"CANCELLED");events.append(actor.tenantId(),id,"conversation.updated",Map.of());audit(actor,id,"conversation.handoff");}
        return events.snapshot(conversations.get(actor.tenantId(),id));
    }
    @Transactional public ConversationDtos.ConversationDto claim(Actor actor,long id,long version){
        if(actor.role()==Role.CUSTOMER)throw new BusinessException("forbidden","当前账号无权执行此操作",403);
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        if("HUMAN_ACTIVE".equals(row.status())&&Objects.equals(row.assignedAgentId(),actor.userId()))return events.snapshot(row);
        if(conversations.claim(actor.tenantId(),id,actor.userId(),version)!=1)throw new BusinessException("claim_conflict","该会话已被领取或状态已变化，请刷新",409);
        cancelFront(conversations.get(actor.tenantId(),id),"CANCELLED");events.append(actor.tenantId(),id,"conversation.updated",Map.of());audit(actor,id,"conversation.claimed");
        return events.snapshot(conversations.get(actor.tenantId(),id));
    }
    @Transactional public ConversationDtos.ConversationDto close(Actor actor,long id){
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        if(actor.role()==Role.AGENT&&!Objects.equals(row.assignedAgentId(),actor.userId()))throw new BusinessException("forbidden","仅接待客服可结束该会话",403);
        if(!"CLOSED".equals(row.status())){conversations.close(actor.tenantId(),id);cancelFront(conversations.get(actor.tenantId(),id),"CANCELLED");events.append(actor.tenantId(),id,"conversation.updated",Map.of());audit(actor,id,"conversation.closed");}
        return events.snapshot(conversations.get(actor.tenantId(),id));
    }
    public ConversationDtos.ContextDto context(Actor actor,long id){
        if(actor.role()==Role.CUSTOMER)throw new BusinessException("forbidden","当前账号无权执行此操作",403);
        authorization.requireCurrent(actor);var conversation=authorized(actor,id,false);var user=auth.customerProfile(actor,conversation.customerId());
        int split=user.email().indexOf('@');String name=user.email().substring(0,split),domain=user.email().substring(split+1);
        return new ConversationDtos.ContextDto(user.id(),user.displayName(),name.substring(0,Math.min(2,name.length()))+"***@"+domain,user.isActive(),orders.listForCustomer(actor,user.id(),3));
    }
    private ConversationMapper.ConversationRow authorized(Actor actor,long id,boolean lock){
        var row=lock?conversations.lock(actor.tenantId(),id):conversations.get(actor.tenantId(),id);
        if(row==null||actor.role()==Role.CUSTOMER&&row.customerId()!=actor.userId())throw new BusinessException("not_found","会话不存在或无权访问",404);return row;
    }
    private void ensureTopic(ConversationMapper.ConversationRow row){
        if(row.activeTopicId()!=null)return;var topic=values(row.tenantId(),row.id());topic.put("title","默认话题");conversations.insertTopic(topic);
        conversations.setTopic(row.tenantId(),row.id(),((Number)topic.get("id")).longValue());
    }
    void cancelFront(ConversationMapper.ConversationRow row,String state){
        conversations.lockRuns(row.tenantId(),row.id(),row.generationEpoch());conversations.cancelRuns(row.tenantId(),row.id(),row.generationEpoch(),state);
        jobs.cancelForConversation(row.tenantId(),row.id(),row.generationEpoch());conversations.excludeCancelledInstructions(row.tenantId(),row.id());
    }
    private void audit(Actor actor,long id,String action){audit.append(actor,action,"conversation",id,Map.of());}
    private LinkedHashMap<String,Object> values(long tenant,long conversation){var result=new LinkedHashMap<String,Object>();result.put("tenantId",tenant);result.put("conversationId",conversation);return result;}
    @SuppressWarnings("unchecked") private Map<String,Object> object(String encoded){return json.readValue(encoded,Map.class);}
    @SuppressWarnings("unchecked") private ConversationDtos.MessageDto message(ConversationMapper.MessageRow row){
        return new ConversationDtos.MessageDto(row.id(),row.conversationId(),row.senderId(),row.authorRole(),row.clientMessageId(),row.aiRunId(),row.body(),json.readValue(row.citationsText(),List.class),row.draftId(),row.createdAt(),row.topicId(),row.routingState(),row.contextStatus(),row.acceptedGenerationEpoch(),row.triggerRunId(),row.triggerJobId());
    }
}
