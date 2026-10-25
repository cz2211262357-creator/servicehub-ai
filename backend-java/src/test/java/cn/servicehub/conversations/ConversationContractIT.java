package cn.servicehub.conversations;

import cn.servicehub.support.HttpIntegrationApp;
import java.util.Map;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationContractIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    HttpIntegrationApp.Login customer,agent,admin;
    @BeforeAll void start() throws Exception {app.start();}
    @AfterAll void stop(){app.close();}
    @BeforeEach void reset() throws Exception {app.resetAuth();customer=app.login(2003);agent=app.login(2002);admin=app.login(2001);}
    long create() throws Exception {
        var r=app.request("POST","/api/v1/conversations",Map.of(),customer);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);return app.body(r).path("id").asLong();
    }
    String path(long id){return "/api/v1/conversations/"+id;}
    @Test void createPreservesDefaultsAndCreatesOneDefaultTopic() throws Exception {
        var r=app.request("POST","/api/v1/conversations",Map.of(),customer);assertThat(r.statusCode()).isEqualTo(201);var dto=app.body(r);
        assertThat(dto.path("title").asString()).isEqualTo("售后咨询");assertThat(dto.path("status").asString()).isEqualTo("AI_ACTIVE");
        assertThat(dto.path("generation_epoch").asLong()).isZero();assertThat(dto.path("version").asLong()).isEqualTo(1);
        assertThat(dto.path("last_event_seq").asLong()).isEqualTo(1);assertThat(dto.path("active_topic_id").asLong()).isPositive();
        assertThat(app.request("POST","/api/v1/conversations",Map.of(),agent).statusCode()).isEqualTo(403);
        var nullable=new java.util.HashMap<String,Object>();nullable.put("title",null);
        assertThat(app.request("POST","/api/v1/conversations",nullable,customer).statusCode()).isEqualTo(422);
    }
    @Test void listHasCursorFiltersAndLiteralSearch() throws Exception {
        create();var literal=app.request("POST","/api/v1/conversations",Map.of("title","%_literal"),customer);assertThat(literal.statusCode()).isEqualTo(201);
        var r=app.request("GET","/api/v1/conversations?limit=1",null,customer);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(app.body(r).path("items").size()).isEqualTo(1);assertThat(app.body(r).path("next_cursor").asLong()).isPositive();
        var filtered=app.request("GET","/api/v1/conversations?search=%25_literal&status=AI_ACTIVE",null,customer);
        assertThat(filtered.statusCode()).isEqualTo(200);assertThat(app.body(filtered).path("items").size()).isEqualTo(1);
        assertThat(app.request("GET","/api/v1/conversations?status=invalid",null,customer).statusCode()).isEqualTo(422);
        assertThat(app.request("GET","/api/v1/conversations?limit=101",null,customer).statusCode()).isEqualTo(422);
    }
    @Test void detailIsScopedByTenantAndCustomer() throws Exception {
        long id=create();assertThat(app.request("GET",path(id),null,customer).statusCode()).isEqualTo(200);
        assertThat(app.request("GET",path(id),null,agent).statusCode()).isEqualTo(200);
        assertThat(app.request("GET",path(id),null,app.login(5001)).statusCode()).isEqualTo(404);
        assertThat(app.request("GET",path(99999999),null,customer).statusCode()).isEqualTo(404);
    }
    @Test void sendBindsOriginalMessageToRunAndJobWithoutChangingAiAuthorField() throws Exception {
        long id=create();var r=app.request("POST",path(id)+"/messages",Map.of("client_message_id","contract-first","body","  查询物流  "),customer);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var dto=app.body(r);
        assertThat(dto.path("body").asString()).isEqualTo("查询物流");assertThat(dto.path("author_role").asString()).isEqualTo("customer");
        assertThat(dto.path("ai_run_id").isNull()).isTrue();assertThat(dto.path("trigger_run_id").asLong()).isPositive();assertThat(dto.path("trigger_job_id").asLong()).isPositive();
        assertThat(dto.path("accepted_generation_epoch").asLong()).isEqualTo(1);assertThat(dto.path("topic_id").asLong()).isPositive();
        assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","bad key!","body","x"),customer).statusCode()).isEqualTo(422);
        assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","blank","body","   "),customer).statusCode()).isEqualTo(422);
    }
    @Test void messagesPageIsChronologicalAndCursorDoesNotRepeat() throws Exception {
        long id=create();for(int i=0;i<3;i++)assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","page-"+i,"body","message"+i),customer).statusCode()).isEqualTo(200);
        var r=app.request("GET",path(id)+"/messages?limit=2",null,customer);assertThat(r.statusCode()).isEqualTo(200);var data=app.body(r);
        assertThat(data.path("items").size()).isEqualTo(2);assertThat(data.at("/items/0/body").asString()).isEqualTo("message1");assertThat(data.at("/items/1/body").asString()).isEqualTo("message2");
        long cursor=data.path("next_cursor").asLong();var older=app.request("GET",path(id)+"/messages?cursor="+cursor+"&limit=2",null,customer);
        assertThat(app.body(older).path("items").size()).isEqualTo(1);assertThat(app.body(older).at("/items/0/body").asString()).isEqualTo("message0");
    }
    @Test void eventsPersistMonotonicSequenceAndFinalMessageReferences() throws Exception {
        long id=create();var sent=app.request("POST",path(id)+"/messages",Map.of("client_message_id","event-first","body","问题"),customer);
        assertThat(sent.statusCode()).isEqualTo(200);var r=app.request("GET",path(id)+"/events?after_seq=0&limit=1",null,customer);
        assertThat(r.statusCode()).isEqualTo(200);assertThat(app.body(r).path("has_more").asBoolean()).isTrue();assertThat(app.body(r).at("/items/0/seq").asLong()).isEqualTo(1);
        var tail=app.request("GET",path(id)+"/events?after_seq=1",null,customer);var event=app.body(tail).path("items").get(0);
        assertThat(event.path("kind").asString()).isEqualTo("message.created");assertThat(event.path("seq").asLong()).isEqualTo(2);
        assertThat(event.at("/payload/message/trigger_run_id").asLong()).isEqualTo(app.body(sent).path("trigger_run_id").asLong());
        assertThat(event.at("/payload/conversation/ai_reply/run_id").asLong()).isEqualTo(app.body(sent).path("trigger_run_id").asLong());
    }
    @Test void handoffIsIdempotentAndStopsOldFrontTask() throws Exception {
        long id=create();assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","handoff-message","body","转人工"),customer).statusCode()).isEqualTo(200);
        var r=app.request("POST",path(id)+"/handoff",null,customer);assertThat(r.statusCode()).isEqualTo(200);assertThat(app.body(r).path("status").asString()).isEqualTo("WAITING_HUMAN");
        long epoch=app.body(r).path("generation_epoch").asLong(),seq=app.body(r).path("last_event_seq").asLong();
        var again=app.request("POST",path(id)+"/handoff",null,customer);assertThat(app.body(again).path("generation_epoch").asLong()).isEqualTo(epoch);assertThat(app.body(again).path("last_event_seq").asLong()).isEqualTo(seq);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM jobs WHERE conversation_id=? AND state IN('QUEUED','RUNNING','RETRY_WAIT')",Integer.class,id)).isZero();
    }
    @Test void claimUsesVersionAndOnlyAssignedStaffCanReply() throws Exception {
        long id=create();var handoff=app.request("POST",path(id)+"/handoff",null,customer);long version=app.body(handoff).path("version").asLong();
        var won=app.request("POST",path(id)+"/claim",Map.of("version",version),agent);assertThat(won.statusCode()).isEqualTo(200);assertThat(app.body(won).path("assigned_agent_id").asLong()).isEqualTo(2002);
        assertThat(app.request("POST",path(id)+"/claim",Map.of("version",version),admin).statusCode()).isEqualTo(409);
        assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","wrong-staff","body","不能发送"),admin).statusCode()).isEqualTo(403);
        var reply=app.request("POST",path(id)+"/messages",Map.of("client_message_id","staff-reply","body","人工回复"),agent);assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(app.body(reply).path("author_role").asString()).isEqualTo("agent");assertThat(app.body(reply).path("trigger_run_id").isNull()).isTrue();
    }
    @Test void closeIsIdempotentAndRejectsNewMessagesButReturnsOriginalRetry() throws Exception {
        long id=create();var input=Map.of("client_message_id","before-close","body","已提交");var sent=app.request("POST",path(id)+"/messages",input,customer);assertThat(sent.statusCode()).isEqualTo(200);
        var closed=app.request("POST",path(id)+"/close",null,customer);assertThat(closed.statusCode()).isEqualTo(200);assertThat(app.body(closed).path("status").asString()).isEqualTo("CLOSED");
        var again=app.request("POST",path(id)+"/close",null,customer);assertThat(app.body(again).path("generation_epoch").asLong()).isEqualTo(app.body(closed).path("generation_epoch").asLong());
        assertThat(app.request("POST",path(id)+"/messages",Map.of("client_message_id","after-close","body","新消息"),customer).statusCode()).isEqualTo(409);
        assertThat(app.body(app.request("POST",path(id)+"/messages",input,customer)).path("id").asLong()).isEqualTo(app.body(sent).path("id").asLong());
    }
    @Test void contextIsStaffOnlyAndMasksCustomerEmail() throws Exception {
        long id=create();assertThat(app.request("GET",path(id)+"/context",null,customer).statusCode()).isEqualTo(403);
        var r=app.request("GET",path(id)+"/context",null,agent);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(app.body(r).path("customer_id").asLong()).isEqualTo(2003);assertThat(app.body(r).path("masked_email").asString()).isEqualTo("us***@example.com");assertThat(app.body(r).path("orders").isArray()).isTrue();
    }
}
