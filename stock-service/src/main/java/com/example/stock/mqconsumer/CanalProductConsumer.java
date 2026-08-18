package com.example.stock.mqconsumer;

import com.example.dto.canal.CanalEvent;
import com.example.stock.mqconsumer.canal.CanalEventConverter;
import com.example.dto.canal.CanalMessage;
import com.example.stock.mqconsumer.canal.CanalPacketParser;
import com.example.stock.mqconsumer.canal.TableSyncHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Canal binlog 变更消费入口（商品 → ES 同步）。
 *
 * <p>与 user-service 的 CanalSyncConsumer 同构：解析 CanalPacket(Entry protobuf) → 按行拆分
 * → 按 database.table 路由 → 分发给各表 Handler。本服务 Handler 均为幂等操作
 * （ES 按 id upsert / delete），无需幂等门面去重。
 *
 * 异常策略：
 * - 消息体解析失败 → 记 error 并跳过（畸形消息重试无意义，避免死循环）；
 * - Handler 业务异常 → 向上抛出，交给 RocketMQ 重试（ES upsert 幂等，重试无副作用）。
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = "canal-topic", consumerGroup = "canal-product-consumer")
public class CanalProductConsumer implements RocketMQListener<MessageExt> {

    private final CanalPacketParser packetParser;
    private final CanalEventConverter eventConverter;
    private final Map<String, TableSyncHandler> handlers;

    public CanalProductConsumer(CanalPacketParser packetParser,
                                CanalEventConverter eventConverter,
                                List<TableSyncHandler> handlerList) {
        this.packetParser = packetParser;
        this.eventConverter = eventConverter;
        // 路由表由 Spring 收集的 Handler Bean 自动组装：新增表监听只需加一个 Handler
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
        // 本项目 Handler 均为天然幂等（ES upsert/delete），直接执行
        handler.handle(message);
    }
}
