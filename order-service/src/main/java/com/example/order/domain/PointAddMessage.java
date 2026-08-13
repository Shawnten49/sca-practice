package com.example.order.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class PointAddMessage {
    private Long orderId;    // 业务主键：回查时靠它查订单表
    private Long userId;     // 给哪个用户加积分
    private Long productId;  //产品ID
    private Integer count;   //购买数量
    private Integer points;  // 加多少积分
}
