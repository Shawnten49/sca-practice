package com.example.order.listener;

import com.example.order.domain.Order;
import com.example.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderMqTransactionListenerTest {

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderMqTransactionListener listener =
            new OrderMqTransactionListener(transactionTemplate, objectMapper, orderMapper);

    private Message<String> messageWithOrderId(String orderId) {
        return MessageBuilder.withPayload("payload")
                .setHeader(RocketMQHeaders.PREFIX + RocketMQHeaders.KEYS, orderId)
                .build();
    }

    @Test
    void checkCommitsWhenOrderExists() {
        when(orderMapper.selectById(123L)).thenReturn(Optional.of(new Order(123L, 1L, 1L, 2)));

        assertThat(listener.checkLocalTransaction(messageWithOrderId("123")))
                .isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }

    @Test
    void checkRollsBackWhenOrderMissing() {
        when(orderMapper.selectById(123L)).thenReturn(Optional.empty());

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
        when(orderMapper.selectById(123L)).thenThrow(new RuntimeException("db down"));

        assertThat(listener.checkLocalTransaction(messageWithOrderId("123")))
                .isEqualTo(RocketMQLocalTransactionState.UNKNOWN);
    }
}
