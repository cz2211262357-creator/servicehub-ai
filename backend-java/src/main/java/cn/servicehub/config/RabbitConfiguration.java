package cn.servicehub.config;
import cn.servicehub.common.DependencyProbe;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.nio.NioParams;
import java.util.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@Profile("!health-contract")
public class RabbitConfiguration {
    @Bean CachingConnectionFactory rabbitConnectionFactory(ServiceHubProperties properties){
        var p=properties.rabbitmq();var raw=new ConnectionFactory();
        raw.setHost(p.host());raw.setPort(p.port());raw.setUsername(p.username());raw.setPassword(p.password());
        raw.setAutomaticRecoveryEnabled(false);raw.setConnectionTimeout(3000);raw.setHandshakeTimeout(3000);raw.setChannelRpcTimeout(3000);raw.setShutdownTimeout(3000);raw.setRequestedHeartbeat(10);
        raw.setNioParams(new NioParams().setWriteEnqueuingTimeoutInMs(1000).setWriteQueueCapacity(100));raw.useNio();
        var cf=new CachingConnectionFactory(raw);cf.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);cf.setPublisherReturns(true);
        cf.setChannelCacheSize(16);cf.setChannelCheckoutTimeout(3000);cf.setCloseTimeout(3000);return cf;
    }
    @Bean Declarables jobTopology(){
        var jobs=new DirectExchange("shj.jobs",true,false);var dlx=new DirectExchange("shj.jobs.dlx",true,false);var declarations=new ArrayList<Declarable>();declarations.add(jobs);declarations.add(dlx);
        for(var route:List.of("ai","ingest")){
            var queue=QueueBuilder.durable("shj."+route).quorum().deadLetterExchange("shj.jobs.dlx").deadLetterRoutingKey(route+".dead").build();
            var dead=QueueBuilder.durable("shj."+route+".dlq").quorum().build();
            declarations.add(queue);declarations.add(dead);declarations.add(BindingBuilder.bind(queue).to(jobs).with(route));declarations.add(BindingBuilder.bind(dead).to(dlx).with(route+".dead"));
        }
        return new Declarables(declarations);
    }
    @Bean RabbitAdmin rabbitAdmin(CachingConnectionFactory factory){return new RabbitAdmin(factory);}
    @Bean RabbitTemplate rabbitTemplate(CachingConnectionFactory factory){
        var t=new RabbitTemplate(factory);t.setMandatory(true);t.setUsePublisherConnection(true);t.setReturnsCallback(returned->{});return t;
    }
    @Bean DependencyProbe rabbitProbe(RabbitTemplate rabbit){
        return new DependencyProbe(){public String name(){return "rabbitmq";}public boolean isHealthy(){return Boolean.TRUE.equals(rabbit.execute(channel->{channel.exchangeDeclarePassive("shj.jobs");channel.queueDeclarePassive("shj.ai");channel.queueDeclarePassive("shj.ingest");return true;}));}};
    }
}
