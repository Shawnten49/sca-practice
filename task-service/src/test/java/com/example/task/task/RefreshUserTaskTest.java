package com.example.task.task;

import com.example.task.service.UserCacheService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshUserTaskTest {

    @Mock
    private UserCacheService userCacheService;

    @Test
    void executesAndWritesResult() {
        when(userCacheService.refreshAll(anyInt(), anyInt())).thenReturn(5L);
        new RefreshUserTask(userCacheService).refreshUserTask();
        verify(userCacheService).refreshAll(anyInt(), anyInt());
    }

    @Test
    void propagatesException() {
        when(userCacheService.refreshAll(anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> new RefreshUserTask(userCacheService).refreshUserTask())
                .isInstanceOf(IllegalStateException.class);
    }
}
