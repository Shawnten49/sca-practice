package com.example.task.service;

import com.example.task.config.TaskProperties;
import com.example.task.mapper.UserMapper;
import com.example.entity.User;
import com.example.converter.UserConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class UserCacheServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private TaskCacheWriter cacheWriter;

    @Test
    void readsAllPagesUntilShortBatch() {
        TaskProperties properties = new TaskProperties();
        properties.setBatchSize(2);

        when(userMapper.selectByShard(0L, 0, 1, 2))
                .thenReturn(List.of(user(1L), user(2L)));
        when(userMapper.selectByShard(2L, 0, 1, 2))
                .thenReturn(List.of(user(3L)));

        UserCacheService service = new UserCacheService(userMapper, cacheWriter, properties, UserConverter.INSTANCE);
        long total = service.refreshAll(0, 1);

        assertThat(total).isEqualTo(3);
        verify(cacheWriter, times(2)).writeBatch(eq("task:user:"), anyList(), any(), eq(Duration.ofDays(7)));
    }

    private static User user(long id) {
        return new User(id, "u" + id, 10, 0, "", LocalDateTime.of(2026, 8, 18, 10, 0));
    }
}
