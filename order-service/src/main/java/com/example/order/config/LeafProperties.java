package com.example.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Leaf 服务连接配置（application.yml 的 leaf 段）。 */
@ConfigurationProperties(prefix = "leaf")
public class LeafProperties {

    /** Leaf 服务地址 */
    private String url = "http://127.0.0.1:8085";

    /** 连接超时 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 读取超时 */
    private Duration readTimeout = Duration.ofSeconds(3);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
