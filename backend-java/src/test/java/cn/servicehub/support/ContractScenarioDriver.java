package cn.servicehub.support;
import cn.servicehub.support.ContractPeer.*;
import java.io.*;
import java.math.*;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Each row executes real requests against independent implementations and validates stored values. */
public final class ContractScenarioDriver implements AutoCloseable {
 private final HttpIntegrationApp app;private final PythonReference reference;private final ContractPeer java,python;private final JsonNode document;private final ContractSchema schema;
 private final AtomicLong ids=new AtomicLong(100000);private final String hash;private final List<HttpResponse<String>> packets=new ArrayList<>();
 private record Actor(String name,long tenant,long id,String role,String email){}
 private record Fixture(long tenant,long otherTenant,long customer,long otherCustomer,String order,List<Actor> actors,Map<String,Login> javaLogins,Map<String,Login> pythonLogins,Map<String,Long> resources){}
 public ContractScenarioDriver(HttpIntegrationApp app){
  this.app=app;reference=new PythonReference(app);java=new ContractPeer(app,null);python=new ContractPeer(app,reference);
  try{
   Path root=PythonReference.worktree(),workspace=Path.of(System.getenv("SERVICEHUB_WORKSPACE_ROOT"));
   Path executable=workspace.resolve(".venv/Scripts/python.exe");if(!Files.exists(executable))executable=workspace.resolve(".venv/bin/python");
   var generate=new ProcessBuilder(executable.toString(),"-B",root.resolve("scripts/export-java-contract.py").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
   if(!generate.waitFor(15,TimeUnit.SECONDS)||generate.exitValue()!=0)throw new IllegalStateException("Cannot export additive contract schema");
   document=app.json.readTree(Files.readString(root.resolve(".cache/java-openapi.json")));schema=new ContractSchema(document);
   hash=new String(Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/legacy-password.txt")).readAllBytes(),StandardCharsets.UTF_8).strip();
  }catch(Exception e){close();throw new IllegalStateException("Contract driver initialization failed",e);}
 }
 public List<HttpResponse<String>> verify(Map<String,Object> contract){
  packets.clear();String id=String.valueOf(contract.get("id"));var fixture=fixture(id);
  if(id.equals("W01")){verifySocket(fixture);return List.copyOf(packets);}
  prepare(fixture,id);
  if(id.startsWith("N")){verifyExtension(fixture,id,contract);return List.copyOf(packets);}
  for(var actor:fixture.actors()){
   String request="api-"+id+"-"+actor.name()+"-"+UUID.randomUUID();
   String path=path(contract,fixture);
   Map<String,Object> body=input(id,fixture,actor);
   Reply a,b;
   if(Set.of("H31","H36").contains(id)){
    var fields=id.equals("H31")?Map.of("title","合同政策"):Map.<String,String>of();
    byte[] content="七天内保持完好可以退换货。".getBytes(StandardCharsets.UTF_8);
    a=java.multipart(path,fields,"policy.txt",content,"text/plain",fixture.javaLogins().get(actor.name()),request);
    b=python.multipart(path,fields,"policy.txt",content,"text/plain",fixture.pythonLogins().get(actor.name()),request);
   }else{
    a=java.request(String.valueOf(contract.get("method")),path,body,fixture.javaLogins().get(actor.name()),request);
    b=python.request(String.valueOf(contract.get("method")),path,body,fixture.pythonLogins().get(actor.name()),request);
   }
   compare(id,contract,a,b,request);
  }
  boundaries(fixture,id,contract);
  return List.copyOf(packets);
 }
 private String path(Map<String,Object> contract,Fixture f){
  String path=String.valueOf(contract.get("path")).replace("{order_number}",f.order());
  for(var resource:f.resources().entrySet())path=path.replace("{"+resource.getKey()+"}",String.valueOf(resource.getValue()));
  if(contract.get("id").equals("H07"))path+="?start=2026-10-01T00:00:00Z&end=2026-11-01T00:00:00Z";
  return path;
 }
 private Map<String,Object> input(String id,Fixture f,Actor actor){
  return switch(id){
   case "H05"->Map.of("email","new-"+actor.name()+"-"+f.tenant()+"@example.com","display_name","新增成员","password",HttpIntegrationApp.PASSWORD,"role","agent");
   case "H06"->Map.of("display_name","已更新名称");
   case "H11"->Map.of("order_id",f.resources().get("order_id"),"kind","RETURN","reason"," 尺码不合适 ");
   case "H12"->Map.of("version",1,"idempotency_key","contract-confirm-"+f.tenant());
   case "H14"->Map.of("version",1,"status","IN_REVIEW","note","核验材料");
   case "H15"->Map.of("tenant_id",f.tenant(),"email",f.actors().get(1).email(),"password",HttpIntegrationApp.PASSWORD);
   case "H19"->Map.of("title","新的售后咨询");
   case "H21"->Map.of("version",1);
   case "H27"->Map.of("client_message_id","contract-message-"+actor.name(),"body"," 查询订单 "+f.order()+" ");
   case "H34"->Map.of("enabled",false,"version",1);
   default->null;
  };
 }
 private void prepare(Fixture f,String id){
  if(id.equals("H21"))for(var peer:List.of(java,python))peer.sql("UPDATE conversations SET status='WAITING_HUMAN',generation_epoch=1 WHERE id=:id",Map.of("id",f.resources().get("conversation_id")));
  if(Set.of("H24","H02","H03").contains(id)&&id.equals("H24"))for(var peer:List.of(java,python)){
   var response=peer.request("POST","/api/v1/conversations/"+f.resources().get("conversation_id")+"/messages",Map.of("client_message_id","contract-history","body","查询物流"),logins(f,peer).get("customer"),"setup-"+UUID.randomUUID());
   assertThat(response.status()).isEqualTo(200);
  }
  if(Set.of("H09","H10","H12","H13","H14").contains(id)){
   Long draft=null,ticket=null;
   for(var peer:List.of(java,python)){
    var response=peer.request("POST","/api/v1/after-sales/drafts",Map.of("order_id",f.resources().get("order_id"),"kind","RETURN","reason","尺码不合适"),logins(f,peer).get("customer"),"setup-"+UUID.randomUUID());
    assertThat(response.status()).as("Draft setup").isEqualTo(201);long value=response.body().path("id").asLong();
    if(draft!=null)assertThat(value).as("Independent fixture sequence must match").isEqualTo(draft);draft=value;
    if(Set.of("H09","H13","H14").contains(id)){
     var confirmed=peer.request("POST","/api/v1/after-sales/drafts/"+value+"/confirm",Map.of("version",1,"idempotency_key","setup-confirm-"+f.tenant()),logins(f,peer).get("customer"),"setup-"+UUID.randomUUID());
     assertThat(confirmed.status()).isEqualTo(200);value=confirmed.body().path("id").asLong();
     if(ticket!=null)assertThat(value).isEqualTo(ticket);ticket=value;
    }
   }
   f.resources().put("draft_id",draft);if(ticket!=null)f.resources().put("ticket_id",ticket);
  }
  if(Set.of("H02","H03","H30","H32","H33","H34","H35","H36").contains(id)){
   Long doc=null,version=null,job=null;
   for(var peer:List.of(java,python)){
    var response=peer.multipart("/api/v1/knowledge/documents",Map.of("title","合同政策"),"policy.txt","七天内保持完好可以退换货。".getBytes(StandardCharsets.UTF_8),"text/plain",logins(f,peer).get("admin"),"setup-"+UUID.randomUUID());
    assertThat(response.status()).as("Document setup").isEqualTo(201);var body=response.body();
    long d=body.path("id").asLong(),v=body.path("latest_version").path("id").asLong(),j=body.path("latest_version").path("job_id").asLong();
    if(doc!=null){assertThat(d).isEqualTo(doc);assertThat(v).isEqualTo(version);assertThat(j).isEqualTo(job);}
    doc=d;version=v;job=j;
    if(id.equals("H03"))peer.sql("UPDATE jobs SET state=:state,error_code='contract_failure' WHERE id=:id",Map.of("state",peer.legacy()?"failed":"FAILED","id",j));
   }
   f.resources().put("document_id",doc);f.resources().put("version_id",version);f.resources().put("job_id",job);
  }
 }
 private Map<String,Login> logins(Fixture f,ContractPeer peer){return peer.legacy()?f.pythonLogins():f.javaLogins();}
 private void boundaries(Fixture f,String id,Map<String,Object> contract){
  if(id.equals("H15")){
   var invalid=Map.<String,Object>of("tenant_id",f.tenant(),"email",f.actors().get(1).email(),"password","Wrong-password-123!");
   paired(id,contract,"POST","/api/v1/auth/login",invalid,null,null);
  }
  if(Set.of("H05","H06","H11","H12","H14","H15","H19","H21","H27","H34").contains(id)){
   String actorName=Set.of("H11","H12","H19","H27").contains(id)?"customer":"admin";
   Map<String,Object> invalid=switch(id){
    case "H05"->Map.of("email","invalid-boundary-"+f.tenant()+"@example.com","display_name","","password",HttpIntegrationApp.PASSWORD);
    case "H06"->Map.of("display_name","");
    case "H11"->Map.of("order_id",f.resources().get("order_id"),"kind","RETURN","reason","");
    case "H12"->Map.of("version",0,"idempotency_key","invalid-version");
    case "H14"->Map.of("version",0,"status","IN_REVIEW");
    case "H15"->Map.of("tenant_id",f.tenant(),"email",f.actors().get(1).email(),"password","");
    case "H19"->Map.of("title","");
    case "H21"->Map.of("version",0);
    case "H27"->Map.of("client_message_id","empty-body","body","");
    default->Map.of("version",0,"enabled",true);
   };
   paired(id,contract,String.valueOf(contract.get("method")),path(contract,f),invalid,id.equals("H15")?null:f.javaLogins().get(actorName),id.equals("H15")?null:f.pythonLogins().get(actorName));
  }
  if(Set.of("H01","H02","H04","H09","H18","H26","H30","H38").contains(id))paired(id,contract,"GET",path(contract,f)+"?limit=101",null,f.javaLogins().get("admin"),f.pythonLogins().get("admin"));
  if(id.equals("H16")){
   var admin=f.actors().stream().filter(actor->actor.name().equals("admin")).findFirst().orElseThrow();
   f.javaLogins().put("admin",java.login(admin.tenant(),admin.id(),admin.email()));f.pythonLogins().put("admin",python.login(admin.tenant(),admin.id(),admin.email()));
  }
  if(!String.valueOf(contract.get("method")).equals("GET")&&!id.equals("H15")){
   String request="csrf-"+UUID.randomUUID();var a=java.request(String.valueOf(contract.get("method")),path(contract,f),input(id,f,f.actors().get(3)),f.javaLogins().get("admin"),request,false,"http://localhost:8081");
   var b=python.request(String.valueOf(contract.get("method")),path(contract,f),input(id,f,f.actors().get(3)),f.pythonLogins().get("admin"),request,false,"http://localhost:8081");
   compare(id,contract,a,b,request);assertThat(a.status()).isEqualTo(403);
  }
 }
 private void paired(String id,Map<String,Object> contract,String method,String path,Map<String,Object> body,Login a,Login b){
  String request="boundary-"+UUID.randomUUID();compare(id,contract,java.request(method,path,body,a,request),python.request(method,path,body,b,request),request);
 }
 private void verifyExtension(Fixture f,String id,Map<String,Object> contract){
  long room=f.resources().get("conversation_id");String path=path(contract,f);
  for(var actor:f.actors()){
   Map<String,Object> body=id.equals("N01")?Map.of("expected_generation_epoch",0):id.equals("N03")?Map.of("mode","NEW","expected_generation_epoch",0):null;
   var response=java.request(String.valueOf(contract.get("method")),path,body,f.javaLogins().get(actor.name()),"new-"+UUID.randomUUID());packets.add(response.actual());
   int expected=actor.name().equals("anonymous")?401:actor.tenant()!=f.tenant()||actor.name().equals("other_customer")?404:!id.equals("N02")&&!actor.name().equals("customer")?409:200;
   assertThat(response.status()).as(id+" "+actor.name()).isEqualTo(expected);
   if(expected==200)schema.response(operation(contract),200,response.body());
  }
  if(id.equals("N01")){
   assertThat(((Number)java.scalar("SELECT generation_epoch FROM conversations WHERE id=:id",Map.of("id",room))).longValue()).isEqualTo(1);
   var again=java.request("POST",path,Map.of("expected_generation_epoch",0),f.javaLogins().get("customer"),"retry-"+UUID.randomUUID());assertThat(again.status()).isEqualTo(200);assertThat(again.body().path("generation_epoch").asLong()).isEqualTo(1);
  }
  if(id.equals("N03")){
   var stale=java.request("POST",path,Map.of("mode","NEW","expected_generation_epoch",0),f.javaLogins().get("customer"),"stale-"+UUID.randomUUID());assertThat(stale.status()).isEqualTo(409);
   assertThat(((Number)java.scalar("SELECT COUNT(*) FROM conversation_topics WHERE conversation_id=:id",Map.of("id",room))).intValue()).isEqualTo(2);
  }
  if(!id.equals("N02")){
   var csrf=java.request("POST",path,Map.of("expected_generation_epoch",1,"mode","NEW"),f.javaLogins().get("customer"),"csrf-"+UUID.randomUUID(),false,"http://localhost:8081");assertThat(csrf.status()).isEqualTo(403);
  }
 }
 private void verifySocket(Fixture f){
  long room=f.resources().get("conversation_id");var customer=f.javaLogins().get("customer");
  var message=java.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id","ws-message","body","查询物流"),customer,"ws-http-"+UUID.randomUUID());assertThat(message.status()).isEqualTo(200);packets.add(message.actual());
  try(var wire=new ContractSocket(app,room,0,customer.cookie(),"http://localhost:8081")){
   var durable=new ArrayList<JsonNode>();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
   while(System.nanoTime()<end&&durable.stream().noneMatch(event->event.path("payload").path("message").path("id").asLong()==message.body().path("id").asLong())){
    var event=wire.next(200);if(event==null)continue;
    assertThat(event.propertyNames()).contains("kind","conversation_id","seq","generation_epoch","payload");assertThat(event.path("conversation_id").asLong()).isEqualTo(room);
    if(!event.path("seq").isNull()){if(!durable.isEmpty())assertThat(event.path("seq").asLong()).isGreaterThan(durable.getLast().path("seq").asLong());durable.add(event);}
   }
   assertThat(durable).isNotEmpty();assertThat(durable.stream().anyMatch(event->event.path("payload").path("message").path("id").asLong()==message.body().path("id").asLong())).isTrue();
   long seq=durable.getLast().path("seq").asLong();
   try(var resumed=new ContractSocket(app,room,seq,customer.cookie(),"http://localhost:8081")){
    var heartbeat=resumed.until("heartbeat",4);assertThat(heartbeat.get("seq").isNull()).isTrue();
   }
  }catch(Exception e){throw new AssertionError("Actual WebSocket replay failed",e);}
  try(var anonymous=new ContractSocket(app,room,0,null,"http://localhost:8081")){assertThat(anonymous.closeCode().get(5,TimeUnit.SECONDS)).isEqualTo(4401);assertThat(anonymous.next(20)).isNull();}catch(Exception e){throw new AssertionError(e);}
  try(var foreign=new ContractSocket(app,room,0,f.javaLogins().get("outsider_customer").cookie(),"http://localhost:8081")){assertThat(foreign.closeCode().get(5,TimeUnit.SECONDS)).isEqualTo(4403);assertThat(foreign.next(20)).isNull();}catch(Exception e){throw new AssertionError(e);}
 }
 private Fixture fixture(String caseId){
  long base=ids.getAndAdd(100),tenant=base+1,other=base+2;var actors=new ArrayList<Actor>();
  actors.add(new Actor("anonymous",tenant,0,"anonymous",""));
  String[] names={"customer","agent","admin","other_customer","outsider_admin","outsider_customer"};
  String[] roles={"customer","agent","admin","customer","admin","customer"};
  for(int i=0;i<names.length;i++)actors.add(new Actor(names[i],i>=4?other:tenant,base+10+i,roles[i],"contract"+(base+10+i)+"@example.com"));
  for(var peer:List.of(java,python)){
   peer.sql("INSERT INTO tenants(id,name,created_at) VALUES(:a,:an,UTC_TIMESTAMP(6)),(:b,:bn,UTC_TIMESTAMP(6))",Map.of("a",tenant,"an","Contract "+caseId,"b",other,"bn","Other "+caseId));
   for(var actor:actors)if(actor.id()>0)peer.sql("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role,is_active,session_version,created_at) VALUES(:id,:tenant,:email,:name,:hash,:role,1,1,UTC_TIMESTAMP(6))",
    Map.of("id",actor.id(),"tenant",actor.tenant(),"email",actor.email(),"name",actor.name(),"hash",hash,"role",actor.role()));
   for(int i=0;i<3;i++){
    long customer=i==0?base+10:i==1?base+13:base+15;
    peer.sql("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount,status,currency,created_at) VALUES(:id,:tenant,:customer,:number,12.30,'DELIVERED','CNY',UTC_TIMESTAMP(6))",
     Map.of("id",base+50+i,"tenant",i==2?other:tenant,"customer",customer,"number","ORD-"+caseId+"-"+(base+50+i)));
   }
  }
  var resources=new HashMap<String,Long>();resources.put("order_id",base+50);resources.put("member_id",base+13);resources.put("conversation_id",base+60);
  for(var peer:List.of(java,python)){
   for(int i=0;i<2;i++){
    peer.sql("INSERT INTO conversations(id,tenant_id,customer_id,title,status,version,generation_epoch,last_event_seq,created_at,updated_at) VALUES(:id,:tenant,:customer,'合同售后','AI_ACTIVE',1,0,0,'2026-10-24 12:34:56.123000','2026-10-24 12:34:56.123000')",Map.of("id",base+60+i,"tenant",i==0?tenant:other,"customer",i==0?base+10:base+15));
    if(!peer.legacy()){
     peer.sql("INSERT INTO conversation_topics(id,tenant_id,conversation_id,title,created_at,last_activity_at) VALUES(:id,:tenant,:room,'默认话题','2026-10-24 12:34:56.123000','2026-10-24 12:34:56.123000')",Map.of("id",base+70+i,"tenant",i==0?tenant:other,"room",base+60+i));
     peer.sql("UPDATE conversations SET active_topic_id=:topic WHERE id=:id",Map.of("topic",base+70+i,"id",base+60+i));
    }
   }
   peer.sql("INSERT INTO order_items(id,tenant_id,order_id,product_name,sku,quantity,unit_price,created_at) VALUES(:id,:tenant,:order,'测试商品','SKU-001',1,12.30,'2026-10-24 12:34:56.123000')",Map.of("id",base+80,"tenant",tenant,"order",base+50));
   if(caseId.equals("H01"))peer.sql("INSERT INTO audit_logs(id,tenant_id,actor_id,action,target_type,target_id,outcome,request_id,details,created_at) VALUES(:id,:tenant,:actor,'contract.seed','conversation',:target,'success','fixture-audit',JSON_OBJECT(),'2026-10-24 12:34:56.123000')",Map.of("id",base+81,"tenant",tenant,"actor",base+12,"target",String.valueOf(base+60)));
  }
  var a=new HashMap<String,Login>();var b=new HashMap<String,Login>();
  for(var actor:actors)if(actor.id()>0){a.put(actor.name(),java.login(actor.tenant(),actor.id(),actor.email()));b.put(actor.name(),python.login(actor.tenant(),actor.id(),actor.email()));}
  return new Fixture(tenant,other,base+10,base+13,"ORD-"+caseId+"-"+(base+50),actors,a,b,resources);
 }
 private void compare(String id,Map<String,Object> contract,Reply a,Reply b,String request){
  packets.add(a.actual());assertThat(a.status()).as(id+" status").isEqualTo(b.status());
  assertThat(a.headers().firstValue("x-request-id")).isPresent();assertThat(b.headers().firstValue("x-request-id")).isPresent();
  if(request!=null){assertThat(a.headers().firstValue("x-request-id").orElseThrow()).isEqualTo(request);assertThat(b.headers().firstValue("x-request-id").orElseThrow()).isEqualTo(request);}
  if(a.status()==204){assertThat(a.text()).isEmpty();assertThat(b.text()).isEmpty();return;}
  if(a.status()>=400){
   for(var response:List.of(a,b)){
    assertThat(response.body().path("error").path("code").asString()).as(id+" error code").isNotBlank();
    assertThat(response.body().path("error").path("message").asString()).as(id+" error message").isNotBlank();
    assertThat(response.body().path("error").path("request_id").asString()).as(id+" error request id").isNotBlank();
   }
   assertThat(normal(a.body(),null,null,java,id)).as(id+" error envelope").isEqualTo(normal(b.body(),null,null,python,id));return;
  }
  if(id.equals("H35")){
   assertThat(a.headers().firstValue("content-type").orElseThrow()).contains("text/plain");assertThat(b.headers().firstValue("content-type").orElseThrow()).contains("text/plain");
   for(var response:List.of(a,b))assertThat(response.headers().firstValue("content-disposition").orElseThrow()).contains("attachment","policy.txt");
   assertThat(a.text()).isEqualTo(b.text()).isEqualTo("七天内保持完好可以退换货。");return;
  }
  if(id.equals("H37")){
   for(var response:List.of(a,b))assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("text/plain");
   assertThat(prometheus(a.text())).isEqualTo(prometheus(b.text()));return;
  }
  assertThat(a.headers().firstValue("content-type").orElseThrow()).contains("application/json");
  var operation=operation(contract);schema.response(operation,a.status(),a.body());schema.response(operation,b.status(),b.body());
  assertThat(normal(a.body(),responseSchema(operation,a.status()),null,java,id)).as(id+" successful response").isEqualTo(normal(b.body(),responseSchema(operation,b.status()),null,python,id));
 }
 private Map<String,Double> prometheus(String text){
  var values=new TreeMap<String,Double>();for(String line:text.split("\\R"))if(!line.isBlank()&&!line.startsWith("#")){String[] pair=line.split(" +");assertThat(pair).hasSize(2);values.put(pair[0],Double.parseDouble(pair[1]));}return values;
 }
 private JsonNode operation(Map<String,Object> contract){return document.path("paths").path(String.valueOf(contract.get("path"))).path(String.valueOf(contract.get("method")).toLowerCase(Locale.ROOT));}
 private JsonNode responseSchema(JsonNode operation,int status){return operation.path("responses").path(String.valueOf(status)).path("content").path("application/json").path("schema");}
 private Object normal(JsonNode node,JsonNode shape,String model,ContractPeer peer,String id){
  if(node.isObject()&&(shape==null||shape.isMissingNode())){
   if(node.has("id")&&node.has("customer_id")&&node.has("generation_epoch"))model="ConversationDTO";
   else if(node.has("id")&&node.has("author_role")&&node.has("body"))model="MessageDTO";
   else if(node.has("run_id")&&node.has("will_retry"))model="AIReplyDTO";
   if(model!=null)shape=document.path("components").path("schemas").path(model);
  }
  if(shape!=null&&shape.has("$ref")){String ref=shape.get("$ref").asString();model=ref.substring(ref.lastIndexOf('/')+1);shape=document.path("components").path("schemas").path(model);}
  if(shape!=null&&shape.has("anyOf")){
   for(var candidate:shape.get("anyOf"))if(candidate.has("$ref")||node.isObject()&&"object".equals(candidate.path("type").asString())||node.isString()&&"string".equals(candidate.path("type").asString())){shape=candidate;return normal(node,shape,model,peer,id);}
  }
  if(node.isObject()){
   var result=new TreeMap<String,Object>();long entity=node.path("id").asLong(0);String table=table(model);
   for(String field:node.propertyNames()){
    if(id.equals("H29")&&!peer.legacy()&&field.equals("rabbitmq")){assertThat(node.get(field).asString()).isEqualTo("ok");continue;}
    if(id.equals("H08")&&!peer.legacy()&&field.equals("dependencies"))continue;
    if(!peer.legacy()&&!(field.equals("execution_version")&&"JobDTO".equals(model))&&Set.of("active_topic_id","topic_id","routing_state","context_status","accepted_generation_epoch","trigger_run_id","trigger_job_id","execution_version","manual_retries","lifetime_attempts","budget_revision","deadline_at").contains(field)){
     var value=node.get(field);
     if(field.equals("routing_state"))assertThat(value.asString()).isIn("PENDING","RESOLVED");
     else if(field.equals("context_status"))assertThat(value.asString()).isIn("ELIGIBLE","SUPERSEDED","EXCLUDED");
     else if(!value.isNull()&&!field.equals("deadline_at"))assertThat(value.asLong()).isGreaterThanOrEqualTo(0);
     continue;
    }
    if("ConversationDTO".equals(model)&&id.equals("H24")&&field.equals("ai_reply")&&!peer.legacy()){
     var reply=node.get(field);assertThat(reply.path("state").asString()).isEqualTo("queued");assertThat(reply.path("generation_epoch").asLong()).isEqualTo(node.path("generation_epoch").asLong());
     assertThat(((Number)peer.scalar("SELECT COUNT(*) FROM ai_runs WHERE id=:id AND conversation_id=:room",Map.of("id",reply.path("run_id").asLong(),"room",node.path("id").asLong()))).intValue()).isEqualTo(1);
     result.put(field,null);continue;
    }
    if("JobDTO".equals(model)&&field.equals("payload")){
     var payload=node.get(field);var resultPayload=new TreeMap<String,Object>();
     if(!peer.legacy()){
      assertThat(payload.path("actor_id").asLong()).isPositive();assertThat(payload.path("auth_version").asLong()).isPositive();
      assertThat(((Number)peer.scalar("SELECT COUNT(*) FROM users WHERE id=:id AND tenant_id=:tenant",Map.of("id",payload.path("actor_id").asLong(),"tenant",payload.path("tenant_id").asLong()))).intValue()).isEqualTo(1);
     }
     for(String key:payload.propertyNames()){
      if(!peer.legacy()&&Set.of("actor_id","auth_version","document_id","document_revision","version_id","topic_id","run_id","route","requires_clarification").contains(key))continue;
      resultPayload.put(key,normal(payload.get(key),null,null,peer,id));
     }
     result.put(field,resultPayload);continue;
    }
    if("JobDTO".equals(model)&&id.equals("H03")&&field.equals("state")){assertThat(node.get(field).asString()).isEqualTo(peer.legacy()?"queued":"retrying");result.put(field,"<manual-retry-scheduled>");continue;}
    if(field.equals("csrf_token")){assertThat(node.get(field).asString().length()).isGreaterThanOrEqualTo(20);result.put(field,"<opaque-csrf>");continue;}
    if(field.equals("request_id")){result.put(field,"<echo-validated-request-id>");continue;}
    if(id.equals("H08")&&field.equals("automatic_retries")){assertThat(node.get(field).asInt()).isEqualTo(peer.legacy()?3:2);result.put(field,"<three-total-attempts>");continue;}
    var value=node.get(field);
    if(table!=null&&entity>0&&Set.of("created_at","updated_at","expires_at","available_at","finished_at","deadline_at","last_activity_at").contains(field)){
     Instant stored=peer.time(table,entity,field);assertThat(value.isNull()?null:Instant.parse(value.asString())).as(model+"."+field+" must equal its stored UTC point").isEqualTo(stored);result.put(field,stored==null?null:"<stored-utc-"+field+">");continue;
    }
    if(Set.of("total_amount","unit_price").contains(field)){assertThat(value.isString()).as("Money remains a decimal string").isTrue();assertThat(value.asString()).matches("[0-9]+\\.[0-9]{2}");if(table!=null&&entity>0){Object stored=peer.scalar("SELECT "+field+" AS value FROM "+table+" WHERE id=:id",Map.of("id",entity));assertThat(value.asString()).isEqualTo(new BigDecimal(String.valueOf(stored)).setScale(2,RoundingMode.UNNECESSARY).toPlainString());}}
    result.put(field,normal(value,shape==null?null:shape.path("properties").path(field),null,peer,id));
   }
   return result;
  }
  if(node.isArray()){
   var values=new ArrayList<Object>();for(var item:node)values.add(normal(item,shape==null?null:shape.path("items"),null,peer,id));return values;
  }
  return node.isNull()?null:app.json.convertValue(node,Object.class);
 }
 private String table(String model){
  if(model==null)return null;return switch(model){case "OrderItemDTO"->"order_items";case "OrderDTO"->"orders";case "UserDTO"->"users";case "ConversationDTO"->"conversations";case "MessageDTO"->"messages";case "TicketDraftDTO"->"ticket_drafts";case "TicketDTO"->"after_sale_tickets";case "TicketEventDTO"->"ticket_events";case "DocumentDTO"->"knowledge_documents";case "VersionDTO"->"document_versions";case "AuditDTO"->"audit_logs";case "JobDTO"->"jobs";case "TopicDTO"->"conversation_topics";default->null;};
 }
 @Override public void close(){reference.close();}
}
