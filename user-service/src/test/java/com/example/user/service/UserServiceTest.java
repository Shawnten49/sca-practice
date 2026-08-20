package com.example.user.service;

import com.example.converter.UserConverter;
import com.example.dto.response.UserResponse;
import com.example.user.mapper.UserMapper;
import com.example.user.metrics.UserMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UserMetricsService userMetricsService = mock(UserMetricsService.class);
    private final UserService userService =
            new UserService(userMapper, UserConverter.INSTANCE, redis, objectMapper,userMetricsService);

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        return ops;
    }

    @Test
    void hitReturnsUserResponseFromCache() {
        ValueOperations<String, String> ops = valueOps();
        when(ops.get("task:user:1")).thenReturn(
                "{\"id\":1,\"nickname\":\"demo\",\"points\":100,\"createTime\":\"2026-08-14T19:23:45\"}");

        UserResponse response = userService.getUserFromCache(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("demo");
        assertThat(response.points()).isEqualTo(100);
        assertThat(response.credits()).isNull();
        assertThat(response.idCard()).isNull();
    }

    @Test
    void missReturnsNull() {
        ValueOperations<String, String> ops = valueOps();
        when(ops.get("task:user:999")).thenReturn(null);

        assertThat(userService.getUserFromCache(999L)).isNull();
    }

    @Test
    void invalidUserIdRejected() {
        assertThatThrownBy(() -> userService.getUserFromCache(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
