package cn.servicehub.config;

import cn.servicehub.common.WireJson;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class WireJsonConfiguration {
    @Bean JsonMapperBuilderCustomizer wireJsonCustomizer() { return builder -> builder.addModule(WireJson.module()); }
}
