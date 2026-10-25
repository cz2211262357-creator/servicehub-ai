package cn.servicehub.conversations;
import cn.servicehub.support.HttpIntegrationApp;
import cn.servicehub.jobs.*;
import java.lang.reflect.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
final class TopicFixture implements AutoCloseable {
    final HttpIntegrationApp app=new HttpIntegrationApp();final List<ExecutionPermit> permits=new ArrayList<>();
    HttpIntegrationApp.Login customer;long room;
    void start() throws Exception {
        app.start();
        app.db.update("INSERT INTO orders(id,tenant_id,customer_id,order_number,total_amount,status) VALUES(6001,1001,2003,'ORD-A-001',12.30,'DELIVERED'),(6002,1001,2003,'ORD-A-002',99.00,'DELIVERED'),(6003,1002,5001,'ORD-B-001',77.00,'DELIVERED')");
    }
    void reset() throws Exception {app.db.update("UPDATE orders SET customer_id=2003 WHERE id IN(6001,6002)");app.resetAuth();customer=app.login(2003);var response=app.request("POST","/api/v1/conversations",Map.of(),customer);assertThat(response.statusCode()).isEqualTo(201);room=app.body(response).path("id").asLong();}
    String path(){return "/api/v1/conversations/"+room;}
    JsonNode snapshot() throws Exception{return app.body(app.request("GET",path(),null,customer));}
    long epoch() throws Exception{return snapshot().path("generation_epoch").asLong();}
    long topic() throws Exception{return snapshot().path("active_topic_id").asLong();}
    JsonNode send(String key,String body) throws Exception{var response=app.request("POST",path()+"/messages",Map.of("client_message_id",key,"body",body),customer);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return app.body(response);}
    java.net.http.HttpResponse<String> switchTo(String mode,Long topic,Long pending,long epoch) throws Exception {
        var input=new LinkedHashMap<String,Object>();input.put("mode",mode);input.put("expected_generation_epoch",epoch);
        if(topic!=null)input.put("topic_id",topic);if(pending!=null)input.put("pending_message_id",pending);
        return app.request("POST",path()+"/topics/switch",input,customer);
    }
    JobLease claim(JsonNode message){
        var admission=app.context.getBean(AdmissionController.class);String owner="topic-"+UUID.randomUUID();
        var permit=admission.tryAcquire(ExecutionClass.INTERACTIVE,owner).orElseThrow();permits.add(permit);
        return app.context.getBean(JobCoordinator.class).claim(message.path("trigger_job_id").asLong(),owner).orElseThrow();
    }
    Object service(){return app.requiredBean("cn.servicehub.conversations.TopicService");}
    Object decision(String kind,Long topic,boolean clarify){
        try{return Class.forName("cn.servicehub.conversations.TopicDecision").getMethod("of",String.class,Long.class,boolean.class).invoke(null,kind,topic,clarify);}
        catch(ReflectiveOperationException e){throw new AssertionError("TopicDecision missing",e);}
    }
    Optional<?> resolve(JobLease lease,long message,String kind,Long topic,boolean clarify){
        return (Optional<?>)call(service(),"resolve",new Class<?>[]{JobLease.class,long.class,type("cn.servicehub.conversations.TopicDecision")},lease,message,decision(kind,topic,clarify));
    }
    Object resolved(JobLease lease,JsonNode message){var r=resolve(lease,message.path("id").asLong(),"CONTINUE",null,false);assertThat(r).isPresent();return r.orElseThrow();}
    Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException e){throw new AssertionError(name+" missing",e);}}
    Object actor(JobLease lease){return lease.actor();}
    List<?> context(JobLease lease,Object fence){return (List<?>)call(app.requiredBean("cn.servicehub.conversations.ContextBuilder"),"build",new Class<?>[]{cn.servicehub.common.Actor.class,type("cn.servicehub.conversations.ExecutionFence")},lease.actor(),fence);}
    static Object call(Object bean,String name,Class<?>[] types,Object...args){
        try{var method=bean.getClass().getMethod(name,types);method.trySetAccessible();return method.invoke(bean,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Missing contract "+name,e);}
    }
    static Object get(Object bean,String field){return call(bean,field,new Class<?>[0]);}
    void finish(JobLease lease){app.context.getBean(JobCoordinator.class).finish(lease,"SUCCEEDED",Map.of());}
    @Override public void close(){permits.forEach(ExecutionPermit::close);app.close();}
}
