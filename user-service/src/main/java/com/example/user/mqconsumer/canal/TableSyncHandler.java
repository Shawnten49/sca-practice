package com.example.user.mqconsumer.canal;

/**
 * 单表变更处理器：一个表一个实现，路由 key 为 "database.table"。
 */
public interface TableSyncHandler {

    /** 路由 key，例如 "seata_user.users"。 */
    String supportedKey();

    /**
     * 是否天然幂等（删缓存 / upsert / 日志类）：默认 true，Consumer 直接执行、不去重；
     * 非幂等操作（累加、发通知等）覆盖为 false，Consumer 会走幂等门面按 binlog 位点去重。
     */
    default boolean idempotent() {
        return true;
    }

    /**
     * 处理一条 binlog 变更事件。
     * 实现需保证幂等（Canal + MQ 为 at-least-once）；
     * 抛异常会让 RocketMQ 重试该消息。
     */
    void handle(CanalMessage message);
}
