package cn.servicehub.support;
import cn.servicehub.ai.*;
import cn.servicehub.jobs.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;
/** Real process loss, expired-owner resumption, dropped broker confirms and dependency outages. */
final class RecoveryScenarios {
 final MultiProcessFixture f;
 RecoveryScenarios(MultiProcessFixture fixture){f=fixture;}
 Map<String,Object> run(String scenario)throws Exception{
  f.resetScenarioResources();f.retireOutstanding();
  return switch(scenario){case "cancel-restart"->cancelRestart();case "lease-fencing"->leaseFencing();case "broker-confirm-loss"->confirmLoss();case "dependency-retry"->dependencyRetry();case "admission-isolation"->isolation();default->throw new AssertionError("Unknown actual recovery scenario: "+scenario);};
 }
 Map<String,Object> cancelRestart()throws Exception{
  f.startWorker();long positive=f.authorizedToolControl();
  var c=f.fresh(0);String tag="CANCEL_"+c.tenant();var gate=f.provider.gate(tag,false,1);gate.tool="prepare_after_sale";gate.arguments=f.app.json.writeValueAsString(Map.of("order_number",c.orderNumber(),"kind","RETURN","reason","尺码不合适"));
  f.startWorker();long before=f.worker.pid();var message=f.send(c,"cancel-old","查询订单 "+c.orderNumber()+"，准备售后 "+tag,0);f.require(message,200);gate.await();long oldRun=message.body().path("trigger_run_id").asLong(),oldJob=message.body().path("trigger_job_id").asLong();
  var stopped=f.request(1,"POST",f.room(c)+"/interrupt",Map.of("expected_generation_epoch",message.body().path("accepted_generation_epoch").asLong()),c.customer());f.require(stopped,200);
  f.app.db.update("UPDATE outbox_events SET dispatched_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND kind='realtime' AND dispatched_at IS NULL",c.tenant());
  long tools=f.count("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=?",oldRun);f.worker.process().destroyForcibly();assertThat(f.worker.process().waitFor(5,TimeUnit.SECONDS)).isTrue();f.worker=null;
  gate.open();f.startWorker();long after=f.worker.pid();var current=f.send(c,"cancel-current","查询另一订单物流",1);f.require(current,200);long currentRun=current.body().path("trigger_run_id").asLong();
  f.until(()->f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",currentRun)==1,15,"replacement request completes after actual Worker restart");
  return Map.of("positive_control_drafts",positive,"worker_pid_before",before,"worker_pid_after",after,"old_tools_after_restart",f.count("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=?",oldRun)-tools,"old_answers",f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",oldRun),"current_answers",f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",currentRun),"persisted_cancel_observed","CANCELLED".equals(f.state(oldJob))?1:0);
 }
 Map<String,Object> leaseFencing()throws Exception{
  var c=f.fresh(0);String tag="LEASE_"+c.tenant();var gate=f.provider.gate(tag,false,1);gate.blockSeconds=90;f.startWorker();var old=f.worker;var message=f.send(c,"lease-old","查询物流 "+tag,0);f.require(message,200);gate.await();long job=message.body().path("trigger_job_id").asLong(),run=message.body().path("trigger_run_id").asLong();
  long before=f.count("SELECT execution_version FROM jobs WHERE id=?",job);int oldConsumers=foregroundConsumers();assertThat(oldConsumers).isPositive();boolean suspended=false;
  try{
   OwnedProcessSignals.change(old.process(),true,f.root);suspended=true;
   f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",job);
   long detecting=System.nanoTime();awaitPausedWorkerBrokerEviction();
   long detectionMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-detecting);assertThat(old.process().isAlive()).isTrue();
   var replacement=f.launch("worker","UTC");f.worker=replacement;
   try{f.until(()->f.count("SELECT execution_version FROM jobs WHERE id=?",job)>before&&gate.hits.get()>=2,20,"replacement Worker holds a new fencing token and reached provider");}
   catch(AssertionError failure){
    var snapshot=f.app.db.queryForMap("SELECT j.state,j.error_code,j.execution_version,j.attempts,r.model_requests_reserved,j.available_at,j.lease_expires_at,j.deadline_at FROM jobs j LEFT JOIN ai_runs r ON r.job_id=j.id WHERE j.id=?",job);
    var diagnostics=new LinkedHashMap<String,Object>();diagnostics.put("job",snapshot.toString());diagnostics.put("provider_hits",gate.hits.get());
    diagnostics.put("provider_requests",f.provider.requests.get());diagnostics.put("provider_active",f.provider.active.get());diagnostics.put("authorization_locked",f.authorizationRowIsLocked(c.tenant(),c.customer().id()));
    var dump=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"jcmd.exe":"jcmd").toString(),String.valueOf(replacement.pid()),"Thread.print","-l").redirectErrorStream(true).redirectOutput(f.root.resolve("replacement-thread-dump.txt").toFile()).start();if(!dump.waitFor(5,TimeUnit.SECONDS))dump.destroyForcibly();
    diagnostics.put("outbox",f.app.db.queryForList("SELECT kind,dispatched_at IS NOT NULL AS dispatched,owner IS NOT NULL AS held,lease_expires_at,available_at FROM outbox_events WHERE job_id=?",job).toString());
    var sessions=new ArrayList<Map<String,Object>>();for(var process:f.app.db.queryForList("SHOW FULL PROCESSLIST")){
     var row=new LinkedHashMap<String,Object>();for(String key:List.of("Id","Command","Time","State"))row.put(key,String.valueOf(process.get(key)));
     Object statement=process.get("Info");if(statement!=null)row.put("statement_prefix",statement.toString().split("WHERE",2)[0].substring(0,Math.min(160,statement.toString().split("WHERE",2)[0].length())));
     sessions.add(row);
    }diagnostics.put("sessions",sessions);diagnostics.put("broker_pending",pendingMessages("shj.ai"));java.nio.file.Files.writeString(f.root.resolve("lease-fencing-failure.json"),f.app.json.writeValueAsString(diagnostics));
    throw new AssertionError(failure.getMessage()+"; persisted state="+snapshot+", provider_hits="+gate.hits.get(),failure);
   }
   long next=f.count("SELECT execution_version FROM jobs WHERE id=?",job);gate.open();
   f.until(()->"SUCCEEDED".equals(f.state(job)),10,"replacement result commits before stale Worker resumes");
   OwnedProcessSignals.change(old.process(),false,f.root);suspended=false;
   Thread.sleep(600);
   long oldWrites=f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=? AND body LIKE ?",run,"%#1]%");
   long nextWrites=f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=? AND body LIKE ?",run,"%#2]%");
   assertThat(f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",run)).isEqualTo(1);
   return Map.of("old_token",before,"replacement_token",next,"old_worker_results",oldWrites,"replacement_results",nextWrites,"lease_gate_hits",gate.hits.get(),"resumed_worker_pid",old.pid(),"replacement_worker_pid",replacement.pid(),"broker_detection_ms",detectionMs,"retired_broker_consumers",oldConsumers);
  }finally{gate.open();if(suspended&&old.process().isAlive())OwnedProcessSignals.change(old.process(),false,f.root);old.process().destroyForcibly();old.process().waitFor(5,TimeUnit.SECONDS);}
 }
 /** A frozen process may retain idle broker consumers until heartbeats evict its connection.
  * Observe that actual boundary before timing takeover; never fake an ack or delete queued work. */
 int foregroundConsumers()throws Exception{
  var report=f.app.dependencies.rabbitmq.execInContainer("rabbitmqctl","list_consumers","queue_name","--quiet");
  assertThat(report.getExitCode()).isZero();return Math.toIntExact(report.getStdout().lines().map(String::strip).filter(line->line.equals("shj.ai")).count());
 }
 void awaitPausedWorkerBrokerEviction()throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
  while(System.nanoTime()<deadline){if(foregroundConsumers()==0)return;Thread.sleep(250);}
  throw new AssertionError("Broker did not evict the actually paused Worker's consumers within40 seconds");
 }
 Map<String,Object> confirmLoss()throws Exception{
  var c=f.fresh(0);var message=f.send(c,"confirm-loss","查询物流",0);f.require(message,200);long job=message.body().path("trigger_job_id").asLong(),run=message.body().path("trigger_run_id").asLong();
  var store=f.app.context.getBean(OutboxStore.class);var redis=f.app.context.getBean(StringRedisTemplate.class);var normal=f.app.context.getBean(OutboxDispatcher.class);
  var broker=f.app.dependencies.rabbitmq;broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
  try{assertThat(normal.dispatchJob(job,"broker-paused")).isFalse();assertThat(dispatched(job)).isZero();}finally{broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec();}
  f.app.db.update("UPDATE outbox_events SET available_at=UTC_TIMESTAMP(6) WHERE job_id=?",job);
  try(var proxy=new AmqpFrameProxy(broker.getHost(),broker.getMappedPort(5672))){
   var connection=new CachingConnectionFactory("127.0.0.1",proxy.localPort());connection.setUsername(f.app.dependencies.username());connection.setPassword(f.app.dependencies.password());connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);connection.setPublisherReturns(true);
   try{
    var rabbit=new RabbitTemplate(connection);rabbit.setMandatory(true);var publisher=new RabbitJobPublisher(rabbit,f.app.json);
    var dispatcher=new OutboxDispatcher(store,publisher,redis,f.app.json);proxy.dropNext.set(1);
    assertThat(dispatcher.dispatchJob(job,"confirm-dropped")).isFalse();long premature=dispatched(job);assertThat(proxy.droppedConfirms.get()).isPositive();
    f.app.db.update("UPDATE outbox_events SET available_at=UTC_TIMESTAMP(6) WHERE job_id=?",job);assertThat(normal.dispatchJob(job,"confirm-recovered")).isTrue();
    f.startWorker();f.until(()->"SUCCEEDED".equals(f.state(job)),15,"duplicate broker deliveries produce one durable result");
    return Map.of("confirm_loss_hits",proxy.droppedConfirms.get(),"logical_jobs",f.count("SELECT COUNT(*) FROM jobs WHERE conversation_id=?",c.room()),"final_results",f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id=?",run),"premature_dispatched",premature);
   }finally{connection.destroy();}
  }
 }
 long dispatched(long job){return f.count("SELECT COUNT(*) FROM outbox_events WHERE job_id=? AND kind='job' AND dispatched_at IS NOT NULL",job);}
 Map<String,Object> dependencyRetry()throws Exception{
  f.startWorker();var c=f.fresh(0);String tag="DBFAULT_"+c.tenant();var gate=f.provider.gate(tag,false,1);var message=f.send(c,"db-fault","查询物流 "+tag,0);f.require(message,200);gate.await();long job=message.body().path("trigger_job_id").asLong();
  var mysql=f.app.dependencies.mysql;int pending;var brokerObservation=new BrokerObservation(f.app.dependencies.rabbitmq);
  assertThat(brokerObservation.unacknowledgedDeliveries()).as("Actual provider call retains its manual delivery before the SQL fault").isPositive();
  mysql.getDockerClient().pauseContainerCmd(mysql.getContainerId()).exec();
  try{assertThat(mysql.getDockerClient().inspectContainerCmd(mysql.getContainerId()).exec().getState().getPaused()).isTrue();
   gate.open();Thread.sleep(1200);pending=brokerObservation.unacknowledgedDeliveries();
   assertThat(pending).as("Broker channel must retain an unacknowledged delivery while SQL disposition cannot commit").isPositive();}
  finally{mysql.getDockerClient().unpauseContainerCmd(mysql.getContainerId()).exec();}
  if("RUNNING".equals(f.state(job)))f.app.db.update("UPDATE jobs SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",job);
  f.until(()->"SUCCEEDED".equals(f.state(job)),20,"database recovery retains accepted work");
  var retry=f.fresh(0);String retryTag="RETRY_"+retry.tenant();var retryGate=f.provider.gate(retryTag,false,1);retryGate.status=503;retryGate.open();var retried=f.send(retry,"provider-retry","查询物流 "+retryTag,1);f.require(retried,200);long retryJob=retried.body().path("trigger_job_id").asLong();
  f.until(()->"FAILED".equals(f.state(retryJob)),55,"three total attempts exhaust after5/30-second backoff");
  long attempts=f.count("SELECT attempts FROM jobs WHERE id=?",retryJob);assertThat(attempts).isEqualTo(3);assertThat(retryGate.hitTimes).hasSize(3);
  long firstDelay=TimeUnit.NANOSECONDS.toMillis(retryGate.hitTimes.get(1)-retryGate.hitTimes.get(0)),secondDelay=TimeUnit.NANOSECONDS.toMillis(retryGate.hitTimes.get(2)-retryGate.hitTimes.get(1));
  assertThat(firstDelay).isGreaterThanOrEqualTo(4900);assertThat(secondDelay).isGreaterThanOrEqualTo(29900);
  var permanent=f.fresh(0);String permanentTag="PERMANENT_"+permanent.tenant();var permanentGate=f.provider.gate(permanentTag,false,1);permanentGate.status=400;permanentGate.open();var rejected=f.send(permanent,"provider-permanent","查询物流 "+permanentTag,0);f.require(rejected,200);long rejectedJob=rejected.body().path("trigger_job_id").asLong();
  f.until(()->"FAILED".equals(f.state(rejectedJob)),12,"permanent provider error does not retry");
  long falseSuccesses=f.count("SELECT COUNT(*) FROM messages WHERE ai_run_id IN(?,?)",retried.body().path("trigger_run_id").asLong(),rejected.body().path("trigger_run_id").asLong());
  return Map.of("db_fault_hits",1,"acks_without_disposition",pending>0?0:1,"maximum_attempts",attempts,"permanent_error_attempts",f.count("SELECT attempts FROM jobs WHERE id=?",rejectedJob),"false_successes",falseSuccesses,"retry_delays_ms",List.of(firstDelay,secondDelay),"pending_broker_deliveries_during_fault",pending);
 }
 int pendingMessages(String name)throws Exception{
  var report=f.app.dependencies.rabbitmq.execInContainer("rabbitmqctl","list_queues","name","messages_unacknowledged","messages_ready","--quiet");assertThat(report.getExitCode()).isZero();
  for(String line:report.getStdout().split("\\R")){String[] values=line.strip().split("\\s+");if(values.length==3&&values[0].equals(name))return Integer.parseInt(values[1])+Integer.parseInt(values[2]);}
  throw new AssertionError("Actual broker queue statistics missing");
 }
 Map<String,Object> isolation()throws Exception{
  f.startWorker();var warm=f.fresh(0);var warming=f.send(warm,"warm-model","查询物流",0);f.require(warming,200);f.until(()->"SUCCEEDED".equals(f.state(warming.body().path("trigger_job_id").asLong())),15,"warm provider connections and vector index");
  var c=f.fresh(0);var gates=new ArrayList<ControlledProvider.Gate>();var documents=new ArrayList<MultiProcessFixture.Packet>();
  for(int i=0;i<3;i++){String tag="BGBLOCK_"+c.tenant()+"_"+i;var gate=f.provider.gate(tag,true,1);gates.add(gate);var document=f.upload(c,tag);f.require(document,201);documents.add(document);gate.await();}
  var fourth=f.upload(c,"BACKGROUND_QUEUED");f.require(fourth,201);long fourthJob=fourth.body().path("latest_version").path("job_id").asLong();
  assertThat(f.state(fourthJob)).isIn("QUEUED","RETRY_WAIT");
  String tag="FOREGROUND_"+c.tenant();var foreground=f.provider.gate(tag,false,1);foreground.background=false;foreground.open();
  long sent=System.nanoTime();var question=f.send(c,"reserved-foreground","查询物流 "+tag,1);f.require(question,200);f.awaitProvider(foreground,question);long modelMs=TimeUnit.NANOSECONDS.toMillis(foreground.firstHitNanos.get()-sent);
  long job=question.body().path("trigger_job_id").asLong();long executionMs=f.count("SELECT TIMESTAMPDIFF(MICROSECOND,m.created_at,j.started_at) DIV 1000 FROM jobs j JOIN messages m ON m.id=CAST(JSON_EXTRACT(j.payload,'$.message_id') AS UNSIGNED) WHERE j.id=?",job);
  var snapshot=f.request(0,"GET",f.room(c),null,c.customer());f.require(snapshot,200);
  f.require(f.request(1,"POST",f.room(c)+"/topics/switch",Map.of("mode","NEW","expected_generation_epoch",snapshot.body().path("generation_epoch").asLong()),c.customer()),200);
  gates.forEach(ControlledProvider.Gate::open);
  for(var document:documents){long backgroundJob=document.body().path("latest_version").path("job_id").asLong();f.until(()->"SUCCEEDED".equals(f.state(backgroundJob)),15,"fixed-version background ingestion survives foreground switch");}
  long completed=documents.stream().filter(document->"SUCCEEDED".equals(f.state(document.body().path("latest_version").path("job_id").asLong()))).count();
  return Map.of("blocked_backgrounds",gates.stream().filter(gate->gate.hits.get()>0).count(),"foreground_admission_ms",executionMs,"foreground_model_admission_ms",modelMs,"peak_active",f.provider.peak.get(),"peak_background",f.provider.backgroundPeak.get(),"background_results_after_switch",completed);
 }
}
