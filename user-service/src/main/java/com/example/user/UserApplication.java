package com.example.user;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import com.example.user.config.CreditsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;

@EnableFeignClients
@MapperScan("com.example.user.mapper")
@EnableMethodCache(basePackages = "com.example.user")
@EnableConfigurationProperties(CreditsProperties.class)
@SpringBootApplication(scanBasePackages = "com.example")
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
