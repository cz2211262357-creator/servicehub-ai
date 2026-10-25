package cn.servicehub.conversations;
import cn.servicehub.common.Actor;
import cn.servicehub.orders.OrderService;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class ContextPolicy {
    public record VerifiedFacts(Set<String> orderNumbers,boolean requiresClarification){
        public VerifiedFacts {orderNumbers=Collections.unmodifiableSet(new LinkedHashSet<>(orderNumbers));}
    }
    private final TopicService service;private final TopicMapper topics;private final ConversationMapper messages;private final OrderService orders;private final JsonMapper json;
    public ContextPolicy(TopicService service,TopicMapper topics,ConversationMapper messages,OrderService orders,JsonMapper json){this.service=service;this.topics=topics;this.messages=messages;this.orders=orders;this.json=json;}
    public VerifiedFacts verifyReferences(Actor actor,ExecutionFence fence){
        var run=service.requireReadable(actor,fence);var scope=json.readTree(run.scopeText());
        var source=messages.messageById(actor.tenantId(),scope.path("message_id").asLong());
        if(source==null||source.conversationId()!=fence.conversationId()||!Objects.equals(source.topicId(),fence.topicId())||!"RESOLVED".equals(source.routingState())||!"ELIGIBLE".equals(source.contextStatus()))
            throw new cn.servicehub.ai.ExecutionCancelledException("source_message_changed");
        var numbers=new LinkedHashSet<>(TopicRouter.explicitOrders(source.body()));boolean clarify=scope.path("requires_clarification").asBoolean(false);
        if(numbers.isEmpty()&&!clarify){
            // Resolve pronouns from the latest eligible customer reference in this topic.
            // The mapper excludes failed/cancelled/superseded turns; AI text and cached facts
            // cannot introduce a business object. The current ownership is checked below.
            for(var proof:topics.context(actor.tenantId(),fence.conversationId(),fence.topicId(),20)){
                if(proof.id()>=source.id()||!"customer".equals(proof.authorRole()))continue;
                var mentioned=TopicRouter.explicitOrders(proof.body());
                if(!mentioned.isEmpty()){numbers.addAll(mentioned);break;}
            }
        }
        // Always fetch current ownership and order state; no cached "authorized" conclusion is accepted.
        for(var number:numbers)orders.get(actor,number);
        service.requireReadable(actor,fence);return new VerifiedFacts(numbers,clarify);
    }
}
