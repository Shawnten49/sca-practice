package com.example.user.service;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheGetResult;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.template.QuickConfig;
import com.example.user.domain.User;
import com.example.user.mapper.UserMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 信用点缓存访问器（JetCache 三级：Caffeine → Redis → MySQL）。
 * 回源用 Redisson 分布式锁做单飞：集群下同一 userId 也只有一个实例查库（其余等待后读缓存/兜底）。
 */
@Component
public class CreditsCache {

    private static final String LOCK_KEY_PREFIX = "user:credits:lock:";
    private static final long LOCK_WAIT_SECONDS = 0;
    private static final long LOCK_LEASE_SECONDS = 10;
    private static final long RETRY_SLEEP_MS = 50;

    private final UserMapper userMapper;
    private final RedissonClient redissonClient;
    private final Cache<Long, Integer> cache;

    public CreditsCache(UserMapper userMapper, RedissonClient redissonClient, CacheManager cacheManager) {
        this.userMapper = userMapper;
        this.redissonClient = redissonClient;
        this.cache = cacheManager.getOrCreateCache(QuickConfig.newBuilder("user:credits")
                .cacheType(CacheType.BOTH)
                .expire(Duration.ofSeconds(300))
                .localExpire(Duration.ofSeconds(30))
                .localLimit(10000)
                .cacheNullValue(true)
                .syncLocal(true)
                .build());
    }

    /** 三级读：Caffeine → Redis 命中直接返回（含缓存 null）；miss 走分布式锁单飞回源。 */
    public Integer load(Long userId) {
        CacheGetResult<Integer> result = cache.GET(userId);
        if (result.isSuccess()) {
            return result.getValue();
        }
        return loadWithMutex(userId);
    }

    /** 写失效：即时删除本地 + 远程缓存。 */
    public void invalidate(Long userId) {
        cache.remove(userId);
    }

    /** 分布式锁单飞：拿锁线程负责回源；拿不到锁的线程 50ms 后重读缓存，仍 miss 则兜底直查 DB。 */
    private Integer loadWithMutex(Long userId) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + userId);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            try {
                // double-check：等锁期间其它实例可能已回填
                CacheGetResult<Integer> result = cache.GET(userId);
                if (result.isSuccess()) {
                    return result.getValue();
                }
                return loadFromDb(userId);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        // 没拿到锁：短暂等待后重读缓存，仍 miss 则兜底直查 DB（保证可用性）
        sleepQuietly(RETRY_SLEEP_MS);
        CacheGetResult<Integer> result = cache.GET(userId);
        if (result.isSuccess()) {
            return result.getValue();
        }
        return loadFromDb(userId);
    }

    /** DB 回源并回填缓存；用户不存在时回填 null（cacheNullValue=true 会缓存空值标记防穿透）。 */
    private Integer loadFromDb(Long userId) {
        Integer credits = userMapper.selectUserById(userId).map(User::getCredits).orElse(null);
        cache.put(userId, credits);
        return credits;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
