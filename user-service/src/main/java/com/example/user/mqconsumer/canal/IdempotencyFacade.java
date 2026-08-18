package com.example.user.mqconsumer.canal;
import com.example.dto.canal.CanalEvent;
import com.example.dto.canal.CanalMessage;

import com.example.user.entity.SyncLog;
import com.example.user.mapper.SyncLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等门面：以 binlog 位点 + 行级 key 为幂等键执行业务，去重记录与业务在同一本地事务。
 * - 重复位点+行（INSERT IGNORE 返回 0）→ 跳过；
 * - 业务抛异常 → 事务整体回滚（含去重记录），MQ 重试可重新执行；
 * - 无位点消息无法去重 → warn 后直接执行，依赖 Handler 自身幂等。
 */
@Slf4j
@Component
public class IdempotencyFacade {

    private final SyncLogMapper syncLogMapper;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyFacade(SyncLogMapper syncLogMapper, TransactionTemplate transactionTemplate) {
        this.syncLogMapper = syncLogMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public void executeWithDedup(CanalMessage message, String rowKey, Runnable business) {
        if (message.logFileName() == null || message.logFileName().isBlank()) {
            log.warn("消息缺少 binlog 位点，跳过幂等去重: {}", message.routeKey());
            business.run();
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            int claimed = syncLogMapper.insertIgnore(SyncLog.builder()
                    .logFileName(message.logFileName())
                    .logFileOffset(message.logFileOffset())
                    .rowKey(rowKey)
                    .build());
            if (claimed == 0) {
                log.info("重复消息跳过（sync_log 唯一索引）: {} pos={}:{} row={}",
                        message.routeKey(), message.logFileName(), message.logFileOffset(), rowKey);
                return;
            }
            business.run();
        });
    }
}
