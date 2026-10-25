package cn.servicehub.support;

import cn.servicehub.ServiceHubApplication;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import static org.assertj.core.api.Assertions.*;

/** Boots the production HTTP application against disposable, loopback-only dependencies. */
public final class HttpIntegrationApp implements AutoCloseable {
    public final DependencyContainers dependencies=new DependencyContainers();
    public ConfigurableApplicationContext context;
    public JdbcTemplate db;
    public JsonMapper json;
    public final HttpClient http=HttpClient.newHttpClient();
    public static final String PASSWORD="Synthetic-password-123!";
    public record Login(String cookie,String csrf,long userId) { }
    public void start() throws Exception {start(Map.of());}
    public void start(Map<String,String> overrides) throws Exception {
        dependencies.start();
        var options=new java.util.ArrayList<String>(java.util.List.of(
            "--server.port=0","--spring.profiles.active=api",
            "--servicehub.database.url="+dependencies.jdbcUrl(),
            "--servicehub.database.username="+dependencies.username(),"--servicehub.database.password="+dependencies.password(),
            "--servicehub.redis.host="+dependencies.redis.getHost(),"--servicehub.redis.port="+dependencies.redis.getMappedPort(6379),
            "--servicehub.redis.password="+dependencies.password(),
            "--servicehub.rabbitmq.host="+dependencies.rabbitmq.getHost(),"--servicehub.rabbitmq.port="+dependencies.rabbitmq.getMappedPort(5672),
            "--servicehub.rabbitmq.username="+dependencies.username(),"--servicehub.rabbitmq.password="+dependencies.password(),
            "--spring.data.redis.host="+dependencies.redis.getHost(),"--spring.data.redis.port="+dependencies.redis.getMappedPort(6379),
            "--spring.data.redis.password="+dependencies.password(),"--spring.data.redis.timeout=1s","--spring.data.redis.connect-timeout=1s",
            "--servicehub.allowed-origins[0]=http://localhost:8081"));
        overrides.forEach((key,value)->options.add("--"+key+"="+value));
        context=new SpringApplicationBuilder(ServiceHubApplication.class).run(options.toArray(String[]::new));
        db=context.getBean(JdbcTemplate.class);json=context.getBean(JsonMapper.class);
        String hash=new String(getClass().getResourceAsStream("/fixtures/legacy-password.txt").readAllBytes(),StandardCharsets.UTF_8).strip();
        db.update("INSERT INTO tenants(id,name) VALUES(1001,'Synthetic A'),(1002,'Synthetic B')");
        for(long id:new long[]{2001,2002,2003,2004,5001}) {
            String role=id==2001?"admin":id==2003||id==5001?"customer":"agent";
            long tenant=id==5001?1002:1001;
            db.update("INSERT INTO users(id,tenant_id,email,display_name,password_hash,role) VALUES(?,?,?,?,?,?)",
                id,tenant,"user"+id+"@example.com","Synthetic "+id,hash,role);
        }
        db.update("INSERT INTO conversations(id,tenant_id,customer_id,title) VALUES(3001,1001,2003,'original')");
    }
    public void resetAuth() {
        db.update("UPDATE users SET is_active=1,session_version=1,role=CASE id WHEN 2001 THEN 'admin' WHEN 2003 THEN 'customer' WHEN 5001 THEN 'customer' ELSE 'agent' END WHERE id IN(2001,2002,2003,2004,5001)");
        var redis=RedisClient.create(RedisURI.builder().withHost(dependencies.redis.getHost()).withPort(dependencies.redis.getMappedPort(6379)).withPassword(dependencies.password().toCharArray()).build());
        try(var connection=redis.connect()){connection.sync().flushdb();}finally{redis.shutdown();}
    }
    public HttpResponse<String> request(String method,String path,Object body,Login login,String csrf,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port")+path)).timeout(java.time.Duration.ofSeconds(15));
        builder.header("Content-Type","application/json").header("x-request-id","contract-"+UUID.randomUUID());
        if(login!=null)builder.header("Cookie",login.cookie());
        if(csrf!=null)builder.header("x-csrf-token",csrf);
        if(origin!=null)builder.header("Origin",origin);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<String> request(String method,String path,Object body,Login login) throws Exception {
        return request(method,path,body,login,login==null?null:login.csrf(),"http://localhost:8081");
    }
    public JsonNode body(HttpResponse<String> response){return json.readTree(response.body());}
    public Login login(long id) throws Exception {
        var response=request("POST","/api/v1/auth/login",Map.of("tenant_id",id==5001?1002:1001,"email","user"+id+"@example.com","password",PASSWORD),null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Login(response.headers().firstValue("set-cookie").orElseThrow().split(";",2)[0],body(response).path("csrf_token").asString(),id);
    }
    public Object requiredBean(String className) {
        try{return context.getBean(Class.forName(className));}catch(ClassNotFoundException e){return fail("Required production service is not implemented: "+className);}
    }
    @Override public void close(){if(context!=null)context.close();dependencies.close();}
}
