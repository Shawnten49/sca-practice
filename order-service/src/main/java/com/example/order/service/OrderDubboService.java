package com.example.order.service;

import com.example.api.StockDubboService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderDubboService {
    private final JdbcTemplate jdbcTemplate;

    private RocketMQTemplate rocketMQTemplate;

    private static final Logger log = LoggerFactory.getLogger(OrderDubboService.class);

    @DubboReference
    private StockDubboService stockDubboService;   // 替代原来的 StockClient（Feign）

    public OrderDubboService(JdbcTemplate jdbcTemplate, RocketMQTemplate rocketMQTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @GlobalTransactional(rollbackFor = Exception.class)
    public String createOrder(Long userId, Long productId, Integer count, boolean fail) {
        jdbcTemplate.update(
                "insert into orders (user_id, product_id, count) values (?, ?, ?)",
                userId, productId, count);

        // 跨服务扣库存：这次走 Dubbo，XID 由 seata-dubbo 自动传递
        String stockResult = stockDubboService.deduct(productId, count);

        if (fail) {
            throw new RuntimeException("模拟下单失败，触发全局回滚");
        }

        String msg = String.format("订单创建成功，用户：%s，商品：%s，数量：%s", userId, productId, count);
        rocketMQTemplate.convertAndSend("topic-order:pay-success", msg);
        log.info("已发送mq消息：" + msg);

        return "下单成功：" + stockResult;
    }
}
