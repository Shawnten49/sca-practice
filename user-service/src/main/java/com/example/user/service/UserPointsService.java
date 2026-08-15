package com.example.user.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
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

    private final UserMapper userMapper;
    private final Cache<Long, PointsValue> pointsCache;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final PointsCacheProperties props;

    public UserPointsService(UserMapper userMapper,
                             Cache<Long, PointsValue> pointsCache,
                             StringRedisTemplate redisTemplate,
                             RedissonClient redissonClient,
                             PointsCacheProperties props) {
        this.userMapper = userMapper;
        this.pointsCache = pointsCache;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.props = props;
    }

    /** 查询积分：Caffeine → Redis → MySQL（含空值缓存与击穿单飞）。 */
    public PointsVO getPoints(Long userId) {
        // ① Caffeine 本地缓存
        PointsValue local = pointsCache.getIfPresent(userId);
        if (local != null) {
            log.info("cache=caffeine userId={} points={}", userId, local.points());
            return new PointsVO(userId, local.points());
        }

        // ② Redis 分布式缓存
        String redisKey = redisKey(userId);
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            PointsValue value = parseRedisValue(cached);
            pointsCache.put(userId, value);
            log.info("cache=redis userId={} points={}", userId, value.points());
            return new PointsVO(userId, value.points());
        }

        // ③ 都未命中：单飞回源（防击穿）
        PointsValue value = loadWithMutex(userId, redisKey);
        return new PointsVO(userId, value.points());
    }

    /** 增量更新积分（可为负）：DB 原子更新 → 失效两级缓存 → 写后读回填。 */
    public PointsVO updatePoints(Long userId, Integer delta) {
        if (delta == null || delta == 0) {
            throw new IllegalArgumentException("delta 不能为 0");
        }
        if (Math.abs(delta) > props.getMaxDelta()) {
            throw new IllegalArgumentException("delta 超出上限: " + props.getMaxDelta());
        }

        int updated = userMapper.increasePoints(userId, delta);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在: userId=" + userId);
        }

        // Cache-Aside：先 DB 后删缓存
        String redisKey = redisKey(userId);
        redisTemplate.delete(redisKey);
        pointsCache.invalidate(userId);

        // 写后读最新值并回填两级缓存
        PointsValue value = loadFromDb(userId, redisKey);
        return new PointsVO(userId, value.points());
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
                String cached = redisTemplate.opsForValue().get(redisKey);
                if (cached != null) {
                    PointsValue value = parseRedisValue(cached);
                    pointsCache.put(userId, value);
                    return value;
                }
                return loadFromDb(userId, redisKey);
            } finally {
                lock.unlock();
            }
        }
        // 没拿到锁：短暂等待后重读缓存，避免所有线程同时打 DB
        sleepQuietly(RETRY_SLEEP_MS);
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            PointsValue value = parseRedisValue(cached);
            pointsCache.put(userId, value);
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
        redisTemplate.opsForValue().set(redisKey, String.valueOf(user.getPoints()), ttl);
        pointsCache.put(userId, PointsValue.of(user.getPoints()));
        log.info("cache=db userId={} points={}", userId, user.getPoints());
        return PointsValue.of(user.getPoints());
    }

    private long randomJitter(Duration jitter) {
        long ms = jitter.toMillis();
        return ThreadLocalRandom.current().nextLong(-ms, ms + 1);
    }

    private String redisKey(Long userId) {
        return REDIS_KEY_PREFIX + userId;
    }

    private PointsValue parseRedisValue(String cached) {
        return EMPTY_MARK.equals(cached)
                ? PointsValue.empty()
                : PointsValue.of(Integer.valueOf(cached));
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
