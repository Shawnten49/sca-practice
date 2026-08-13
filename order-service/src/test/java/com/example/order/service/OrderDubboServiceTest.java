package com.example.order.service;

import com.example.api.StockDubboService;
import com.example.order.mapper.OrderMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderDubboServiceTest {

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final StockDubboService stockDubboService = mock(StockDubboService.class);
    private final OrderDubboService orderDubboService = new OrderDubboService(orderMapper, rocketMQTemplate);

    @Test
    void createOrderSuccessThenMessageSent() {
        ReflectionTestUtils.setField(orderDubboService, "stockDubboService", stockDubboService);
        when(stockDubboService.deduct(1L, 2)).thenReturn("dubbo:扣减成功");

        String result = orderDubboService.createOrder(10L, 1L, 2, false);
        assertThat(result).contains("下单成功");

        verify(rocketMQTemplate).convertAndSend(eq("topic-order:pay-success"), anyString());
    }

    @Test
    void createOrderFailThrows() {
        ReflectionTestUtils.setField(orderDubboService, "stockDubboService", stockDubboService);
        when(stockDubboService.deduct(1L, 2)).thenReturn("dubbo:扣减成功");

        assertThatThrownBy(() -> orderDubboService.createOrder(10L, 1L, 2, true))
                .isInstanceOf(RuntimeException.class);
    }
}
