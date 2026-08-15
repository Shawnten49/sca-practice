package com.example.user.cache;

import com.alicp.jetcache.Cache;
import com.alicp.jetcache.CacheGetResult;
import com.alicp.jetcache.CacheManager;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.template.QuickConfig;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 通用多级缓存模板：JetCache 读穿透 + 空值缓存（防穿透）+ Redisson 分布式锁单飞（防击穿）+ 缓存失效。
 *
 * <p>子类只需实现 {@link #cacheName()} 与 {@link #loadFromDb(Object)}，
 * 其余流程由模板固定；缓存层级/TTL/锁参数通过钩子覆写。
 *
 * @param <K> 缓存 key 类型（须可被全局 keyConvertor=jackson 序列化，如 Long/String）
 * @param <V> 缓存值类型（须可被 valueEncoder=java 序列化，如 Integer/Serializable POJO）
 */
public abstract class MultiLevelCacheTemplate<K, V> {

    private static final long DEFAULT_LOCK_WAIT_SECONDS = 0;
    private static final long DEFAULT_LOCK_LEASE_SECONDS = 10;
    private static final long DEFAULT_RETRY_SLEEP_MS = 50;

    private final CacheManager cacheManager;
    private final RedissonClient redissonClient;
    private volatile Cache<K, V> cache;

    protected MultiLevelCacheTemplate(CacheManager cacheManager, RedissonClient redissonClient) {
        this.cacheManager = cacheManager;
        this.redissonClient = redissonClient;
    }

    // ==================== 子类必须实现 ====================

    /** 缓存名（全局唯一）。 */
    protected abstract String cacheName();

    /** DB 回源：按 key 从数据库加载；返回 null 表示"空值"（会被缓存，防穿透）。 */
    protected abstract V loadFromDb(K key);

    // ==================== 可选覆写（默认值） ====================

    /** 缓存层级：{@link CacheType#BOTH} 为三级（本地+远程），{@link CacheType#REMOTE} 为两级（仅远程）。 */
    protected CacheType cacheType() {
        return CacheType.BOTH;
    }

    /** 远程缓存 TTL。 */
    protected Duration expire() {
        return Duration.ofMinutes(5);
    }

    /** 本地缓存 TTL（仅 BOTH 生效）。 */
    protected Duration localExpire() {
        return Duration.ofSeconds(30);
    }

    /** 本地缓存最大元素数（仅 BOTH 生效）。 */
    protected int localLimit() {
        return 10000;
    }

    /** 是否缓存空值（防穿透）。 */
    protected boolean cacheNullValue() {
        return true;
    }

    /** 分布式锁 key 前缀。 */
    protected String lockKeyPrefix() {
        return cacheName() + ":lock:";
    }

    protected long lockWaitSeconds() {
        return DEFAULT_LOCK_WAIT_SECONDS;
    }

    protected long lockLeaseSeconds() {
        return DEFAULT_LOCK_LEASE_SECONDS;
    }

    protected long retrySleepMs() {
        return DEFAULT_RETRY_SLEEP_MS;
    }

    // ==================== 固定流程（子类通常无需覆写） ====================

    /** 读缓存：命中（含缓存 null）直接返回；miss 走分布式锁单飞回源。 */
    public V get(K key) {
        CacheGetResult<V> result = cache().GET(key);
        if (result.isSuccess()) {
            return result.getValue();
        }
        return loadWithMutex(key);
    }

    /** 写失效：删除本机本地 + 共享远程缓存。 */
    public void invalidate(K key) {
        cache().remove(key);
    }

    // ==================== 内部流程 ====================

    /** 分布式锁单飞：拿锁线程负责回源；拿不到锁的线程重读缓存，仍 miss 则兜底直查 DB。 */
    private V loadWithMutex(K key) {
        RLock lock = redissonClient.getLock(lockKeyPrefix() + key);
        boolean locked;
        try {
            locked = lock.tryLock(lockWaitSeconds(), lockLeaseSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (locked) {
            try {
                // double-check：等锁期间其它实例可能已回填
                CacheGetResult<V> result = cache().GET(key);
                if (result.isSuccess()) {
                    return result.getValue();
                }
                return loadAndPut(key);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        // 没拿到锁：短暂等待后重读缓存，仍 miss 则兜底直查 DB（保证可用性）
        sleepQuietly(retrySleepMs());
        CacheGetResult<V> result = cache().GET(key);
        if (result.isSuccess()) {
            return result.getValue();
        }
        return loadAndPut(key);
    }

    private V loadAndPut(K key) {
        V value = loadFromDb(key);
        cache().put(key, value);
        return value;
    }

    /** 惰性初始化缓存：避免父类构造时调用子类抽象方法。 */
    private Cache<K, V> cache() {
        Cache<K, V> c = cache;
        if (c == null) {
            synchronized (this) {
                c = cache;
                if (c == null) {
                    c = cacheManager.getOrCreateCache(QuickConfig.newBuilder(cacheName())
                            .cacheType(cacheType())
                            .expire(expire())
                            .localExpire(localExpire())
                            .localLimit(localLimit())
                            .cacheNullValue(cacheNullValue())
                            // 关闭跨节点本地缓存广播：写频繁时每 key 广播到所有节点 + 回源回填 put 也广播，
                            // 易形成"广播风暴"压 Redis；跨节点本地缓存靠 localExpire 短 TTL 最终一致。
                            .syncLocal(false)
                            .build());
                    cache = c;
                }
            }
        }
        return c;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
