package com.example.order.config;

import com.example.id.IdGenerator;
import com.example.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {
    @Bean
    public IdGenerator idGenerator(@Value("${id.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
