package cn.servicehub.auth;

import cn.servicehub.support.HttpIntegrationApp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthContractIT {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    @BeforeAll void start() throws Exception {app.start();}
    @AfterAll void stop(){app.close();}
    @BeforeEach void reset(){app.resetAuth();}
    @Test void anonymousRequestIs401WithBaselineEnvelope() throws Exception {
        var r=app.request("GET","/api/v1/auth/me",null,null);
        assertThat(r.statusCode()).isEqualTo(401);assertThat(app.body(r).at("/error/code").asString()).isEqualTo("unauthorized");
        assertThat(app.body(r).at("/error/request_id").asString()).isNotBlank();
    }
    @Test void legacyArgon2LoginProducesOpaqueHttpOnlySessionAndSnakeCaseUser() throws Exception {
        var r=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","USER2001@example.com","password",HttpIntegrationApp.PASSWORD),null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        String cookie=r.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).contains("servicehub_java_session=","HttpOnly","SameSite=Lax","Path=/","Max-Age=28800").doesNotContain("JSESSIONID");
        assertThat(cookie.split(";",2)[0].split("=",2)[1]).matches("[A-Za-z0-9_-]{43}");
        var data=app.body(r);assertThat(data.path("csrf_token").asString()).matches("[A-Za-z0-9_-]{43}");
        assertThat(data.at("/user/id").asLong()).isEqualTo(2001);
        assertThat(data.at("/user/is_active").asBoolean()).isTrue();
        assertThat(data.at("/user/role").asString()).isEqualTo("admin");
        assertThat(data.at("/user/created_at").asString()).endsWith("Z");
        assertThat(r.body()).doesNotContain("password","session_version","auth_version");
    }
    @Test void wrongPasswordUnknownAccountAndDisabledUserUseSameCredentialsError() throws Exception {
        var a=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","user2001@example.com","password","wrong"),null);
        var b=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","absent@example.com","password","wrong"),null);
        app.db.update("UPDATE users SET is_active=0 WHERE id=2001");
        var c=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","user2001@example.com","password",HttpIntegrationApp.PASSWORD),null);
        for(var r:java.util.List.of(a,b,c)) {
            assertThat(r.statusCode()).isEqualTo(401);assertThat(app.body(r).at("/error/code").asString()).isEqualTo("invalid_credentials");
            assertThat(app.body(r).at("/error/message").asString()).isEqualTo("企业编号、账号或密码不正确");
        }
    }
    @Test void csrfMustMatchSessionAndOriginMustBeAllowed() throws Exception {
        var login=app.login(2001);
        for(String csrf:new String[]{"","wrong"}) {
            var r=app.request("POST","/api/v1/auth/logout",null,login,csrf,"http://localhost:8081");
            assertThat(r.statusCode()).isEqualTo(403);assertThat(app.body(r).at("/error/code").asString()).isEqualTo("csrf_rejected");
        }
        var denied=app.request("POST","/api/v1/auth/logout",null,login,login.csrf(),"https://untrusted.example");
        assertThat(denied.statusCode()).isEqualTo(403);assertThat(app.body(denied).at("/error/code").asString()).isEqualTo("origin_denied");
        var loginDenied=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","user2001@example.com","password",HttpIntegrationApp.PASSWORD),null,null,"https://untrusted.example");
        assertThat(loginDenied.statusCode()).isEqualTo(403);
    }
    @Test void logoutReturnsEmpty204AndInvalidatesCookie() throws Exception {
        var login=app.login(2001);var before=app.request("GET","/api/v1/auth/me",null,login);
        assertThat(before.statusCode()).isEqualTo(200);assertThat(app.body(before).path("csrf_token").asString()).isEqualTo(login.csrf());
        var r=app.request("POST","/api/v1/auth/logout",null,login);assertThat(r.statusCode()).isEqualTo(204);assertThat(r.body()).isEmpty();
        assertThat(r.headers().firstValue("set-cookie").orElseThrow()).contains("Max-Age=0");
        assertThat(app.request("GET","/api/v1/auth/me",null,login).statusCode()).isEqualTo(401);
    }
    @Test void membersRespectRolesTenantPaginationAndInputShape() throws Exception {
        var admin=app.login(2001);var agent=app.login(2002);
        assertThat(app.request("GET","/api/v1/admin/members",null,agent).statusCode()).isEqualTo(403);
        var foreign=app.request("PATCH","/api/v1/admin/members/5001",Map.of("is_active",false),admin);
        assertThat(foreign.statusCode()).isEqualTo(404);
        var page=app.request("GET","/api/v1/admin/members?limit=2",null,admin);assertThat(page.statusCode()).isEqualTo(200);
        assertThat(app.body(page).path("items").size()).isEqualTo(2);assertThat(app.body(page).path("next_cursor").isNull()).isFalse();
        assertThat(app.request("GET","/api/v1/admin/members?limit=101",null,admin).statusCode()).isEqualTo(422);
        String email="new-"+UUID.randomUUID()+"@example.com";
        var member=Map.of("email",email,"display_name","新客服","password",HttpIntegrationApp.PASSWORD);
        var created=app.request("POST","/api/v1/admin/members",member,admin);assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(app.body(created).path("role").asString()).isEqualTo("agent");
        assertThat(app.request("POST","/api/v1/admin/members",member,admin).statusCode()).isEqualTo(409);
        long id=app.body(created).path("id").asLong();
        var updated=app.request("PATCH","/api/v1/admin/members/"+id,Map.of("display_name","已更新"),admin);
        assertThat(updated.statusCode()).isEqualTo(200);assertThat(app.body(updated).path("display_name").asString()).isEqualTo("已更新");
        assertThat(app.request("PATCH","/api/v1/admin/members/2001",Map.of("role","agent"),admin).statusCode()).isEqualTo(409);
        assertThat(app.request("POST","/api/v1/admin/members",Map.of("email","bad","password","short","display_name",""),admin).statusCode()).isEqualTo(422);
    }
    @Test void explicitNullRoleDoesNotSilentlyGrantDefaultAgentRole() throws Exception {
        var admin=app.login(2001);var input=new java.util.LinkedHashMap<String,Object>();
        input.put("email","null-role@example.com");input.put("display_name","Invalid role");input.put("password",HttpIntegrationApp.PASSWORD);input.put("role",null);
        var r=app.request("POST","/api/v1/admin/members",input,admin);
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(app.db.queryForObject("SELECT COUNT(*) FROM users WHERE email='null-role@example.com'",Integer.class)).isZero();
    }
    @Test void loginRateLimitIsAtomicAndBounded() throws Exception {
        for(int i=0;i<10;i++)assertThat(app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","rate@example.com","password","wrong"),null).statusCode()).isEqualTo(401);
        var r=app.request("POST","/api/v1/auth/login",Map.of("tenant_id",1001,"email","rate@example.com","password","wrong"),null);
        assertThat(r.statusCode()).isEqualTo(429);assertThat(app.body(r).at("/error/code").asString()).isEqualTo("login_rate_limited");
    }
    @Test @Order(Integer.MAX_VALUE) void redisFailureNeverAuthenticatesCachedIdentity() throws Exception {
        var login=app.login(2001);app.dependencies.redis.stop();
        var r=app.request("GET","/api/v1/auth/me",null,login);
        assertThat(r.statusCode()).isEqualTo(503);assertThat(app.body(r).at("/error/code").asString()).isEqualTo("dependencies_unavailable");
    }
}
