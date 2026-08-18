package com.example.task.service;

import com.example.task.config.TaskProperties;
import com.example.task.mapper.OrderShardMapper;
import com.example.entity.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCacheServiceTest {

    @Mock
    private OrderShardMapper orderShardMapper;
    @Mock
    private TaskCacheWriter cacheWriter;

    private static Order order(long id) {
        return new Order(id, 10L, 20L, 1, LocalDateTime.of(2026, 8, 18, 10, 0));
    }

    @Test
    void terminatesOnShortBatchAndWritesEveryBatch() {
        TaskProperties properties = new TaskProperties();
        properties.setBatchSize(2);
        properties.setOrderShardTables(List.of("orders_0"));

        when(orderShardMapper.selectRecent(eq("orders_0"), any(), eq(0L), eq(2)))
                .thenReturn(List.of(order(1L), order(2L)));
        when(orderShardMapper.selectRecent(eq("orders_0"), any(), eq(2L), eq(2)))
                .thenReturn(List.of(order(3L)));

        OrderCacheService service = new OrderCacheService(orderShardMapper, cacheWriter, properties);
        long total = service.refreshRecent(0, 1);

        assertThat(total).isEqualTo(3);
        verify(orderShardMapper, times(2)).selectRecent(eq("orders_0"), any(), anyLong(), eq(2));
        verify(cacheWriter, times(2)).writeBatch(eq("task:order:"), anyList(), any(), eq(Duration.ofDays(3)));
    }

    @Test
    void onlyProcessesTablesAssignedToShard() {
        TaskProperties properties = new TaskProperties();
        properties.setOrderShardTables(List.of("orders_0", "orders_1", "orders_2", "orders_3"));
        when(orderShardMapper.selectRecent(anyString(), any(), anyLong(), anyInt()))
                .thenReturn(List.of());

        OrderCacheService service = new OrderCacheService(orderShardMapper, cacheWriter, properties);
        service.refreshRecent(1, 4);

        verify(orderShardMapper).selectRecent(eq("orders_1"), any(), anyLong(), anyInt());
        verify(orderShardMapper, never()).selectRecent(eq("orders_0"), any(), anyLong(), anyInt());
        verify(orderShardMapper, never()).selectRecent(eq("orders_2"), any(), anyLong(), anyInt());
        verify(orderShardMapper, never()).selectRecent(eq("orders_3"), any(), anyLong(), anyInt());
    }
}
