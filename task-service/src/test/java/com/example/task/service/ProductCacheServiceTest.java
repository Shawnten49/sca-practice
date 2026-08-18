package com.example.task.service;

import com.example.task.config.TaskProperties;
import com.example.task.mapper.ProductMapper;
import com.example.task.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductCacheServiceTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private TaskCacheWriter cacheWriter;

    @Test
    void passesShardToQueryAndWritesEveryBatch() {
        TaskProperties properties = new TaskProperties();
        properties.setBatchSize(2);

        when(productMapper.selectRecentByShard(any(), eq(0L), eq(1), eq(2), eq(2)))
                .thenReturn(List.of(product(1L), product(2L)));
        when(productMapper.selectRecentByShard(any(), eq(2L), eq(1), eq(2), eq(2)))
                .thenReturn(List.of(product(3L)));

        ProductCacheService service = new ProductCacheService(productMapper, cacheWriter, properties);
        long total = service.refreshRecent(1, 2);

        assertThat(total).isEqualTo(3);
        verify(cacheWriter, times(2)).writeBatch(eq("task:product:"), anyList(), any(), eq(Duration.ofDays(3)));
    }

    private static Product product(long id) {
        return new Product(id, "p" + id, "brand", new BigDecimal("10.00"), "",
                LocalDateTime.of(2026, 8, 18, 10, 0));
    }
}
