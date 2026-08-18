package com.example.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 积分流水实体（MyBatis-Plus）。
 * order_id 有唯一索引：同一订单的积分只能落库一次，作为消息去重的 DB 层兜底。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_points")
public class UserPoints {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long orderId;

    private Integer points;

    private LocalDateTime createTime;
}
