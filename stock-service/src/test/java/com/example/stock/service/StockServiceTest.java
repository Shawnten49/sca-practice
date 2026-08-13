package com.example.stock.service;

import com.example.exception.BusinessException;
import com.example.exception.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final StockService stockService = new StockService(jdbcTemplate);

    @Test
    void deductSuccess() {
        when(jdbcTemplate.update(anyString(), eq(3), eq(1L), eq(3))).thenReturn(1);

        stockService.deduct(1L, 3);

        verify(jdbcTemplate).update(
                "update stock set quantity = quantity - ? where product_id = ? and quantity >= ?",
                3, 1L, 3);
    }

    @Test
    void deductInsufficientThrowsBusinessException() {
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> stockService.deduct(1L, 3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("库存不足");
    }

    @Test
    void deductInvalidCountRejected() {
        assertThatThrownBy(() -> stockService.deduct(1L, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stockService.deduct(1L, -5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryNotFoundThrows404() {
        when(jdbcTemplate.queryForList(anyString(), eq(99L))).thenReturn(List.of());

        assertThatThrownBy(() -> stockService.query(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99");
    }

    @Test
    void queryFoundReturnsRow() {
        when(jdbcTemplate.queryForList(anyString(), eq(1L)))
                .thenReturn(List.of(Map.of("product_id", 1, "quantity", 100)));

        assertThat(stockService.query(1L)).containsEntry("quantity", 100);
    }
}
