package cn.servicehub.ai;
import cn.servicehub.jobs.JobLease;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentCancellationIT {
    final ProviderFixture p=new ProviderFixture();
    @BeforeAll void start() throws Exception{p.start();}
    @BeforeEach void reset() throws Exception{p.reset();}
    @AfterAll void close(){p.close();}
    Object handler(){return p.f.app.requiredBean("cn.servicehub.ai.AIService");}
    void run(){AgentFixture.call(handler(),"handle",new Class<?>[]{JobLease.class},p.f.lease);}
    void stop(String action) throws Exception{
        long room=p.f.room;
        if(action.equals("message")){
            assertThat(p.f.app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","换个问题，先不查了"),p.f.customer).statusCode()).isEqualTo(200);return;
        }
        var state=p.f.app.body(p.f.app.request("GET","/api/v1/conversations/"+room,null,p.f.customer));var body=new LinkedHashMap<String,Object>();body.put("expected_generation_epoch",state.path("generation_epoch").asLong());
        String endpoint=action.equals("switch")?"topics/switch":"interrupt";if(action.equals("switch"))body.put("mode","NEW");
        assertThat(p.f.app.request("POST","/api/v1/conversations/"+room+"/"+endpoint,body,p.f.customer).statusCode()).isEqualTo(200);
    }
    @Test void interruptBeforeToolPreventsCallAndCommit() throws Exception{cancelDuringStream("interrupt");}
    @Test void secondMessagePreventsOldToolAndReply() throws Exception{cancelDuringStream("message");}
    @Test void topicSwitchPreventsOldToolAndReply() throws Exception{cancelDuringStream("switch");}
    void cancelDuringStream(String action) throws Exception{
        handler();p.mode="tool";p.entered=new CountDownLatch(1);p.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(this::run);
            try{
                assertThat(p.entered.await(3,TimeUnit.SECONDS)).isTrue();stop(action);p.release.countDown();
                try{pending.get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){}
                assertThat(p.count).hasValue(1);
                assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=?",Integer.class,p.f.fence.runId())).isZero();
                assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",Integer.class,p.f.room)).isZero();
                assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isZero();
            }finally{p.release.countDown();}
        }
    }
    @Test void completeToolRoundReturnsOneAnswerAndKeepsSqlUnlocked() throws Exception{
        handler();p.mode="tool";p.entered=new CountDownLatch(1);p.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()){
            var pending=pool.submit(this::run);
            try{
                assertThat(p.entered.await(3,TimeUnit.SECONDS)).isTrue();
                // A separate HTTP write to this conversation must finish while the provider is blocked.
                var write=CompletableFuture.supplyAsync(()->p.f.app.db.update("UPDATE conversations SET title=title WHERE id=?",p.f.room));assertThat(write.get(2,TimeUnit.SECONDS)).isEqualTo(1);
                p.release.countDown();pending.get(5,TimeUnit.SECONDS);
                assertThat(p.count).hasValue(2);
                var registry=p.f.app.context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                assertThat(registry.get("servicehub.stage.duration").tags("stage","tool","kind","lookup_order","outcome","success").timer().count()).isEqualTo(1);
                assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",Integer.class,p.f.room)).isEqualTo(1);
                assertThat(p.f.app.db.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=?",Integer.class,p.f.fence.runId())).isEqualTo(1);
                assertThat(p.f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,p.f.lease.jobId())).isEqualTo("SUCCEEDED");
            }finally{p.release.countDown();}
        }
    }
}
