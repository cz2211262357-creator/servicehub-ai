package cn.servicehub.conversations;

import cn.servicehub.ai.ExecutionCancelledException;
import cn.servicehub.auth.*;
import cn.servicehub.common.*;
import cn.servicehub.jobs.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TopicService {
    private final cn.servicehub.operations.AuditService audit;
    private final ConversationMapper conversations;private final ConversationService service;private final TopicMapper topics;
    private final AuthorizationService authorization;private final JobMapper jobs;private final JobCoordinator coordinator;private final EventStore events;private final JsonMapper json;
    public TopicService(ConversationMapper conversations,ConversationService service,TopicMapper topics,AuthorizationService authorization,JobMapper jobs,JobCoordinator coordinator,EventStore events,JsonMapper json,cn.servicehub.operations.AuditService audit){this.audit=audit;
        this.conversations=conversations;this.service=service;this.topics=topics;this.authorization=authorization;this.jobs=jobs;this.coordinator=coordinator;this.events=events;this.json=json;
    }
    @Transactional public ConversationDtos.ConversationDto interrupt(Actor actor,long id,long expected){
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);
        String receipt=topics.receipt(actor.tenantId(),id,actor.userId(),expected);
        if(receipt!=null)return json.readValue(receipt,ConversationDtos.ConversationDto.class);
        open(row);epoch(row,expected);conversations.advance(actor.tenantId(),id);
        service.cancelFront(conversations.get(actor.tenantId(),id),"CANCELLED");
        events.append(actor.tenantId(),id,"conversation.updated",Map.of("reason","interrupted"));audit(actor,id,"conversation.interrupted");
        var result=events.snapshot(conversations.get(actor.tenantId(),id));topics.receiptInsert(actor.tenantId(),id,actor.userId(),expected,result.generationEpoch(),json.writeValueAsString(result));return result;
    }
    public TopicDtos.TopicPage list(Actor actor,long id){
        authorization.requireCurrent(actor);var row=authorized(actor,id,false);
        return new TopicDtos.TopicPage(topics.list(actor.tenantId(),id).stream().map(t->new TopicDtos.TopicDto(t.id(),t.title(),
            "CLOSED".equals(row.status())?"COMPLETED":Objects.equals(t.id(),row.activeTopicId())?"ACTIVE":"SUSPENDED",t.lastActivityAt())).toList(),row.activeTopicId());
    }
    @Transactional public ConversationDtos.ConversationDto switchTopic(Actor actor,long id,TopicDtos.TopicSwitchCommand command){
        authorization.requireForWrite(actor);var row=authorized(actor,id,true);open(row);epoch(row,command.expectedGenerationEpoch());
        if(command.mode()==null||command.mode()==TopicDtos.Mode.NEW&&command.topicId()!=null||command.mode()==TopicDtos.Mode.RESUME&&command.topicId()==null)
            throw new BusinessException("validation_error","话题切换参数不完整",422);
        if(command.mode()==TopicDtos.Mode.RESUME&&topics.topic(actor.tenantId(),id,command.topicId())==null)throw missingTopic();
        ConversationMapper.MessageRow pending=null;
        if(command.pendingMessageId()!=null){
            if(actor.role()!=Role.CUSTOMER||!"AI_ACTIVE".equals(row.status()))throw new BusinessException("forbidden","当前状态不能解析 AI 待确认问题",403);
            Long latest=topics.latestPending(actor.tenantId(),id);
            pending=conversations.messageById(actor.tenantId(),command.pendingMessageId());
            if(!Objects.equals(latest,command.pendingMessageId())||pending==null||pending.conversationId()!=id||pending.acceptedGenerationEpoch()!=row.generationEpoch())
                throw new BusinessException("pending_conflict","待确认问题已变化，请刷新",409);
        }
        if(pending==null&&command.mode()==TopicDtos.Mode.RESUME&&Objects.equals(row.activeTopicId(),command.topicId()))return events.snapshot(row);
        long target=command.mode()==TopicDtos.Mode.NEW?newTopic(row,"新话题"):command.topicId();
        conversations.advance(actor.tenantId(),id);conversations.setTopic(actor.tenantId(),id,target);
        service.cancelFront(conversations.get(actor.tenantId(),id),"SUPERSEDED");topics.touch(actor.tenantId(),target);
        long seq=events.append(actor.tenantId(),id,"conversation.updated",Map.of("reason","topic_switched"));
        if(pending!=null){
            long run=service.startReply(actor,id,pending.id(),seq,target,false);
            topics.resolveMessage(actor.tenantId(),id,pending.id(),target,run);
            var scope=Map.<String,Object>of("conversation_id",id,"topic_id",target,"message_id",pending.id(),"route","CONTINUE","requires_clarification",false);
            topics.resolveRun(actor.tenantId(),run,target,json.writeValueAsString(scope));
            // A second durable snapshot includes the newly created reply; both events share this transaction.
            events.append(actor.tenantId(),id,"conversation.updated",Map.of("reason","pending_resolved"));
        }
        audit(actor,id,"conversation.topic_switched");return events.snapshot(conversations.get(actor.tenantId(),id));
    }
    private record Current(ConversationMapper.ConversationRow conversation,TopicMapper.RunRow run,JobMapper.JobRow job,boolean valid){}
    @Transactional public Optional<ExecutionFence> resolve(JobLease lease,long messageId,TopicDecision decision){
        var auth=authorization.inspectForExecution(lease.tenantId(),lease.actor().userId(),lease.actor().authVersion());
        var current=lock(lease);
        if(!auth.permitted()||!current.valid()||auth.actor().role()!=Role.CUSTOMER)return Optional.empty();
        var row=current.conversation();var run=current.run();var message=conversations.messageById(lease.tenantId(),messageId);
        if(message==null||message.conversationId()!=row.id()||!Objects.equals(message.senderId(),lease.actor().userId())||number(lease.payload().get("message_id"))!=messageId)return Optional.empty();
        if("RESOLVED".equals(message.routingState())&&run.topicId()!=null&&Objects.equals(message.topicId(),row.activeTopicId()))return Optional.of(new ExecutionFence(lease.tenantId(),row.id(),run.topicId(),run.id(),row.generationEpoch(),lease.executionVersion()));
        if(decision.kind()==TopicDecision.Kind.AMBIGUOUS){
            topics.waiting(lease.tenantId(),run.id());coordinator.finish(lease,"WAITING_INPUT",Map.of("reason","topic_choice_required"));return Optional.empty();
        }
        long target;
        if(decision.kind()==TopicDecision.Kind.NEW_TOPIC)target=newTopic(row,title(message.body()));
        else if(decision.kind()==TopicDecision.Kind.RESUME){
            if(topics.topic(lease.tenantId(),row.id(),decision.topicId())==null)throw missingTopic();target=decision.topicId();
        }else target=Objects.requireNonNull(row.activeTopicId());
        if(decision.kind()==TopicDecision.Kind.REVISE){topics.clearFacts(lease.tenantId(),target);topics.supersedeContext(lease.tenantId(),row.id(),target,messageId);}
        conversations.setTopic(lease.tenantId(),row.id(),target);
        var scope=Map.<String,Object>of("conversation_id",row.id(),"topic_id",target,"message_id",messageId,"route",decision.kind().name(),"requires_clarification",decision.requiresClarification());
        topics.resolveRun(lease.tenantId(),run.id(),target,json.writeValueAsString(scope));topics.resolveMessage(lease.tenantId(),row.id(),messageId,target,run.id());topics.touch(lease.tenantId(),target);
        events.append(lease.tenantId(),row.id(),"conversation.updated",Map.of("reason","topic_resolved"));
        return Optional.of(new ExecutionFence(lease.tenantId(),row.id(),target,run.id(),row.generationEpoch(),lease.executionVersion()));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireCurrentForWrite(Actor actor,JobLease lease,ExecutionFence fence){
        authorization.requireForWrite(actor);var current=lock(lease);
        if(actor.tenantId()!=lease.tenantId()||actor.userId()!=lease.actor().userId()||!current.valid()
            ||fence.tenantId()!=lease.tenantId()||fence.runId()!=current.run().id()||fence.conversationId()!=current.conversation().id()
            ||fence.generationEpoch()!=current.conversation().generationEpoch()||fence.executionVersion()!=lease.executionVersion()
            ||!Objects.equals(current.run().topicId(),fence.topicId())||!Objects.equals(current.conversation().activeTopicId(),fence.topicId()))throw new ExecutionCancelledException("execution_fence_changed");
    }
    public TopicMapper.RunRow requireReadable(Actor actor,ExecutionFence fence){
        authorization.requireCurrent(actor);var conversation=authorized(actor,fence.conversationId(),false);var run=topics.run(actor.tenantId(),fence.runId());
        var job=run==null?null:jobs.peek(run.jobId());var now=jobs.now();
        if(actor.tenantId()!=fence.tenantId()||conversation.customerId()!=actor.userId()||!Objects.equals(conversation.activeTopicId(),fence.topicId())
            ||conversation.generationEpoch()!=fence.generationEpoch()||!"AI_ACTIVE".equals(conversation.status())
            ||run==null||run.conversationId()!=conversation.id()||!Objects.equals(run.topicId(),fence.topicId())||!"RUNNING".equals(run.state())
            ||run.authVersion()!=actor.authVersion()||job==null||job.tenantId()!=actor.tenantId()||!"RUNNING".equals(job.state())
            ||job.executionVersion()!=fence.executionVersion()||job.budgetRevision()!=run.budgetRevision()
            ||job.leaseExpiresAt()==null||!job.leaseExpiresAt().isAfter(now)||job.deadlineAt()==null||!job.deadlineAt().isAfter(now))throw new ExecutionCancelledException("context_fence_changed");
        return run;
    }
    /** Caller has acquired the authorization row first; locks domain, run, then job. */
    private Current lock(JobLease lease){
        long id=number(lease.payload().get("conversation_id"));var c=conversations.lock(lease.tenantId(),id);
        var r=topics.lockRun(lease.tenantId(),lease.jobId());var j=jobs.lockJob(lease.tenantId(),lease.jobId());var now=jobs.now();
        boolean valid=c!=null&&r!=null&&j!=null&&c.customerId()==lease.actor().userId()&&"AI_ACTIVE".equals(c.status())
            &&c.generationEpoch()==number(lease.payload().get("generation_epoch"))&&r.generationEpoch()==c.generationEpoch()
            &&r.authVersion()==lease.actor().authVersion()&&r.budgetRevision()==lease.budgetRevision()&&"RUNNING".equals(r.state())
            &&(r.topicId()==null||Objects.equals(r.topicId(),c.activeTopicId()))&&"RUNNING".equals(j.state())
            &&"ai_reply".equals(j.kind())&&"INTERACTIVE".equals(j.executionClass())&&j.budgetRevision()==lease.budgetRevision()
            &&j.executionVersion()==lease.executionVersion()&&Objects.equals(j.owner(),lease.owner())
            &&j.leaseExpiresAt()!=null&&j.leaseExpiresAt().isAfter(now)&&j.deadlineAt()!=null&&j.deadlineAt().isAfter(now);
        return new Current(c,r,j,valid);
    }
    private ConversationMapper.ConversationRow authorized(Actor actor,long id,boolean lock){
        var row=lock?conversations.lock(actor.tenantId(),id):conversations.get(actor.tenantId(),id);
        if(row==null||actor.role()==Role.CUSTOMER&&row.customerId()!=actor.userId())throw new BusinessException("not_found","会话不存在或无权访问",404);return row;
    }
    private long newTopic(ConversationMapper.ConversationRow row,String title){
        var values=new LinkedHashMap<String,Object>();values.put("tenantId",row.tenantId());values.put("conversationId",row.id());values.put("title",title);conversations.insertTopic(values);return ((Number)values.get("id")).longValue();
    }
    private void open(ConversationMapper.ConversationRow row){if("CLOSED".equals(row.status()))throw new BusinessException("conversation_closed","会话已结束",409);}
    private void epoch(ConversationMapper.ConversationRow row,long expected){if(row.generationEpoch()!=expected)throw new BusinessException("epoch_conflict","会话已变化，请刷新",409);}
    private BusinessException missingTopic(){return new BusinessException("not_found","话题不存在或无权访问",404);}
    private long number(Object value){try{return Long.parseLong(String.valueOf(value));}catch(NumberFormatException e){return 0;}}
    private String title(String body){return body.substring(0,body.offsetByCodePoints(0,Math.min(40,body.codePointCount(0,body.length()))));}
    private void audit(Actor actor,long id,String action){audit.append(actor,action,"conversation",id,Map.of());}
}
