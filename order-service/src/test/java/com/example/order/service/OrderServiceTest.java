package com.example.order.service;

import com.example.id.IdGenerator;
import com.example.order.client.StockClient;
import com.example.order.domain.Order;
import com.example.order.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final StockClient stockClient = mock(StockClient.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final OrderService orderService = new OrderService(orderMapper, stockClient, idGenerator);

    @Test
    void createOrder_success() {
        when(stockClient.deduct(100L, 2)).thenReturn("扣减成功");

        String result = orderService.createOrder(1L, 100L, 2, false);

        assertThat(result).isEqualTo("下单成功：扣减成功");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insertOrder(captor.capture());
        Order saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getProductId()).isEqualTo(100L);
        assertThat(saved.getCount()).isEqualTo(2);

        verify(stockClient).deduct(100L, 2);
    }

    @Test
    void createOrder_fail_throwsException() {
        when(stockClient.deduct(100L, 2)).thenReturn("扣减成功");

        assertThatThrownBy(() -> orderService.createOrder(1L, 100L, 2, true))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("模拟下单失败，触发全局回滚");

        verify(orderMapper).insertOrder(any(Order.class));
        verify(stockClient).deduct(100L, 2);
    }
}
