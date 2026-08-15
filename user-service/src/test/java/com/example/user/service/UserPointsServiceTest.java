package com.example.user.service;

import com.example.exception.BusinessException;
import com.example.user.config.PointsCacheProperties;
import com.example.user.domain.PointsValue;
import com.example.user.domain.User;
import com.example.user.dto.PointsVO;
import com.example.user.mapper.UserMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserPointsServiceTest {

    private UserMapper userMapper;
    private Cache<Long, PointsValue> caffeine;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RLock lock;
    private UserPointsService service;

    @BeforeEach
    void setUp() throws Exception {
        userMapper = mock(UserMapper.class);
        caffeine = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(30))
                .build();
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        service = new UserPointsService(userMapper, caffeine, redisTemplate, redisson,
                new PointsCacheProperties());
    }

    @Test
    void caffeineHitReturnsWithoutRedisOrDb() {
        caffeine.put(1L, PointsValue.of(300));

        PointsVO vo = service.getPoints(1L);

        assertThat(vo.points()).isEqualTo(300);
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void redisHitFillsCaffeineWithoutDb() {
        when(valueOps.get("user:points:1")).thenReturn("200");

        PointsVO vo = service.getPoints(1L);

        assertThat(vo.points()).isEqualTo(200);
        assertThat(caffeine.getIfPresent(1L).points()).isEqualTo(200);
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void dbMissFillsBothCaches() {
        when(valueOps.get("user:points:1")).thenReturn(null);
        when(userMapper.selectUserById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).nickname("demo").points(100).build()));

        PointsVO vo = service.getPoints(1L);

        assertThat(vo.points()).isEqualTo(100);
        assertThat(caffeine.getIfPresent(1L).points()).isEqualTo(100);
        verify(valueOps).set(eq("user:points:1"), eq("100"), any(Duration.class));
    }

    @Test
    void missingUserCachesEmptyMark() {
        when(valueOps.get("user:points:99")).thenReturn(null);
        when(userMapper.selectUserById(99L)).thenReturn(Optional.empty());

        PointsVO vo = service.getPoints(99L);

        assertThat(vo.points()).isNull();
        assertThat(caffeine.getIfPresent(99L).isEmpty()).isTrue();
        verify(valueOps).set(eq("user:points:99"), anyString(), any(Duration.class));
    }

    @Test
    void withoutLockReReadsRedisThenFallsBackToDb() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        // 第一次读 Redis miss，50ms 后重读命中（其他线程已回填）
        when(valueOps.get("user:points:1")).thenReturn(null, "500");

        PointsVO vo = service.getPoints(1L);

        assertThat(vo.points()).isEqualTo(500);
        assertThat(caffeine.getIfPresent(1L).points()).isEqualTo(500);
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void updatePointsInvalidatesCachesAndReturnsLatest() {
        caffeine.put(1L, PointsValue.of(100));
        when(userMapper.increasePoints(1L, 100)).thenReturn(1);
        when(userMapper.selectUserById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).nickname("demo").points(200).build()));

        PointsVO vo = service.updatePoints(1L, 100);

        assertThat(vo.points()).isEqualTo(200);
        assertThat(caffeine.getIfPresent(1L).points()).isEqualTo(200);
        verify(redisTemplate).delete("user:points:1");
        verify(valueOps).set(eq("user:points:1"), eq("200"), any(Duration.class));
    }

    @Test
    void updatePointsUserNotFoundThrows404() {
        when(userMapper.increasePoints(99L, 100)).thenReturn(0);

        assertThatThrownBy(() -> service.updatePoints(99L, 100))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("99");
    }

    @Test
    void updatePointsRejectsZeroAndOverLimitDelta() {
        assertThatThrownBy(() -> service.updatePoints(1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updatePoints(1L, 200000))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
