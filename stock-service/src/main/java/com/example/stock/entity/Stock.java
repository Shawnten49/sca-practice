package com.example.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 库存实体（MyBatis-Plus）。
 * id 数据库自增；product_id 为业务唯一键，扣减/查询都按它路由。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Integer quantity;
}
