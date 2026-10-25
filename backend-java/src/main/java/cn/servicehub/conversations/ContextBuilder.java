package cn.servicehub.conversations;
import cn.servicehub.common.Actor;
import cn.servicehub.config.ServiceHubProperties;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class ContextBuilder {
    public record ContextMessage(String role,String body,long messageId,List<Map<String,Object>> citations){}
    private final cn.servicehub.knowledge.CitationAuthorizer citationAuthorization;private final TopicService service;private final TopicMapper topics;private final ContextPolicy policy;private final ServiceHubProperties properties;private final JsonMapper json;
    public ContextBuilder(TopicService service,TopicMapper topics,ContextPolicy policy,ServiceHubProperties properties,JsonMapper json,cn.servicehub.knowledge.CitationAuthorizer citationAuthorization){this.citationAuthorization=citationAuthorization;this.service=service;this.topics=topics;this.policy=policy;this.properties=properties;this.json=json;}
    @SuppressWarnings("unchecked") public List<ContextMessage> build(Actor actor,ExecutionFence fence){
        service.requireReadable(actor,fence);var facts=policy.verifyReferences(actor,fence);
        int remaining=Math.max(0,properties.limits().contextCharacters()-json.writeValueAsString(facts).length());
        var result=new ArrayList<ContextMessage>();
        for(var row:topics.context(actor.tenantId(),fence.conversationId(),fence.topicId(),properties.limits().contextMessages())){
            String role="customer".equals(row.authorRole())?"user":"assistant";int cost=row.body().length()+row.citationsText().length()+role.length();
            if(cost>remaining)break;
            List<Map<String,Object>> citations=json.readValue(row.citationsText(),List.class);
            if(!citations.isEmpty()&&!citationAuthorization.historyReadable(actor,fence.conversationId(),citations))continue;
            result.add(new ContextMessage(role,row.body(),row.id(),List.copyOf(citations)));remaining-=cost;
        }
        service.requireReadable(actor,fence);Collections.reverse(result);return List.copyOf(result);
    }
}
