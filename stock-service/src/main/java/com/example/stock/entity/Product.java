package com.example.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体（MyBatis-Plus）。
 * id 由调用方预生成（雪花 ID，IdType.INPUT）；数据权威在 MySQL，
 * 由 Canal 异步同步到 ES 作为查询副本（见 es.ProductDocument）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("product")
public class Product {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 商品名称（搜索主字段） */
    private String name;

    private String brand;

    private BigDecimal price;

    private String description;

    private LocalDateTime createTime;
}
