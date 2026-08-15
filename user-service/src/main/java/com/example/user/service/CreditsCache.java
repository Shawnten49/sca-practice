package com.example.user.service;

import com.alicp.jetcache.CacheManager;
import com.example.user.cache.MultiLevelCacheTemplate;
import com.example.user.domain.User;
import com.example.user.mapper.UserMapper;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 信用点缓存访问器：三级（Caffeine → Redis → MySQL）。
 * 固定流程（读穿透 + 分布式锁单飞 + 空值缓存 + 失效）由 {@link MultiLevelCacheTemplate} 提供，
 * 这里只声明「缓存名」与「怎么查库」。
 */
@Component
public class CreditsCache extends MultiLevelCacheTemplate<Long, Integer> {

    private final UserMapper userMapper;

    public CreditsCache(CacheManager cacheManager, RedissonClient redissonClient, UserMapper userMapper) {
        super(cacheManager, redissonClient);
        this.userMapper = userMapper;
    }

    @Override
    protected String cacheName() {
        return "user:credits";
    }

    @Override
    protected Integer loadFromDb(Long userId) {
        return userMapper.selectUserById(userId).map(User::getCredits).orElse(null);
    }
}
