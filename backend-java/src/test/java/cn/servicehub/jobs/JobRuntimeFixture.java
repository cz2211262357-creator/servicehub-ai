package cn.servicehub.jobs;

import cn.servicehub.support.HttpIntegrationApp;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

final class JobRuntimeFixture implements AutoCloseable {
    final HttpIntegrationApp app=new HttpIntegrationApp();
    final AtomicLong ids=new AtomicLong(10000);
    final List<AutoCloseable> permits=new ArrayList<>();
    void start() throws Exception {app.start();}
    void reset(){app.resetAuth();}
    Object coordinator(){return app.requiredBean("cn.servicehub.jobs.JobCoordinator");}
    Object admission(){return app.requiredBean("cn.servicehub.jobs.AdmissionController");}
    Object recovery(){return app.requiredBean("cn.servicehub.jobs.JobRecoveryScheduler");}
    long background() {
        long document=ids.incrementAndGet(),version=ids.incrementAndGet();
        return new TransactionTemplate(app.context.getBean(PlatformTransactionManager.class)).execute(status->{
            app.db.update("INSERT INTO knowledge_documents(id,tenant_id,title,uploaded_by) VALUES(?,1001,'Synthetic policy',2002)",document);
            app.db.update("INSERT INTO document_versions(id,tenant_id,document_id,number,storage_key,filename,media_type,size_bytes,sha256) VALUES(?,1001,?,1,?,'synthetic.txt','text/plain',1,?)",version,document,"synthetic-"+version,"0".repeat(64));
            var payload=Map.<String,Object>of("tenant_id",1001,"actor_id",2002,"auth_version",1,"document_id",document,"version_id",version,"document_revision",1);
            var job=app.context.getBean(JobJournal.class).enqueue(new JobCommand(1001,"knowledge_index",ExecutionClass.BACKGROUND,"version:"+version,payload));
            app.db.update("UPDATE document_versions SET job_id=? WHERE id=?",job.id(),version);return job.id();
        });
    }
    long foreground() throws Exception {
        var user=app.login(2003);var room=app.request("POST","/api/v1/conversations",Map.of(),user);
        assertThat(room.statusCode()).isEqualTo(201);long id=app.body(room).path("id").asLong();
        var msg=app.request("POST","/api/v1/conversations/"+id+"/messages",Map.of("client_message_id","runtime-message","body","查询政策"),user);
        assertThat(msg.statusCode()).isEqualTo(200);return app.body(msg).path("trigger_job_id").asLong();
    }
    Object permit(ExecutionClass type,String owner){
        var result=(Optional<?>)call(admission(),"tryAcquire",new Class<?>[]{ExecutionClass.class,String.class},type,owner);
        assertThat(result).isPresent();var permit=result.orElseThrow();permits.add((AutoCloseable)permit);return permit;
    }
    Optional<?> claim(long id,String owner){
        return (Optional<?>)call(coordinator(),"claim",new Class<?>[]{long.class,String.class},id,owner);
    }
    Object claimed(long id,String owner){var result=claim(id,owner);assertThat(result).isPresent();return result.orElseThrow();}
    void finish(Object lease,String state){
        call(coordinator(),"finish",new Class<?>[]{leaseType(),String.class,Map.class},lease,state,Map.of("synthetic",true));
    }
    void fail(Object lease,boolean retryable){call(coordinator(),"fail",new Class<?>[]{leaseType(),String.class,boolean.class},lease,"controlled_failure",retryable);}
    boolean renew(Object lease){return (Boolean)call(coordinator(),"renew",new Class<?>[]{leaseType()},lease);}
    void requireValid(Object lease){call(coordinator(),"requireValid",new Class<?>[]{leaseType()},lease);}
    void recover(){call(recovery(),"recoverOnce",new Class<?>[0]);}
    Instant instant(String column,long id){
        return app.db.queryForObject("SELECT "+column+" FROM jobs WHERE id=?",(rs,n)->{var value=rs.getObject(1,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);},id);
    }
    Instant now(){return app.db.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC));}
    String state(long id){return app.db.queryForObject("SELECT state FROM jobs WHERE id=?",String.class,id);}
    void ready(long id){app.db.update("UPDATE jobs SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",id);}
    static Class<?> leaseType(){try{return Class.forName("cn.servicehub.jobs.JobLease");}catch(ClassNotFoundException e){return org.assertj.core.api.Assertions.fail("JobLease is not implemented");}}
    static Object get(Object value,String name){return call(value,name,new Class<?>[0]);}
    static long number(Object value,String name){return ((Number)get(value,name)).longValue();}
    static Object call(Object bean,String method,Class<?>[] types,Object...args){
        try{return bean.getClass().getMethod(method,types).invoke(bean,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;if(e.getCause() instanceof Error error)throw error;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Runtime contract is not implemented: "+method,e);}
    }
    @Override public void close(){
        for(var permit:permits){try{permit.close();}catch(Exception ignored){}}
        app.close();
    }
}
