package cn.servicehub.realtime;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import org.springframework.web.socket.config.annotation.*;
@Configuration(proxyBeanMethods=false)
@EnableWebSocket
@Profile("api")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class LiveSocketConfiguration implements WebSocketConfigurer {
 private final LiveWebSocketHandler handler;
 public LiveSocketConfiguration(LiveWebSocketHandler handler){this.handler=handler;}
 @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(handler,"/api/v1/conversations/{conversation_id}/live").setAllowedOriginPatterns("*");}
 @Bean(destroyMethod="shutdownNow") ExecutorService liveNotifications(){
  return new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().daemon(true).name("live-notification-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
 }
 @Bean RedisMessageListenerContainer liveRedisNotifications(RedisConnectionFactory factory,LiveEventHub hub,ExecutorService liveNotifications){
  var container=new RedisMessageListenerContainer();container.setConnectionFactory(factory);container.setTaskExecutor(liveNotifications);container.setRecoveryInterval(1000);
  container.setMessageListeners(Map.of(hub,java.util.List.of(new PatternTopic("shj:conversation:*"))));return container;
 }
}
