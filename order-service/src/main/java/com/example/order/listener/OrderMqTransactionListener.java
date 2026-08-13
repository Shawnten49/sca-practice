package com.example.order.listener;

import com.example.order.domain.Order;
import com.example.order.domain.PointAddMessage;
import com.example.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RocketMQTransactionListener
@Slf4j
public class OrderMqTransactionListener implements RocketMQLocalTransactionListener {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            PointAddMessage body = objectMapper.readValue((byte[]) msg.getPayload(), PointAddMessage.class);
            // 1. 本地事务：插入订单，且必须先提交（原因见 3.3 第 2 条）
            transactionTemplate.execute(status -> {
                orderMapper.insert(new Order(body.getOrderId(), body.getUserId(), body.getProductId(), body.getCount()));
                return null;
            });
            // 2. 数据库已提交，才允许消费者看到这条消息
            //测试使用返回UNKOWN
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("本地事务失败，丢弃消息 orderId={}", msg.getHeaders().get(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS), e);
            // 3. 数据库回滚了，消息一起丢弃
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String orderId = (String) msg.getHeaders().get(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS);
        log.info("检查本地事务，orderId={}", orderId);

        Order order = orderMapper.selectById(Long.valueOf(orderId));
        if (order != null) {
            // 订单在 → 说明本地事务其实提交成功了 → 补一个 COMMIT
            log.info("订单在，补一个 COMMIT，orderId={}", orderId);
            return RocketMQLocalTransactionState.COMMIT;
        }
        // 订单不在 → 本地事务没执行或已回滚 → 丢弃消息
        // 若暂时拿不准（如订单在分库分表、当前查不到），先返回 UNKNOW，Broker 稍后再查
        log.info("订单不在，补一个 ROLLBACK，orderId={}", orderId);
        return RocketMQLocalTransactionState.ROLLBACK;
    }
}
