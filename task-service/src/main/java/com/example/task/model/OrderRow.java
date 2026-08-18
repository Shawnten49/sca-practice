package com.example.task.model;

import java.time.LocalDateTime;

/** 订单行（orders_0 ~ orders_3）。 */
public record OrderRow(Long id, Long userId, Long productId, Integer count, LocalDateTime createTime) {
}
