package com.example.task.service;

import com.example.task.config.TaskProperties;
import com.example.task.mapper.OrderShardMapper;
import com.example.dto.OrderDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 订单缓存刷新：按分片广播路由到物理分片表，逐表游标分页读取并写 Redis。 */
@Slf4j
@Service
public class OrderCacheService {

    private static final String KEY_PREFIX = "task:order:";

    private final OrderShardMapper orderShardMapper;
    private final TaskCacheWriter cacheWriter;
    private final TaskProperties properties;

    public OrderCacheService(OrderShardMapper orderShardMapper, TaskCacheWriter cacheWriter,
                             TaskProperties properties) {
        this.orderShardMapper = orderShardMapper;
        this.cacheWriter = cacheWriter;
        this.properties = properties;
    }

    /** 刷新最近 N 天订单，返回本实例处理的总条数。 */
    public long refreshRecent(int shardIndex, int shardTotal) {
        List<String> tables = properties.getOrderShardTables();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getOrderDays());
        long total = 0L;

        // 分片广播：只处理 tableIndex % shardTotal == shardIndex 的物理分片表
        for (int tableIndex = shardIndex; tableIndex < tables.size(); tableIndex += shardTotal) {
            String table = tables.get(tableIndex);
            total += refreshTable(table, cutoff);
        }
        return total;
    }

    private long refreshTable(String table, LocalDateTime cutoff) {
        long lastId = 0L;
        long total = 0L;
        while (true) {
            List<OrderDTO> batch = orderShardMapper.selectRecent(
                    table, cutoff, lastId, properties.getBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            cacheWriter.writeBatch(KEY_PREFIX, batch, OrderDTO::id, properties.getOrderTtl());
            total += batch.size();
            lastId = batch.get(batch.size() - 1).id();
            log.info("订单分片 {} 刷新一批 {} 条，lastId={}", table, batch.size(), lastId);
            if (batch.size() < properties.getBatchSize()) {
                break;
            }
            sleepIfNeeded();
        }
        log.info("订单分片 {} 刷新完成，共 {} 条", table, total);
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
