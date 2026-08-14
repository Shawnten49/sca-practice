package com.example.order.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单实体（MyBatis-Plus）。
 * id 由 IdWorker 预生成后显式插入（IdType.INPUT），不依赖数据库自增；
 * createTime 只读映射，不参与 insert，由数据库 DEFAULT CURRENT_TIMESTAMP 填充。
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
