package com.example.order.service;

import com.example.id.IdGenerator;
import com.example.order.client.StockClient;
import com.example.order.domain.Order;
import com.example.order.mapper.OrderMapper;
import org.apache.seata.common.util.IdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final StockClient stockClient;

//    private static final IdWorker ID_WORKER = new IdWorker(2L);
    private final IdGenerator idGenerator;

    public OrderService(OrderMapper orderMapper, StockClient stockClient, IdGenerator idGenerator) {
        this.orderMapper = orderMapper;
        this.stockClient = stockClient;
        this.idGenerator = idGenerator;
    }

    // 显式指定 seataTransactionManager（BASE 数据源）：
    // 本方法跨服务扣库存，需要 Seata 全局事务；配合 OrderMapper 走分片路由。
    @Transactional("seataTransactionManager")
    public String createOrder(Long userId, Long productId, Integer count, boolean fail) {
        Long orderId = idGenerator.nextId();
        // 1) 本库插订单
        orderMapper.insertOrder(Order.builder()
                .id(orderId)
                .userId(userId)
                .productId(productId)
                .count(count)
                .build());
        // 2) 跨服务扣库存（XID 由 Seata 自动通过 Feign 传递）
        String stockResult = stockClient.deduct(productId, count);

        // 3) 模拟业务失败：订单和库存都要回滚
        if (fail) {
            throw new RuntimeException("模拟下单失败，触发全局回滚");
        }
        return "下单成功：" + stockResult;
    }
}
