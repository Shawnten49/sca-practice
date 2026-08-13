package com.example.order.service;

import com.example.order.client.StockClient;
import com.example.order.domain.Order;
import com.example.order.mapper.OrderMapper;
import org.apache.seata.common.util.IdWorker;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final StockClient stockClient;

    private static final IdWorker ID_WORKER = new IdWorker(2L);

    public OrderService(OrderMapper orderMapper, StockClient stockClient) {
        this.orderMapper = orderMapper;
        this.stockClient = stockClient;
    }

    @GlobalTransactional(rollbackFor = Exception.class)
    public String createOrder(Long userId, Long productId, Integer count, boolean fail) {
        Long orderId = ID_WORKER.nextId();
        // 1) 本库插订单
        orderMapper.insert(new Order(orderId, userId, productId, count));
        // 2) 跨服务扣库存（XID 由 Seata 自动通过 Feign 传递）
        String stockResult = stockClient.deduct(productId, count);

        // 3) 模拟业务失败：订单和库存都要回滚
        if (fail) {
            throw new RuntimeException("模拟下单失败，触发全局回滚");
        }
        return "下单成功：" + stockResult;
    }
}