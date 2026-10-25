package cn.servicehub.support;
import cn.servicehub.ai.*;
import cn.servicehub.conversations.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
/** Repeats every cross-API race twenty times with new tenants and independent sessions. */
final class ConcurrentScenarios {
 final MultiProcessFixture f;
 ConcurrentScenarios(MultiProcessFixture fixture){f=fixture;}
 List<Map<String,Object>> duplicates()throws Exception{
  f.stopWorker();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(0);String key="duplicate-"+round;long before=f.count("SELECT generation_epoch FROM conversations WHERE id=?",c.room());
   var racing=f.race(20,i->f.send(c,key,"查询物流",i));var receipts=new HashSet<String>();
   for(var packet:racing){f.require(packet,200);receipts.add(receipt(packet));}
   for(int i=0;i<10;i++){var retry=f.send(c,key,"查询物流",i);f.require(retry,200);receipts.add(receipt(retry));}
   rounds.add(Map.of("message_count",f.count("SELECT COUNT(*) FROM messages WHERE conversation_id=?",c.room()),"run_count",f.count("SELECT COUNT(*) FROM ai_runs WHERE conversation_id=?",c.room()),
    "job_count",f.count("SELECT COUNT(*) FROM jobs WHERE conversation_id=?",c.room()),"epoch_delta",f.count("SELECT generation_epoch FROM conversations WHERE id=?",c.room())-before,"distinct_receipts",receipts.size(),"gate_hits",racing.size()));
  }return rounds;
 }
 String receipt(MultiProcessFixture.Packet p){var body=p.body();return body.path("id").asLong()+":"+body.path("trigger_run_id").asLong()+":"+body.path("trigger_job_id").asLong()+":"+body.path("accepted_generation_epoch").asLong();}
 List<Map<String,Object>> atomicity()throws Exception{
  f.stopWorker();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(0);long initial=f.count("SELECT generation_epoch FROM conversations WHERE id=?",c.room());
   for(String stage:List.of("message","job")){
    f.app.db.update("INSERT INTO fixture_fault_controls(tenant_id,stage) VALUES(?,?) ON DUPLICATE KEY UPDATE stage=VALUES(stage)",c.tenant(),stage);
    var failed=f.send(c,"atomic-rollback","不能半提交",round);f.require(failed,503);
   }
   long rows=f.count("SELECT COUNT(*) FROM messages WHERE conversation_id=?",c.room())+f.count("SELECT COUNT(*) FROM ai_runs WHERE conversation_id=?",c.room())+f.count("SELECT COUNT(*) FROM jobs WHERE conversation_id=?",c.room());
   long delta=f.count("SELECT generation_epoch FROM conversations WHERE id=?",c.room())-initial;
   long hits=f.count("SELECT COUNT(*) FROM fixture_fault_hits WHERE tenant_id=?",c.tenant());f.app.db.update("DELETE FROM fixture_fault_controls WHERE tenant_id=?",c.tenant());
   f.require(f.send(c,"different-content","原始内容",0),200);var conflict=f.send(c,"different-content","替换内容",1);
   rounds.add(Map.of("conflict_status",conflict.status(),"rollback_rows",rows,"rollback_epoch_delta",delta,"injection_hits",hits));
  }return rounds;
 }
 List<Map<String,Object>> switches()throws Exception{
  f.stopWorker();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(0);var message=f.send(c,"A1","查询原订单",0);f.require(message,200);var lease=f.claim(message);var fence=f.resolve(lease,message);long a=fence.topicId(),oldEpoch=fence.generationEpoch();
   var stopped=f.request(0,"POST",f.room(c)+"/interrupt",Map.of("expected_generation_epoch",oldEpoch),c.customer());f.require(stopped,200);
   var newer=f.send(c,"A-before-switch","继续查询物流",1);f.require(newer,200);long expected=newer.body().path("accepted_generation_epoch").asLong();
   var switches=f.race(2,i->f.request(i,"POST",f.room(c)+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",expected),c.customer()));
   long won=switches.stream().filter(p->p.status()==200).count(),conflicts=switches.stream().filter(p->p.status()==409).count();
   var success=switches.stream().filter(p->p.status()==200).findFirst().orElseThrow();
   var resumed=f.request(1,"POST",f.room(c)+"/topics/switch",Map.of("mode","RESUME","topic_id",a,"expected_generation_epoch",success.body().path("generation_epoch").asLong()),c.customer());f.require(resumed,200);
   var current=f.send(c,"A2","新的 A 回答",0);f.require(current,200);long currentJob=current.body().path("trigger_job_id").asLong();
   f.require(f.request(1,"POST",f.room(c)+"/interrupt",Map.of("expected_generation_epoch",oldEpoch),c.customer()),200);
   boolean late=f.app.context.getBean(AIService.class).commit(lease,fence,new AgentResult("迟到 A1",List.of(),null,List.of(),null));
   rounds.add(Map.of("success_count",won,"conflict_count",conflicts,"new_task_cancelled_by_old_stop",Set.of("CANCELLED","SUPERSEDED").contains(f.state(currentJob))?1:0,"late_a1_commits",late?1:0));
   f.permits.forEach(captured->captured.close());f.permits.clear();
  }return rounds;
 }
 List<Map<String,Object>> confirmations()throws Exception{
  f.stopWorker();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(20);var draft=f.request(0,"POST","/api/v1/after-sales/drafts",Map.of("order_id",c.order(),"kind","RETURN","reason","尺码不合适"),c.customer());f.require(draft,201);
   String confirm="/api/v1/after-sales/drafts/"+draft.body().path("id").asLong()+"/confirm";var confirmations=f.race(20,i->f.request(i,"POST",confirm,Map.of("version",1,"idempotency_key","confirm-"+c.tenant()),c.customer()));
   for(var confirmation:confirmations)f.require(confirmation,200);
   var handed=f.request(0,"POST",f.room(c)+"/handoff",null,c.customer());f.require(handed,200);long version=handed.body().path("version").asLong();
   var claims=f.race(20,i->f.request(i,"POST",f.room(c)+"/claim",Map.of("version",version),c.agents().get(i)));
   for(var claim:claims)assertThat(claim.status()).isIn(200,409);
   rounds.add(Map.of("ticket_count",f.count("SELECT COUNT(*) FROM after_sale_tickets WHERE tenant_id=?",c.tenant()),"claim_winners",claims.stream().filter(p->p.status()==200).count(),"confirmation_requests",confirmations.size(),"claim_requests",claims.size()));
  }return rounds;
 }
 List<Map<String,Object>> revokeFirst()throws Exception{
  f.stopWorker();f.retireOutstanding();f.startWorker();long positive=f.authorizedToolControl();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(0);String tag="REVOKE_"+c.tenant();var gate=f.provider.gate(tag,false,1);gate.tool="prepare_after_sale";gate.arguments=f.app.json.writeValueAsString(Map.of("order_number",c.orderNumber(),"kind","RETURN","reason","尺码不合适"));
   var message=f.send(c,"revoke","查询订单 "+c.orderNumber()+"，准备售后 "+tag,0);f.require(message,200);f.awaitProvider(gate,message);long job=message.body().path("trigger_job_id").asLong();
   var revoked=f.request(1,"PATCH","/api/v1/admin/members/"+c.customer().id(),Map.of("is_active",false),c.admin());f.require(revoked,200);gate.open();
   f.until(()->!Set.of("QUEUED","RETRY_WAIT","RUNNING").contains(f.state(job)),10,"revoked run is durably retired");
   rounds.add(Map.of("positive_control_drafts",positive,"provider_gate_hits",gate.hits.get(),"unauthorized_answers",f.count("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND author_role='ai'",c.room()),"unauthorized_drafts",f.count("SELECT COUNT(*) FROM ticket_drafts WHERE tenant_id=?",c.tenant())));
  }f.stopWorker();return rounds;
 }
 List<Map<String,Object>> businessFirst()throws Exception{
  f.stopWorker();var rounds=new ArrayList<Map<String,Object>>();
  for(int round=0;round<20;round++){
   var c=f.fresh(0);String gate="fixture-business-"+c.tenant();
   f.app.db.update("INSERT INTO fixture_fault_controls(tenant_id,stage,gate_name) VALUES(?,'draft',?)",c.tenant(),gate);
   try(var connection=DriverManager.getConnection(f.app.dependencies.jdbcUrl(),f.app.dependencies.username(),f.app.dependencies.password())){
    try(var lock=connection.prepareStatement("SELECT GET_LOCK(?,0)")){lock.setString(1,gate);try(var value=lock.executeQuery()){assertThat(value.next()).isTrue();assertThat(value.getInt(1)).isEqualTo(1);}}
    var writing=f.calls.submit(()->f.request(0,"POST","/api/v1/after-sales/drafts",Map.of("order_id",c.order(),"kind","RETURN","reason","合法确认前草稿"),c.customer()));
    f.until(()->f.count("SELECT IS_USED_LOCK(?) IS NOT NULL",gate+":entered")>0,5,"business transaction reached its actual SQL advisory gate");
    var revoking=f.calls.submit(()->f.request(1,"PATCH","/api/v1/admin/members/"+c.customer().id(),Map.of("is_active",false),c.admin()));
    assertThat(f.authorizationRowIsLocked(c.tenant(),c.customer().id())).as("An independent SQL transaction must get NOWAIT conflict3572 on the protected user row").isTrue();
    try{revoking.get(200,TimeUnit.MILLISECONDS);throw new AssertionError("Revocation committed while the business transaction held authorization");}catch(TimeoutException expectedWaiting){}
    int finishedBefore=revoking.isDone()?1:0;
    try(var unlock=connection.prepareStatement("SELECT RELEASE_LOCK(?)")){unlock.setString(1,gate);unlock.execute();}
    f.require(writing.get(8,TimeUnit.SECONDS),201);f.require(revoking.get(8,TimeUnit.SECONDS),200);
    long written=f.count("SELECT COUNT(*) FROM ticket_drafts WHERE tenant_id=?",c.tenant());
    var after=f.request(0,"POST","/api/v1/after-sales/drafts",Map.of("order_id",c.order(),"kind","RETURN","reason","撤权后禁止写入"),c.customer());f.require(after,401);
    rounds.add(Map.of("transaction_gate_hits",f.count("SELECT COUNT(*) FROM fixture_fault_hits WHERE tenant_id=? AND stage='draft'",c.tenant()),"revocation_finished_before_release",finishedBefore,"legitimate_writes",written,"protected_writes_after_revocation",f.count("SELECT COUNT(*) FROM ticket_drafts WHERE tenant_id=?",c.tenant())-written));
   }
   f.app.db.update("DELETE FROM fixture_fault_controls WHERE tenant_id=?",c.tenant());
  }return rounds;
 }
}
