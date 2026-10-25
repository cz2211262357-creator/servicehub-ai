package cn.servicehub.ai;
import com.openai.client.*;
import com.openai.core.*;
import com.openai.models.embeddings.EmbeddingCreateParams;
import java.time.Duration;
import java.util.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/** Spring AI's OpenAI mapping requires prompt_tokens; Bailian may report total_tokens only. */
final class BailianEmbeddingModel implements EmbeddingModel,AutoCloseable {
    private final OpenAIClient client;private final OpenAiEmbeddingOptions defaults;
    BailianEmbeddingModel(OpenAiEmbeddingOptions defaults){
        this.defaults=defaults;
        var transport=SpringAiOpenAiHttpClient.builder().timeout(defaults.getTimeout()).interceptor(chain->{HttpCallCancellation.registerCurrent(chain.call());return chain.proceed(chain.request());}).build();
        client=new OpenAIClientImpl(ClientOptions.builder().httpClient(transport).baseUrl(defaults.getBaseUrl()).apiKey(defaults.getApiKey())
            .timeout(defaults.getTimeout()).maxRetries(0).responseValidation(false).build());
    }
    @Override public EmbeddingResponse call(EmbeddingRequest request){
        Duration timeout=request.getOptions() instanceof OpenAiEmbeddingOptions options&&options.getTimeout()!=null?options.getTimeout():defaults.getTimeout();
        var params=EmbeddingCreateParams.builder().model(defaults.getModel()).dimensions(defaults.getDimensions().longValue())
            .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT).inputOfArrayOfStrings(request.getInstructions()).build();
        var response=client.embeddings().create(params,RequestOptions.builder().timeout(timeout).responseValidation(false).build());
        var vectors=new ArrayList<Embedding>();
        for(var item:response.data()){
            var value=item.embeddingValue();if(!value.isFloats())throw new IllegalArgumentException("Expected float embedding");
            var values=value.asFloats();float[] vector=new float[values.size()];for(int i=0;i<vector.length;i++)vector[i]=values.get(i);
            vectors.add(new Embedding(vector,Math.toIntExact(item.index())));
        }
        var usage=response._usage().asKnown();
        Integer prompt=usage.flatMap(u->u._promptTokens().asKnown()).map(Math::toIntExact).orElse(null);
        Integer total=usage.flatMap(u->u._totalTokens().asKnown()).map(Math::toIntExact).orElse(null);
        return new EmbeddingResponse(vectors,new EmbeddingResponseMetadata(response.model(),new ReportedUsage(prompt,total,usage.orElse(null))));
    }
    @Override public float[] embed(Document document){return call(new EmbeddingRequest(List.of(Objects.requireNonNull(document.getText())),defaults)).getResult().getOutput();}
    @Override public int dimensions(){return defaults.getDimensions();}
    @Override public void close(){client.close();}
    private record ReportedUsage(Integer prompt,Integer total,Object nativeUsage) implements Usage {
        @Override public Integer getPromptTokens(){return prompt;}
        @Override public Integer getCompletionTokens(){return null;}
        @Override public Integer getTotalTokens(){return total;}
        @Override public Object getNativeUsage(){return nativeUsage;}
    }
}
