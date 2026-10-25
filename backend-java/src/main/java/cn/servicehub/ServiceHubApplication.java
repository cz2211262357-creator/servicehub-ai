package cn.servicehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class ServiceHubApplication {
    public static void main(String[] args) { SpringApplication.run(ServiceHubApplication.class, args); }
}
