package cn.servicehub.operations;
import cn.servicehub.ai.*;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import cn.servicehub.support.HttpIntegrationApp;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationsContractIT {
 final HttpIntegrationApp app=new HttpIntegrationApp();final List<ExecutionPermit> permits=new ArrayList<>();
 HttpIntegrationApp.Login admin,agent,customer;long room,job,run;JobLease lease;
 @BeforeAll void start() throws Exception{app.start();}
 @BeforeEach void reset() throws Exception{
  permits.forEach(ExecutionPermit::close);permits.clear();app.resetAuth();admin=app.login(2001);agent=app.login(2002);customer=app.login(2003);
  room=app.body(app.request("POST","/api/v1/conversations",Map.of(),customer)).path("id").asLong();
  var message=app.body(app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","查询订单 ORD-A-001"),customer));
  job=message.path("trigger_job_id").asLong();run=message.path("trigger_run_id").asLong();
  String owner="ops-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());
  lease=app.context.getBean(JobCoordinator.class).claim(job,owner).orElseThrow();app.context.getBean(TopicService.class).resolve(lease,message.path("id").asLong(),TopicDecision.of("CONTINUE",null,false));
 }
 @AfterAll void close(){permits.forEach(ExecutionPermit::close);app.close();}
 JsonNode get(String path,HttpIntegrationApp.Login actor) throws Exception{var response=app.request("GET",path,null,actor);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return app.body(response);}
 int outboxes(){return app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=? AND kind='job'",Integer.class,job);}
 void fail(){app.context.getBean(JobCoordinator.class).fail(lease,"model_unavailable",false);}
 @Test void sixLegacyOperationsEndpointsRetainRoleAndTenantBoundaries() throws Exception{
  for(String path:List.of("/api/v1/admin/jobs","/api/v1/admin/audits","/api/v1/metrics")){
   assertThat(app.request("GET",path,null,customer).statusCode()).isEqualTo(403);assertThat(app.request("GET",path,null,agent).statusCode()).isEqualTo(403);assertThat(app.request("GET",path,null,null).statusCode()).isEqualTo(401);
  }
  for(String path:List.of("/api/v1/admin/metrics/summary","/api/v1/admin/runtime")){assertThat(app.request("GET",path,null,customer).statusCode()).isEqualTo(403);get(path,agent);}
  var jobs=get("/api/v1/admin/jobs?limit=1",admin);assertThat(jobs.path("items").size()).isEqualTo(1);assertThat(jobs.path("items").get(0).path("tenant_id").asLong()).isEqualTo(1001);
  assertThat(app.request("GET","/api/v1/admin/jobs",null,app.login(5001)).statusCode()).isEqualTo(403);
  app.db.update("UPDATE users SET role='admin' WHERE id=5001");var other=app.login(5001);
  assertThat(get("/api/v1/admin/jobs",other).path("items").size()).isZero();
  assertThat(app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,other).statusCode()).isEqualTo(404);
 }
 @Test void runtimeContainsConfigurationFlagsAndNoSecretsOrPrivateEndpoints() throws Exception{
  var response=app.request("GET","/api/v1/admin/runtime",null,agent);assertThat(response.statusCode()).isEqualTo(200);var body=app.body(response);
  assertThat(body.path("global_model_slots").asInt()).isEqualTo(4);assertThat(body.path("automatic_retries").asInt()).isEqualTo(2);
  assertThat(body.path("dependencies").has("rabbitmq")).isTrue();assertThat(body.path("dependencies").has("redis")).isTrue();
  for(String key:List.of("servicehub.model.api-key","servicehub.model.embedding-api-key","servicehub.database.password","servicehub.redis.password")){
   var secret=app.context.getEnvironment().getProperty(key);if(secret!=null&&!secret.isBlank())assertThat(response.body().contains(secret)).as("Runtime must not expose configured secret").isFalse();
  }
  assertThat(response.body()).doesNotContain("base_url","password","username","api_key");
 }
 @Test void metricsKeepUnknownUsageNullAndTenantPrometheusLabelsBounded() throws Exception{
  var actor=app.context.getBean(cn.servicehub.auth.AuthService.class).authenticate(agent.cookie().split("=",2)[1]);
  app.context.getBean(OperationsService.class).summary(actor,null,null);
  var data=get("/api/v1/admin/metrics/summary",agent);assertThat(data.get("total_tokens").isNull()).isTrue();assertThat(data.path("usage_records").asInt()).isZero();
  app.db.update("UPDATE ai_runs SET "+Character.toString(96)+"usage"+Character.toString(96)+"=JSON_OBJECT('total_tokens',0) WHERE id=?",run);
  data=get("/api/v1/admin/metrics/summary",agent);assertThat(data.path("usage_records").asInt()).isEqualTo(1);assertThat(data.path("total_tokens").asLong()).isZero();
  var metrics=app.request("GET","/api/v1/metrics",null,admin);assertThat(metrics.statusCode()).isEqualTo(200);assertThat(metrics.body()).contains("servicehub_conversations").doesNotContain("user_id=","message_id=","ORD-A-001");
 }
 @Test void summaryValidatesOffsetTimeWindowAndPagination() throws Exception{
  for(String path:List.of("/api/v1/admin/metrics/summary?start=2026-10-24T00:00:00","/api/v1/admin/metrics/summary?start=2027-01-01T00:00:00Z&end=2026-01-01T00:00:00Z","/api/v1/admin/jobs?limit=101","/api/v1/admin/audits?cursor=0"))
   assertThat(app.request("GET",path,null,admin).statusCode()).as(path).isEqualTo(422);
 }
 @Test void manualRetryUsesSameLogicalIdsAndFreshBudgetWithoutResettingLifetime() throws Exception{
  var budgets=app.context.getBean(ModelBudget.class);budgets.reserve(lease,"model");budgets.reserve(lease,"tool");fail();int before=outboxes();
  var response=app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
  assertThat(app.body(response).path("id").asLong()).isEqualTo(job);assertThat(app.body(response).path("state").asString()).isEqualTo("retrying");assertThat(outboxes()).isEqualTo(before+1);
  var row=app.db.queryForMap("SELECT attempts,lifetime_attempts,manual_retries,budget_revision,started_at,deadline_at FROM jobs WHERE id=?",job);
  assertThat(((Number)row.get("attempts")).intValue()).isZero();assertThat(((Number)row.get("lifetime_attempts")).intValue()).isEqualTo(1);assertThat(((Number)row.get("manual_retries")).intValue()).isEqualTo(1);
  assertThat(((Number)row.get("budget_revision")).longValue()).isEqualTo(lease.budgetRevision()+1);assertThat(row.get("started_at")).isNull();assertThat(row.get("deadline_at")).isNull();
  assertThat(app.db.queryForObject("SELECT model_requests_reserved+tool_executions_reserved FROM ai_runs WHERE id=?",Integer.class,run)).isZero();
  assertThatThrownBy(()->budgets.reserve(lease,"model")).isInstanceOf(ExecutionCancelledException.class);
  String owner="ops-retry-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());var next=app.context.getBean(JobCoordinator.class).claim(job,owner).orElseThrow();
  assertThat(next.executionVersion()).isGreaterThan(lease.executionVersion());assertThat(next.budgetRevision()).isEqualTo(lease.budgetRevision()+1);
  assertThat(app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='job.retried' AND target_id=?",Integer.class,String.valueOf(job))).isEqualTo(1);
 }
 @Test void cancelledJobCannotBeAutoRetried() throws Exception{
  assertThat(app.request("POST","/api/v1/conversations/"+room+"/interrupt",Map.of("expected_generation_epoch",lease.payload().get("generation_epoch")),customer).statusCode()).isEqualTo(200);
  int before=outboxes();var response=app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin);assertThat(response.statusCode()).isEqualTo(409);assertThat(outboxes()).isEqualTo(before);
  assertThat(app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job)).isEqualTo("CANCELLED");
 }
 @Test void supersededFailedRunAndRevokedOwnerCannotBeRetried() throws Exception{
  fail();app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body","新问题"),customer);
  int before=outboxes();assertThat(app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin).statusCode()).isEqualTo(409);assertThat(outboxes()).isEqualTo(before);
 }
 @Test void retryRequiresAdminCsrfAndDoesNotCreateSecondAuditOnNetworkRepeat() throws Exception{
  fail();int before=outboxes();String path="/api/v1/admin/jobs/"+job+"/retry";
  assertThat(app.request("POST",path,null,agent).statusCode()).isEqualTo(403);assertThat(app.request("POST",path,null,admin,null,"http://localhost:8081").statusCode()).isEqualTo(403);assertThat(outboxes()).isEqualTo(before);
  assertThat(app.request("POST",path,null,admin).statusCode()).isEqualTo(200);assertThat(app.request("POST",path,null,admin).statusCode()).isEqualTo(409);assertThat(outboxes()).isEqualTo(before+1);
 }

 @Test void actuatorRequiresAdministratorEvenForAuthenticatedCustomers() throws Exception{
  assertThat(app.request("GET","/actuator/health",null,customer).statusCode()).isEqualTo(403);
  assertThat(app.request("GET","/actuator/health",null,agent).statusCode()).isEqualTo(403);
  assertThat(app.request("GET","/actuator/health",null,null).statusCode()).isEqualTo(401);
  assertThat(app.request("GET","/actuator/health",null,admin).statusCode()).isEqualTo(200);
 }
 @Test void revokedOriginalOwnerCannotBorrowAnAdministratorsPermissionToRetry() throws Exception{
  fail();int before=outboxes();app.db.update("UPDATE users SET is_active=0,session_version=session_version+1 WHERE id=2003");
  assertThat(app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin).statusCode()).isEqualTo(409);assertThat(outboxes()).isEqualTo(before);
  assertThat(app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job)).isEqualTo("FAILED");
 }

 @Test void concurrentManualRetryHasOneWinnerAndOneNewOutbox() throws Exception{
  fail();int before=outboxes();var barrier=new java.util.concurrent.CyclicBarrier(2);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var calls=new ArrayList<java.util.concurrent.Future<Integer>>();
   for(int i=0;i<2;i++)calls.add(pool.submit(()->{barrier.await();return app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin).statusCode();}));
   assertThat(List.of(calls.get(0).get(8,java.util.concurrent.TimeUnit.SECONDS),calls.get(1).get(8,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
  }
  assertThat(outboxes()).isEqualTo(before+1);
 }
 @Test void backgroundRetryRechecksDocumentRevisionAndUploaderPermission() throws Exception{
  long document=880000+room*2,version=document+1;
  var tx=new org.springframework.transaction.support.TransactionTemplate(app.context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  job=tx.execute(status->{
   app.db.update("INSERT INTO knowledge_documents(id,tenant_id,title,uploaded_by) VALUES(?,1001,'Synthetic policy',2002)",document);
   app.db.update("INSERT INTO document_versions(id,tenant_id,document_id,number,storage_key,filename,media_type,size_bytes,sha256,status) VALUES(?,1001,?,1,?,'policy.txt','text/plain',12,?,'queued')",version,document,"c".repeat(32)+".txt","d".repeat(64));
   long id=app.context.getBean(JobJournal.class).enqueue(new JobCommand(1001,"knowledge_index",ExecutionClass.BACKGROUND,"ops-version-"+version,Map.of("document_id",document,"version_id",version,"actor_id",2002,"auth_version",1,"document_revision",1))).id();
   app.db.update("UPDATE document_versions SET job_id=? WHERE id=?",id,version);return id;
  });
  String owner="ops-bg-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.BACKGROUND,owner).orElseThrow());
  lease=app.context.getBean(JobCoordinator.class).claim(job,owner).orElseThrow();fail();
  assertThat(app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin).statusCode()).isEqualTo(200);
  owner="ops-bg-second-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.BACKGROUND,owner).orElseThrow());
  lease=app.context.getBean(JobCoordinator.class).claim(job,owner).orElseThrow();fail();int before=outboxes();
  app.db.update("UPDATE knowledge_documents SET enabled=0,version=version+1 WHERE id=?",document);
  assertThat(app.request("POST","/api/v1/admin/jobs/"+job+"/retry",null,admin).statusCode()).isEqualTo(409);assertThat(outboxes()).isEqualTo(before);
 }
 @Test void failedQueueIsDistinguishableWhileLivenessRemainsHealthy() throws Exception{
  var docker=app.dependencies.rabbitmq.getDockerClient();String id=app.dependencies.rabbitmq.getContainerId();docker.pauseContainerCmd(id).exec();
  try{
   assertThat(get("/api/v1/admin/runtime",agent).path("dependencies").path("rabbitmq").asString()).isEqualTo("unavailable");
   assertThat(app.request("GET","/api/v1/health/ready",null,null).statusCode()).isEqualTo(503);
   assertThat(app.request("GET","/api/v1/health/live",null,null).statusCode()).isEqualTo(200);
  }finally{docker.unpauseContainerCmd(id).exec();}
 }
 @Test void cacheFailureKeepsAuthenticationClosedAndReadinessNamesFailedDependency() throws Exception{
  var docker=app.dependencies.redis.getDockerClient();String id=app.dependencies.redis.getContainerId();docker.pauseContainerCmd(id).exec();
  try{
   assertThat(app.request("GET","/api/v1/admin/runtime",null,agent).statusCode()).isEqualTo(503);
   var ready=app.request("GET","/api/v1/health/ready",null,null);assertThat(ready.statusCode()).isEqualTo(503);
   assertThat(app.body(ready).path("error").path("details").path("dependencies").path("redis").asString()).isEqualTo("unavailable");
   assertThat(app.request("GET","/api/v1/health/live",null,null).statusCode()).isEqualTo(200);
  }finally{docker.unpauseContainerCmd(id).exec();}
 }

 @Test void auditWriterJoinsBusinessTransactionAndCannotWriteOutsideIt(){
  var actor=app.context.getBean(cn.servicehub.auth.AuthService.class).authenticate(admin.cookie().split("=",2)[1]);
  var audit=app.context.getBean(AuditService.class);int before=app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='synthetic.audit'",Integer.class);
  assertThatThrownBy(()->audit.append(actor,"synthetic.audit","conversation",room,Map.of())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  var tx=new org.springframework.transaction.support.TransactionTemplate(app.context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
  tx.executeWithoutResult(status->{audit.append(actor,"synthetic.audit","conversation",room,Map.of());status.setRollbackOnly();});
  assertThat(app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='synthetic.audit'",Integer.class)).isEqualTo(before);
  tx.executeWithoutResult(status->audit.append(actor,"synthetic.audit","conversation",room,Map.of()));
  assertThat(app.db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='synthetic.audit'",Integer.class)).isEqualTo(before+1);
 }
}
