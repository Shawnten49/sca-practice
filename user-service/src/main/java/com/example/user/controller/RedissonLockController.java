package com.example.user.controller;

import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

@RestController
public class RedissonLockController {
    private final RedissonClient redisson;

    public RedissonLockController(RedissonClient redisson) {
        this.redisson = redisson;
    }

    // 1) 普通锁：最多等 3 秒；默认看门狗自动续期，业务跑多久都行
    @GetMapping("/lock-redisson")
    public String lock(@RequestParam Long orderId) throws InterruptedException {
        RLock lock = redisson.getLock("lock:order:" + orderId);
        if (!lock.tryLock(3, TimeUnit.SECONDS)) {
            return "busy";
        }
        try {
            Thread.sleep(6000);            // 模拟业务耗时
            return "locked, do business";   // 业务若超过 30s，看门狗会持续续期
        } finally {
            lock.unlock();                  // 释放即取消续期
        }
    }

    // 2) 可重入：同一个线程重复 lock()，计数 +1；unlock 次数匹配才真正释放
    @GetMapping("/lock-reentrant")
    public String reentrant(@RequestParam Long orderId) {
        RLock lock = redisson.getLock("lock:order:" + orderId);
        lock.lock();
        try {
            lock.lock();                    // 重入：计数 1 -> 2，不会死锁
            try {
                Thread.sleep(6000);            // 模拟业务耗时
                return "reentrant ok";
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                lock.unlock();              // 计数 2 -> 1，锁还在
            }
        } finally {
            lock.unlock();                  // 计数 1 -> 0，真正释放
        }
    }

    // 3) 读写锁：读读并发，读写 / 写写互斥
    @GetMapping("/lock-rw/read")
    public String rwRead(@RequestParam Long orderId) throws InterruptedException {
        RReadWriteLock rwLock = redisson.getReadWriteLock("rw:order:" + orderId);
        rwLock.readLock().lock();
        try {
            Thread.sleep(5000);             // 模拟读操作
            return "read done";
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @GetMapping("/lock-rw/write")
    public String rwWrite(@RequestParam Long orderId) throws InterruptedException {
        RReadWriteLock rwLock = redisson.getReadWriteLock("rw:order:" + orderId);
        rwLock.writeLock().lock();
        try {
            Thread.sleep(2000);             // 模拟写操作
            return "write done";
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
