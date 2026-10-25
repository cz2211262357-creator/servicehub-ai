package cn.servicehub.ai;
import cn.servicehub.knowledge.Evidence;
import java.util.*;
public record AgentResult(String body,List<Evidence> evidence,Long draftId,List<Map<String,Object>> toolTrace,ModelGateway.ActualUsage actualUsage){
    public AgentResult{evidence=List.copyOf(evidence);toolTrace=toolTrace.stream().map(Map::copyOf).toList();}
}
