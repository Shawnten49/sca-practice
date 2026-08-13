package com.example.order.service;

import com.example.dto.PointAddMessage;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.apache.seata.common.util.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class OrderMqService {

    private static final Logger log = LoggerFactory.getLogger(OrderMqService.class);

    /** IdWorker 线程安全，全局共用一个即可，避免每次下单都新建。 */
    private static final IdWorker ID_WORKER = new IdWorker(0L);

    private final RocketMQTemplate rocketMQTemplate;

    public OrderMqService(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public void createOrder(Long userId, Long productId, Integer count) {
        // 关键点 1：订单号必须先预生成（雪花 ID / UUID 都行），
        // 这样消息体里能带上 orderId，本地事务和回查都靠它定位
        Long orderId = ID_WORKER.nextId();

        PointAddMessage body = new PointAddMessage(orderId, userId, productId, count, count * 100);
        Message<PointAddMessage> message = MessageBuilder.withPayload(body)
                .setHeader(RocketMQHeaders.KEYS, String.valueOf(orderId)) // 业务 key：回查/排障用，监听器从 rocketmq_KEYS 取
                .build();

        // 关键点 2：sendMessageInTransaction = 半消息 + 本地事务 一起完成
        TransactionSendResult result = rocketMQTemplate
                .sendMessageInTransaction("topic-point", message, null);

        // 走到这里，本地事务已执行完，消息的最终状态也定了
        log.info("下单完成 orderId={}，事务状态={}", orderId, result.getLocalTransactionState());
    }
}
