package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import com.openai.client.*;
import com.openai.core.*;
import com.openai.models.*;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Official SDK SSE over Spring AI transport preserves optional provider usage without synthetic zeros. */
@Service
public class SpringAiModelGateway implements ModelGateway {
    private final cn.servicehub.common.RuntimeMetrics metrics;private final ServiceHubProperties properties;private final GlobalModelSlots slots;private final ModelBudget budgets;private final JsonMapper json;
    private volatile OpenAIClient client;
    public SpringAiModelGateway(ServiceHubProperties properties,GlobalModelSlots slots,ModelBudget budgets,JsonMapper json,cn.servicehub.common.RuntimeMetrics metrics){this.metrics=metrics;this.properties=properties;this.slots=slots;this.budgets=budgets;this.json=json;}
    private synchronized OpenAIClient client(){
        if(client==null){
            var p=properties.model();if(p.apiKey()==null||p.apiKey().isBlank())throw new BusinessException("model_unconfigured","未配置对话模型服务",503);
            var timeout=Duration.ofSeconds(Math.min(45,p.timeoutSeconds()));
            var transport=SpringAiOpenAiHttpClient.builder().timeout(timeout).interceptor(chain->{HttpCallCancellation.registerCurrent(chain.call());return chain.proceed(chain.request());}).build();
            client=new OpenAIClientImpl(ClientOptions.builder().httpClient(transport).baseUrl(p.baseUrl()).apiKey(p.apiKey()).timeout(timeout).maxRetries(0).responseValidation(false).build());
        }
        return client;
    }
    @Override public ModelReply stream(ModelRequest request,Consumer<ModelEvent> sink,CancellationGuard guard){
        return metrics.timed("model","chat",()->{
            var reply=streamOnce(request,sink,guard);metrics.usage("chat",reply.actualUsage()==null?null:reply.actualUsage().totalTokens());return reply;
        });
    }
    private ModelReply streamOnce(ModelRequest request,Consumer<ModelEvent> sink,CancellationGuard guard){
        if(!(guard instanceof JobCancellationGuard bound))throw new IllegalArgumentException("A server-bound job guard is required");
        if(json.writeValueAsString(request.messages()).length()+json.writeValueAsString(request.toolSchemas()).length()>24000||request.messages().isEmpty()||request.messages().size()>40)throw new BusinessException("model_input_too_large","本轮模型输入超出预算",422);
        guard.check();var snapshot=budgets.reserve(bound.jobLease(),"model");
        if(snapshot.runId()!=request.remainingBudget().runId())throw new ExecutionCancelledException("model_scope_changed");
        var cancellation=new HttpCallCancellation();
        try{
            return slots.withPermit(bound.executionClass(),snapshot.remaining(),()->cancellation.bound(()->{
                guard.check();var current=budgets.snapshot(bound.jobLease());
                var timeout=current.requestTimeout();var maximum=Duration.ofSeconds(Math.min(45,properties.model().timeoutSeconds()));
                if(timeout.compareTo(maximum)>0)timeout=maximum;
                return consume(request,sink,guard,current.maxOutputTokens(),timeout);
            }),guard,cancellation::cancel);
        }catch(ExecutionCancelledException|BusinessException e){throw e;}
        catch(com.openai.errors.OpenAIServiceException e){
            if(e.statusCode()!=429&&e.statusCode()<500)throw new BusinessException("model_rejected","模型服务拒绝了请求，请检查权限和输入",422);
            throw new BusinessException("model_unavailable","模型服务暂时不可用",503);
        }catch(com.openai.errors.OpenAIInvalidDataException e){
            if(!causedByIo(e))throw invalid();throw new BusinessException("model_unavailable","模型响应中断",503);
        }catch(RuntimeException e){throw new BusinessException("model_unavailable","模型服务暂时不可用",503);}
        finally{cancellation.cancel();}
    }
    private ModelReply consume(ModelRequest request,Consumer<ModelEvent> sink,CancellationGuard guard,int maxTokens,Duration timeout){
        var builder=ChatCompletionCreateParams.builder().model(properties.model().name()).maxTokens((long)maxTokens).n(1L)
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
        for(var message:request.messages())append(builder,message);
        for(var tool:request.toolSchemas()){
            Object raw=tool.get("parameters");if(!(raw instanceof Map<?,?> schema))throw invalid();
            var parameters=FunctionParameters.builder();schema.forEach((key,value)->parameters.putAdditionalProperty(String.valueOf(key),JsonValue.from(value)));
            builder.addFunctionTool(FunctionDefinition.builder().name(required(tool,"name")).description(required(tool,"description")).parameters(parameters.build()).build());
        }
        if(!request.toolSchemas().isEmpty())builder.parallelToolCalls(false).toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO);
        var pending=new TreeMap<Long,PendingTool>();var text=new StringBuilder();String finish=null;ActualUsage usage=null;
        guard.check();
        try(var response=client().chat().completions().createStreaming(builder.build(),RequestOptions.builder().timeout(timeout).responseValidation(false).build())){
            var iterator=response.stream().iterator();
            while(iterator.hasNext()){
                guard.check();var chunk=iterator.next();guard.check();
                if(chunk._usage().asKnown().isPresent()){usage=usage(chunk._usage().asKnown().orElseThrow());sink.accept(new ModelEvent(null,null,usage));}
                for(var choice:chunk.choices()){
                    if(choice.index()!=0)throw invalid();var delta=choice.delta();
                    if(delta.content().isPresent()){
                        String fragment=delta.content().orElseThrow();if(text.length()+fragment.length()>16000)throw new BusinessException("model_output_too_large","模型输出超出预算",422);
                        if(!fragment.isEmpty()){text.append(fragment);sink.accept(new ModelEvent(fragment,null,null));}
                    }
                    for(var tool:delta.toolCalls().orElseGet(List::of)){
                        if(tool.index()<0||tool.index()>=4)throw invalid();var item=pending.computeIfAbsent(tool.index(),ignored->new PendingTool());
                        tool.id().filter(id->!id.isEmpty()).ifPresent(id->item.id=consistent(item.id,id,128));
                        tool.function().ifPresent(function->{
                            function.name().filter(name->!name.isEmpty()).ifPresent(name->item.name=consistent(item.name,name,80));
                            function.arguments().ifPresent(fragment->{if(item.arguments.length()+fragment.length()>16000)throw invalid();item.arguments.append(fragment);});
                        });
                    }
                    if(choice.finishReason().isPresent()){
                        String value=choice.finishReason().orElseThrow().asString();if(finish!=null&&!finish.equals(value))throw invalid();finish=value;
                    }
                }
            }
        }
        guard.check();
        if(!Set.of("stop","tool_calls").contains(Objects.requireNonNullElse(finish,"")))throw new BusinessException("model_incomplete","模型响应未正常完成",422);
        // Some compatible providers end a complete tool turn with stop; the bounded,
        // consistent id/name and complete JSON validation below remain authoritative.
        if("tool_calls".equals(finish)&&pending.isEmpty())throw invalid();
        var calls=new ArrayList<ToolCall>();var ids=new HashSet<String>();
        for(var item:pending.values()){
            if(item.id==null||item.name==null||!ids.add(item.id))throw invalid();
            Map<String,Object> arguments=arguments(item.arguments.toString());var call=new ToolCall(item.id,item.name,arguments);calls.add(call);sink.accept(new ModelEvent(null,call,null));
        }
        return new ModelReply(text.toString(),calls,usage);
    }
    private void append(ChatCompletionCreateParams.Builder builder,Map<String,Object> message){
        String role=required(message,"role");String content=message.get("content") instanceof String s?s:"";
        switch(role){
            case "system"->builder.addSystemMessage(content);
            case "user"->builder.addUserMessage(content);
            case "assistant"->{
                var assistant=ChatCompletionAssistantMessageParam.builder().content(content);
                if(message.get("tool_calls") instanceof List<?> calls)for(Object raw:calls){
                    if(!(raw instanceof ToolCall call))throw invalid();
                    assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder().id(call.id()).function(ChatCompletionMessageFunctionToolCall.Function.builder().name(call.name()).arguments(json.writeValueAsString(call.arguments())).build()).build());
                }
                builder.addMessage(assistant.build());
            }
            case "tool"->builder.addMessage(ChatCompletionToolMessageParam.builder().toolCallId(required(message,"tool_call_id")).content(content).build());
            default->throw invalid();
        }
    }
    @SuppressWarnings("unchecked") private Map<String,Object> arguments(String text){
        try{var tree=json.readTree(text);if(!tree.isObject())throw invalid();return json.readValue(text,Map.class);}catch(BusinessException e){throw e;}catch(RuntimeException e){throw invalid();}
    }
    private ActualUsage usage(CompletionUsage value){
        Long prompt=value._promptTokens().asKnown().orElse(null),completion=value._completionTokens().asKnown().orElse(null),total=value._totalTokens().asKnown().orElse(null);
        if(prompt!=null&&prompt<0||completion!=null&&completion<0||total!=null&&total<0)throw invalid();return new ActualUsage(prompt,completion,total);
    }
    private String required(Map<String,Object> value,String name){if(!(value.get(name) instanceof String text)||text.isBlank())throw invalid();return text;}
    private String consistent(String old,String next,int limit){if(next.isBlank()||next.length()>limit||old!=null&&!old.equals(next))throw invalid();return next;}
    private boolean causedByIo(Throwable e){for(int i=0;e!=null&&i<10;i++,e=e.getCause())if(e instanceof java.io.IOException)return true;return false;}
    private static final class PendingTool{String id,name;final StringBuilder arguments=new StringBuilder();}
    private BusinessException invalid(){return new BusinessException("invalid_model_response","模型返回了无效协议内容",422);}
    @PreDestroy public synchronized void close(){if(client!=null)client.close();}
}
