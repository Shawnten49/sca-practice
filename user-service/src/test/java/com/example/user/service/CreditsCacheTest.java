package com.example.user.service;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheGetResult;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.CacheResultCode;
import com.alicp.jetcache.CacheValueHolder;
import com.alicp.jetcache.template.QuickConfig;
import com.example.user.domain.User;
import com.example.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditsCacheTest {

    private UserMapper userMapper;
    private RedissonClient redissonClient;
    private RLock lock;
    private Cache<Long, Integer> cache;
    private CreditsCache creditsCache;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        cache = mock(Cache.class);

        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.<Long, Integer>getOrCreateCache(any(QuickConfig.class))).thenReturn(cache);
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        creditsCache = new CreditsCache(userMapper, redissonClient, cacheManager);
    }

    private CacheGetResult<Integer> hit(Integer value) {
        CacheValueHolder<Integer> holder = new CacheValueHolder<>(value, 0);
        return new CacheGetResult<>(CacheResultCode.SUCCESS, null, holder);
    }

    @SuppressWarnings("unchecked")
    private CacheGetResult<Integer> miss() {
        return (CacheGetResult<Integer>) CacheGetResult.NOT_EXISTS_WITHOUT_MSG;
    }

    @Test
    void cacheHitReturnsWithoutDb() {
        when(cache.GET(1L)).thenReturn(hit(100));

        assertThat(creditsCache.load(1L)).isEqualTo(100);
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void cachedNullReturnsWithoutDb() {
        when(cache.GET(99L)).thenReturn(hit(null));

        assertThat(creditsCache.load(99L)).isNull();
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void missAcquiresLockLoadsDbAndBackfills() throws InterruptedException {
        when(cache.GET(1L)).thenReturn(miss());
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(userMapper.selectUserById(1L))
                .thenReturn(Optional.of(User.builder().id(1L).nickname("demo").credits(80).build()));

        assertThat(creditsCache.load(1L)).isEqualTo(80);
        verify(cache).put(eq(1L), eq(80));
    }

    @Test
    void missWithoutLockReReadsCacheAndReturns() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        // 第一次读 miss，50ms 后重读命中（其它实例已回填）
        when(cache.GET(1L)).thenReturn(miss(), hit(500));

        assertThat(creditsCache.load(1L)).isEqualTo(500);
        verify(userMapper, never()).selectUserById(any());
    }

    @Test
    void invalidateRemovesCache() {
        creditsCache.invalidate(1L);

        verify(cache).remove(1L);
    }
}
