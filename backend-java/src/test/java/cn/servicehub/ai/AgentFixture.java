package cn.servicehub.ai;
import cn.servicehub.common.Actor;
import cn.servicehub.conversations.*;
import cn.servicehub.jobs.*;
import cn.servicehub.support.HttpIntegrationApp;
import java.lang.reflect.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
final class AgentFixture implements AutoCloseable {
    final HttpIntegrationApp app=new HttpIntegrationApp();final List<ExecutionPermit> permits=new ArrayList<>();
    HttpIntegrationApp.Login customer,admin;long room;JobLease lease;ExecutionFence fence;JsonNode message;
    void start() throws Exception{start(Map.of());}
    void start(Map<String,String> overrides) throws Exception{
        app.start(overrides);
        app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount,status) VALUES(6001,1001,2003,'ORD-A-001',12.30,'DELIVERED'),(6002,1001,2003,'ORD-A-002',99.00,'DELIVERED'),(6003,1002,5001,'ORD-B-001',77.00,'DELIVERED'),(6004,1001,2001,'ORD-OTHER',23.00,'DELIVERED')");
    }
    void reset() throws Exception{
        permits.forEach(ExecutionPermit::close);permits.clear();app.resetAuth();customer=app.login(2003);admin=app.login(2001);
        var response=app.request("POST","/api/v1/conversations",Map.of(),customer);assertThat(response.statusCode()).isEqualTo(201);room=app.body(response).path("id").asLong();send("查询订单 ORD-A-001");
    }
    void send(String text) throws Exception{
        var response=app.request("POST","/api/v1/conversations/"+room+"/messages",Map.of("client_message_id",UUID.randomUUID().toString(),"body",text),customer);assertThat(response.statusCode()).isEqualTo(200);message=app.body(response);
        claim(message.path("trigger_job_id").asLong());fence=app.context.getBean(TopicService.class).resolve(lease,message.path("id").asLong(),TopicDecision.of("CONTINUE",null,false)).orElseThrow();
    }
    void claim(long job){
        String owner="agent-"+UUID.randomUUID();permits.add(app.context.getBean(AdmissionController.class).tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow());
        lease=app.context.getBean(JobCoordinator.class).claim(job,owner).orElseThrow();
    }
    Actor actor(){return lease.actor();}
    CancellationGuard guard(){return app.context.getBean(CancellationGuardFactory.class).forRun(fence,lease);}
    Object budget(){return app.requiredBean("cn.servicehub.ai.ModelBudget");}
    Object reserve(String kind){return call(budget(),"reserve",new Class<?>[]{JobLease.class,String.class},lease,kind);}
    Object snapshot(){return call(budget(),"snapshot",new Class<?>[]{JobLease.class},lease);}
    static Object call(Object bean,String method,Class<?>[] types,Object...args){
        try{var m=bean.getClass().getMethod(method,types);m.trySetAccessible();return m.invoke(bean,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){return fail("Missing agent contract "+method,e);}
    }
    static Object field(Object value,String name){return call(value,name,new Class<?>[]{});}
    @Override public void close(){permits.forEach(ExecutionPermit::close);app.close();}
}
