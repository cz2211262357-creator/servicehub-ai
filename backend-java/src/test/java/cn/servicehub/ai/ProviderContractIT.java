package cn.servicehub.ai;
import cn.servicehub.common.BusinessException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderContractIT {
    final ProviderFixture p=new ProviderFixture();
    @BeforeAll void start() throws Exception{p.start();}
    @BeforeEach void reset() throws Exception{p.reset();}
    @AfterAll void close(){p.close();}
    @Test void textDeltasAreCombinedButMissingUsageStaysUnknown(){
        var events=new ArrayList<Object>();var reply=p.stream(events::add);
        assertThat(AgentFixture.field(reply,"text")).isEqualTo("Synthetic provider text");assertThat(AgentFixture.field(reply,"actualUsage")).isNull();
        assertThat(p.count).hasValue(1);var request=p.requests.getFirst();
        assertThat(request.path("model").asString()).isEqualTo("qwen-plus");assertThat(request.path("max_tokens").asInt()).isEqualTo(1600);assertThat(request.path("stream").asBoolean()).isTrue();
        assertThat(events.stream().map(e->AgentFixture.field(e,"delta")).filter(Objects::nonNull).toList()).containsExactly("Synthetic ","provider text");
    }
    @Test void reportedZeroAndPartiallyReportedUsageAreNotInvented(){
        p.usageMode="zero";var zero=AgentFixture.field(p.stream(e->{}),"actualUsage");assertThat(zero).isNotNull();assertThat(((Number)AgentFixture.field(zero,"totalTokens")).longValue()).isZero();
        p.usageMode="total_only";var partial=AgentFixture.field(p.stream(e->{}),"actualUsage");assertThat(partial).isNotNull();
        assertThat(AgentFixture.field(partial,"promptTokens")).isNull();assertThat(AgentFixture.field(partial,"completionTokens")).isNull();assertThat(((Number)AgentFixture.field(partial,"totalTokens")).longValue()).isEqualTo(23);
    }
    @Test void fragmentedToolArgumentsProduceOneCompleteCall(){
        p.mode="tool";var reply=p.stream(e->{});var calls=(List<?>)AgentFixture.field(reply,"toolCalls");assertThat(calls).hasSize(1);
        assertThat(AgentFixture.field(calls.getFirst(),"name")).isEqualTo("lookup_order");assertThat(AgentFixture.field(calls.getFirst(),"arguments")).isEqualTo(Map.of("order_number","ORD-A-001"));
        assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=?",Integer.class,p.f.fence.runId())).isZero();
    }
    List<tools.jackson.databind.JsonNode> capturedStream()throws Exception{
        var data=p.f.app.json.readTree(getClass().getResourceAsStream("/fixtures/bailian-tool-stream.json"));var frames=new ArrayList<tools.jackson.databind.JsonNode>();data.forEach(frames::add);return frames;
    }
    @Test void realBailianEmptyContinuationIdsAndStopMetadataKeepOneCompleteToolCall()throws Exception{
        p.mode="replay";p.replayFrames=capturedStream();var events=new ArrayList<Object>();var reply=p.stream(events::add);var calls=(List<?>)AgentFixture.field(reply,"toolCalls");assertThat(calls).hasSize(1);
        assertThat(AgentFixture.field(calls.getFirst(),"id")).isEqualTo("call-bailian-fixture");assertThat(AgentFixture.field(calls.getFirst(),"name")).isEqualTo("lookup_order");
        assertThat(AgentFixture.field(calls.getFirst(),"arguments")).isEqualTo(Map.of("order_number","ORD-SYSTEM-1000000"));
        assertThat(((Number)AgentFixture.field(AgentFixture.field(reply,"actualUsage"),"totalTokens")).longValue()).isEqualTo(204);assertThat(p.count).hasValue(1);
        assertThat(events.stream().map(event->AgentFixture.field(event,"delta")).filter(Objects::nonNull).toList()).isEmpty();
    }
    @Test void capturedStreamStillRejectsChangingOrMissingIdentifiersAndBrokenJson()throws Exception{
        for(String variant:List.of("changed_id","empty_id","empty_name","broken_json","whitespace_id")){
            p.mode="replay";var frames=capturedStream();
            if(variant.equals("changed_id"))((tools.jackson.databind.node.ObjectNode)frames.get(1).at("/choices/0/delta/tool_calls/0")).put("id","other-call");
            for(var frame:frames)for(var choice:frame.path("choices"))for(var tool:choice.path("delta").path("tool_calls")){
                var object=(tools.jackson.databind.node.ObjectNode)tool;var function=(tools.jackson.databind.node.ObjectNode)tool.get("function");
                if(variant.equals("empty_id"))object.put("id","");
                if(variant.equals("whitespace_id"))object.put("id"," ");
                if(variant.equals("empty_name")&&function.has("name"))function.put("name","");
                if(variant.equals("broken_json")&&function.hasNonNull("arguments"))function.put("arguments","not-json");
            }
            p.replayFrames=frames;assertThatThrownBy(()->p.stream(event->{})).as(variant).isInstanceOf(BusinessException.class);
        }
    }
    @Test void sdkRetriesAreDisabledAndPermanentProviderErrorsAreIdentified(){
        p.status=503;assertThatThrownBy(()->p.stream(e->{})).isInstanceOf(BusinessException.class);assertThat(p.count).hasValue(1);
        p.status=401;assertThatThrownBy(()->p.stream(e->{})).isInstanceOf(BusinessException.class).satisfies(error->assertThat(((BusinessException)error).status()).isLessThan(500));
        assertThat(p.count).hasValue(2);
        assertThat(p.f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,p.f.lease.jobId())).isEqualTo(2);
    }
    @Test void prematureEndOfStreamCannotReturnAnApparentlyCompleteReply(){
        p.mode="incomplete";assertThatThrownBy(()->p.stream(e->{})).isInstanceOf(BusinessException.class);assertThat(p.count).hasValue(1);
    }
    @Test void queryEmbeddingAndChatShareTheSamePersistedSixRequestCap(){
        var embeddings=p.f.app.context.getBean(cn.servicehub.knowledge.EmbeddingGateway.class);
        for(int i=0;i<5;i++)embeddings.embed(List.of("售后政策"),p.f.guard());
        p.stream(e->{});
        assertThatThrownBy(()->embeddings.embed(List.of("第七次请求"),p.f.guard())).isInstanceOf(BusinessException.class);
        assertThat(p.embeddingCount).hasValue(5);assertThat(p.count).hasValue(1);
        assertThat(p.f.app.db.queryForObject("SELECT model_requests_reserved FROM ai_runs WHERE job_id=?",Integer.class,p.f.lease.jobId())).isEqualTo(6);
    }
    @Test void persistedRemainingDeadlineBoundsBlockedHttpCall() throws Exception{
        p.f.app.db.update("UPDATE jobs SET deadline_at=UTC_TIMESTAMP(6)+INTERVAL 2 SECOND WHERE id=?",p.f.lease.jobId());
        p.entered=new CountDownLatch(1);p.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var response=pool.submit(()->p.stream(e->{}));
            try{assertThat(p.entered.await(1,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->response.get(4,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);}
            finally{p.release.countDown();}
        }
        assertThat(p.count).hasValue(1);
    }

    @Test void actualMissingUsageResponseUpdatesUnknownMetricWithoutInventingTokens(){
        var registry=p.f.app.context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        var oldUnknown=registry.find("servicehub.model.usage").tags("kind","chat","status","unknown").counter();
        double unknown=oldUnknown==null?0:oldUnknown.count();
        var oldTokens=registry.find("servicehub.model.tokens").tags("kind","chat").counter();double tokens=oldTokens==null?0:oldTokens.count();
        p.stream(e->{});
        assertThat(registry.get("servicehub.model.usage").tags("kind","chat","status","unknown").counter().count()).isEqualTo(unknown+1);
        var current=registry.find("servicehub.model.tokens").tags("kind","chat").counter();assertThat(current==null?0:current.count()).isEqualTo(tokens);
        assertThat(registry.get("servicehub.stage.duration").tags("stage","model","kind","chat","outcome","success").timer().count()).isPositive();
    }
}
