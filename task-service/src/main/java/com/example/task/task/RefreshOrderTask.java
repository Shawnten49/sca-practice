package com.example.task.task;

import com.example.task.service.OrderCacheService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/** 订单缓存刷新任务：最近 3 天订单 → Redis，TTL 3 天；Admin 配置为每天执行、分片广播。 */
@Component
public class RefreshOrderTask {

    private final OrderCacheService orderCacheService;

    public RefreshOrderTask(OrderCacheService orderCacheService) {
        this.orderCacheService = orderCacheService;
    }

    @XxlJob("refreshOrderTask")
    public void refreshOrderTask() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        long start = System.currentTimeMillis();
        long total = orderCacheService.refreshRecent(shardIndex, shardTotal);
        long cost = System.currentTimeMillis() - start;
        XxlJobHelper.log("refreshOrderTask 完成，共 {} 条，shard {}/{}，耗时 {}ms",
                total, shardIndex, shardTotal, cost);
        XxlJobHelper.handleSuccess("total=" + total + ", cost=" + cost + "ms");
    }
}
