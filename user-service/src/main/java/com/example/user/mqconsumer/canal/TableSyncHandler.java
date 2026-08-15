package com.example.user.mqconsumer.canal;

/**
 * 单表变更处理器：一个表一个实现，路由 key 为 "database.table"。
 */
public interface TableSyncHandler {

    /** 路由 key，例如 "seata_user.users"。 */
    String supportedKey();

    /**
     * 处理一条 binlog 变更事件。
     * 实现需保证幂等（Canal + MQ 为 at-least-once）；
     * 抛异常会让 RocketMQ 重试该消息。
     */
    void handle(CanalMessage message);
}
