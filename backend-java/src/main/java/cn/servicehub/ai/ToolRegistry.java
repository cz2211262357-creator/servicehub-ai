package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import java.util.*;

/** Server identity and topic scope are never accepted from model arguments. */
@org.springframework.stereotype.Service
public class ToolRegistry {
    public record ToolResult(Object value,List<cn.servicehub.knowledge.Evidence> evidence,Long draftId){public ToolResult{evidence=List.copyOf(evidence);}}
    private final cn.servicehub.common.RuntimeMetrics metrics;private final ModelBudget budgets;private final cn.servicehub.conversations.ContextPolicy policy;private final cn.servicehub.knowledge.HybridRetriever retrieval;
    private final cn.servicehub.orders.OrderService orders;private final cn.servicehub.aftersales.AfterSaleService sales;private final tools.jackson.databind.json.JsonMapper json;
    public ToolRegistry(ModelBudget budgets,cn.servicehub.conversations.ContextPolicy policy,cn.servicehub.knowledge.HybridRetriever retrieval,cn.servicehub.orders.OrderService orders,cn.servicehub.aftersales.AfterSaleService sales,tools.jackson.databind.json.JsonMapper json,cn.servicehub.common.RuntimeMetrics metrics){this.metrics=metrics;
        this.budgets=budgets;this.policy=policy;this.retrieval=retrieval;this.orders=orders;this.sales=sales;this.json=json;
    }
    public ToolResult execute(cn.servicehub.common.Actor actor,cn.servicehub.conversations.ExecutionFence fence,String name,Map<String,Object> arguments,CancellationGuard guard){
        return metrics.timed("tool",name,()->executeOnce(actor,fence,name,arguments,guard));
    }
    private ToolResult executeOnce(cn.servicehub.common.Actor actor,cn.servicehub.conversations.ExecutionFence fence,String name,Map<String,Object> arguments,CancellationGuard guard){
        guard.check();var args=validate(name,arguments);
        if(!(guard instanceof JobCancellationGuard bound)||!bound.jobLease().actor().equals(actor))throw new ExecutionCancelledException("tool_actor_changed");
        var lease=bound.jobLease();var current=budgets.snapshot(lease);
        if(current.runId()!=fence.runId()||lease.executionVersion()!=fence.executionVersion())throw new ExecutionCancelledException("tool_scope_changed");
        var facts=policy.verifyReferences(actor,fence);
        if(!name.equals("search_policy")&&(facts.requiresClarification()||!facts.orderNumbers().contains(args.get("order_number"))))
            throw new BusinessException("tool_object_outside_context","请先在当前话题明确订单号",403);
        var reservation=budgets.reserve(lease,"tool");guard.check();budgets.startTool(lease,reservation,name,json.writeValueAsString(args));long started=System.nanoTime();
        try{
            ToolResult result;
            if(name.equals("search_policy")){var evidence=retrieval.search(actor,(String)args.get("query"),6,guard);result=new ToolResult(evidence,evidence,null);}
            else if(name.equals("lookup_order"))result=new ToolResult(orders.get(actor,(String)args.get("order_number")),List.of(),null);
            else{var draft=sales.prepareForRun(actor,fence,lease,(String)args.get("order_number"),(String)args.get("kind"),(String)args.get("reason"));result=new ToolResult(draft,List.of(),draft.id());}
            guard.check();budgets.finishTool(lease,reservation,"success",latency(started),null);return result;
        }catch(RuntimeException error){
            try{budgets.finishTool(lease,reservation,"failed",latency(started),error instanceof BusinessException b?b.code():"tool_failed");}catch(RuntimeException stale){}
            throw error;
        }
    }
    private int latency(long started){return (int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-started)/1000000);}

    public static List<Map<String,Object>> schemas(){
        return List.of(schema("search_policy","检索当前租户有效售后政策",Map.of("query",string(1,2000))),
            schema("lookup_order","查询本次话题明确引用且属于当前客户的订单",Map.of("order_number",string(1,80))),
            schema("prepare_after_sale","准备售后草稿，正式提交必须由客户确认",Map.of("order_number",string(1,80),"kind",Map.of("type","string","enum",List.of("RETURN","EXCHANGE","REPAIR")),"reason",string(2,1000))));
    }
    public static Map<String,Object> validate(String name,Map<String,Object> args){
        if(name==null||args==null)throw invalid();
        Set<String> required=switch(name){case "search_policy"->Set.of("query");case "lookup_order"->Set.of("order_number");case "prepare_after_sale"->Set.of("order_number","kind","reason");default->throw invalid();};
        if(!required.equals(args.keySet()))throw invalid();var result=new LinkedHashMap<String,Object>();
        for(var key:required){
            Object raw=args.get(key);if(!(raw instanceof String text))throw invalid();text=text.strip();int count=text.codePointCount(0,text.length());
            int min="reason".equals(key)?2:1,max="query".equals(key)?2000:"reason".equals(key)?1000:80;
            if(count<min||count>max)throw invalid();
            if("kind".equals(key)&&!Set.of("RETURN","EXCHANGE","REPAIR").contains(text))throw invalid();result.put(key,text);
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> schema(String name,String description,Map<String,?> properties){
        return Map.of("name",name,"description",description,"parameters",Map.of("type","object","properties",properties,"required",properties.keySet().stream().sorted().toList(),"additionalProperties",false));
    }
    private static Map<String,Object> string(int min,int max){return Map.of("type","string","minLength",min,"maxLength",max);}
    private static BusinessException invalid(){return new BusinessException("invalid_tool_call","工具名称或参数不被允许",422);}
}
