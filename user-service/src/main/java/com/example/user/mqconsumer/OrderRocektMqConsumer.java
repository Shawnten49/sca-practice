package com.example.user.mqconsumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(topic = "topic-order", consumerGroup = "order-consumer",
        selectorExpression = "pay-success")
public class OrderRocektMqConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(OrderRocektMqConsumer.class);

    @Override
    public void onMessage(String msg) {
        log.info("消费到: {}", msg);
        // 业务处理：加积分 / 发短信……
    }
}