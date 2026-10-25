package cn.servicehub.support;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** Actual Java HTTP packets or actual legacy ASGI packets, with the same request inputs. */
public final class ContractPeer {
 public record Login(String cookie,String csrf,long userId){}
 public record Reply(HttpResponse<String> actual,int status,HttpHeaders headers,String text,JsonNode body){}
 private final HttpIntegrationApp app;private final PythonReference reference;
 public ContractPeer(HttpIntegrationApp app,PythonReference reference){this.app=app;this.reference=reference;}
 public boolean legacy(){return reference!=null;}
 public Reply request(String method,String path,Map<String,Object> body,Login login,String requestId){return request(method,path,body,login,requestId,true,"http://localhost:8081");}
 public Reply request(String method,String path,Map<String,Object> body,Login login,String requestId,boolean csrf,String origin){
  var headers=headers(login,requestId,csrf,origin);
  if(reference!=null)return reply(reference.request(method,path,body,headers));
  var b=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15));headers.forEach(b::header);
  if(body!=null)b.header("Content-Type","application/json");
  b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(app.json.writeValueAsString(body),StandardCharsets.UTF_8));
  try{return reply(app.http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError("Actual Java HTTP request failed",e);}
 }
 public Reply raw(String method,String path,String body,Login login,String id){
  var headers=headers(login,id,true,"http://localhost:8081");headers.put("Content-Type","application/json");
  if(reference!=null)return reply(reference.raw(method,path,body,headers));
  var request=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15));headers.forEach(request::header);request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
  try{return reply(app.http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));}catch(Exception error){throw new AssertionError("Actual raw HTTP request failed",error);}
 }
 public Reply multipart(String path,Map<String,String> fields,String filename,byte[] bytes,String media,Login login,String requestId){
  var headers=headers(login,requestId,true,"http://localhost:8081");
  if(reference!=null)return reply(reference.multipart("POST",path,fields,List.of(Map.of("name","file","filename",filename,"media_type",media,"content",Base64.getEncoder().encodeToString(bytes))),headers));
  String boundary="contract-"+UUID.randomUUID();var out=new java.io.ByteArrayOutputStream();
  try{
   for(var field:fields.entrySet())out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
   out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+media+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
   var b=HttpRequest.newBuilder(URI.create(base()+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));headers.forEach(b::header);
   return reply(app.http.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
  }catch(Exception e){throw new AssertionError("Actual multipart request failed",e);}
 }
 public Login login(long tenant,long id,String email){
  var response=request("POST","/api/v1/auth/login",Map.of("tenant_id",tenant,"email",email,"password",HttpIntegrationApp.PASSWORD),null,"login-"+UUID.randomUUID());
  assertThat(response.status()).as("Fixture login").isEqualTo(200);String cookie=response.headers().firstValue("set-cookie").orElseThrow();
  assertThat(cookie.toLowerCase(Locale.ROOT)).contains("httponly","samesite=lax","max-age=28800","path=/");
  String csrf=response.body().path("csrf_token").asString();assertThat(csrf).isNotBlank();assertThat(response.body().path("user").path("id").asLong()).isEqualTo(id);
  return new Login(cookie.split(";",2)[0],csrf,id);
 }
 public void sql(String sql,Map<String,Object> parameters){
  if(reference!=null)reference.sql(sql,parameters);else new NamedParameterJdbcTemplate(app.db).update(sql,parameters);
 }
 public Object scalar(String sql,Map<String,Object> parameters){
  if(reference!=null){var rows=reference.sql(sql,parameters).path("results").get(0).path("rows");if(rows.isEmpty())return null;return app.json.convertValue(rows.get(0).properties().iterator().next().getValue(),Object.class);}
  return new NamedParameterJdbcTemplate(app.db).query(sql,parameters,rs->rs.next()?rs.getObject(1):null);
 }
 public Instant time(String table,long id,String field){
  if(!Set.of("order_items","orders","users","conversations","messages","ticket_drafts","after_sale_tickets","ticket_events","knowledge_documents","document_versions","audit_logs","jobs","conversation_topics").contains(table)
   ||!Set.of("created_at","updated_at","expires_at","available_at","finished_at","deadline_at","last_activity_at").contains(field))throw new IllegalArgumentException("Unbounded timestamp lookup");
  String sql="SELECT "+field+" AS value FROM "+table+" WHERE id=:id";
  if(reference!=null){Object value=scalar(sql,Map.of("id",id));return value==null?null:Instant.parse(String.valueOf(value));}
  return new NamedParameterJdbcTemplate(app.db).query(sql,Map.of("id",id),rs->{if(!rs.next())return null;var value=rs.getObject(1,java.time.LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);});
 }
 private Map<String,String> headers(Login login,String id,boolean csrf,String origin){
  var headers=new LinkedHashMap<String,String>();headers.put("x-request-id",id);if(origin!=null)headers.put("Origin",origin);
  if(login!=null){headers.put("Cookie",login.cookie());if(csrf)headers.put("x-csrf-token",login.csrf());}return headers;
 }
 private String base(){return "http://127.0.0.1:"+app.context.getEnvironment().getProperty("local.server.port");}
 private Reply reply(HttpResponse<String> response){return new Reply(response,response.statusCode(),response.headers(),response.body(),parse(response.headers(),response.body()));}
 private Reply reply(JsonNode response){
  var headers=new LinkedHashMap<String,List<String>>();for(var row:response.path("headers"))headers.computeIfAbsent(row.get(0).asString(),ignored->new ArrayList<>()).add(row.get(1).asString());
  var http=HttpHeaders.of(headers,(k,v)->true);String text=new String(Base64.getDecoder().decode(response.path("body_base64").asString()),StandardCharsets.UTF_8);
  return new Reply(null,response.path("status").asInt(),http,text,parse(http,text));
 }
 private JsonNode parse(HttpHeaders headers,String text){return !text.isEmpty()&&headers.firstValue("content-type").orElse("").contains("application/json")?app.json.readTree(text):app.json.nullNode();}
}
