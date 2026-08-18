package com.example.task.service;

import com.example.task.config.TaskProperties;
import com.example.task.mapper.ProductMapper;
import com.example.dto.ProductDTO;
import com.example.task.converter.ProductConverter;
import com.example.entity.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 商品缓存刷新：最近 N 天商品按分片 + 游标分页读取并写 Redis。 */
@Slf4j
@Service
public class ProductCacheService {

    private static final String KEY_PREFIX = "task:product:";

    private final ProductMapper productMapper;
    private final TaskCacheWriter cacheWriter;
    private final TaskProperties properties;

    public ProductCacheService(ProductMapper productMapper, TaskCacheWriter cacheWriter,
                               TaskProperties properties) {
        this.productMapper = productMapper;
        this.cacheWriter = cacheWriter;
        this.properties = properties;
    }

    /** 刷新最近 N 天商品，返回本实例处理的总条数。 */
    public long refreshRecent(int shardIndex, int shardTotal) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getProductDays());
        long lastId = 0L;
        long total = 0L;
        while (true) {
            List<Product> batch = productMapper.selectRecentByShard(
                    cutoff, lastId, shardIndex, shardTotal, properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            cacheWriter.writeBatch(KEY_PREFIX, batch.stream().map(ProductConverter.INSTANCE::toDTO).toList(), ProductDTO::id, properties.getProductTtl());
            total += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
            sleepIfNeeded();
        }
        log.info("商品刷新完成，共 {} 条（shard {}/{}）", total, shardIndex, shardTotal);
        return total;
    }

    private void sleepIfNeeded() {
        long sleepMs = properties.getBatchSleepMs();
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
