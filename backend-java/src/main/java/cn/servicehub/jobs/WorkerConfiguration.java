package cn.servicehub.jobs;

import com.rabbitmq.client.Channel;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@Profile("worker")
public class WorkerConfiguration {
    @Bean(name="foregroundListener",destroyMethod="destroy")
    SimpleMessageListenerContainer foreground(CachingConnectionFactory factory,JobConsumer consumer){return listener(factory,consumer,"shj.ai",ExecutionClass.INTERACTIVE,4);}
    @Bean(name="backgroundListener",destroyMethod="destroy")
    SimpleMessageListenerContainer background(CachingConnectionFactory factory,JobConsumer consumer){return listener(factory,consumer,"shj.ingest",ExecutionClass.BACKGROUND,3);}
    private SimpleMessageListenerContainer listener(CachingConnectionFactory factory,JobConsumer consumer,String queue,ExecutionClass type,int concurrency){
        var c=new SimpleMessageListenerContainer(factory);c.setQueueNames(queue);c.setConcurrentConsumers(concurrency);c.setMaxConcurrentConsumers(concurrency);
        c.setPrefetchCount(1);c.setAcknowledgeMode(AcknowledgeMode.MANUAL);c.setForceStop(true);c.setShutdownTimeout(3000);c.setDefaultRequeueRejected(true);
        c.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
            long tag=message.getMessageProperties().getDeliveryTag();
            consumer.accept(message.getBody(),type,()->settle(channel,tag,true,false),requeue->settle(channel,tag,false,requeue));
        });
        return c;
    }
    private static void settle(Channel channel,long tag,boolean ack,boolean requeue){
        try{if(ack)channel.basicAck(tag,false);else channel.basicReject(tag,requeue);}catch(java.io.IOException e){throw new IllegalStateException("Broker settlement failed",e);}
    }
    @Bean(destroyMethod="close") WorkerLoops workerLoops(OutboxDispatcher dispatcher,JobRecoveryScheduler recovery,cn.servicehub.knowledge.InactiveIndexCleanup cleanup){return new WorkerLoops(dispatcher,recovery,cleanup);}
    static final class WorkerLoops implements AutoCloseable {
        private final ScheduledExecutorService publishing=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("outbox-dispatch").factory());
        private final ScheduledExecutorService recovering=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("job-recovery").factory());
        private final ScheduledExecutorService cleaning=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("index-cleanup").factory());
        WorkerLoops(OutboxDispatcher outbox,JobRecoveryScheduler recovery,cn.servicehub.knowledge.InactiveIndexCleanup cleanup){
            String owner="p-"+UUID.randomUUID();
            publishing.scheduleWithFixedDelay(()->safe(()->outbox.dispatchOnce(owner)),0,250,TimeUnit.MILLISECONDS);
            recovering.scheduleWithFixedDelay(()->safe(recovery::recoverOnce),0,1,TimeUnit.SECONDS);
            cleaning.scheduleWithFixedDelay(()->safe(cleanup::cleanupOnce),60,60,TimeUnit.SECONDS);
        }
        private void safe(Runnable action){try{action.run();}catch(RuntimeException e){LoggerFactory.getLogger(WorkerLoops.class).warn("worker_tick_failed exception_type={}",e.getClass().getSimpleName());}}
        @Override public void close(){publishing.shutdownNow();recovering.shutdownNow();cleaning.shutdownNow();}
    }
}
