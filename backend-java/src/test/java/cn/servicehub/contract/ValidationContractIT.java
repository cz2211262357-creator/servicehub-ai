package cn.servicehub.contract;
import cn.servicehub.support.*;
import cn.servicehub.support.ContractPeer.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationContractIT {
 final HttpIntegrationApp app=new HttpIntegrationApp();PythonReference reference;ContractPeer java,python;Login a,b;
 @BeforeAll void start()throws Exception{app.start();reference=new PythonReference(app);java=new ContractPeer(app,null);python=new ContractPeer(app,reference);a=java.login(1001,2001,"user2001@example.com");b=python.login(1001,2001,"user2001@example.com");}
 @AfterAll void close(){if(reference!=null)reference.close();app.close();}
 static List<Arguments> malformedRequests(){return List.of(
  Arguments.of("POST","/api/v1/conversations","{bad"),
  Arguments.of("POST","/api/v1/conversations",""),
  Arguments.of("POST","/api/v1/conversations","{\"title\":null}"),
  Arguments.of("POST","/api/v1/auth/login","{\"tenant_id\":1001,\"email\":\"user2001@example.com\"}"),
  Arguments.of("PATCH","/api/v1/after-sales/900/status","{\"status\":\"IN_REVIEW\"}"),
  Arguments.of("POST","/api/v1/admin/members","{\"email\":\"new@example.com\",\"display_name\":\"Member\",\"role\":\"invalid\",\"password\":\"Synthetic-password-123!\"}"),
  Arguments.of("GET","/api/v1/orders?limit=abc",null),
  Arguments.of("PATCH","/api/v1/knowledge/documents/1","{\"enabled\":null,\"version\":1}")
 );}
 @ParameterizedTest @MethodSource("malformedRequests") void errorsMatchOriginalFieldAndTypeWithoutRejectedValues(String method,String path,String body){
  String request="validation-"+UUID.randomUUID();var x=java.raw(method,path,body,a,request);var y=python.raw(method,path,body,b,request);
  assertThat(x.status()).as(path).isEqualTo(422).isEqualTo(y.status());assertThat(x.headers().firstValue("x-request-id").orElseThrow()).isEqualTo(request);
  assertThat(error(x.body())).isEqualTo(error(y.body()));
  assertThat(x.text()).doesNotContain("Synthetic-password-123!","user2001@example.com","new@example.com");
 }
 Object error(JsonNode node){var result=app.json.convertValue(node.path("error"),Map.class);result.remove("request_id");return result;}
}
