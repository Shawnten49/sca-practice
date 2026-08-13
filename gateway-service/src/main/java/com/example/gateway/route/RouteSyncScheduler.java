package com.example.gateway.route;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 兜底同步：定期比较 route_config 表的（行数, 最大版本号），
 * 发生变化即触发路由刷新。即使仪表盘的主动推送失败，路由也会在数秒内生效。
 */
@Component
public class RouteSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(RouteSyncScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final RouteRefreshPublisher refreshPublisher;
    private final RouteSyncProperties properties;

    private volatile String lastChecksum = "";

    public RouteSyncScheduler(JdbcTemplate jdbcTemplate,
                              RouteRefreshPublisher refreshPublisher,
                              RouteSyncProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.refreshPublisher = refreshPublisher;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        lastChecksum = currentChecksum();
    }

    @Scheduled(fixedDelayString = "${gateway-dashboard.route-sync.poll-interval-ms:5000}")
    public void poll() {
        String current = currentChecksum();
        if (current != null && !current.equals(lastChecksum)) {
            lastChecksum = current;
            refreshPublisher.refresh();
            log.info("检测到路由配置变化（{}），已触发刷新", current);
        }
    }

    private String currentChecksum() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT CONCAT(COUNT(*), ':', COALESCE(MAX(version), 0)) FROM route_config",
                    String.class);
        } catch (Exception e) {
            log.warn("读取路由配置版本失败: {}", e.getMessage());
            return null;
        }
    }
}
