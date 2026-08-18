package com.example.order.service;

import com.example.api.StockDubboService;
import com.example.api.dto.StockDeductResult;
import com.example.entity.Order;
import com.example.order.mapper.OrderMapper;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.seata.common.util.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderDubboService {

    private final RocketMQTemplate rocketMQTemplate;

    private final OrderMapper orderMapper;

    private static final Logger log = LoggerFactory.getLogger(OrderDubboService.class);

    private static final IdWorker ID_WORKER = new IdWorker(1L);

    @DubboReference
    private StockDubboService stockDubboService;   // 替代原来的 StockClient（Feign）

    public OrderDubboService(OrderMapper orderMapper, RocketMQTemplate rocketMQTemplate) {
        this.orderMapper = orderMapper;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 全局事务（下单 + Dubbo 扣库存），由 ShardingSphere Seata 集成 +
     * {@code @Transactional("seataTransactionManager")} 管理。
     * 注意：消息发送不能放在事务方法里——否则会出现"消息已发出、全局事务却回滚"的不一致。
     * 正确姿势：本方法返回（全局事务已提交）后，再由 controller 调 sendPaySuccessMessage。
     */
    @Transactional("seataTransactionManager")
    public String createOrder(Long userId, Long productId, Integer count, boolean fail) {
        Long orderId = ID_WORKER.nextId();

        orderMapper.insertOrder(Order.builder()
                .id(orderId)
                .userId(userId)
                .productId(productId)
                .count(count)
                .build());

        // 跨服务扣库存：这次走 Dubbo，XID 由 seata-dubbo 自动传递
        StockDeductResult stockResult = stockDubboService.deduct(productId, count);

        if (fail) {
            throw new RuntimeException("模拟下单失败，触发全局回滚");
        }

        //仅仅做mq消息测试，不需要事务消息
        sendPaySuccessMessage(userId, productId, count);

        return "下单成功：" + stockResult.message();
    }

    /** after-commit：全局事务提交后再发消息，避免消息与事务状态不一致。 */
    public void sendPaySuccessMessage(Long userId, Long productId, Integer count) {
        String msg = String.format("订单创建成功，用户：%s，商品：%s，数量：%s", userId, productId, count);
        rocketMQTemplate.convertAndSend("topic-order:pay-success", msg);
        log.info("已发送mq消息：{}", msg);
    }
}
