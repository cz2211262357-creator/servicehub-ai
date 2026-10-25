package cn.servicehub.conversations;
import cn.servicehub.jobs.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterruptionIT {
    final TopicFixture f=new TopicFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void persistentStopCancelsQueuedJobAndDuplicateDeliveryNeverCallsHandler() throws Exception{
        var m=f.send("stop","查一下订单");long epoch=f.epoch(),job=m.path("trigger_job_id").asLong();
        var response=f.app.request("POST",f.path()+"/interrupt",Map.of("expected_generation_epoch",epoch),f.customer);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(f.epoch()).isEqualTo(epoch+1);
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job)).isEqualTo("CANCELLED");
        var acked=new AtomicInteger();f.app.context.getBean(JobConsumer.class).accept(f.app.json.writeValueAsBytes(Map.of("job_id",job,"schema_version",1)),ExecutionClass.INTERACTIVE,acked::incrementAndGet,requeue->fail("Terminal message rejected"));
        assertThat(acked).hasValue(1);
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,job)).isZero();
    }
    @Test void repeatedOldStopReturnsReceiptWithoutCancellingNewRun() throws Exception{
        var old=f.send("first","查询物流");long target=f.epoch();
        var stop=f.app.request("POST",f.path()+"/interrupt",Map.of("expected_generation_epoch",target),f.customer);assertThat(stop.statusCode()).isEqualTo(200);
        var next=f.send("next","那运费呢");long current=f.epoch();
        var repeated=f.app.request("POST",f.path()+"/interrupt",Map.of("expected_generation_epoch",target),f.customer);
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(200);assertThat(f.app.body(repeated)).isEqualTo(f.app.body(stop));
        assertThat(f.epoch()).isEqualTo(current);
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,next.path("trigger_job_id").asLong())).isEqualTo("QUEUED");
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM conversation_interruptions WHERE conversation_id=?",Integer.class,f.room)).isEqualTo(1);
    }
    @Test void duplicateOldMessageDoesNotInterruptLatestRun() throws Exception{
        var first=f.send("same","查询物流");f.send("new","查询退货政策");long epoch=f.epoch();
        var duplicate=f.send("same"," 查询物流 ");
        for(var field:List.of("id","accepted_generation_epoch","trigger_run_id","trigger_job_id","body"))assertThat(duplicate.path(field)).isEqualTo(first.path(field));
        assertThat(duplicate.path("context_status").asString()).isEqualTo("SUPERSEDED");assertThat(f.epoch()).isEqualTo(epoch);
    }
    @Test void twoPagesSwitchingSameEpochHaveOneWinner() throws Exception{
        long epoch=f.epoch();var gate=new CyclicBarrier(2);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);return f.switchTo("NEW",null,null,epoch).statusCode();});
            var b=pool.submit(()->{gate.await(5,TimeUnit.SECONDS);return f.switchTo("NEW",null,null,epoch).statusCode();});
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(f.epoch()).isEqualTo(epoch+1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM conversation_topics WHERE conversation_id=?",Integer.class,f.room)).isEqualTo(2);
    }
    @Test void topicListIsBoundedAndSwitchWithoutPendingDoesNotExecuteOldTools() throws Exception{
        long before=f.app.db.queryForObject("SELECT COUNT(*) FROM jobs",Long.class);
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(200);
        for(int i=0;i<105;i++)f.app.db.update("INSERT INTO conversation_topics(tenant_id,conversation_id,title) VALUES(1001,?,'Synthetic history')",f.room);
        var list=f.app.request("GET",f.path()+"/topics",null,f.customer);assertThat(list.statusCode()).isEqualTo(200);
        assertThat(f.app.body(list).path("items").size()).isEqualTo(100);assertThat(f.app.body(list).path("active_topic_id").asLong()).isEqualTo(f.topic());
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM jobs",Long.class)).isEqualTo(before);
    }
    @Test void closedOrMalformedCommandsAreRejectedAndHumanStatusCannotBeBypassed() throws Exception{
        assertThat(f.app.request("POST",f.path()+"/interrupt",Map.of(),f.customer).statusCode()).isEqualTo(422);
        assertThat(f.switchTo("RESUME",null,null,f.epoch()).statusCode()).isEqualTo(422);
        assertThat(f.app.request("POST",f.path()+"/handoff",null,f.customer).statusCode()).isEqualTo(200);
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(200);assertThat(f.snapshot().path("status").asString()).isEqualTo("WAITING_HUMAN");
        assertThat(f.app.request("POST",f.path()+"/close",null,f.customer).statusCode()).isEqualTo(200);
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(409);
    }
}
