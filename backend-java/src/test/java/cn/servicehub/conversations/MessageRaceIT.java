package cn.servicehub.conversations;

import cn.servicehub.support.HttpIntegrationApp;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageRaceIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();HttpIntegrationApp.Login customer;
    @BeforeAll void start() throws Exception {
        app.start();String hash=app.db.queryForObject("SELECT password_hash FROM users WHERE id=2001",String.class);
        for(long id=4000;id<4020;id++)app.db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(?,1001,?,?,?,'agent')",id,"user"+id+"@example.com","Synthetic agent "+id,hash);
    }
    @AfterAll void stop(){app.close();}
    @BeforeEach void reset() throws Exception {app.resetAuth();customer=app.login(2003);}
    long create() throws Exception {var r=app.request("POST","/api/v1/conversations",Map.of(),customer);assertThat(r.statusCode()).isEqualTo(201);return app.body(r).path("id").asLong();}
    String path(long id){return "/api/v1/conversations/"+id;}
    @FunctionalInterface interface Send {HttpResponse<String> send(int index) throws Exception;}
    List<HttpResponse<String>> race(int count,Send send) throws Exception {
        var barrier=new CyclicBarrier(count);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<HttpResponse<String>>>();for(int i=0;i<count;i++){int n=i;futures.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return send.send(n);}));}
            var results=new ArrayList<HttpResponse<String>>();for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));return results;
        }
    }
    @Test void twentyRetriesCreateOneMessageAndJob() throws Exception {
        long id=create();var results=race(20,n->app.request("POST",path(id)+"/messages",Map.of("client_message_id","same-message","body","查订单"),customer));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(results.stream().map(r->app.body(r).path("id").asLong()).distinct()).hasSize(1);
        assertThat(results.stream().map(r->app.body(r).path("trigger_run_id").asLong()).distinct()).hasSize(1);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM jobs WHERE conversation_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM ai_runs WHERE conversation_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(app.db.queryForObject("SELECT generation_epoch FROM conversations WHERE id=?",Long.class,id)).isEqualTo(1);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM outbox_events o JOIN jobs j ON j.tenant_id=o.tenant_id AND j.id=o.job_id WHERE j.conversation_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void sameKeyWithDifferentContentsReturnsConflictWithoutOverwritingWinner() throws Exception {
        long id=create();var results=race(20,n->app.request("POST",path(id)+"/messages",Map.of("client_message_id","content-race","body",n%2==0?"A":"B"),customer));
        assertThat(results.stream().filter(r->r.statusCode()==200)).hasSize(10);assertThat(results.stream().filter(r->r.statusCode()==409)).hasSize(10);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM messages WHERE conversation_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(app.db.queryForObject("SELECT generation_epoch FROM conversations WHERE id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void oldRetryReturnsImmutableReferencesAndDoesNotAdvanceCurrentEpoch() throws Exception {
        long id=create();var first=Map.of("client_message_id","first","body","最初问题");var a=app.request("POST",path(id)+"/messages",first,customer);
        assertThat(a.statusCode()).isEqualTo(200);var b=app.request("POST",path(id)+"/messages",Map.of("client_message_id","second","body","新的问题"),customer);assertThat(b.statusCode()).isEqualTo(200);
        long before=app.db.queryForObject("SELECT generation_epoch FROM conversations WHERE id=?",Long.class,id);
        var retry=app.request("POST",path(id)+"/messages",first,customer);assertThat(retry.statusCode()).isEqualTo(200);
        for(String field:List.of("id","accepted_generation_epoch","trigger_run_id","trigger_job_id"))assertThat(app.body(retry).path(field)).isEqualTo(app.body(a).path(field));
        assertThat(app.db.queryForObject("SELECT generation_epoch FROM conversations WHERE id=?",Long.class,id)).isEqualTo(before);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM jobs WHERE conversation_id=?",Integer.class,id)).isEqualTo(2);
    }
    @Test void twentyDifferentAgentsHaveOneClaimWinner() throws Exception {
        long id=create();var handoff=app.request("POST",path(id)+"/handoff",null,customer);assertThat(handoff.statusCode()).isEqualTo(200);
        long version=app.body(handoff).path("version").asLong();var agents=new ArrayList<HttpIntegrationApp.Login>();for(long user=4000;user<4020;user++)agents.add(app.login(user));
        var results=race(20,n->app.request("POST",path(id)+"/claim",Map.of("version",version),agents.get(n)));
        assertThat(results.stream().filter(r->r.statusCode()==200)).hasSize(1);assertThat(results.stream().filter(r->r.statusCode()==409)).hasSize(19);
        long winner=app.db.queryForObject("SELECT assigned_agent_id FROM conversations WHERE id=?",Long.class,id);
        assertThat(winner).isBetween(4000L,4019L);
    }
    @Test void concurrentMessagesHaveContiguousDurableEventSequences() throws Exception {
        long id=create();var results=race(20,n->app.request("POST",path(id)+"/messages",Map.of("client_message_id","distinct-"+n,"body","第"+n+"条"),customer));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(app.db.queryForList("SELECT seq FROM conversation_events WHERE conversation_id=? ORDER BY seq",Long.class,id)).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1,21).boxed().toList());
        assertThat(app.db.queryForObject("SELECT generation_epoch FROM conversations WHERE id=?",Long.class,id)).isEqualTo(20);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM jobs WHERE conversation_id=? AND state='QUEUED'",Integer.class,id)).isEqualTo(1);
    }
    @Test void handoffRaceCannotLeaveRunnableAiTask() throws Exception {
        long id=create();var results=race(2,n->n==0?app.request("POST",path(id)+"/handoff",null,customer):app.request("POST",path(id)+"/messages",Map.of("client_message_id","handoff-race","body","需要人工"),customer));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(app.db.queryForObject("SELECT status FROM conversations WHERE id=?",String.class,id)).isEqualTo("WAITING_HUMAN");
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM jobs WHERE conversation_id=? AND state IN('QUEUED','RUNNING','RETRY_WAIT')",Integer.class,id)).isZero();
    }
}
