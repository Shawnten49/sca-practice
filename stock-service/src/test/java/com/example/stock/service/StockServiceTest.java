package com.example.stock.service;

import com.example.exception.BusinessException;
import com.example.exception.InsufficientStockException;
import com.example.stock.domain.Stock;
import com.example.stock.mapper.StockMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockServiceTest {

    private final StockMapper stockMapper = mock(StockMapper.class);
    private final StockService stockService = new StockService(stockMapper);

    @Test
    void deductSuccess() {
        when(stockMapper.deductStock(1L, 3)).thenReturn(1);

        stockService.deduct(1L, 3);

        verify(stockMapper).deductStock(1L, 3);
    }

    @Test
    void deductInsufficientThrowsBusinessException() {
        when(stockMapper.deductStock(1L, 3)).thenReturn(0);

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
        when(stockMapper.selectStockByProductId(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> stockService.query(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99");
    }

    @Test
    void queryFoundReturnsTypedStock() {
        when(stockMapper.selectStockByProductId(1L))
                .thenReturn(Optional.of(Stock.builder().id(1L).productId(1L).quantity(100).build()));

        Stock stock = stockService.query(1L);
        assertThat(stock.getProductId()).isEqualTo(1L);
        assertThat(stock.getQuantity()).isEqualTo(100);
    }
}
