package cn.servicehub.jobs;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class RabbitJobPublisher implements JobPublisher {
    private final RabbitTemplate rabbit;private final JsonMapper json;
    public RabbitJobPublisher(RabbitTemplate rabbit,JsonMapper json){this.rabbit=rabbit;this.json=json;}
    @Override public void publish(long id,String route){
        var p=new MessageProperties();p.setContentType(MessageProperties.CONTENT_TYPE_JSON);p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var correlation=new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send("shj.jobs",route,new Message(json.writeValueAsBytes(Map.of("job_id",id,"schema_version",1)),p),correlation);
            var confirm=correlation.getFuture().get(3,TimeUnit.SECONDS);
            if(!confirm.isAck()||correlation.getReturned()!=null)throw new IllegalStateException("Publication not routed and confirmed");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Publication interrupted",e);}
        catch(Exception e){throw new IllegalStateException("Publication outcome unknown",e);}
    }
}
