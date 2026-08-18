package com.example.stock.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import java.util.Set;

/**
 * 表变更处理器：一个表一个实现，路由 key 为 "database.table"。
 */
public interface TableSyncHandler {

    /** 路由 key 集合，例如 "seata_stock.product"。 */
    Set<String> supportedKeys();

    /**
     * 是否天然幂等（删缓存 / upsert / 日志类）：默认 true，Consumer 直接执行、不去重；
     * 非幂等操作（累加、发通知等）覆盖为 false（本项目 ES upsert 天然幂等，暂无此场景）。
     */
    default boolean idempotent() {
        return true;
    }

    /**
     * 字段级变更过滤：默认全部处理；只关心某些字段变更的 Handler 覆盖此方法。
     * 返回 false 时 Consumer 直接跳过。
     */
    default boolean shouldHandle(CanalMessage message) {
        return true;
    }

    /**
     * 处理一条 binlog 变更事件。
     * 实现需保证幂等（Canal + MQ 为 at-least-once）；
     * 抛异常会让 RocketMQ 重试该消息。
     */
    void handle(CanalMessage message);
}
