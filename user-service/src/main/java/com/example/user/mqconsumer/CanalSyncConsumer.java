package com.example.user.mqconsumer;

import com.example.user.mqconsumer.canal.CanalMessage;
import com.example.user.mqconsumer.canal.TableSyncHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Canal binlog 变更消费入口：
 * 解析 JSON → 按 database.table 路由 → 分发给各表 Handler。
 *
 * 异常策略：
 * - JSON 解析失败 → 记 error 并跳过（畸形消息重试无意义，避免死循环）；
 * - Handler 业务异常 → 向上抛出，交给 RocketMQ 重试（Handler 内需保证幂等）。
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "canal-topic", consumerGroup = "canal-consumer")
public class CanalSyncConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final Map<String, TableSyncHandler> handlers;

    public CanalSyncConsumer(ObjectMapper objectMapper, List<TableSyncHandler> handlerList) {
        this.objectMapper = objectMapper;
        // 路由表由 Spring 收集的 Handler Bean 自动组装：新增表监听只需加一个 Handler
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(TableSyncHandler::supportedKey, Function.identity()));
    }

    @Override
    public void onMessage(String json) {
        CanalMessage message;
        try {
            message = objectMapper.readValue(json, CanalMessage.class);
        } catch (Exception e) {
            log.error("解析 Canal 消息失败，跳过该消息: {}", json, e);
            return;
        }

        if (message.isDdl()) {
            log.info("DDL 事件不处理，跳过: {}.{}", message.database(), message.table());
            return;
        }

        TableSyncHandler handler = handlers.get(message.routeKey());
        if (handler == null) {
            log.info("未注册的表变更事件，跳过: {}", message.routeKey());
            return;
        }
        handler.handle(message);
    }
}
