package com.example.user.mqconsumer;

import com.example.dto.PointAddMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RocketMQMessageListener(topic = "topic-point", consumerGroup = "point-consumer")
public class PointMqConsumer implements RocketMQListener<PointAddMessage> {

    @Override
    public void onMessage(PointAddMessage msg) {
        // MQ 保证"至少一次"，不保证不重复，消费端必须幂等：
        // 用订单号做唯一索引 / Redis SETNX 去重，重复消息直接跳过
        log.info("给用户 {} 加 {} 积分（来自订单 {}）", msg.userId(), msg.points(), msg.orderId());
    }
}