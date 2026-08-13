package com.example.order.service;

import com.example.order.client.StockClient;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final JdbcTemplate jdbcTemplate;
    private final StockClient stockClient;

    public OrderService(JdbcTemplate jdbcTemplate, StockClient stockClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockClient = stockClient;
    }

    @GlobalTransactional(rollbackFor = Exception.class)
    public String createOrder(Long userId, Long productId, Integer count, boolean fail) {
        // 1) 本库插订单
        jdbcTemplate.update(
                "insert into orders (user_id, product_id, count) values (?, ?, ?)",
                userId, productId, count);

        // 2) 跨服务扣库存（XID 由 Seata 自动通过 Feign 传递）
        String stockResult = stockClient.deduct(productId, count);

        // 3) 模拟业务失败：订单和库存都要回滚
        if (fail) {
            throw new RuntimeException("模拟下单失败，触发全局回滚");
        }
        return "下单成功：" + stockResult;
    }
}