package cn.servicehub.realtime;
import java.util.Map;
public record LiveEvent(String kind,long conversationId,Long seq,long generationEpoch,Long topicId,Long runId,Long executionVersion,Map<String,Object> payload){
 public LiveEvent{payload=Map.copyOf(payload);}
}
