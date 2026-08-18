package com.example.task.task;

import com.example.task.service.ProductCacheService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/** 商品缓存刷新任务：最近 3 天商品 → Redis，TTL 3 天；Admin 配置为每小时执行、分片广播。 */
@Component
public class RefreshProduct {

    private final ProductCacheService productCacheService;

    public RefreshProduct(ProductCacheService productCacheService) {
        this.productCacheService = productCacheService;
    }

    @XxlJob("refreshProduct")
    public void refreshProduct() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        long start = System.currentTimeMillis();
        long total = productCacheService.refreshRecent(shardIndex, shardTotal);
        long cost = System.currentTimeMillis() - start;
        XxlJobHelper.log("refreshProduct 完成，共 {} 条，shard {}/{}，耗时 {}ms",
                total, shardIndex, shardTotal, cost);
        XxlJobHelper.handleSuccess("total=" + total + ", cost=" + cost + "ms");
    }
}
