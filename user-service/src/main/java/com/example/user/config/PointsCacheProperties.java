package com.example.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 三级缓存配置（application.yml 的 app.points-cache 段）。 */
@ConfigurationProperties(prefix = "app.points-cache")
public class PointsCacheProperties {

    /** Redis 缓存 TTL */
    private Duration redisTtl = Duration.ofMinutes(5);

    /** Redis TTL 随机抖动范围（防雪崩） */
    private Duration redisTtlJitter = Duration.ofSeconds(30);

    /** Caffeine 本地缓存 TTL */
    private Duration caffeineTtl = Duration.ofSeconds(30);

    /** 空值标记 TTL（防穿透） */
    private Duration emptyTtl = Duration.ofSeconds(30);

    /** 单次增量上限（防误操作） */
    private int maxDelta = 100000;

    /** 延迟双删的延时（写后延迟删缓存，缩小 Cache-Aside 竞态窗口） */
    private Duration doubleDeleteDelay = Duration.ofMillis(500);

    public Duration getRedisTtl() {
        return redisTtl;
    }

    public void setRedisTtl(Duration redisTtl) {
        this.redisTtl = redisTtl;
    }

    public Duration getRedisTtlJitter() {
        return redisTtlJitter;
    }

    public void setRedisTtlJitter(Duration redisTtlJitter) {
        this.redisTtlJitter = redisTtlJitter;
    }

    public Duration getCaffeineTtl() {
        return caffeineTtl;
    }

    public void setCaffeineTtl(Duration caffeineTtl) {
        this.caffeineTtl = caffeineTtl;
    }

    public Duration getEmptyTtl() {
        return emptyTtl;
    }

    public void setEmptyTtl(Duration emptyTtl) {
        this.emptyTtl = emptyTtl;
    }

    public int getMaxDelta() {
        return maxDelta;
    }

    public void setMaxDelta(int maxDelta) {
        this.maxDelta = maxDelta;
    }

    public Duration getDoubleDeleteDelay() {
        return doubleDeleteDelay;
    }

    public void setDoubleDeleteDelay(Duration doubleDeleteDelay) {
        this.doubleDeleteDelay = doubleDeleteDelay;
    }
}
