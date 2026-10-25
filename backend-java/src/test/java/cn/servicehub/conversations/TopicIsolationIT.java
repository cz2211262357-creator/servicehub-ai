package cn.servicehub.conversations;
import cn.servicehub.ai.*;
import cn.servicehub.jobs.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TopicIsolationIT {
    final TopicFixture f=new TopicFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    @Test void returningToSameTopicDoesNotMakeOldEpochValid() throws Exception{
        long a=f.topic();var first=f.send("a1","查询订单 ORD-A-001");var old=f.claim(first);var oldFence=f.resolved(old,first);f.finish(old);
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(200);
        var b=f.send("b","查询订单 ORD-A-002");var bLease=f.claim(b);f.resolved(bLease,b);f.finish(bLease);
        assertThat(f.switchTo("RESUME",a,null,f.epoch()).statusCode()).isEqualTo(200);
        var next=f.send("a2","那运费呢");var current=f.claim(next);var currentFence=f.resolved(current,next);
        assertThat(f.topic()).isEqualTo(a);
        assertThatThrownBy(()->f.app.context.getBean(JobCoordinator.class).finish(old,"SUCCEEDED",Map.of())).isInstanceOf(ExecutionCancelledException.class);
        var bodies=f.context(current,currentFence).stream().map(m->String.valueOf(TopicFixture.get(m,"body"))).toList();
        assertThat(bodies).anyMatch(s->s.contains("ORD-A-001"));assertThat(bodies).noneMatch(s->s.contains("ORD-A-002"));
        assertThat(TopicFixture.get(currentFence,"generationEpoch")).isNotEqualTo(TopicFixture.get(oldFence,"generationEpoch"));
    }
    @Test void lateRouteCannotReplaceLatestTopic() throws Exception{
        var m=f.send("pending","接着那个做，还是当前这个？");var old=f.claim(m);
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(200);long latest=f.topic(),epoch=f.epoch();
        assertThat(f.resolve(old,m.path("id").asLong(),"NEW_TOPIC",null,false)).isEmpty();
        assertThat(f.topic()).isEqualTo(latest);assertThat(f.epoch()).isEqualTo(epoch);
    }
    @Test void ambiguousRunWaitsAndChoiceCreatesNewRunWithoutMutatingOriginalTrigger() throws Exception{
        var m=f.send("ambiguous","接着那个做，还是当前这个？");var lease=f.claim(m);
        assertThat(f.resolve(lease,m.path("id").asLong(),"AMBIGUOUS",null,true)).isEmpty();
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,lease.jobId())).isEqualTo("WAITING_INPUT");
        var before=f.app.db.queryForMap("SELECT topic_id,routing_state,trigger_run_id FROM messages WHERE id=?",m.path("id").asLong());
        assertThat(before.get("topic_id")).isNull();assertThat(before.get("routing_state")).isEqualTo("PENDING");
        var response=f.switchTo("NEW",null,m.path("id").asLong(),f.epoch());assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        long nextRun=f.app.body(response).path("ai_reply").path("run_id").asLong();assertThat(nextRun).isPositive().isNotEqualTo(m.path("trigger_run_id").asLong());
        var row=f.app.db.queryForMap("SELECT topic_id,routing_state,context_status,trigger_run_id,context_run_id FROM messages WHERE id=?",m.path("id").asLong());
        assertThat(row.get("trigger_run_id")).isEqualTo(before.get("trigger_run_id"));assertThat(((Number)row.get("context_run_id")).longValue()).isEqualTo(nextRun);
        assertThat(row.get("routing_state")).isEqualTo("RESOLVED");assertThat(row.get("context_status")).isEqualTo("ELIGIBLE");
        assertThat(f.switchTo("NEW",null,m.path("id").asLong(),f.epoch()).statusCode()).isEqualTo(409);
    }
    @Test void onlyLatestPendingMessageCanBeResolved() throws Exception{
        var old=f.send("old-pending","接着那个做，还是当前这个？");f.send("latest-pending","上一个还是当前这个？");long epoch=f.epoch(),topic=f.topic();
        assertThat(f.switchTo("NEW",null,old.path("id").asLong(),epoch).statusCode()).isEqualTo(409);assertThat(f.epoch()).isEqualTo(epoch);assertThat(f.topic()).isEqualTo(topic);
    }
    @Test void crossTenantOrOtherConversationTopicCannotBeResumed() throws Exception{
        assertThat(f.app.request("GET",f.path()+"/topics",null,f.customer).statusCode()).isEqualTo(200);
        var other=f.app.login(5001);var room=f.app.request("POST","/api/v1/conversations",Map.of(),other);assertThat(room.statusCode()).isEqualTo(201);
        long foreign=f.app.body(room).path("active_topic_id").asLong();long epoch=f.epoch();
        assertThat(f.switchTo("RESUME",foreign,null,epoch).statusCode()).isEqualTo(404);assertThat(f.epoch()).isEqualTo(epoch);
    }
    @Test void revisionClearsOldObjectAndRequiresNewNumberInsteadOfFallingBack() throws Exception{
        var first=f.send("order-a","查询订单 ORD-A-001");var firstLease=f.claim(first);f.resolved(firstLease,first);f.finish(firstLease);
        f.app.db.update("UPDATE conversation_topics SET facts=JSON_OBJECT('order_number','ORD-A-001','source_message_id',?,'order_version',1) WHERE id=?",first.path("id").asLong(),f.topic());
        var revised=f.send("revise","刚才那个订单不用查了，查另一个订单");var lease=f.claim(revised);
        var resolved=f.resolve(lease,revised.path("id").asLong(),"REVISE",null,true);assertThat(resolved).isPresent();
        assertThat(f.app.db.queryForObject("SELECT JSON_LENGTH(facts) FROM conversation_topics WHERE id=?",Integer.class,f.topic())).isZero();
        var facts=TopicFixture.call(f.app.requiredBean("cn.servicehub.conversations.ContextPolicy"),"verifyReferences",new Class<?>[]{cn.servicehub.common.Actor.class,f.type("cn.servicehub.conversations.ExecutionFence")},lease.actor(),resolved.orElseThrow());
        assertThat((Collection<?>)TopicFixture.get(facts,"orderNumbers")).isEmpty();assertThat(TopicFixture.get(facts,"requiresClarification")).isEqualTo(true);
        assertThat(f.context(lease,resolved.orElseThrow()).stream().map(m->String.valueOf(TopicFixture.get(m,"body")))).noneMatch(s->s.contains("ORD-A-001"));
    }
    @Test void contextIncludesOnlyCompleteAuthorizedEligibleMessagesAndIsBounded() throws Exception{
        long topic=f.topic();var success=f.send("complete-source","查询政策");var first=f.claim(success);f.resolved(first,success);f.finish(first);
        insert("complete-answer","ai",success.path("trigger_run_id").asLong(),topic,"ELIGIBLE");
        var failure=f.send("failed-source","查询物流");var failed=f.claim(failure);f.resolved(failed,failure);f.app.context.getBean(JobCoordinator.class).finish(failed,"FAILED",Map.of());
        insert("failed-answer","ai",failure.path("trigger_run_id").asLong(),topic,"ELIGIBLE");
        insert("partial-answer","ai",null,topic,"ELIGIBLE");insert("excluded-instruction","customer",null,topic,"EXCLUDED");
        var question=f.send("current","那运费呢");var lease=f.claim(question);var fence=f.resolved(lease,question);
        var bodies=f.context(lease,fence).stream().map(m->String.valueOf(TopicFixture.get(m,"body"))).toList();
        assertThat(bodies).contains("complete-answer").doesNotContain("failed-answer","partial-answer","excluded-instruction");
        for(int i=0;i<30;i++)insert("synthetic-"+i+"x".repeat(900),"agent",null,topic,"ELIGIBLE");
        var limited=f.context(lease,fence);assertThat(limited).hasSizeLessThanOrEqualTo(20);
        assertThat(limited.stream().mapToInt(m->String.valueOf(TopicFixture.get(m,"body")).length()).sum()).isLessThanOrEqualTo(16000);
    }
    @Test void databaseGuardRejectsCancelledRunWithoutRedisNotification() throws Exception{
        var message=f.send("guard","查询退货政策");var lease=f.claim(message);var fence=f.resolved(lease,message);
        var guard=TopicFixture.call(f.app.requiredBean("cn.servicehub.ai.CancellationGuardFactory"),"forRun",new Class<?>[]{f.type("cn.servicehub.conversations.ExecutionFence"),JobLease.class},fence,lease);
        TopicFixture.call(guard,"check",new Class<?>[0]);
        // A real SQL mutation reproduces persistent cancellation with no pub/sub notification.
        f.app.db.update("UPDATE jobs SET state='CANCELLED',owner=NULL,lease_expires_at=NULL WHERE id=?",lease.jobId());
        assertThatThrownBy(()->TopicFixture.call(guard,"check",new Class<?>[0])).isInstanceOf(ExecutionCancelledException.class);
    }
    @Test void waitingInputExpiresWithoutReclaimOrImplicitConsent() throws Exception {
        var message=f.send("expires","接着那个做，还是当前这个？");var lease=f.claim(message);
        assertThat(f.resolve(lease,message.path("id").asLong(),"AMBIGUOUS",null,true)).isEmpty();
        f.app.db.update("UPDATE ai_runs SET input_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_id=?",lease.jobId());
        f.app.context.getBean(JobRecoveryScheduler.class).recoverOnce();
        assertThat(f.app.db.queryForObject("SELECT state FROM ai_runs WHERE job_id=?",String.class,lease.jobId())).isEqualTo("EXPIRED");
        assertThat(f.app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,lease.jobId())).isEqualTo("FAILED");
        assertThat(f.switchTo("NEW",null,message.path("id").asLong(),f.epoch()).statusCode()).isEqualTo(409);
    }
    @Test void explicitCrossTopicOrderIsReauthorizedAndNoOtherObjectIsInherited() throws Exception {
        var original=f.send("original-order","查询订单 ORD-A-001");var old=f.claim(original);f.resolved(old,original);f.finish(old);
        f.app.db.update("UPDATE conversation_topics SET facts=JSON_OBJECT('order_number','ORD-A-001','source_message_id',?) WHERE id=?",original.path("id").asLong(),f.topic());
        assertThat(f.switchTo("NEW",null,null,f.epoch()).statusCode()).isEqualTo(200);
        var explicit=f.send("explicit-new-order","查询订单 ORD-A-002");var lease=f.claim(explicit);var fence=f.resolved(lease,explicit);
        var policy=f.app.requiredBean("cn.servicehub.conversations.ContextPolicy");
        var types=new Class<?>[]{cn.servicehub.common.Actor.class,f.type("cn.servicehub.conversations.ExecutionFence")};
        var facts=TopicFixture.call(policy,"verifyReferences",types,lease.actor(),fence);
        assertThat(TopicFixture.get(facts,"orderNumbers")).isEqualTo(Set.of("ORD-A-002"));
        f.app.db.update("UPDATE orders SET customer_id=2002 WHERE id=6002");
        assertThatThrownBy(()->TopicFixture.call(policy,"verifyReferences",types,lease.actor(),fence)).isInstanceOf(cn.servicehub.common.BusinessException.class);
    }
    void insert(String body,String role,Long run,long topic,String status){
        f.app.db.update("INSERT INTO messages(tenant_id,conversation_id,sender_id,author_role,ai_run_id,body,citations,topic_id,routing_state,context_status) VALUES(1001,?,NULL,?,?,?,JSON_ARRAY(),?,'RESOLVED',?)",f.room,role,run,body,topic,status);
    }
}
