package com.example.task.task;

import com.example.task.service.ProductCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshProductTaskTest {

    @Mock
    private ProductCacheService productCacheService;

    @Test
    void executesAndWritesResult() {
        when(productCacheService.refreshRecent(anyInt(), anyInt())).thenReturn(5L);
        new RefreshProduct(productCacheService).refreshProduct();
        verify(productCacheService).refreshRecent(anyInt(), anyInt());
    }

    @Test
    void propagatesException() {
        when(productCacheService.refreshRecent(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> new RefreshProduct(productCacheService).refreshProduct())
                .isInstanceOf(IllegalStateException.class);
    }
}
