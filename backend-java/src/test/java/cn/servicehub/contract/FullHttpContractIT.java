package cn.servicehub.contract;
import cn.servicehub.support.HttpIntegrationApp;
import java.lang.reflect.*;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullHttpContractIT {
 final HttpIntegrationApp app=new HttpIntegrationApp();Object scenarios;List<Map<String,Object>> contracts;
 @SuppressWarnings("unchecked") @BeforeAll void start() throws Exception{
  app.start();Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("contracts/python-v1/http-contracts.json")))root=root.getParent();assertThat(root).isNotNull();
  var base=app.json.readTree(Files.readString(root.resolve("contracts/python-v1/http-contracts.json")));var extra=app.json.readTree(Files.readString(root.resolve("contracts/python-v1/extensions.json")));
  contracts=new ArrayList<>();for(var row:base.path("operations"))contracts.add(app.json.convertValue(row,Map.class));for(var row:extra)contracts.add(app.json.convertValue(row,Map.class));assertThat(contracts).hasSize(42);
 }
 @AfterAll void close() throws Exception{if(scenarios instanceof AutoCloseable owned)owned.close();app.close();}
 @Order(1) @TestFactory Stream<DynamicTest> requestResponseRoleAndBoundaryContracts(){
  String selected=System.getProperty("servicehub.contract.case");
  Set<String> selection=selected==null?Set.of():Set.of(selected.split(","));
  if(selected!=null)for(String code:selection)assertThat(contracts.stream().anyMatch(c->code.equals(c.get("id")))).as("Selected contract must exist").isTrue();
  return contracts.stream().filter(c->selected==null||selection.contains(c.get("id"))).map(contract->DynamicTest.dynamicTest(contract.get("id")+" "+contract.get("method")+" "+contract.get("path"),()->{
   List<HttpResponse<String>> responses=run(contract);
   assertThat(responses).isNotEmpty();assertThat(responses.stream().anyMatch(r->r.statusCode()>=200&&r.statusCode()<300)).isTrue();
   for(var response:responses)assertThat(response.headers().firstValue("x-request-id")).isPresent();
  }));
 }
 @Order(2) @TestFactory Stream<DynamicTest> anonymousRequestsNeverReachProtectedBusinessHandlers(){
  return contracts.stream().filter(c->!String.valueOf(c.get("path")).startsWith("/api/v1/health/")&&!String.valueOf(c.get("path")).equals("/api/v1/auth/login"))
   .map(c->DynamicTest.dynamicTest(c.get("id")+" anonymous",()->{
    String path=String.valueOf(c.get("path")).replaceAll("\\{[^}]+}","3001");
    var response=app.request(String.valueOf(c.get("method")),path,Map.of(),null);
    assertThat(response.statusCode()).as(path).isEqualTo(401);assertThat(app.body(response).path("error").path("code").asString()).isEqualTo("unauthorized");
   }));
 }
 @Order(3) @Test void websocketContractIsVerifiedThroughAnActualUpgrade(){run(Map.of("id","W01","method","WEBSOCKET","path","/api/v1/conversations/{conversation_id}/live"));}
 @SuppressWarnings("unchecked") List<HttpResponse<String>> run(Map<String,Object> contract){
  try{
   if(scenarios==null)scenarios=Class.forName("cn.servicehub.support.ContractScenarioDriver").getConstructor(HttpIntegrationApp.class).newInstance(app);
   return (List<HttpResponse<String>>)scenarios.getClass().getMethod("verify",Map.class).invoke(scenarios,contract);
  }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}
  catch(ReflectiveOperationException e){return fail("Complete runtime contract driver missing for "+contract.get("id"),e);}
 }
}
