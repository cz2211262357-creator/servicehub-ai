package cn.servicehub.support;
import cn.servicehub.ai.*;
import cn.servicehub.auth.*;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import cn.servicehub.common.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
/** Owned disposable dependencies, two independent production API JVMs and separate Worker JVMs. */
public final class MultiProcessFixture implements AutoCloseable {
 final boolean online;
 final HttpIntegrationApp app=new HttpIntegrationApp();
 final Path workspace=Path.of(Objects.requireNonNull(System.getenv("SERVICEHUB_WORKSPACE_ROOT"))),root=workspace.resolve(".cache/java-verification/system-"+UUID.randomUUID());
 final AtomicLong ids=new AtomicLong(1000000);final List<Child> children=new ArrayList<>();final List<ExecutionPermit> permits=new ArrayList<>();
 final ExecutorService calls=Executors.newVirtualThreadPerTaskExecutor();ControlledProvider provider;Child first,second,worker;String hash;boolean initialized;
 record Child(Process process,int port,String profile,Path log){long pid(){return process.pid();}}
 record Session(long id,String cookie,String csrf){}
 record Packet(HttpResponse<String> response,JsonNode body){int status(){return response.statusCode();}}
 record Case(long tenant,Session customer,Session admin,List<Session> agents,long room,long order,String orderNumber){}
 public MultiProcessFixture(){this(false);}
 public MultiProcessFixture(boolean online){this.online=online;}
 public Map<String,Object> run(String scenario){
  try{
   if(Set.of("maven-boundary","docker-boundary").contains(scenario))return invokeBuild(scenario);
   initialize();Map<String,Object> result=new LinkedHashMap<>();
   switch(scenario){
    case "duplicate-message"->result.put("rounds",new ConcurrentScenarios(this).duplicates());
    case "message-atomicity"->result.put("rounds",new ConcurrentScenarios(this).atomicity());
    case "switch-race"->result.put("rounds",new ConcurrentScenarios(this).switches());
    case "confirmation-claim"->result.put("rounds",new ConcurrentScenarios(this).confirmations());
    case "revoke-first"->result.put("rounds",new ConcurrentScenarios(this).revokeFirst());
    case "business-first"->result.put("rounds",new ConcurrentScenarios(this).businessFirst());
    default->result.putAll(new RecoveryScenarios(this).run(scenario));
   }
   result.put("api_pids",List.of(first.pid(),second.pid()));result.put("scenario",scenario);result.put("owned_fixture",root.getFileName().toString());
   result.put("java",System.getProperty("java.version"));result.putAll(SourceIdentity.current());result.put("fixture_seed",Map.of("first_tenant_id",1000000,"tenant_stride",100,"private_dependencies",true));result.put("jar_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar()))));
   Files.writeString(root.resolve(scenario+".json"),app.json.writeValueAsString(result),StandardCharsets.UTF_8);
   return result;
  }catch(Exception error){throw new AssertionError("Actual system scenario failed: "+scenario,error);}
 }
 @SuppressWarnings("unchecked") private Map<String,Object> invokeBuild(String scenario)throws Exception{return new BuildScenarios(workspace,root).run(scenario);}
 void initialize()throws Exception{
  if(initialized)return;Files.createDirectories(root);
  app.dependencies.mysql.withCommand("--default-time-zone=+00:00","--ngram_token_size=2","--log-bin-trust-function-creators=1");
  if(online)app.start(Map.of("servicehub.workspace-root",root.toString(),"servicehub.environment","test"));
  else{provider=new ControlledProvider();app.start(Map.of("servicehub.workspace-root",root.toString(),"servicehub.model.base-url",provider.base(),"servicehub.model.api-key","synthetic-system-key",
   "servicehub.model.embedding-base-url",provider.base(),"servicehub.model.embedding-api-key","synthetic-system-key","servicehub.environment","test"));}
  hash=new String(Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/legacy-password.txt")).readAllBytes(),StandardCharsets.UTF_8).strip();
  app.db.execute("CREATE TABLE fixture_fault_controls(tenant_id BIGINT NOT NULL PRIMARY KEY,stage VARCHAR(30),gate_name VARCHAR(80)) ENGINE=InnoDB");
  app.db.execute("CREATE TABLE fixture_fault_hits(id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT,stage VARCHAR(30)) ENGINE=MEMORY");
  app.db.execute("CREATE TRIGGER fixture_message_fault BEFORE INSERT ON messages FOR EACH ROW BEGIN IF EXISTS(SELECT 1 FROM fixture_fault_controls WHERE tenant_id=NEW.tenant_id AND stage='message') THEN INSERT INTO fixture_fault_hits(tenant_id,stage) VALUES(NEW.tenant_id,'message'); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic message insertion failure'; END IF; END");
  app.db.execute("CREATE TRIGGER fixture_job_fault BEFORE INSERT ON jobs FOR EACH ROW BEGIN IF EXISTS(SELECT 1 FROM fixture_fault_controls WHERE tenant_id=NEW.tenant_id AND stage='job') THEN INSERT INTO fixture_fault_hits(tenant_id,stage) VALUES(NEW.tenant_id,'job'); SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic job insertion failure'; END IF; END");
  app.db.execute("CREATE TRIGGER fixture_draft_gate BEFORE INSERT ON ticket_drafts FOR EACH ROW BEGIN DECLARE gateName VARCHAR(80); DECLARE acquired INT; SELECT gate_name INTO gateName FROM fixture_fault_controls WHERE tenant_id=NEW.tenant_id AND stage='draft'; IF gateName IS NOT NULL THEN INSERT INTO fixture_fault_hits(tenant_id,stage) VALUES(NEW.tenant_id,'draft'); DO GET_LOCK(CONCAT(gateName,':entered'),0); SELECT GET_LOCK(gateName,12) INTO acquired; DO RELEASE_LOCK(CONCAT(gateName,':entered')); IF acquired<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic transaction gate timeout'; END IF; DO RELEASE_LOCK(gateName); END IF; END");
  first=launch("api","Asia/Shanghai");second=launch("api","Pacific/Honolulu");initialized=true;
 }
 private Path jar(){return PythonReference.worktree().resolve("backend-java/target/servicehub-backend-0.1.0.jar");}
 Child launch(String profile,String timezone)throws Exception{
  Path java=Path.of(System.getProperty("java.home")).resolve("bin").resolve(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
  Path log=root.resolve(profile+"-"+UUID.randomUUID()+".log");
  var command=new ArrayList<String>(List.of(java.toString(),"-Xmx256m","-Dfile.encoding=UTF-8","-Djava.io.tmpdir="+workspace.resolve(".cache/java-tmp"),"-Duser.timezone="+timezone,"-jar",jar().toString(),"--spring.profiles.active="+profile,"--server.port=0"));
  var builder=new ProcessBuilder(command).directory(PythonReference.worktree().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
  builder.environment().keySet().removeIf(key->key.startsWith("SERVICEHUB_MODEL_")||key.startsWith("SERVICEHUB_EMBEDDING_")||key.startsWith("SERVICEHUB_JAVA_")||Set.of("OPENAI_API_KEY","DASHSCOPE_API_KEY").contains(key));
  var config=new LinkedHashMap<String,Object>();var d=app.dependencies;
  config.put("servicehub.workspace-root",root.toString());config.put("servicehub.environment","test");
  config.put("servicehub.database.url",d.jdbcUrl());config.put("servicehub.database.username",d.username());config.put("servicehub.database.password",d.password());
  config.put("servicehub.redis.host",d.redis.getHost());config.put("servicehub.redis.port",d.redis.getMappedPort(6379));config.put("servicehub.redis.password",d.password());
  config.put("spring.data.redis.host",d.redis.getHost());config.put("spring.data.redis.port",d.redis.getMappedPort(6379));config.put("spring.data.redis.password",d.password());
  config.put("servicehub.rabbitmq.host",d.rabbitmq.getHost());config.put("servicehub.rabbitmq.port",d.rabbitmq.getMappedPort(5672));config.put("servicehub.rabbitmq.username",d.username());config.put("servicehub.rabbitmq.password",d.password());
  var model=app.context.getBean(cn.servicehub.config.ServiceHubProperties.class).model();
  config.put("servicehub.model.base-url",model.baseUrl());config.put("servicehub.model.api-key",model.apiKey());config.put("servicehub.model.name",model.name());
  config.put("servicehub.model.embedding-base-url",model.embeddingBaseUrl());config.put("servicehub.model.embedding-api-key",model.embeddingApiKey());config.put("servicehub.model.embedding-name",model.embeddingName());config.put("servicehub.model.embedding-dimensions",model.embeddingDimensions());
  builder.environment().put("SPRING_APPLICATION_JSON",app.json.writeValueAsString(config));
  var process=builder.start();var child=new Child(process,0,profile,log);children.add(child);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);Pattern port=Pattern.compile("Tomcat started on port (\\d+)");
  while(System.nanoTime()<end){
   if(!process.isAlive())throw new AssertionError("Owned "+profile+" child exited; diagnostic: "+log);
   String output=Files.exists(log)?Files.readString(log,StandardCharsets.UTF_8):"";
   if(profile.equals("api")){var match=port.matcher(output);if(match.find()){var ready=new Child(process,Integer.parseInt(match.group(1)),profile,log);children.set(children.size()-1,ready);return ready;}}
   else if(output.contains("Started ServiceHubApplication"))return child;
   Thread.sleep(100);
  }
  throw new AssertionError("Owned child did not become ready: "+log);
 }
 Child api(int index){return (index&1)==0?first:second;}
 Packet request(int api,String method,String path,Map<String,Object> body,Session session)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api(api).port()+path)).timeout(Duration.ofSeconds(20)).header("Origin","http://localhost:8081").header("Content-Type","application/json").header("x-request-id","system-"+UUID.randomUUID());
  if(session!=null)builder.header("Cookie",session.cookie()).header("x-csrf-token",session.csrf());
  builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(app.json.writeValueAsString(body),StandardCharsets.UTF_8));
  var response=app.http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  return new Packet(response,response.body().isBlank()?app.json.nullNode():app.json.readTree(response.body()));
 }
 Session login(long tenant,long user)throws Exception{
  var response=request(0,"POST","/api/v1/auth/login",Map.of("tenant_id",tenant,"email","system"+user+"@example.com","password",HttpIntegrationApp.PASSWORD),null);
  require(response,200);return new Session(user,response.response().headers().firstValue("set-cookie").orElseThrow().split(";",2)[0],response.body().path("csrf_token").asString());
 }
 Packet upload(Case f,String tag)throws Exception{return upload(f,tag,"policy.txt","text/plain",("七天内可退换。"+tag).getBytes(StandardCharsets.UTF_8));}
 Packet upload(Case f,String title,String name,String media,byte[] bytes)throws Exception{
  String boundary="system-"+UUID.randomUUID();var out=new java.io.ByteArrayOutputStream();
  out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n"+title+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+media+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
  out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+first.port()+"/api/v1/knowledge/documents")).timeout(Duration.ofSeconds(15)).header("Cookie",f.admin().cookie()).header("x-csrf-token",f.admin().csrf()).header("Origin","http://localhost:8081").header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
  var response=app.http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));return new Packet(response,app.json.readTree(response.body()));
 }
 long authorizedToolControl()throws Exception{
  var c=fresh(0);String tag="CONTROL_"+c.tenant();var gate=provider.gate(tag,false,1);
  gate.tool="prepare_after_sale";gate.arguments=app.json.writeValueAsString(Map.of("order_number",c.orderNumber(),"kind","RETURN","reason","尺码不合适"));
  var accepted=send(c,"positive-control","查询订单 "+c.orderNumber()+"，准备售后 "+tag,0);require(accepted,200);gate.await();gate.open();
  long job=accepted.body().path("trigger_job_id").asLong(),run=accepted.body().path("trigger_run_id").asLong();
  until(()->"SUCCEEDED".equals(state(job)),15,"authorized positive control must complete the same gated tool action");
  long drafts=count("SELECT COUNT(*) FROM ticket_drafts WHERE tenant_id=? AND origin_run_id=?",c.tenant(),run);
  assertThat(drafts).isEqualTo(1);assertThat(count("SELECT COUNT(*) FROM tool_calls WHERE ai_run_id=? AND outcome='success'",run)).isEqualTo(1);
  assertThat(count("SELECT COUNT(*) FROM after_sale_tickets WHERE tenant_id=?",c.tenant())).isZero();return drafts;
 }
 Case fresh(int agents)throws Exception{
  long base=ids.getAndAdd(100);long tenant=base;app.db.update("INSERT INTO tenants(id,name) VALUES(?,'System test')",tenant);
  for(int i=1;i<=agents+2;i++){String role=i==1?"admin":i==2?"customer":"agent";
   app.db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(?,?,?,'Synthetic user',?,?)",base+i,tenant,"system"+(base+i)+"@example.com",hash,role);
  }
  var admin=login(tenant,base+1);var customer=login(tenant,base+2);var staff=new ArrayList<Session>();for(int i=0;i<agents;i++)staff.add(login(tenant,base+3+i));
  String order="ORD-SYSTEM-"+base;app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount,currency,status) VALUES(?,?,?,?,12.30,'CNY','DELIVERED')",base+50,tenant,customer.id(),order);
  var created=request(0,"POST","/api/v1/conversations",Map.of(),customer);require(created,201);
  return new Case(tenant,customer,admin,staff,created.body().path("id").asLong(),base+50,order);
 }
 Packet send(Case f,String key,String text,int api)throws Exception{return request(api,"POST","/api/v1/conversations/"+f.room()+"/messages",Map.of("client_message_id",key,"body",text),f.customer());}
 String room(Case f){return "/api/v1/conversations/"+f.room();}
 void awaitProvider(ControlledProvider.Gate gate,Packet message)throws Exception{
  try{gate.await();}catch(AssertionError missing){
   long job=message.body().path("trigger_job_id").asLong();
   var row=app.db.queryForMap("SELECT j.state,j.error_code,j.attempts,r.classification_attempted,r.classification_result FROM jobs j LEFT JOIN ai_runs r ON r.job_id=j.id WHERE j.id=?",job);
   throw new AssertionError("Actual provider gate not reached: state="+row+", requests="+provider.requests.get()+", outbox_remaining="+count("SELECT COUNT(*) FROM outbox_events WHERE dispatched_at IS NULL"),missing);
  }
 }
 void require(Packet packet,int status){assertThat(packet.status()).as("Actual API status; code="+packet.body().path("error").path("code").asString()).isEqualTo(status);}
 long count(String sql,Object...args){return app.db.queryForObject(sql,Long.class,args);}
 String state(long job){return app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,job);}
 void until(BooleanSupplier condition,int seconds,String label)throws Exception{
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(50);}throw new AssertionError("Actual condition timed out: "+label);
 }
 <T> List<T> race(int size,ThrowingIntFunction<T> action)throws Exception{
  var ready=new CountDownLatch(size);var go=new CountDownLatch(1);var futures=new ArrayList<Future<T>>();
  for(int i=0;i<size;i++){int index=i;futures.add(calls.submit(()->{ready.countDown();if(!go.await(5,TimeUnit.SECONDS))throw new AssertionError("Request start barrier failed");return action.apply(index);}));}
  if(!ready.await(5,TimeUnit.SECONDS))throw new AssertionError("All racing clients must reach barrier");go.countDown();var values=new ArrayList<T>();for(var future:futures)values.add(future.get(25,TimeUnit.SECONDS));return values;
 }
 @FunctionalInterface interface ThrowingIntFunction<T>{T apply(int index)throws Exception;}
 boolean authorizationRowIsLocked(long tenant,long user)throws Exception{
  try(var connection=DriverManager.getConnection(app.dependencies.jdbcUrl(),app.dependencies.username(),app.dependencies.password())){
   connection.setAutoCommit(false);
   try(var statement=connection.prepareStatement("SELECT id FROM users WHERE tenant_id=? AND id=? FOR UPDATE NOWAIT")){
    statement.setLong(1,tenant);statement.setLong(2,user);statement.setQueryTimeout(2);
    try(var rows=statement.executeQuery()){if(!rows.next())throw new AssertionError("Expected authorization row missing");return false;}
    catch(java.sql.SQLException error){if(error.getErrorCode()==3572)return true;throw error;}
   }finally{connection.rollback();}
  }
 }
 void resetScenarioResources()throws Exception{
  provider.openAllGates();stopWorker();for(var child:children)if(child.profile().equals("worker")&&child.process().isAlive())stop(child);
  until(()->provider.active.get()==0,5,"previous fixture provider calls closed");
  var redis=app.context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
  for(String pattern:List.of("shj:{slots:execution}:*","shj:{slots:model}:*")){var keys=redis.keys(pattern);if(keys!=null&&!keys.isEmpty())redis.delete(keys);}
  provider.peak.set(0);provider.backgroundPeak.set(0);
 }
 void stopWorker()throws Exception{if(worker!=null){stop(worker);worker=null;}permits.forEach(ExecutionPermit::close);permits.clear();}
 void retireOutstanding(){app.db.update("UPDATE jobs SET state='CANCELLED',owner=NULL,lease_expires_at=NULL WHERE tenant_id>=1000000 AND state IN('QUEUED','RETRY_WAIT','RUNNING','WAITING_INPUT')");app.db.update("UPDATE ai_runs SET state='CANCELLED' WHERE tenant_id>=1000000 AND state IN('QUEUED','RETRY_WAIT','RUNNING','WAITING_INPUT')");app.db.update("UPDATE outbox_events SET dispatched_at=UTC_TIMESTAMP(6) WHERE tenant_id>=1000000 AND dispatched_at IS NULL");}
 void startWorker()throws Exception{if(worker==null)worker=launch("worker","UTC");}
 JobLease claim(Packet message){
  String owner="system-parent-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());
  return app.context.getBean(JobCoordinator.class).claim(message.body().path("trigger_job_id").asLong(),owner).orElseThrow();
 }
 ExecutionFence resolve(JobLease lease,Packet message){return app.context.getBean(TopicService.class).resolve(lease,message.body().path("id").asLong(),TopicDecision.of("CONTINUE",null,false)).orElseThrow();}
 private void stop(Child child)throws Exception{
  if(!child.process().isAlive())return;child.process().destroy();if(!child.process().waitFor(5,TimeUnit.SECONDS)){child.process().destroyForcibly();child.process().waitFor(5,TimeUnit.SECONDS);}
 }
 @Override public void close(){
  if(provider!=null)provider.close();permits.forEach(ExecutionPermit::close);for(int i=children.size()-1;i>=0;i--)try{stop(children.get(i));}catch(Exception e){children.get(i).process().destroyForcibly();}
  calls.shutdownNow();app.close();
 }
}
