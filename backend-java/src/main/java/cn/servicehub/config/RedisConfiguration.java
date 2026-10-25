package cn.servicehub.config;

import cn.servicehub.common.DependencyProbe;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods=false)
public class RedisConfiguration {
    @Bean LettuceConnectionFactory redisConnectionFactory(ServiceHubProperties properties){
        var p=properties.redis();var standalone=new RedisStandaloneConfiguration(p.host(),p.port());
        if(p.password()!=null&&!p.password().isBlank())standalone.setPassword(RedisPassword.of(p.password()));
        var client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).shutdownTimeout(Duration.ofMillis(100))
            .clientOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build()).build()).build();
        return new LettuceConnectionFactory(standalone,client);
    }
    @Bean StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory){return new StringRedisTemplate(factory);}
    @Bean DependencyProbe redisProbe(StringRedisTemplate redis){
        return new DependencyProbe(){
            public String name(){return "redis";}
            public boolean isHealthy(){return "PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>)c->c.ping()));}
        };
    }
}
