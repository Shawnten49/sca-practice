package com.example.user.mqconsumer;

import com.example.dto.PointAddMessage;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RocketMQMessageListener(topic = "topic-point", consumerGroup = "point-consumer")
public class PointMqConsumer implements RocketMQListener<PointAddMessage> {

    private static final Logger log = LoggerFactory.getLogger(PointMqConsumer.class);

    /** 去重键 TTL：需覆盖 RocketMQ 最长重试窗口；真正的生产兜底还应配合数据库唯一索引。 */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private static final String DEDUP_KEY_PREFIX = "mq:dedup:point:";

    private final StringRedisTemplate redisTemplate;

    public PointMqConsumer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
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

    /** 业务动作：给用户加积分。当前为演示实现，真实场景在这里写积分落库。 */
    void addPoints(PointAddMessage msg) {
        log.info("给用户 {} 加 {} 积分（来自订单 {}）", msg.userId(), msg.points(), msg.orderId());
    }
}
