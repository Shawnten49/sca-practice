package com.example.user.config;

import com.example.user.entity.PointsValue;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PointsCacheProperties.class)
public class CaffeineConfig {

    @Bean
    public Cache<Long, PointsValue> pointsCaffeineCache(PointsCacheProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(props.getCaffeineTtl())
                .build();
    }
}
