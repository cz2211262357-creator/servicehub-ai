package cn.servicehub.jobs;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxDeliveryIT {
    final JobRuntimeFixture f=new JobRuntimeFixture();
    @BeforeAll void start() throws Exception {f.start();}
    @AfterAll void close(){f.close();}
    @BeforeEach void reset(){f.reset();}
    Object store(){return f.app.requiredBean("cn.servicehub.jobs.OutboxStore");}
    Object publisher(){return f.app.requiredBean("cn.servicehub.jobs.RabbitJobPublisher");}
    Object dispatcher(Object publisher){
        try {
            return Class.forName("cn.servicehub.jobs.OutboxDispatcher").getConstructor(
                Class.forName("cn.servicehub.jobs.OutboxStore"),Class.forName("cn.servicehub.jobs.JobPublisher"),
                org.springframework.data.redis.core.StringRedisTemplate.class,tools.jackson.databind.json.JsonMapper.class)
                .newInstance(store(),publisher,f.app.context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class),f.app.json);
        }catch(ReflectiveOperationException e){throw new AssertionError("OutboxDispatcher contract missing",e);}
    }
    boolean dispatch(Object dispatcher,long job,String owner){
        return (Boolean)JobRuntimeFixture.call(dispatcher,"dispatchJob",new Class<?>[]{long.class,String.class},job,owner);
    }
    Object decorate(java.util.function.BiConsumer<Long,String> call){
        try{return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Class.forName("cn.servicehub.jobs.JobPublisher")},(p,m,args)->{
            if(m.getName().equals("publish")){call.accept(((Number)args[0]).longValue(),(String)args[1]);return null;}
            if(m.getName().equals("toString"))return "test-publication-boundary";
            throw new UnsupportedOperationException(m.getName());
        });}catch(ClassNotFoundException e){throw new AssertionError("JobPublisher contract missing",e);}
    }
    void publish(long id,String route){JobRuntimeFixture.call(publisher(),"publish",new Class<?>[]{long.class,String.class},id,route);}
    void clearQueue(){while(f.app.context.getBean(RabbitTemplate.class).receive("shj.ingest",100)!=null){}}
    long outbox(long job){return f.app.db.queryForObject("SELECT id FROM outbox_events WHERE job_id=? AND kind='job' AND dispatch_key='initial'",Long.class,job);}
    int dispatched(long job){return f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=? AND dispatched_at IS NOT NULL",Integer.class,job);}

    @Test void lostConfirmAllowsDuplicateDeliveryButOnlyOneLogicalExecution(){
        clearQueue();long job=f.background();var lost=dispatcher(decorate((id,route)->{publish(id,route);throw new IllegalStateException("injected-confirm-loss");}));
        assertThat(dispatch(lost,job,"publisher-a")).isFalse();assertThat(dispatched(job)).isZero();
        f.app.db.update("UPDATE outbox_events SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",outbox(job));
        assertThat(dispatch(dispatcher(publisher()),job,"publisher-b")).isTrue();
        var rabbit=f.app.context.getBean(RabbitTemplate.class);
        var first=rabbit.receive("shj.ingest",3000);var second=rabbit.receive("shj.ingest",3000);
        assertThat(first).isNotNull();assertThat(second).isNotNull();
        assertThat(f.app.json.readTree(first.getBody()).path("job_id").asLong()).isEqualTo(job);
        assertThat(f.app.json.readTree(second.getBody()).path("job_id").asLong()).isEqualTo(job);
        f.permit(ExecutionClass.BACKGROUND,"worker-a");f.permit(ExecutionClass.BACKGROUND,"worker-b");
        var lease=f.claimed(job,"worker-a");assertThat(f.claim(job,"worker-b")).isEmpty();f.finish(lease,"SUCCEEDED");
        assertThat(f.claim(job,"worker-b")).isEmpty();
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,job)).isEqualTo(1);
    }

    @Test void expiredPublisherCannotMarkNewLeaseDispatched(){
        long job=f.background();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var held=dispatcher(decorate((id,route)->{publish(id,route);entered.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){throw new RuntimeException(e);}}));
        try(var pool=Executors.newSingleThreadExecutor()){
            var old=pool.submit(()->dispatch(held,job,"publisher-old"));
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                long before=f.app.db.queryForObject("SELECT lease_version FROM outbox_events WHERE id=?",Long.class,outbox(job));
                f.app.db.update("UPDATE outbox_events SET lease_expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?",outbox(job));
                assertThat(dispatch(dispatcher(publisher()),job,"publisher-new")).isTrue();
                assertThat(f.app.db.queryForObject("SELECT lease_version FROM outbox_events WHERE id=?",Long.class,outbox(job))).isGreaterThan(before);
            }catch(Exception e){throw new AssertionError(e);}finally{release.countDown();}
            try{assertThat(old.get(5,TimeUnit.SECONDS)).isFalse();}catch(Exception e){throw new AssertionError(e);}
        }
        assertThat(dispatched(job)).isEqualTo(1);
    }

    @Test void returnedUnroutablePublicationIsNotSuccessful(){
        assertThatThrownBy(()->publish(f.background(),"missing-route")).isInstanceOf(RuntimeException.class);
    }

    @Test void brokerUnavailableDoesNotRollBackAcceptedJobAndLaterDispatches(){
        var container=f.app.dependencies.rabbitmq;long job;
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
        try{
            job=f.background();assertThat(f.state(job)).isEqualTo("QUEUED");
            assertThat(dispatch(dispatcher(publisher()),job,"publisher-paused")).isFalse();
            assertThat(dispatched(job)).isZero();
        }finally{container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();}
        f.app.db.update("UPDATE outbox_events SET available_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE job_id=?",job);
        assertThat(dispatch(dispatcher(publisher()),job,"publisher-resumed")).isTrue();
    }

    Object consumer(java.util.function.Consumer<Object> handler,AtomicInteger executions){
        try{
            Class<?> handlerType=Class.forName("cn.servicehub.jobs.JobHandler");
            Object impl=Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{handlerType},(p,m,args)->{
                if(m.getName().equals("handle")){executions.incrementAndGet();handler.accept(args[0]);return null;}
                if(m.getName().equals("toString"))return "test-handler";
                throw new UnsupportedOperationException(m.getName());
            });
            Object created=Class.forName("cn.servicehub.jobs.JobConsumer").getConstructor(JobCoordinator.class,AdmissionController.class,JobMapper.class,Map.class,cn.servicehub.config.ServiceHubProperties.class,tools.jackson.databind.json.JsonMapper.class,cn.servicehub.common.RuntimeMetrics.class)
                .newInstance(f.coordinator(),f.admission(),f.app.context.getBean(JobMapper.class),Map.of("knowledge_index",impl),f.app.context.getBean(cn.servicehub.config.ServiceHubProperties.class),f.app.json,f.app.context.getBean(cn.servicehub.common.RuntimeMetrics.class));
            f.permits.add((AutoCloseable)created);return created;
        }catch(ReflectiveOperationException e){throw new AssertionError("JobConsumer contract missing",e);}
    }
    void consume(Object consumer,byte[] body,Runnable ack,java.util.function.Consumer<Boolean> reject){
        JobRuntimeFixture.call(consumer,"accept",new Class<?>[]{byte[].class,ExecutionClass.class,Runnable.class,java.util.function.Consumer.class},body,ExecutionClass.BACKGROUND,ack,reject);
    }
    byte[] body(long job){return f.app.json.writeValueAsBytes(Map.of("job_id",job,"schema_version",1));}

    @Test void successAndDuplicateAreAckedOnlyAfterDurableDisposition(){
        long job=f.background();var executions=new AtomicInteger();var acks=new AtomicInteger();
        var consumer=consumer(lease->f.finish(lease,"SUCCEEDED"),executions);
        Runnable ack=()->{assertThat(f.state(job)).isEqualTo("SUCCEEDED");acks.incrementAndGet();};
        consume(consumer,body(job),ack,requeue->fail("Unexpected rejection"));
        consume(consumer,body(job),ack,requeue->fail("Unexpected rejection"));
        assertThat(executions).hasValue(1);assertThat(acks).hasValue(2);
    }

    @Test void retryIsCommittedBeforeAckAndAttemptBudgetIsNotConsumedByNoCapacity(){
        long job=f.background();var executions=new AtomicInteger();var acks=new AtomicInteger();
        var consumer=consumer(lease->{throw new IllegalStateException("controlled transient failure");},executions);
        consume(consumer,body(job),()->{
            assertThat(f.state(job)).isEqualTo("RETRY_WAIT");
            assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=?",Integer.class,job)).isEqualTo(2);
            acks.incrementAndGet();
        },requeue->fail("Unexpected rejection"));
        assertThat(acks).hasValue(1);assertThat(executions).hasValue(1);
        f.ready(job);
        for(int i=0;i<3;i++)f.permit(ExecutionClass.BACKGROUND,"busy-"+i);
        consume(consumer,body(job),()->acks.incrementAndGet(),requeue->fail("Unexpected rejection"));
        assertThat(executions).hasValue(1);
        assertThat(f.app.db.queryForObject("SELECT attempts FROM jobs WHERE id=?",Integer.class,job)).isEqualTo(1);
    }

    public static class WorkerProbe {
        @org.springframework.context.annotation.Bean("knowledge_index")
        JobHandler probeHandler(JobCoordinator coordinator){return lease->coordinator.finish(lease,"SUCCEEDED",Map.of("synthetic",true));}
    }
    @Test void realWorkerProfileConsumesAndDeadLettersMalformedProtocol() throws Exception {
        long job=f.background();var d=f.app.dependencies;
        try(var worker=new org.springframework.boot.builder.SpringApplicationBuilder(cn.servicehub.ServiceHubApplication.class,WorkerProbe.class).run(
            "--spring.profiles.active=worker","--spring.main.web-application-type=none","--spring.main.allow-bean-definition-overriding=true",
            "--servicehub.database.url="+d.jdbcUrl(),"--servicehub.database.username="+d.username(),"--servicehub.database.password="+d.password(),
            "--servicehub.redis.host="+d.redis.getHost(),"--servicehub.redis.port="+d.redis.getMappedPort(6379),"--servicehub.redis.password="+d.password(),
            "--servicehub.rabbitmq.host="+d.rabbitmq.getHost(),"--servicehub.rabbitmq.port="+d.rabbitmq.getMappedPort(5672),"--servicehub.rabbitmq.username="+d.username(),"--servicehub.rabbitmq.password="+d.password())){
            assertThat(worker).isNotInstanceOf(org.springframework.boot.web.server.context.WebServerApplicationContext.class);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(worker.getBean("foregroundListener",org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer.class).getActiveConsumerCount()).isEqualTo(4));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(f.state(job)).isEqualTo("SUCCEEDED"));
            var rabbit=f.app.context.getBean(RabbitTemplate.class);
            while(rabbit.receive("shj.ingest.dlq",100)!=null){}
            rabbit.send("shj.jobs","ingest",new org.springframework.amqp.core.Message("{bad".getBytes(java.nio.charset.StandardCharsets.UTF_8),new org.springframework.amqp.core.MessageProperties()));
            assertThat(rabbit.receive("shj.ingest.dlq",5000)).isNotNull();
        }
    }

    @Test void guardCancellationAndPermissionErrorsNeverEnqueueRetry(){
        for(var cause:List.of(new cn.servicehub.ai.ExecutionCancelledException("controlled_cancel"),new cn.servicehub.common.BusinessException("forbidden","Synthetic denial",403))){
            long job=f.background();var executions=new AtomicInteger();var acks=new AtomicInteger();
            var consumer=consumer(lease->{throw cause;},executions);
            consume(consumer,body(job),acks::incrementAndGet,requeue->fail("Unexpected rejection"));
            assertThat(acks).hasValue(1);assertThat(executions).hasValue(1);
            assertThat(f.state(job)).isEqualTo(cause instanceof cn.servicehub.ai.ExecutionCancelledException?"CANCELLED":"FAILED");
            assertThat(f.app.db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE job_id=?",Integer.class,job)).isEqualTo(1);
        }
    }

    @Test void badProtocolGoesToDeadLetterAndUnknownIdIsAcked(){
        var executions=new AtomicInteger();var rejected=new AtomicInteger();var acked=new AtomicInteger();var consumer=consumer(lease->fail("Should not execute"),executions);
        consume(consumer,f.app.json.writeValueAsBytes(Map.of("job_id",f.background(),"schema_version",1,"tenant_id",1002)),()->fail("Bad protocol acked"),requeue->{assertThat(requeue).isFalse();rejected.incrementAndGet();});
        consume(consumer,body(Long.MAX_VALUE),acked::incrementAndGet,requeue->fail("Unknown job rejected"));
        assertThat(rejected).hasValue(1);assertThat(acked).hasValue(1);assertThat(executions).hasValue(0);
    }
}
