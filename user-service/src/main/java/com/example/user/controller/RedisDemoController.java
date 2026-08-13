package com.example.user.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class RedisDemoController {

    private final StringRedisTemplate redis;

    public RedisDemoController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 缓存读写
    @GetMapping("/cache")
    public String cache(@RequestParam String userId) {
        String key = "user:" + userId;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return "cache: " + cached;          // 命中缓存
        }

        String value = "user-" + userId;       // 模拟查库
        redis.opsForValue().set(key, value, 60, TimeUnit.SECONDS);
        return "db: " + value;                  // 未命中，回填
    }

    // 分布式锁
    @GetMapping("/lock")
    public String lock(@RequestParam Long orderId) {
        String lockKey = "lock:order:" + orderId;
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, 10, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(ok)) {
            return "busy";
        }
        try {
            return "locked, do business";
        } finally {
            // 先比对再删，防误删别人的锁（value 是当前 token 才删）
            if (token.equals(redis.opsForValue().get(lockKey))) {
                redis.delete(lockKey);
            }
        }
    }

    // 计数器
    @GetMapping("/visit")
    public Long visit() {
        return redis.opsForValue().increment("visit:2026-08-13");
    }
}
