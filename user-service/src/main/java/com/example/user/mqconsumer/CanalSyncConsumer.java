package com.example.user.mqconsumer;

import com.example.dto.canal.CanalEvent;
import com.example.user.mqconsumer.canal.CanalEventConverter;
import com.example.dto.canal.CanalMessage;
import com.example.user.mqconsumer.canal.CanalPacketParser;
import com.example.user.mqconsumer.canal.IdempotencyFacade;
import com.example.user.mqconsumer.canal.TableSyncHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.AbstractMap;
import java.util.stream.Collectors;

/**
 * Canal binlog 变更消费入口：
 * 解析 CanalPacket(Entry protobuf) → 按行拆分 → 按 database.table 路由 → 分发给各表 Handler。
 *
 * <p>与 flatMessage 模式的区别：Entry Header 携带 binlog 位点（logfileName/logfileOffset），
 * 非幂等 Handler 可以由幂等门面按「位点 + 行级 key」精确去重。
 *
 * 异常策略：
 * - 消息体解析失败 → 记 error 并跳过（畸形消息重试无意义，避免死循环）；
 * - Handler 业务异常 → 向上抛出，交给 RocketMQ 重试（Handler 内需保证幂等）。
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "canal-topic", consumerGroup = "canal-consumer")
public class CanalSyncConsumer implements RocketMQListener<MessageExt> {

    private final CanalPacketParser packetParser;
    private final CanalEventConverter eventConverter;
    private final Map<String, TableSyncHandler> handlers;
    private final IdempotencyFacade idempotencyFacade;

    public CanalSyncConsumer(CanalPacketParser packetParser,
                             CanalEventConverter eventConverter,
                             List<TableSyncHandler> handlerList,
                             IdempotencyFacade idempotencyFacade) {
        this.packetParser = packetParser;
        this.eventConverter = eventConverter;
        this.idempotencyFacade = idempotencyFacade;
        // 路由表由 Spring 收集的 Handler Bean 自动组装：新增表监听只需加一个 Handler；
        // 分表 Handler 返回多个物理表 key，这里展平为 key → Handler 映射
        this.handlers = handlerList.stream()
                .flatMap(handler -> handler.supportedKeys().stream()
                        .map(key -> new AbstractMap.SimpleImmutableEntry<>(key, handler)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void onMessage(MessageExt messageExt) {
        byte[] body = messageExt.getBody();
        log.info("接受到消息：topic={} queue={} size={}B", messageExt.getTopic(), messageExt.getQueueId(), body.length);

        List<CanalEvent> events;
        try {
            events = eventConverter.toEvents(packetParser.parse(body));
        } catch (Exception e) {
            log.error("解析 Canal 消息失败，跳过该消息: msgId={}", messageExt.getMsgId(), e);
            return;
        }

        for (CanalEvent event : events) {
            dispatch(event);
        }
    }

    private void dispatch(CanalEvent event) {
        CanalMessage message = event.message();
        log.info("dispatching: {}", message);
        if (message.isDdl()) {
            log.info("DDL 事件不处理，跳过: {}.{}", message.database(), message.table());
            return;
        }

        TableSyncHandler handler = handlers.get(message.routeKey());
        if (handler == null) {
            log.info("未注册的表变更事件，跳过: {}", message.routeKey());
            return;
        }
        if (!handler.shouldHandle(message)) {
            log.info("字段未变更，跳过: {}", message.routeKey());
            return;
        }
        if (handler.idempotent()) {
            // 天然幂等（删缓存 / upsert / 日志）：直接执行，零额外开销
            handler.handle(message);
        } else {
            // 非幂等操作：幂等门面按 binlog 位点 + 行级 key 去重（去重记录与业务同事务）
            idempotencyFacade.executeWithDedup(message, event.rowKey(), () -> handler.handle(message));
        }
    }
}
