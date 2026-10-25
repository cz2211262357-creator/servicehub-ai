package cn.servicehub.conversations;
import java.util.Objects;
public record TopicDecision(Kind kind,Long topicId,boolean requiresClarification) {
    public enum Kind {CONTINUE,REVISE,NEW_TOPIC,RESUME,AMBIGUOUS}
    public TopicDecision {
        Objects.requireNonNull(kind);
        if(kind==Kind.RESUME&&(topicId==null||topicId<=0))throw new IllegalArgumentException("Resume needs a topic");
        if(kind!=Kind.RESUME&&topicId!=null)throw new IllegalArgumentException("Unexpected target topic");
    }
    public static TopicDecision of(String kind,Long topicId,boolean clarify){return new TopicDecision(Kind.valueOf(kind),topicId,clarify);}
}
