package cn.servicehub.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceHubProperties.class)
public class ServiceHubConfiguration {
    @Bean cn.servicehub.knowledge.PrivateFileStore privateFileStore(ServiceHubProperties p){return new cn.servicehub.knowledge.PrivateFileStore(p.uploadDirectory(),p.limits().maxUploadBytes());}
    @Bean(name = "healthExecutor", destroyMethod = "close")
    ExecutorService healthExecutor() {
        return new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().name("health-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
