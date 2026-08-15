package com.example.user.config;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 积分缓存延迟失效队列：基于 Redisson RDelayedQueue。
 * 延迟任务持久化到 Redis，JVM 重启不丢；投递方与消费方（监听器）共用同一队列名。
 */
@Component
public class PointsCacheEvictQueue {

    private static final String QUEUE_NAME = "user:points:evict:queue";

    private final RBlockingQueue<Long> queue;
    private final RDelayedQueue<Long> delayedQueue;

    public PointsCacheEvictQueue(RedissonClient redissonClient) {
        this.queue = redissonClient.getBlockingQueue(QUEUE_NAME);
        this.delayedQueue = redissonClient.getDelayedQueue(queue);
    }

    /** 投递延迟失效任务：delay 后 userId 从延迟队列转入 base queue 供消费。 */
    public void submit(Long userId, Duration delay) {
        delayedQueue.offer(userId, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 阻塞取出一个到期的 userId；无元素时阻塞直到有元素或线程被中断。 */
    public Long take() throws InterruptedException {
        return queue.take();
    }
}
