package com.example.dto;

/**
 * 跨服务消息体：下单后给用户加积分。
 * 统一放在共享模块，避免 order/user 两端各自维护一份。
 */
public record PointAddMessage(
        Long orderId,    // 业务主键：回查时靠它查订单表
        Long userId,     // 给哪个用户加积分
        Long productId,  // 产品ID
        Integer count,   // 购买数量
        Integer points   // 加多少积分
) {
}
