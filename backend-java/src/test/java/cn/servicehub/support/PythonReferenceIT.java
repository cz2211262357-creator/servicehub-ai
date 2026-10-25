package cn.servicehub.support;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonReferenceIT {
 final HttpIntegrationApp app=new HttpIntegrationApp();Object reference;
 @BeforeAll void start() throws Exception{
  Class<?> type;
  try{type=Class.forName("cn.servicehub.support.PythonReference");}catch(ClassNotFoundException e){fail("Isolated real Python reference bridge is missing",e);return;}
  app.start();reference=type.getConstructor(HttpIntegrationApp.class).newInstance(app);
 }
 @AfterAll void close() throws Exception{if(reference instanceof AutoCloseable owned)owned.close();app.close();}
 Object call(String name,Class<?>[] types,Object...args){
  try{return reference.getClass().getMethod(name,types).invoke(reference,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}catch(ReflectiveOperationException e){return fail("Reference contract method missing: "+name,e);}
 }
 JsonNode request(String method,String path,Map<String,Object> body,Map<String,String> headers){return (JsonNode)call("request",new Class<?>[]{String.class,String.class,Map.class,Map.class},method,path,body,headers);}
 JsonNode body(JsonNode reply){return app.json.readTree(Base64.getDecoder().decode(reply.path("body_base64").asString()));}
 @Test void unchangedPythonRoutesRunOnRealOwnedDatabase(){
  var reply=request("GET","/api/v1/health/live",null,Map.of());assertThat(reply.path("status").asInt()).isEqualTo(200);assertThat(body(reply).path("service").asString()).isEqualTo("servicehub-ai");
  var query=(JsonNode)call("sql",new Class<?>[]{String.class,Map.class},"SELECT COUNT(*) AS total FROM users",Map.of());
  assertThat(query.path("results").get(0).path("rows").get(0).path("total").asInt()).isEqualTo(5);
 }
 @Test void originalCookieAndCsrfCanAuthenticateTheReferenceApi(){
  var login=request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","user2003@example.com","password",HttpIntegrationApp.PASSWORD),Map.of("Origin","http://localhost:8081"));
  assertThat(login.path("status").asInt()).isEqualTo(200);String cookie=null;
  for(var header:login.path("headers"))if(header.get(0).asString().equalsIgnoreCase("set-cookie")){String value=header.get(1).asString();assertThat(value).contains("HttpOnly","SameSite=lax","Max-Age=28800");cookie=value.split(";",2)[0];}
  assertThat(cookie).startsWith("servicehub_session=");assertThat(body(login).path("csrf_token").asString()).isNotBlank();
  var me=request("GET","/api/v1/auth/me",null,Map.of("Cookie",cookie));assertThat(me.path("status").asInt()).isEqualTo(200);assertThat(body(me).path("user").path("id").asLong()).isEqualTo(2003);
 }
 @Test void referenceConfigurationNeverCopiesRealProviderCredentials() throws Exception{
  var path=(Path)call("configPath",new Class<?>[]{});String content=Files.readString(path);var properties=app.context.getBean(cn.servicehub.config.ServiceHubProperties.class);
  for(String secret:Arrays.asList(properties.model().apiKey(),properties.model().embeddingApiKey()))if(secret!=null&&!secret.isBlank())assertThat(content.contains(secret)).as("Provider secret must not enter reference settings").isFalse();
 }
}
