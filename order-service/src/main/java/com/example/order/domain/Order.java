package com.example.order.domain;

/**
 * 订单业务对象（只读值对象）：本地事务插入与事务消息回查时使用。
 */
public record Order(Long orderId, Long userId, Long productId, Integer count) {
}
