package cn.servicehub.system;
import cn.servicehub.support.BrokerObservation;
import cn.servicehub.support.DependencyContainers;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
/** A real manual delivery must be visible even before Quorum's cached counters tick. */
class BrokerObservationIT {
 final DependencyContainers dependencies=new DependencyContainers();
 @BeforeEach void start(){
  dependencies.rabbitmq.withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS","-rabbit quorum_tick_interval 60000").start();
 }
 @AfterEach void close(){dependencies.rabbitmq.stop();}
 @Test void observesManualDeliveryAndActualAckBeforeCachedQueueStatisticsRefresh()throws Exception{
  var broker=dependencies.rabbitmq;var factory=new ConnectionFactory();
  factory.setHost(broker.getHost());factory.setPort(broker.getMappedPort(5672));
  factory.setUsername(dependencies.username());factory.setPassword(dependencies.password());
  try(var connection=factory.newConnection();var channel=connection.createChannel()){
   String queue="owned-observation-"+UUID.randomUUID();
   channel.queueDeclare(queue,true,false,false,Map.of("x-queue-type","quorum"));
   var entered=new CountDownLatch(1);var deliveryTag=new AtomicLong();
   channel.basicConsume(queue,false,(consumerTag,message)->{
    deliveryTag.set(message.getEnvelope().getDeliveryTag());entered.countDown();
   },consumerTag->{});
   channel.basicPublish("",queue,null,"synthetic observation".getBytes(StandardCharsets.UTF_8));
   assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   var cached=broker.execInContainer("rabbitmqctl","list_queues","name","messages_unacknowledged","--quiet");
   assertThat(cached.getExitCode()).isZero();
   assertThat(cached.getStdout().lines().map(String::strip).filter(line->line.startsWith(queue+"\t"))).containsExactly(queue+"\t0");
   var observation=new BrokerObservation(broker);
   assertThat(observation.unacknowledgedDeliveries()).as("Actual delivery is still unacked despite stale Quorum counters").isEqualTo(1);
   channel.basicAck(deliveryTag.get(),false);
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   int outstanding;
   do{outstanding=observation.unacknowledgedDeliveries();if(outstanding==0)break;Thread.sleep(50);}
   while(System.nanoTime()<deadline);
   assertThat(outstanding).as("Actual client ack clears the delivery").isZero();
  }
 }
}
