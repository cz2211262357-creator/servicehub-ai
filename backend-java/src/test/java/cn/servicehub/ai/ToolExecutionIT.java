package cn.servicehub.ai;
import cn.servicehub.common.*;
import cn.servicehub.conversations.ExecutionFence;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolExecutionIT {
    final AgentFixture f=new AgentFixture();
    @BeforeAll void start() throws Exception{f.start();}
    @BeforeEach void reset() throws Exception{f.reset();}
    @AfterAll void close(){f.close();}
    Object execute(String name,Map<String,Object> args){return AgentFixture.call(f.app.requiredBean("cn.servicehub.ai.ToolRegistry"),"execute",new Class<?>[]{Actor.class,ExecutionFence.class,String.class,Map.class,CancellationGuard.class},f.actor(),f.fence,name,args,f.guard());}
    @Test void onlyExplicitCurrentTopicOrderCanBeRead(){
        assertThat(execute("lookup_order",Map.of("order_number","ORD-A-001"))).isNotNull();
        assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-A-002"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-OTHER"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-B-001"))).isInstanceOf(BusinessException.class);
    }
    @Test void currentOwnershipIsCheckedAgainRatherThanCachedInFacts(){
        assertThat(execute("lookup_order",Map.of("order_number","ORD-A-001"))).isNotNull();
        f.app.db.update("UPDATE orders SET customer_id=2001 WHERE id=6001");
        try{assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-A-001"))).isInstanceOf(BusinessException.class);}
        finally{f.app.db.update("UPDATE orders SET customer_id=2003 WHERE id=6001");}
    }
    @Test void preparingSameActionReusesDraftAndNeverCreatesFormalTicket(){
        var args=Map.<String,Object>of("order_number","ORD-A-001","kind","RETURN","reason","商品质量问题");
        var first=execute("prepare_after_sale",args);var second=execute("prepare_after_sale",args);
        assertThat(AgentFixture.field(first,"draftId")).isNotNull().isEqualTo(AgentFixture.field(second,"draftId"));
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM ticket_drafts WHERE origin_run_id=?",Integer.class,f.fence.runId())).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isZero();
    }
    @Test void interruptedRunCannotWriteDraftEvenThroughDirectServiceCall() throws Exception{
        f.app.requiredBean("cn.servicehub.ai.ToolRegistry");
        var state=f.app.body(f.app.request("GET","/api/v1/conversations/"+f.room,null,f.customer));
        assertThat(f.app.request("POST","/api/v1/conversations/"+f.room+"/interrupt",Map.of("expected_generation_epoch",state.path("generation_epoch").asLong()),f.customer).statusCode()).isEqualTo(200);
        assertThatThrownBy(()->execute("prepare_after_sale",Map.of("order_number","ORD-A-001","kind","RETURN","reason","质量问题"))).isInstanceOf(ExecutionCancelledException.class);
        assertThatThrownBy(()->AgentFixture.call(f.app.requiredBean("cn.servicehub.aftersales.AfterSaleService"),"prepareForRun",new Class<?>[]{Actor.class,ExecutionFence.class,cn.servicehub.jobs.JobLease.class,String.class,String.class,String.class},f.actor(),f.fence,f.lease,"ORD-A-001","RETURN","质量问题")).isInstanceOf(ExecutionCancelledException.class);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM ticket_drafts WHERE origin_run_id=?",Integer.class,f.fence.runId())).isZero();
    }

    @Test void followUpCanPreparePreviouslyMentionedOrderWithoutSeededTopicFacts() throws Exception {
        assertThat(f.app.context.getBean(AIService.class).commit(f.lease,f.fence,
            new AgentResult("订单已送达，请说明售后问题。",List.of(),null,List.of(),null))).isTrue();
        f.send("帮我给这个订单准备退货，原因是破损");
        var draft=execute("prepare_after_sale",Map.of("order_number","ORD-A-001","kind","RETURN","reason","破损"));
        assertThat(AgentFixture.field(draft,"draftId")).isNotNull();
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM ticket_drafts WHERE origin_run_id=? AND order_id=6001",Integer.class,f.fence.runId())).isEqualTo(1);
        assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM after_sale_tickets",Integer.class)).isZero();
    }
    @Test void failedPriorTurnCannotAuthorizeOrderInAPronounFollowUp() throws Exception {
        f.app.context.getBean(cn.servicehub.jobs.JobCoordinator.class).finish(f.lease,"FAILED",Map.of());
        f.send("帮我给这个订单准备退货，原因是破损");
        assertThatThrownBy(()->execute("prepare_after_sale",Map.of("order_number","ORD-A-001","kind","RETURN","reason","破损"))).isInstanceOf(BusinessException.class);
    }
    @Test void latestExplicitCustomerOrderReplacesOlderOrderReference() throws Exception {
        assertThat(f.app.context.getBean(AIService.class).commit(f.lease,f.fence,new AgentResult("已查到第一笔订单",List.of(),null,List.of(),null))).isTrue();
        f.send("查询订单 ORD-A-002");
        assertThat(f.app.context.getBean(AIService.class).commit(f.lease,f.fence,new AgentResult("已查到第二笔订单",List.of(),null,List.of(),null))).isTrue();
        f.send("这个订单能退吗");
        assertThat(execute("lookup_order",Map.of("order_number","ORD-A-002"))).isNotNull();
        assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-A-001"))).isInstanceOf(BusinessException.class);
    }
    @Test void merchantOrderNumberUsesTheSameOwnershipAndToolAuthorizationPath() throws Exception {
        f.app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount,status) VALUES(6999,1001,2003,'SH20261021001',599.00,'DELIVERED')");
        try{
            f.send("查询订单 SH20261021001");
            assertThat(execute("lookup_order",Map.of("order_number","SH20261021001"))).isNotNull();
            assertThatThrownBy(()->execute("lookup_order",Map.of("order_number","ORD-A-001"))).isInstanceOf(BusinessException.class);
        }finally{f.app.db.update("DELETE FROM orders WHERE id=6999");}
    }
}
