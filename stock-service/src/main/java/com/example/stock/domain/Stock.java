package com.example.stock.domain;

/**
 * 库存只读值对象（库存查询返回，避免裸 Map 无类型约束）。
 */
public record Stock(Long productId, Integer quantity) {
}
