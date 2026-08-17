package com.example.order.listener;

import com.example.dto.PointAddMessage;
import com.example.order.dao.LocalOrderMapper;
import com.example.order.domain.Order;
import com.example.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RocketMQTransactionListener
public class OrderMqTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(OrderMqTransactionListener.class);

    /** 绑定 LOCAL 数据源的写入 mapper：配合 @Transactional("localTransactionManager") 明确不走 Seata。 */
    private final LocalOrderMapper localOrderMapper;
    private final ObjectMapper objectMapper;
    private final OrderMapper orderMapper;

    public OrderMqTransactionListener(LocalOrderMapper localOrderMapper,
                                      ObjectMapper objectMapper,
                                      OrderMapper orderMapper) {
        this.localOrderMapper = localOrderMapper;
        this.objectMapper = objectMapper;
        this.orderMapper = orderMapper;
    }

    @Override
    @Transactional("localTransactionManager")
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            PointAddMessage body = objectMapper.readValue((byte[]) msg.getPayload(), PointAddMessage.class);
            // 1. 本地事务：插入订单（走 LOCAL 数据源，分片路由照常 orders → orders_0~3），
            //    且必须先提交（本地提交成功后才允许消费者看到消息）
            localOrderMapper.insertOrder(Order.builder()
                    .id(body.orderId())
                    .userId(body.userId())
                    .productId(body.productId())
                    .count(body.count())
                    .build());
            // 2. 数据库已提交 → 提交半消息
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("本地事务失败，丢弃消息 orderId={}",
                    msg.getHeaders().get(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS), e);
            // 3. 数据库回滚了，消息一起丢弃
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String orderId = (String) msg.getHeaders().get(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS);
        log.info("检查本地事务，orderId={}", orderId);

        Long orderIdValue;
        try {
            orderIdValue = Long.valueOf(orderId);
        } catch (NumberFormatException e) {
            log.error("回查失败：orderId 非法: {}", orderId, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }

        // 订单在 → 本地事务其实提交成功了 → 补 COMMIT；不在 → 丢弃消息
        boolean orderExists;
        try {
            orderExists = orderMapper.selectOrderById(orderIdValue).isPresent();
        } catch (Exception e) {
            // 查询异常（如 DB 抖动）时无法确认本地事务结果：返回 UNKNOWN，
            // 让 Broker 稍后再次回查，绝不能擅自 COMMIT/ROLLBACK
            log.error("回查失败：查询订单异常 orderId={}", orderId, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        if (orderExists) {
            log.info("订单在，补一个 COMMIT，orderId={}", orderId);
            return RocketMQLocalTransactionState.COMMIT;
        }
        log.info("订单不在，补一个 ROLLBACK，orderId={}", orderId);
        return RocketMQLocalTransactionState.ROLLBACK;
    }
}
