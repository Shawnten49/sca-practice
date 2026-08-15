package com.example.user.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.user.config.PointsCacheEvictQueue;
import com.example.user.config.PointsCacheProperties;
import com.example.user.domain.PointsValue;
import com.example.user.domain.User;
import com.example.user.dto.PointsVO;
import com.example.user.mapper.UserMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 用户积分服务：查询走 Caffeine → Redis → MySQL 三级缓存；
 * 更新走 DB 原子增量 + 缓存失效（Cache-Aside）。
 */
@Service
public class UserPointsService {

    private static final Logger log = LoggerFactory.getLogger(UserPointsService.class);

    private static final String REDIS_KEY_PREFIX = "user:points:";
    private static final String LOCK_KEY_PREFIX = "user:points:lock:";
    private static final String EMPTY_MARK = "__EMPTY__";

    private static final long LOCK_WAIT_SECONDS = 0;
    private static final long LOCK_LEASE_SECONDS = 10;
    private static final long RETRY_SLEEP_MS = 50;

    /** TTL 抖动后可能为负的下限兜底值。 */
    private static final Duration MIN_REDIS_TTL = Duration.ofSeconds(1);

    private final UserMapper userMapper;
    private final Cache<Long, PointsValue> pointsCache;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final PointsCacheProperties props;
    private final PointsCacheEvictQueue evictQueue;

    public UserPointsService(UserMapper userMapper,
                             Cache<Long, PointsValue> pointsCache,
                             StringRedisTemplate redisTemplate,
                             RedissonClient redissonClient,
                             PointsCacheProperties props,
                             PointsCacheEvictQueue evictQueue) {
        this.userMapper = userMapper;
        this.pointsCache = pointsCache;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.props = props;
        this.evictQueue = evictQueue;
    }

    /** 查询积分：Caffeine → Redis → MySQL（含空值缓存与击穿单飞）。用户不存在返回 404。 */
    public PointsVO getPoints(Long userId) {
        validateUserId(userId);

        // ① Caffeine 本地缓存
        PointsValue local = pointsCache.getIfPresent(userId);
        if (local != null) {
            PointsVO vo = toVo(userId, local);
            log.debug("cache=caffeine userId={} points={}", userId, vo.points());
            return vo;
        }

        // ② Redis 分布式缓存
        PointsValue value = tryReadRedis(redisKey(userId), userId);
        if (value != null) {
            PointsVO vo = toVo(userId, value);
            log.debug("cache=redis userId={} points={}", userId, vo.points());
            return vo;
        }

        // ③ 都未命中：单飞回源（防击穿）
        return toVo(userId, loadWithMutex(userId, redisKey(userId)));
    }

    /** 增量更新积分（可为负）：DB 原子更新 → 失效两级缓存 → 写后读回填。 */
    public PointsVO updatePoints(Long userId, Integer delta) {
        validateUserId(userId);
        if (delta == null || delta == 0) {
            throw new IllegalArgumentException("delta 不能为 0");
        }
        // Math.abs(int) 对 Integer.MIN_VALUE 溢出为负，先转 long 再比较
        if (Math.abs((long) delta) > props.getMaxDelta()) {
            throw new IllegalArgumentException("delta 超出上限: " + props.getMaxDelta());
        }

        // 延迟双删 ①：写前删，避免写库期间命中旧缓存
        invalidateNow(userId);

        int updated = userMapper.increasePoints(userId, delta);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
        }

        // 延迟双删 ②：写后立即删 + 投递延迟删（兜住并发读回填旧值的竞态窗口）
        invalidatePoints(userId);

        // 写后读最新值并回填两级缓存
        return toVo(userId, loadFromDb(userId, redisKey(userId)));
    }

    /** 立即删除两级缓存（不含延迟任务），供延迟队列监听器与写前删复用。 */
    public void invalidateNow(Long userId) {
        redisTemplate.delete(redisKey(userId));
        pointsCache.invalidate(userId);
    }

    /**
     * 立即失效 + 投递延迟失效（延迟双删）。
     * 供更新接口写后、以及 PointMqConsumer 事务提交后复用。
     */
    public void invalidatePoints(Long userId) {
        invalidateNow(userId);
        submitDelayedInvalidate(userId);
    }

    private void submitDelayedInvalidate(Long userId) {
        evictQueue.submit(userId, props.getDoubleDeleteDelay());
    }

    /** 击穿保护：拿锁线程负责回源；拿不到锁的线程 50ms 后重读 Redis，仍 miss 则直查 DB。 */
    private PointsValue loadWithMutex(Long userId, String redisKey) {
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
                // double-check：等锁期间其他线程可能已回填
                PointsValue value = tryReadRedis(redisKey, userId);
                if (value != null) {
                    return value;
                }
                return loadFromDb(userId, redisKey);
            } finally {
                // 回源耗时超过 lease 时锁已被 Redisson 自动释放，直接 unlock 会抛 IllegalMonitorStateException
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        // 没拿到锁：短暂等待后重读缓存，避免所有线程同时打 DB
        sleepQuietly(RETRY_SLEEP_MS);
        PointsValue value = tryReadRedis(redisKey, userId);
        if (value != null) {
            return value;
        }
        return loadFromDb(userId, redisKey);
    }

    /** DB 回源并回填两级缓存；用户不存在时回填空值标记（防穿透）。 */
    private PointsValue loadFromDb(Long userId, String redisKey) {
        User user = userMapper.selectUserById(userId).orElse(null);
        if (user == null) {
            redisTemplate.opsForValue().set(redisKey, EMPTY_MARK, props.getEmptyTtl());
            pointsCache.put(userId, PointsValue.empty());
            log.info("cache=db userId={} empty=true", userId);
            return PointsValue.empty();
        }

        Duration ttl = props.getRedisTtl().plus(
                Duration.ofMillis(randomJitter(props.getRedisTtlJitter())));
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = MIN_REDIS_TTL;
        }
        redisTemplate.opsForValue().set(redisKey, String.valueOf(user.getPoints()), ttl);
        pointsCache.put(userId, PointsValue.of(user.getPoints()));
        log.info("cache=db userId={} points={}", userId, user.getPoints());
        return PointsValue.of(user.getPoints());
    }

    /**
     * 读 Redis 并回填 Caffeine；未命中返回 null。
     * 值非法（非数字且非空标记）时视为脏数据，删除后按未命中处理。
     */
    private PointsValue tryReadRedis(String redisKey, Long userId) {
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached == null) {
            return null;
        }
        PointsValue value = parseRedisValue(cached);
        if (value == null) {
            redisTemplate.delete(redisKey);
            return null;
        }
        pointsCache.put(userId, value);
        return value;
    }

    private long randomJitter(Duration jitter) {
        long ms = jitter.toMillis();
        return ThreadLocalRandom.current().nextLong(-ms, ms + 1);
    }

    private String redisKey(Long userId) {
        return REDIS_KEY_PREFIX + userId;
    }

    /** 返回 null 表示缓存值非法（脏数据），由调用方删除后回源。 */
    private PointsValue parseRedisValue(String cached) {
        if (EMPTY_MARK.equals(cached)) {
            return PointsValue.empty();
        }
        try {
            return PointsValue.of(Integer.valueOf(cached));
        } catch (NumberFormatException e) {
            log.warn("积分缓存值非法，按未命中处理: {}", cached);
            return null;
        }
    }

    private PointsVO toVo(Long userId, PointsValue value) {
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
        }
        return new PointsVO(userId, value.points());
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId 必须是正整数");
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
