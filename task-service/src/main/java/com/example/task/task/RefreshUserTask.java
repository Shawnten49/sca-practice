package com.example.task.task;

import com.example.task.service.UserCacheService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

/** 用户缓存刷新任务：全量用户 → Redis，TTL 7 天；Admin 配置为每天执行、分片广播。 */
@Component
public class RefreshUserTask {

    private final UserCacheService userCacheService;

    public RefreshUserTask(UserCacheService userCacheService) {
        this.userCacheService = userCacheService;
    }

    @XxlJob("refreshUserTask")
    public void refreshUserTask() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        long start = System.currentTimeMillis();
        long total = userCacheService.refreshAll(shardIndex, shardTotal);
        long cost = System.currentTimeMillis() - start;
        XxlJobHelper.log("refreshUserTask 完成，共 {} 条，shard {}/{}，耗时 {}ms",
                total, shardIndex, shardTotal, cost);
        XxlJobHelper.handleSuccess("total=" + total + ", cost=" + cost + "ms");
    }
}
