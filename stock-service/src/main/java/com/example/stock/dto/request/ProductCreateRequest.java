package com.example.stock.dto;

import java.math.BigDecimal;

/**
 * 保存商品请求体。
 *
 * @param name        商品名称（必填）
 * @param brand       品牌（必填）
 * @param price       价格（必填，>= 0）
 * @param description 描述（可空，规范化为空字符串）
 */
public record ProductSaveRequest(String name, String brand, BigDecimal price, String description) {
}
