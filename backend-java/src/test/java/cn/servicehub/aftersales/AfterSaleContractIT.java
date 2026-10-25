package cn.servicehub.aftersales;

import java.util.Map;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AfterSaleContractIT {
    final AfterSaleFixture f=new AfterSaleFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @AfterAll void stop(){f.close();}
    @BeforeEach void reset() throws Exception {f.reset();}
    @Test void prepareOnlyCreatesExpiringDraftForVisibleEligibleOrder() throws Exception {
        var r=f.app.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",6001,"kind","EXCHANGE","reason","  尺码不合适  "),f.customer);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);var draft=f.app.body(r);
        assertThat(draft.path("reason").asString()).isEqualTo("尺码不合适");assertThat(draft.path("version").asLong()).isEqualTo(1);
        assertThat(draft.path("customer_id").asLong()).isEqualTo(2003);assertThat(draft.path("consumed").asBoolean()).isFalse();
        assertThat(java.time.Instant.parse(draft.path("expires_at").asString())).isAfter(java.time.Instant.now().plusSeconds(14*60));
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isZero();
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",6002,"kind","RETURN","reason","无权访问"),f.customer).statusCode()).isEqualTo(404);
        f.app.db.update("UPDATE orders SET status='CREATED' WHERE id=6001");
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",6001,"kind","RETURN","reason","尚未交付"),f.customer).statusCode()).isEqualTo(409);
    }
    @Test void draftListOnlyContainsOwnUnconsumedUnexpiredDrafts() throws Exception {
        long visible=f.draft(6001),expired=f.draft(6001);
        f.app.db.update("UPDATE ticket_drafts SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",expired);
        var r=f.app.request("GET","/api/v1/after-sales/drafts",null,f.customer);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(f.app.body(r).path("items").size()).isEqualTo(1);assertThat(f.app.body(r).at("/items/0/id").asLong()).isEqualTo(visible);
        assertThat(f.app.body(f.app.request("GET","/api/v1/after-sales/drafts",null,f.other)).path("items").size()).isZero();
    }
    @Test void confirmationRequiresCustomerAndReusesOnlyMatchingIdempotencyRequest() throws Exception {
        long draft=f.draft(6001);
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts/"+draft+"/confirm",Map.of("version",1,"idempotency_key","staff-forbidden"),f.agent).statusCode()).isEqualTo(403);
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts/"+draft+"/confirm",Map.of("version",1,"idempotency_key","other-forbidden"),f.other).statusCode()).isEqualTo(404);
        var first=f.confirm(draft,"confirmed-once");assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        long id=f.app.body(first).path("id").asLong();assertThat(f.app.body(first).path("events").size()).isZero();
        assertThat(f.app.body(f.confirm(draft,"confirmed-once")).path("id").asLong()).isEqualTo(id);
        assertThat(f.confirm(draft,"another-key").statusCode()).isEqualTo(409);
        long second=f.draft(6001);assertThat(f.confirm(second,"confirmed-once").statusCode()).isEqualTo(409);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isEqualTo(1);
    }
    @Test void ticketListUsesCursorStatusAndCustomerScope() throws Exception {
        long draft=f.draft(6001);assertThat(f.confirm(draft,"list-one").statusCode()).isEqualTo(200);
        assertThat(f.confirm(f.draft(6001),"list-two").statusCode()).isEqualTo(200);
        var page=f.app.request("GET","/api/v1/after-sales?limit=1&status=SUBMITTED",null,f.customer);assertThat(page.statusCode()).isEqualTo(200);
        assertThat(f.app.body(page).path("items").size()).isEqualTo(1);assertThat(f.app.body(page).path("next_cursor").asLong()).isPositive();
        assertThat(f.app.body(f.app.request("GET","/api/v1/after-sales",null,f.other)).path("items").size()).isZero();
        assertThat(f.app.request("GET","/api/v1/after-sales?limit=101",null,f.customer).statusCode()).isEqualTo(422);
    }
    @Test void ticketDetailHasOrderedHistoryAndNoCrossCustomerOrTenantAccess() throws Exception {
        long draft=f.draft(6001);var confirmed=f.confirm(draft,"detail-one");assertThat(confirmed.statusCode()).isEqualTo(200);
        long id=f.app.body(confirmed).path("id").asLong();var r=f.app.request("GET","/api/v1/after-sales/"+id,null,f.customer);assertThat(r.statusCode()).isEqualTo(200);
        assertThat(f.app.body(r).at("/events/0/to_status").asString()).isEqualTo("SUBMITTED");assertThat(f.app.body(r).at("/events/0/from_status").isNull()).isTrue();
        assertThat(f.app.body(r).at("/events/0/actor_id").asLong()).isEqualTo(2003);
        assertThat(f.app.request("GET","/api/v1/after-sales/"+id,null,f.other).statusCode()).isEqualTo(404);
        assertThat(f.app.request("GET","/api/v1/after-sales/"+id,null,f.app.login(5001)).statusCode()).isEqualTo(404);
    }
    @Test void transitionsRequireCurrentVersionAndPreserveRejectionAudit() throws Exception {
        var confirmed=f.confirm(f.draft(6001),"transition-one");assertThat(confirmed.statusCode()).isEqualTo(200);long id=f.app.body(confirmed).path("id").asLong();
        String path="/api/v1/after-sales/"+id+"/status";
        var rejected=f.app.request("PATCH",path,Map.of("version",1,"status","APPROVED"),f.agent);assertThat(rejected.statusCode()).isEqualTo(409);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='ticket.transition' AND outcome='rejected'",Integer.class)).isEqualTo(1);
        assertThat(f.app.request("PATCH",path,Map.of("version",1,"status","IN_REVIEW"),f.customer).statusCode()).isEqualTo(409);
        var review=f.app.request("PATCH",path,Map.of("version",1,"status","IN_REVIEW","note","核实材料"),f.agent);assertThat(review.statusCode()).as(review.body()).isEqualTo(200);
        assertThat(f.app.body(review).path("version").asLong()).isEqualTo(2);
        assertThat(f.app.request("PATCH",path,Map.of("version",1,"status","APPROVED"),f.agent).statusCode()).isEqualTo(409);
        assertThat(f.app.request("PATCH",path,Map.of("version",2,"status","APPROVED"),f.agent).statusCode()).isEqualTo(200);
        assertThat(f.app.request("PATCH",path,Map.of("version",3,"status","RESOLVED"),f.agent).statusCode()).isEqualTo(200);
        assertThat(f.app.request("PATCH",path,Map.of("version",4,"status","IN_REVIEW"),f.admin).statusCode()).isEqualTo(409);
        var detail=f.app.request("GET","/api/v1/after-sales/"+id,null,f.customer);assertThat(f.app.body(detail).path("events").size()).isEqualTo(4);
    }
    @Test void expiredDraftAndInvalidPayloadsHaveExplicitFailures() throws Exception {
        long id=f.draft(6001);f.app.db.update("UPDATE ticket_drafts SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);
        var expired=f.confirm(id,"expired");assertThat(expired.statusCode()).isEqualTo(410);assertThat(f.app.body(expired).at("/error/code").asString()).isEqualTo("draft_expired");
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",6001,"kind","REFUND_NOW","reason","无效类型"),f.customer).statusCode()).isEqualTo(422);
        assertThat(f.app.request("POST","/api/v1/after-sales/drafts/"+id+"/confirm",Map.of("version",1,"idempotency_key","bad key!"),f.customer).statusCode()).isEqualTo(422);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isZero();
    }
}
