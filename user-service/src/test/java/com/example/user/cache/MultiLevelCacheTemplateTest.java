package com.example.user.cache;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheGetResult;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.CacheResultCode;
import com.alicp.jetcache.CacheValueHolder;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.template.QuickConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiLevelCacheTemplateTest {

    private CacheManager cacheManager;
    private RedissonClient redissonClient;
    private RLock lock;
    private Cache<Long, Integer> cache;
    private Function<Long, Integer> loader;
    private FakeCache template;

    static class FakeCache extends MultiLevelCacheTemplate<Long, Integer> {
        private final Function<Long, Integer> loader;

        FakeCache(CacheManager cacheManager, RedissonClient redissonClient, Function<Long, Integer> loader) {
            super(cacheManager, redissonClient);
            this.loader = loader;
        }

        @Override protected String cacheName() { return "fake"; }

        @Override protected Integer loadFromDb(Long key) { return loader.apply(key); }
    }

    static class RemoteFakeCache extends MultiLevelCacheTemplate<Long, Integer> {
        private final Function<Long, Integer> loader;

        RemoteFakeCache(CacheManager cacheManager, RedissonClient redissonClient, Function<Long, Integer> loader) {
            super(cacheManager, redissonClient);
            this.loader = loader;
        }

        @Override protected String cacheName() { return "fake-remote"; }

        @Override protected CacheType cacheType() { return CacheType.REMOTE; }

        @Override protected Integer loadFromDb(Long key) { return loader.apply(key); }
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        cache = mock(Cache.class);
        loader = mock(Function.class);

        when(cacheManager.<Long, Integer>getOrCreateCache(any(QuickConfig.class))).thenReturn(cache);
        when(redissonClient.getLock(anyString())).thenReturn(lock);

        template = new FakeCache(cacheManager, redissonClient, loader);
    }

    private CacheGetResult<Integer> hit(Integer value) {
        return new CacheGetResult<>(CacheResultCode.SUCCESS, null, new CacheValueHolder<>(value, 0));
    }

    @SuppressWarnings("unchecked")
    private CacheGetResult<Integer> miss() {
        return (CacheGetResult<Integer>) CacheGetResult.NOT_EXISTS_WITHOUT_MSG;
    }

    @Test
    void cacheHitReturnsWithoutDb() {
        when(cache.GET(1L)).thenReturn(hit(100));

        assertThat(template.get(1L)).isEqualTo(100);
        verify(loader, never()).apply(anyLong());
    }

    @Test
    void cachedNullReturnsWithoutDb() {
        when(cache.GET(99L)).thenReturn(hit(null));

        assertThat(template.get(99L)).isNull();
        verify(loader, never()).apply(anyLong());
    }

    @Test
    void missAcquiresLockLoadsDbAndBackfills() throws InterruptedException {
        when(cache.GET(1L)).thenReturn(miss());
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(loader.apply(1L)).thenReturn(80);

        assertThat(template.get(1L)).isEqualTo(80);
        verify(cache).put(eq(1L), eq(80));
    }

    @Test
    void missWithoutLockReReadsCacheAndReturns() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        // 第一次读 miss，重试后命中（其它实例已回填）
        when(cache.GET(1L)).thenReturn(miss(), hit(500));

        assertThat(template.get(1L)).isEqualTo(500);
        verify(loader, never()).apply(anyLong());
    }

    @Test
    void invalidateRemovesCache() {
        template.invalidate(1L);

        verify(cache).remove(1L);
    }

    @Test
    void remoteCacheTypeBuildsRemoteCache() {
        RemoteFakeCache remote = new RemoteFakeCache(cacheManager, redissonClient, loader);
        when(cache.GET(1L)).thenReturn(hit(1));

        remote.get(1L);

        ArgumentCaptor<QuickConfig> captor = ArgumentCaptor.forClass(QuickConfig.class);
        verify(cacheManager).getOrCreateCache(captor.capture());
        assertThat(captor.getValue().getCacheType()).isEqualTo(CacheType.REMOTE);
    }
}
