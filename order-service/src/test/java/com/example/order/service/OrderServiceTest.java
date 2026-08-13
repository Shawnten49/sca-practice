package com.example.order.service;

import com.example.order.client.StockClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final StockClient stockClient = mock(StockClient.class);
    private final OrderService orderService = new OrderService(jdbcTemplate, stockClient);

    @Test
    void createOrderSuccess() {
        when(stockClient.deduct(1L, 2)).thenReturn("http:扣减成功");

        String result = orderService.createOrder(10L, 1L, 2, false);

        assertThat(result).contains("下单成功");
        verify(jdbcTemplate).update("insert into orders (user_id, product_id, count) values (?, ?, ?)", 10L, 1L, 2);
        verify(stockClient).deduct(1L, 2);
    }

    @Test
    void createOrderFailThrowsAndInvokesStock() {
        when(stockClient.deduct(1L, 2)).thenReturn("http:扣减成功");

        assertThatThrownBy(() -> orderService.createOrder(10L, 1L, 2, true))
                .isInstanceOf(RuntimeException.class);

        verify(jdbcTemplate).update(anyString(), any(), any(), any());
        verify(stockClient).deduct(1L, 2);
    }
}
