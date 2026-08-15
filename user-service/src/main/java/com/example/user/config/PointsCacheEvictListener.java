package com.example.user.config;

import com.example.user.service.UserPointsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 积分缓存延迟失效监听器：常驻 daemon 线程消费延迟队列到期的 userId，
 * 并执行立即删缓存（对应"延迟双删"的第二删）。
 */
@Component
public class PointsCacheEvictListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PointsCacheEvictListener.class);

    private final PointsCacheEvictQueue evictQueue;
    private final UserPointsService userPointsService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public PointsCacheEvictListener(PointsCacheEvictQueue evictQueue,
                                    UserPointsService userPointsService) {
        this.evictQueue = evictQueue;
        this.userPointsService = userPointsService;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this::runLoop, "points-cache-evict-listener");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                consume(evictQueue.take());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("处理积分缓存延迟失效任务失败", e);
            }
        }
    }

    /** 执行一次失效（包内可见，便于单测）。 */
    void consume(Long userId) {
        if (userId != null) {
            userPointsService.invalidateNow(userId);
        }
    }
}
