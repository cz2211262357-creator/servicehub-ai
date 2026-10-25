package cn.servicehub.ai;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.common.*;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import cn.servicehub.knowledge.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
@Service("ai_reply")
public class AIService implements JobHandler {
    private final cn.servicehub.operations.AuditService audit;private final AuthorizationService authorization;private final ConversationService conversations;private final TopicService topics;private final TopicRouter routing;private final JobCoordinator coordinator;
    private final CitationAuthorizer citations;private final KnowledgeService knowledge;private final SupportAgent agent;private final AIMapper ai;private final EventStore events;private final JsonMapper json;private final ServiceHubProperties properties;private final TransactionTemplate transactions;
    public AIService(AuthorizationService authorization,ConversationService conversations,TopicService topics,TopicRouter routing,JobCoordinator coordinator,CitationAuthorizer citations,KnowledgeService knowledge,SupportAgent agent,AIMapper ai,EventStore events,JsonMapper json,ServiceHubProperties properties,PlatformTransactionManager manager,cn.servicehub.operations.AuditService audit){this.audit=audit;
        this.authorization=authorization;this.conversations=conversations;this.topics=topics;this.routing=routing;this.coordinator=coordinator;this.citations=citations;this.knowledge=knowledge;this.agent=agent;this.ai=ai;this.events=events;this.json=json;this.properties=properties;transactions=new TransactionTemplate(manager);
    }
    @Override public void handle(JobLease lease){
        var command=coordinator.load(lease);long conversation=Long.parseLong(String.valueOf(command.payload().get("conversation_id"))),message=Long.parseLong(String.valueOf(command.payload().get("message_id")));
        var decision=routing.routeForJob(lease);coordinator.requireValid(lease);
        var resolved=topics.resolve(lease,message,decision);if(resolved.isEmpty())return;
        var fence=resolved.orElseThrow();var result=agent.execute(lease.actor(),lease,fence);
        if(!commit(lease,fence,result))coordinator.fail(lease,"result_invalidated",false);
    }
    public boolean commit(JobLease lease,ExecutionFence fence,AgentResult result){
        try{return Boolean.TRUE.equals(transactions.execute(status->{
            var actor=lease.actor();authorization.requireForWrite(actor);conversations.lock(actor,fence.conversationId());citations.requireReadable(actor,fence.conversationId(),result.evidence());
            topics.requireCurrentForWrite(actor,lease,fence);
            if(result.body()==null||result.body().isBlank()||result.body().length()>16000)throw new BusinessException("invalid_model_reply","模型回答为空或超出预算",422);
            var wire=new ArrayList<Map<String,Object>>();
            for(var e:result.evidence().stream().sorted(Comparator.comparingDouble(Evidence::score).reversed().thenComparingLong(Evidence::chunkId)).limit(6).toList()){
                var document=knowledge.get(actor,e.documentId());
                wire.add(Map.of("chunk_id",e.chunkId(),"document_id",e.documentId(),"version_id",e.versionId(),"page",e.page(),"text",e.excerpt(),"score",e.score(),"title",document.title()));
            }
            if(result.draftId()!=null&&ai.validDraft(actor.tenantId(),actor.userId(),fence.runId(),fence.topicId(),fence.generationEpoch(),lease.budgetRevision(),result.draftId())!=1)throw new ExecutionCancelledException("draft_scope_changed");
            var row=new LinkedHashMap<String,Object>();row.put("tenantId",actor.tenantId());row.put("conversationId",fence.conversationId());row.put("runId",fence.runId());row.put("topicId",fence.topicId());row.put("body",result.body());row.put("citations",json.writeValueAsString(wire));row.put("draftId",result.draftId());ai.insertAnswer(row);
            if(ai.result(actor.tenantId(),fence.runId(),properties.model().name(),result.actualUsage()==null?null:json.writeValueAsString(result.actualUsage()))!=1)throw new ExecutionCancelledException("run_changed");
            coordinator.finish(lease,"SUCCEEDED",Map.of("message_id",row.get("id"),"tools",result.toolTrace()));
            var message=conversations.message(actor,fence.conversationId(),((Number)row.get("id")).longValue());events.append(actor.tenantId(),fence.conversationId(),"message.created",Map.of("message",message));
            audit.append(actor,"ai.committed","run",fence.runId(),Map.of());return true;
        }));}catch(ExecutionCancelledException e){return false;}
        catch(BusinessException e){if(e.status()==401||e.status()==403||e.status()==404||e.code().equals("citation_unavailable"))return false;throw e;}
    }
}
