package cn.servicehub.ai;
import java.util.*;
import java.util.function.Consumer;
public interface ModelGateway {
    record ModelRequest(List<Map<String,Object>> messages,List<Map<String,Object>> toolSchemas,ModelBudget.Snapshot remainingBudget){
        public ModelRequest{messages=messages.stream().map(Map::copyOf).toList();toolSchemas=toolSchemas.stream().map(Map::copyOf).toList();Objects.requireNonNull(remainingBudget);}
    }
    record ToolCall(String id,String name,Map<String,Object> arguments){public ToolCall{arguments=Map.copyOf(arguments);}}
    record ActualUsage(Long promptTokens,Long completionTokens,Long totalTokens){}
    record ModelEvent(String delta,ToolCall toolCall,ActualUsage usage){}
    record ModelReply(String text,List<ToolCall> toolCalls,ActualUsage actualUsage){public ModelReply{toolCalls=List.copyOf(toolCalls);}}
    ModelReply stream(ModelRequest request,Consumer<ModelEvent> sink,CancellationGuard guard);
}
