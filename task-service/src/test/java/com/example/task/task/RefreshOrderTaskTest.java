package com.example.task.task;

import com.example.task.service.OrderCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshOrderTaskTest {

    @Mock
    private OrderCacheService orderCacheService;

    @Test
    void executesAndWritesResult() {
        when(orderCacheService.refreshRecent(anyInt(), anyInt())).thenReturn(5L);
        new RefreshOrderTask(orderCacheService).refreshOrderTask();
        verify(orderCacheService).refreshRecent(anyInt(), anyInt());
    }

    @Test
    void propagatesException() {
        when(orderCacheService.refreshRecent(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> new RefreshOrderTask(orderCacheService).refreshOrderTask())
                .isInstanceOf(IllegalStateException.class);
    }
}
