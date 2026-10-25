package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.ExecutionClass;
import cn.servicehub.knowledge.*;
import java.time.*;
import java.util.*;
import org.springframework.ai.embedding.*;
import org.springframework.ai.openai.*;
import org.springframework.stereotype.Service;

@Service
public class SpringAiEmbeddingGateway implements EmbeddingGateway {
    private final cn.servicehub.common.RuntimeMetrics metrics;private final ServiceHubProperties properties;private final GlobalModelSlots slots;private final ModelBudget budgets;private final cn.servicehub.jobs.JobCoordinator coordinator;
    private volatile BailianEmbeddingModel model;private volatile OpenAiEmbeddingOptions defaults;
    public SpringAiEmbeddingGateway(ServiceHubProperties properties,GlobalModelSlots slots,ModelBudget budgets,cn.servicehub.jobs.JobCoordinator coordinator,cn.servicehub.common.RuntimeMetrics metrics){this.metrics=metrics;this.properties=properties;this.slots=slots;this.budgets=budgets;this.coordinator=coordinator;}
    private synchronized BailianEmbeddingModel model(){
        if(model==null){
            var p=properties.model();
            if(p.embeddingApiKey()==null||p.embeddingApiKey().isBlank())throw new BusinessException("embedding_unconfigured","未配置向量模型服务",503);
            defaults=OpenAiEmbeddingOptions.builder().baseUrl(p.embeddingBaseUrl()).apiKey(p.embeddingApiKey()).model(p.embeddingName())
                .dimensions(p.embeddingDimensions()).encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT)
                .timeout(Duration.ofSeconds(p.timeoutSeconds())).maxRetries(0).build();
            model=new BailianEmbeddingModel(defaults);
        }
        return model;
    }
    @Override public List<float[]> embed(List<String> texts,CancellationGuard guard){
        return metrics.timed("model","embedding",()->embedOnce(texts,guard));
    }
    private List<float[]> embedOnce(List<String> texts,CancellationGuard guard){
        if(texts.isEmpty()||texts.size()>10||texts.stream().anyMatch(s->s==null||s.isBlank()))throw new IllegalArgumentException("Embedding batch must contain 1-10 nonempty texts");
        if(!(guard instanceof JobCancellationGuard deadline))throw new IllegalArgumentException("A persisted deadline is required");
        guard.check();var client=model();
        if(deadline.executionClass()==ExecutionClass.INTERACTIVE)budgets.reserve(deadline.jobLease(),"model");
        var cancellation=new HttpCallCancellation();
        try{
            return slots.withPermit(deadline.executionClass(),remaining(deadline),()->cancellation.bound(()->{
                guard.check();
                var options=OpenAiEmbeddingOptions.builder().from(defaults).timeout(remaining(deadline)).build();
                var response=client.call(new EmbeddingRequest(List.copyOf(texts),options));
                var usage=response.getMetadata().getUsage();Integer tokens=usage==null?null:usage.getTotalTokens();metrics.usage("embedding",tokens==null?null:tokens.longValue());
                guard.check();var results=response.getResults();
                if(results.size()!=texts.size())throw invalid();
                var ordered=new float[texts.size()][];
                for(var result:results){
                    Integer i=result.getIndex();
                    if(i==null||i<0||i>=ordered.length||ordered[i]!=null)throw invalid();
                    float[] vector=result.getOutput();VectorIndex.validate(vector,properties.model().embeddingDimensions());ordered[i]=vector;
                }
                return Arrays.asList(ordered);
            }),guard,cancellation::cancel);
        }catch(ExecutionCancelledException|BusinessException e){throw e;}
        catch(com.openai.errors.OpenAIServiceException e){
            if(e.statusCode()!=429&&e.statusCode()<500)throw new BusinessException("embedding_rejected","向量模型服务拒绝了请求，请检查模型权限或输入",422);
            throw new BusinessException("embedding_unavailable","向量模型服务暂时不可用",503);
        }
        catch(com.openai.errors.OpenAIInvalidDataException e){
            if(!causedByIo(e))throw invalid();
            throw new BusinessException("embedding_unavailable","向量模型响应中断",503);
        }
        catch(RuntimeException e){var failure=new BusinessException("embedding_unavailable","向量模型服务暂时不可用",503);failure.initCause(e);throw failure;}
        finally{cancellation.cancel();}
    }
    private boolean causedByIo(Throwable error){for(int i=0;error!=null&&i<10;i++,error=error.getCause())if(error instanceof java.io.IOException)return true;return false;}
    private Duration remaining(DeadlineCancellationGuard guard){
        if(!(guard instanceof JobCancellationGuard bound))throw new IllegalArgumentException("A server job guard is required");
        Duration remaining=coordinator.remaining(bound.jobLease());
        if(remaining.isNegative()||remaining.isZero())throw new ExecutionCancelledException("deadline_exceeded");
        return remaining.compareTo(Duration.ofSeconds(properties.model().timeoutSeconds()))<0?remaining:Duration.ofSeconds(properties.model().timeoutSeconds());
    }
    @jakarta.annotation.PreDestroy public synchronized void close(){if(model!=null)model.close();}
    private BusinessException invalid(){return new BusinessException("invalid_embedding","向量模型返回了无效结果",422);}
}
