package com.example.order.listener;

import com.example.dto.PointAddMessage;
import com.example.order.dao.LocalOrderMapper;
import com.example.order.entity.Order;
import com.example.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderMqTransactionListenerTest {

    private final LocalOrderMapper localOrderMapper = mock(LocalOrderMapper.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderMqTransactionListener listener =
            new OrderMqTransactionListener(localOrderMapper, objectMapper, orderMapper);

    private Message<String> messageWithOrderId(String orderId) {
        return MessageBuilder.withPayload("payload")
                .setHeader(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS, orderId)
                .build();
    }

    @Test
    void checkCommitsWhenOrderExists() {
        when(orderMapper.selectOrderById(123L))
                .thenReturn(Optional.of(Order.builder().id(123L).userId(1L).productId(1L).count(2).build()));

        assertThat(listener.checkLocalTransaction(messageWithOrderId("123")))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void checkRollsBackWhenOrderMissing() {
        when(orderMapper.selectOrderById(123L)).thenReturn(Optional.empty());

        assertThat(listener.checkLocalTransaction(messageWithOrderId("123")))
                .isEqualTo(RocketMQLocalTransactionState.ROLLBACK);
    }

    @Test
    void checkReturnsUnknownForIllegalOrderId() {
        assertThat(listener.checkLocalTransaction(messageWithOrderId("not-a-number")))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    @Test
    void checkReturnsUnknownWhenQueryFails() {
        when(orderMapper.selectOrderById(123L)).thenThrow(new RuntimeException("db down"));

        assertThat(listener.checkLocalTransaction(messageWithOrderId("123")))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }

    @Test
    void executesLocalInsertViaLocalOrderMapper() throws Exception {
        PointAddMessage body = new PointAddMessage(123L, 1L, 1L, 2, 200);
        when(objectMapper.readValue(any(byte[].class), eq(PointAddMessage.class))).thenReturn(body);

        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS, "123")
                .build();

        assertThat(listener.executeLocalTransaction(message, null))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
        // 写入走 LocalOrderMapper（LOCAL 数据源），由 @Transactional("localTransactionManager") 保证本地原子性
        verify(localOrderMapper).insertOrder(argThat(order ->
                order.getId() == 123L
                        && order.getUserId() == 1L
                        && order.getProductId() == 1L
                        && order.getCount() == 2));
    }
}
