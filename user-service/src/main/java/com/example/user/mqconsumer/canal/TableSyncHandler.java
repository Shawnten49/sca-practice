package com.example.user.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import java.util.Set;

/**
 * 表变更处理器：一个表一个实现，路由 key 为 "database.table"。
 * 分表场景（如 orders_0~3）可返回多个物理表 key，Consumer 会按物理表名精确分发。
 */
public interface TableSyncHandler {

    /** 路由 key 集合，例如 "seata_user.users"，分表为 "seata_order.orders_0" ~ "orders_3"。 */
    Set<String> supportedKeys();

    /**
     * 是否天然幂等（删缓存 / upsert / 日志类）：默认 true，Consumer 直接执行、不去重；
     * 非幂等操作（累加、发通知等）覆盖为 false，Consumer 会走幂等门面按 binlog 位点去重。
     */
    default boolean idempotent() {
        return true;
    }

    /**
     * 字段级变更过滤：默认全部处理；只关心某些字段变更的 Handler 覆盖此方法
     * （例如只关心 users.points 变化）。返回 false 时 Consumer 直接跳过，
     * 不进入幂等门面、不写 sync_log。
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
