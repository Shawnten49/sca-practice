package com.example.user.config;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointsCacheEvictQueueTest {

    @SuppressWarnings("unchecked")
    private final RBlockingQueue<Long> queue = mock(RBlockingQueue.class);

    @SuppressWarnings("unchecked")
    private final RDelayedQueue<Long> delayedQueue = mock(RDelayedQueue.class);

    private final PointsCacheEvictQueue evictQueue;

    PointsCacheEvictQueueTest() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.<Long>getBlockingQueue("user:points:evict:queue")).thenReturn(queue);
        when(redisson.getDelayedQueue(queue)).thenReturn(delayedQueue);
        evictQueue = new PointsCacheEvictQueue(redisson);
    }

    @Test
    void submitOffersWithDelayInMillis() {
        evictQueue.submit(1L, Duration.ofMillis(500));

        verify(delayedQueue).offer(1L, 500L, TimeUnit.MILLISECONDS);
    }

    @Test
    void takeDelegatesToQueue() throws InterruptedException {
        when(queue.take()).thenReturn(1L);

        assertThat(evictQueue.take()).isEqualTo(1L);
    }
}
