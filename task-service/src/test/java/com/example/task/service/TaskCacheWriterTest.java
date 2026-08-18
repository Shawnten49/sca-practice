package com.example.task.service;

import com.example.dto.UserDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCacheWriterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void writesJsonWithTtlViaPipeline() throws Exception {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands stringCommands = mock(RedisStringCommands.class);
        RedisKeyCommands keyCommands = mock(RedisKeyCommands.class);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(connection.keyCommands()).thenReturn(keyCommands);
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            callback.doInRedis(connection);
            return java.util.Collections.emptyList();
        });

        TaskCacheWriter writer = new TaskCacheWriter(redisTemplate, objectMapper);
        writer.writeBatch("task:user:", List.of(
                        new UserDTO(1L, "alice", 10, LocalDateTime.of(2026, 8, 18, 10, 0))),
                UserDTO::id, Duration.ofDays(7));

        ArgumentCaptor<byte[]> keyCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> valueCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(stringCommands).set(keyCaptor.capture(), valueCaptor.capture());
        verify(keyCommands).expire(any(byte[].class), eq(604800L));
        verify(redisTemplate).executePipelined(any(RedisCallback.class));

        assertThat(new String(keyCaptor.getValue(), StandardCharsets.UTF_8)).isEqualTo("task:user:1");
        assertThat(new String(valueCaptor.getValue(), StandardCharsets.UTF_8))
                .contains("\"nickname\":\"alice\"")
                .contains("\"points\":10");
    }
}
