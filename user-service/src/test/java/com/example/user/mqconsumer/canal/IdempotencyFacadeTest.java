package com.example.user.mqconsumer.canal;

import com.example.user.entity.SyncLog;
import com.example.user.mapper.SyncLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyFacadeTest {

    private SyncLogMapper syncLogMapper;
    private TransactionTemplate transactionTemplate;
    private IdempotencyFacade facade;

    @BeforeEach
    void setUp() throws Exception {
        syncLogMapper = mock(SyncLogMapper.class);
        transactionTemplate = mock(TransactionTemplate.class);
        // 让 mock 的 TransactionTemplate 立即执行回调，便于验证事务内逻辑
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        facade = new IdempotencyFacade(syncLogMapper, transactionTemplate);
    }

    private CanalMessage message(String logFile, long offset) {
        return new CanalMessage("seata_user", "users", "INSERT", false, 1L,
                logFile, offset, List.of("id"), null, null);
    }

    @Test
    void firstPositionClaimsAndRunsBusiness() {
        when(syncLogMapper.insertIgnore(any(SyncLog.class))).thenReturn(1);
        Runnable business = mock(Runnable.class);

        facade.executeWithDedup(message("mysql-bin.000001", 123L), "3", business);

        verify(business).run();
        ArgumentCaptor<SyncLog> captor = ArgumentCaptor.forClass(SyncLog.class);
        verify(syncLogMapper).insertIgnore(captor.capture());
        assertThat(captor.getValue().getLogFileName()).isEqualTo("mysql-bin.000001");
        assertThat(captor.getValue().getLogFileOffset()).isEqualTo(123L);
        assertThat(captor.getValue().getRowKey()).isEqualTo("3");
    }

    @Test
    void duplicatePositionSkipsBusiness() {
        when(syncLogMapper.insertIgnore(any(SyncLog.class))).thenReturn(0);
        Runnable business = mock(Runnable.class);

        facade.executeWithDedup(message("mysql-bin.000001", 123L), "3", business);

        verify(business, never()).run();
        verify(syncLogMapper).insertIgnore(any(SyncLog.class));
    }

    @Test
    void missingPositionRunsDirectlyWithoutDedup() {
        Runnable business = mock(Runnable.class);

        facade.executeWithDedup(message(null, 0L), "3", business);

        verify(business).run();
        verify(syncLogMapper, never()).insertIgnore(any(SyncLog.class));
    }

    @Test
    void businessFailurePropagatesForRetry() {
        when(syncLogMapper.insertIgnore(any(SyncLog.class))).thenReturn(1);
        Runnable business = mock(Runnable.class);
        doThrow(new RuntimeException("db down")).when(business).run();

        assertThatThrownBy(() -> facade.executeWithDedup(message("mysql-bin.000001", 123L), "3", business))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");

        // 业务失败 → 异常上抛，事务回滚（含去重记录），MQ 重试可重新执行
        verify(syncLogMapper).insertIgnore(any(SyncLog.class));
    }
}
