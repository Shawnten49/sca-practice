package com.example.user.mqconsumer;

import com.example.dto.PointAddMessage;
import com.example.user.domain.UserPoints;
import com.example.user.mapper.UserMapper;
import com.example.user.mapper.UserPointsMapper;
import com.example.user.service.UserPointsService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@RocketMQMessageListener(topic = "topic-point", consumerGroup = "point-consumer")
public class PointMqConsumer implements RocketMQListener<PointAddMessage> {

    private static final Logger log = LoggerFactory.getLogger(PointMqConsumer.class);

    /** 去重键 TTL：需覆盖 RocketMQ 最长重试窗口；生产兜底由 user_points.order_id 唯一索引保证。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private static final String DEDUP_KEY_PREFIX = "mq:dedup:point:";

    private final StringRedisTemplate redisTemplate;

    private final UserPointsMapper userPointsMapper;

    private final UserMapper userMapper;

    private final TransactionTemplate transactionTemplate;

    private final UserPointsService userPointsService;

    public PointMqConsumer(StringRedisTemplate redisTemplate,
                           UserPointsMapper userPointsMapper,
                           UserMapper userMapper,
                           TransactionTemplate transactionTemplate,
                           UserPointsService userPointsService) {
        this.redisTemplate = redisTemplate;
        this.userPointsMapper = userPointsMapper;
        this.userMapper = userMapper;
        this.transactionTemplate = transactionTemplate;
        this.userPointsService = userPointsService;
    }

    @Override
    public void onMessage(PointAddMessage msg) {
        String dedupKey = DEDUP_KEY_PREFIX + msg.orderId();

        // SETNX 抢占：只有第一个消费者能拿到 true，重复消息直接跳过
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "1", DEDUP_TTL);
        if (!Boolean.TRUE.equals(claimed)) {
            log.info("重复消息跳过：订单 {} 的积分已处理过", msg.orderId());
            return;
        }

        try {
            addPoints(msg);
        } catch (Exception e) {
            // 处理失败：释放去重键，让 RocketMQ 重试时能重新消费
            redisTemplate.delete(dedupKey);
            log.error("加积分失败，已释放去重键 orderId={}", msg.orderId(), e);
            throw e;
        }
    }

    /**
     * 业务动作：积分流水落库 + 累加用户积分，两者在同一本地事务中。
     * INSERT IGNORE：order_id 唯一索引命中时静默忽略（返回 0）；
     * 只有真正插入成功（返回 1）才累加 users.points，避免重复加分。
     */
    void addPoints(PointAddMessage msg) {
        AtomicBoolean added = new AtomicBoolean(false);
        transactionTemplate.executeWithoutResult(status -> {
            int inserted = userPointsMapper.insertUserPoints(UserPoints.builder()
                    .userId(msg.userId())
                    .orderId(msg.orderId())
                    .points(msg.points())
                    .build());
            if (inserted == 0) {
                // 唯一索引兜底：该订单积分已落库（如 Redis 去重键过期后的重试），不重复累加
                log.info("积分流水已存在（INSERT IGNORE 兜底），跳过 orderId={}", msg.orderId());
                return;
            }
            userMapper.increasePoints(msg.userId(), msg.points());
            log.info("给用户 {} 加 {} 积分（来自订单 {}）", msg.userId(), msg.points(), msg.orderId());
            added.set(true);
        });
        // 事务提交后再失效缓存，避免回滚时缓存已回填错误值；也防止读到加积分前的旧缓存
        if (added.get()) {
            userPointsService.invalidatePoints(msg.userId());
        }
    }
}
