package com.example.task.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** 任务参数配置（application.yml 的 app.task 段）。 */
@ConfigurationProperties(prefix = "app.task")
public class TaskProperties {

    /** 每批条数 */
    private int batchSize = 500;

    /** 批间休眠毫秒数（防突发压力，默认 0） */
    private long batchSleepMs = 0L;

    /** 订单时间范围（天） */
    private int orderDays = 3;

    /** 商品时间范围（天） */
    private int productDays = 3;

    private Duration orderTtl = Duration.ofDays(3);

    private Duration userTtl = Duration.ofDays(7);

    private Duration productTtl = Duration.ofDays(3);

    /** 订单物理分片表清单 */
    private List<String> orderShardTables = List.of("orders_0", "orders_1", "orders_2", "orders_3");

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getBatchSleepMs() {
        return batchSleepMs;
    }

    public void setBatchSleepMs(long batchSleepMs) {
        this.batchSleepMs = batchSleepMs;
    }

    public int getOrderDays() {
        return orderDays;
    }

    public void setOrderDays(int orderDays) {
        this.orderDays = orderDays;
    }

    public int getProductDays() {
        return productDays;
    }

    public void setProductDays(int productDays) {
        this.productDays = productDays;
    }

    public Duration getOrderTtl() {
        return orderTtl;
    }

    public void setOrderTtl(Duration orderTtl) {
        this.orderTtl = orderTtl;
    }

    public Duration getUserTtl() {
        return userTtl;
    }

    public void setUserTtl(Duration userTtl) {
        this.userTtl = userTtl;
    }

    public Duration getProductTtl() {
        return productTtl;
    }

    public void setProductTtl(Duration productTtl) {
        this.productTtl = productTtl;
    }

    public List<String> getOrderShardTables() {
        return orderShardTables;
    }

    public void setOrderShardTables(List<String> orderShardTables) {
        this.orderShardTables = orderShardTables;
    }
}
