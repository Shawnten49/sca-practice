package com.example.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Redis 批量写入：pipeline + TTL，覆盖写（幂等）。 */
@Component
public class TaskCacheWriter {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public TaskCacheWriter(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 将一批数据写入 Redis：Key = keyPrefix + id，Value = JSON，并设置 TTL。 */
    public <T> void writeBatch(String keyPrefix, List<T> rows, Function<T, Object> idExtractor,
                               Duration ttl) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        RedisSerializer<String> serializer = RedisSerializer.string();
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (T row : rows) {
                String key = keyPrefix + idExtractor.apply(row);
                byte[] keyBytes = serializer.serialize(key);
                try {
                    byte[] valueBytes = serializer.serialize(objectMapper.writeValueAsString(row));
                    connection.stringCommands().set(keyBytes, valueBytes);
                    connection.keyCommands().expire(keyBytes, ttl.getSeconds());
                } catch (JsonProcessingException e) {
                    throw new UncheckedIOException("序列化失败: " + key, e);
                }
            }
            return null;
        });
    }
}
