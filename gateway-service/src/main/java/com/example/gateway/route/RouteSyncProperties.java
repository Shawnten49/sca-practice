package com.example.gateway.route;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gateway-dashboard.route-sync")
public class RouteSyncProperties {

    private long pollIntervalMs = 5000;
    /** 内网管理接口令牌。不设代码默认值：生产环境通过环境变量/配置注入（见 application.yml）；留空表示不鉴权，仅限本地开发。 */
    private String internalToken;

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
