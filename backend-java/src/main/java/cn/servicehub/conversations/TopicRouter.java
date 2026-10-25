package cn.servicehub.conversations;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.jobs.JobLease;
import cn.servicehub.common.Actor;
import cn.servicehub.common.BusinessException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
@Service
public class TopicRouter {
    public record Candidate(long id,String title){}
    public record ClassificationInput(Actor actor,long conversationId,long messageId,String body,String currentSummary,List<Candidate> candidates){}
    @FunctionalInterface public interface Classifier {TopicDecision classify(ClassificationInput input);default TopicDecision classify(ClassificationInput input,JobLease lease){return classify(input);}}
    private final AuthorizationService authorization;private final ConversationMapper conversations;private final TopicMapper topics;private final ObjectProvider<Classifier> classifiers;
    public TopicRouter(AuthorizationService authorization,ConversationMapper conversations,TopicMapper topics,ObjectProvider<Classifier> classifiers){this.authorization=authorization;this.conversations=conversations;this.topics=topics;this.classifiers=classifiers;}
    public TopicDecision routeForJob(JobLease lease){
        return route(lease.actor(),Long.parseLong(String.valueOf(lease.payload().get("conversation_id"))),Long.parseLong(String.valueOf(lease.payload().get("message_id"))),lease);
    }
    public TopicDecision route(Actor actor,long conversationId,long messageId){return route(actor,conversationId,messageId,null);}
    private TopicDecision route(Actor actor,long conversationId,long messageId,JobLease lease){
        authorization.requireCurrent(actor);var conversation=conversations.get(actor.tenantId(),conversationId);
        if(conversation==null||conversation.customerId()!=actor.userId())throw new BusinessException("not_found","会话不存在或无权访问",404);
        var message=conversations.messageById(actor.tenantId(),messageId);
        if(message==null||message.conversationId()!=conversationId)throw new BusinessException("not_found","消息不存在",404);
        // An explicit UI choice already resolved this source; do not classify it again.
        if("RESOLVED".equals(message.routingState())&&"ELIGIBLE".equals(message.contextStatus())
            &&Objects.equals(message.topicId(),conversation.activeTopicId()))return TopicDecision.of("CONTINUE",null,false);
        return decide(message.body(),text->{
            var classifier=classifiers.getIfAvailable();if(classifier==null)return null;
            var rows=topics.list(actor.tenantId(),conversationId);String summary=rows.stream().filter(t->Objects.equals(t.id(),conversation.activeTopicId())).map(TopicMapper.TopicRow::summary).filter(Objects::nonNull).findFirst().orElse("");
            var input=new ClassificationInput(actor,conversationId,messageId,text,summary,rows.stream().map(t->new Candidate(t.id(),t.title())).toList());
            return lease==null?classifier.classify(input):classifier.classify(input,lease);
        });
    }
    /** Pure rules can run in message acceptance; classification itself always runs outside row locks. */
    public static Optional<TopicDecision> quick(String body){
        String text=body.strip();
        if(text.startsWith("另开话题")||text.startsWith("换个话题")||text.startsWith("新话题"))return Optional.of(TopicDecision.of("NEW_TOPIC",null,false));
        var resume=Pattern.compile("^回到话题\\s*(\\d+)").matcher(text);
        if(resume.find()){try{return Optional.of(TopicDecision.of("RESUME",Long.parseLong(resume.group(1)),false));}catch(IllegalArgumentException e){return Optional.empty();}}
        if(text.contains("另一个订单")||text.contains("换订单")||text.contains("换个订单")||text.startsWith("改查"))return Optional.of(TopicDecision.of("REVISE",null,explicitOrders(text).isEmpty()));
        if(text.contains("接着那个")||text.contains("上一个还是")||text.contains("刚才那个和现在这个"))return Optional.empty();
        return Optional.of(TopicDecision.of("CONTINUE",null,false));
    }
    public static TopicDecision decide(String body,Function<String,TopicDecision> classifier){
        var deterministic=quick(body);if(deterministic.isPresent())return deterministic.orElseThrow();
        TopicDecision classified=classifier==null?null:classifier.apply(body);
        return classified==null?TopicDecision.of("AMBIGUOUS",null,true):classified;
    }
    public static Set<String> explicitOrders(String text){
        var result=new LinkedHashSet<String>();var matches=Pattern.compile("\\bORD-[A-Za-z0-9-]{1,64}\\b|订单(?:号)?\\s*[:：]?\\s*([A-Za-z0-9_-]{4,80})").matcher(text);
        while(matches.find())result.add(matches.group(1)==null?matches.group():matches.group(1));return Collections.unmodifiableSet(result);
    }
}
