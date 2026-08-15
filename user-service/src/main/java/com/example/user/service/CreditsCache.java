package com.example.user.service;

import com.alicp.jetcache.anno.CacheInvalidate;
import com.alicp.jetcache.anno.CachePenetrationProtect;
import com.alicp.jetcache.anno.CacheType;
import com.alicp.jetcache.anno.Cached;
import com.example.user.domain.User;
import com.example.user.mapper.UserMapper;
import org.springframework.stereotype.Component;

/**
 * 信用点缓存访问器（JetCache 三级：Caffeine → Redis → MySQL）。
 * 独立 Bean 以规避 self-invocation 导致注解失效。
 */
@Component
public class CreditsCache {

    private final UserMapper userMapper;

    public CreditsCache(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 三级读：Caffeine → Redis → MySQL；cacheNullValue 防穿透，@CachePenetrationProtect 防击穿。 */
    @Cached(name = "user:credits", key = "#userId",
            cacheType = CacheType.BOTH,
            expire = 300, localExpire = 30, localLimit = 10000,
            cacheNullValue = true)
    @CachePenetrationProtect
    public Integer load(Long userId) {
        return userMapper.selectUserById(userId).map(User::getCredits).orElse(null);
    }

    /** 写失效：即时失效本地+远程，并广播到其它节点（依赖 remote.broadcastChannel 配置）。 */
    @CacheInvalidate(name = "user:credits", key = "#userId")
    public void invalidate(Long userId) {
        // 方法体由 JetCache 拦截执行失效，此处无需逻辑
    }
}
