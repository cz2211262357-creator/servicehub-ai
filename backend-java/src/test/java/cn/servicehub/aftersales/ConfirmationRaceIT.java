package cn.servicehub.aftersales;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfirmationRaceIT {
    final AfterSaleFixture f=new AfterSaleFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @AfterAll void stop(){f.close();}
    @BeforeEach void reset() throws Exception {f.reset();}
    @FunctionalInterface interface Send {HttpResponse<String> send(int index) throws Exception;}
    List<HttpResponse<String>> race(Send send) throws Exception {
        var barrier=new CyclicBarrier(20);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var pending=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<20;i++){int n=i;pending.add(pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return send.send(n);}));}
            var results=new ArrayList<HttpResponse<String>>();for(var future:pending)results.add(future.get(20,TimeUnit.SECONDS));return results;
        }
    }
    @Test void twentyConfirmationsReturnOneTicket() throws Exception {
        long draft=f.draft(6001);var results=race(n->f.confirm(draft,"same-confirmation"));
        assertThat(results).allSatisfy(r->assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(results.stream().map(r->f.app.body(r).path("id").asLong()).distinct()).hasSize(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM ticket_events",Integer.class)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ticket.confirmed'",Integer.class)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT consumed FROM ticket_drafts WHERE id=?",Boolean.class,draft)).isTrue();
    }
    @Test void differentKeysCannotConsumeSameDraftTwice() throws Exception {
        long draft=f.draft(6001);var results=race(n->f.confirm(draft,"different-"+n));
        assertThat(results.stream().filter(r->r.statusCode()==200)).hasSize(1);assertThat(results.stream().filter(r->r.statusCode()==409)).hasSize(19);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isEqualTo(1);
    }
    @Test void sameKeyForDifferentDraftsCannotOverwriteOrConsumeLoser() throws Exception {
        long a=f.draft(6001),b=f.draft(6001);var results=race(n->f.confirm(n%2==0?a:b,"shared-key"));
        assertThat(results.stream().filter(r->r.statusCode()==200)).hasSize(10);assertThat(results.stream().filter(r->r.statusCode()==409)).hasSize(10);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM ticket_drafts WHERE consumed=1",Integer.class)).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isEqualTo(1);
    }
    @Test void topicChangeAndChatClosureDoNotCancelExplicitBusinessConfirmation() throws Exception {
        long draft=f.draft(6001);var created=f.app.request("POST","/api/v1/conversations",Map.of(),f.customer);assertThat(created.statusCode()).isEqualTo(201);
        long conversation=f.app.body(created).path("id").asLong();
        long epoch=f.app.body(created).path("generation_epoch").asLong();
        var switched=f.app.request("POST","/api/v1/conversations/"+conversation+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",epoch),f.customer);
        assertThat(switched.statusCode()).as(switched.body()).isEqualTo(200);
        assertThat(f.app.body(switched).path("active_topic_id")).isNotEqualTo(f.app.body(created).path("active_topic_id"));
        assertThat(f.app.request("POST","/api/v1/conversations/"+conversation+"/close",null,f.customer).statusCode()).isEqualTo(200);
        var result=f.confirm(draft,"explicit-after-switch");assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        long ticket=f.app.body(result).path("id").asLong();
        assertThat(f.app.db.queryForObject("SELECT status FROM after_sale_tickets WHERE id=?",String.class,ticket)).isEqualTo("SUBMITTED");
    }
}
