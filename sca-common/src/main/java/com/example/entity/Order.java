package com.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单实体（MyBatis-Plus，跨服务共享：order-service 经 ShardingSphere 逻辑表路由，
 * task-service 直接按物理分片表 orders_0~3 缓存投影复用）。
 * id 由 IdWorker 预生成后显式插入（IdType.INPUT）；createTime 由数据库默认值填充。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("orders")
public class Order {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Long productId;

    private Integer count;

    private LocalDateTime createTime;
}
