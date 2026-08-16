package com.example.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/** HTTP 客户端配置：为 Leaf 调用提供带超时的 RestTemplate（防止 Leaf 挂掉时请求悬挂）。 */
@Configuration
@EnableConfigurationProperties(LeafProperties.class)
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(LeafProperties leafProperties) {
        return new RestTemplateBuilder()
                .setConnectTimeout(leafProperties.getConnectTimeout())
                .setReadTimeout(leafProperties.getReadTimeout())
                .build();
    }
}
