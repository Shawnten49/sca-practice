package com.example.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 信用点配置（application.yml 的 app.credits 段）。 */
@ConfigurationProperties(prefix = "app.credits")
public class CreditsProperties {

    /** 单次增量上限（防误操作） */
    private int maxDelta = 100000;

    public int getMaxDelta() {
        return maxDelta;
    }

    public void setMaxDelta(int maxDelta) {
        this.maxDelta = maxDelta;
    }
}
