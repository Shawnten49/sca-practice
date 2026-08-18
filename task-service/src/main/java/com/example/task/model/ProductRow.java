package com.example.task.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品行（product）。 */
public record ProductRow(Long id, String name, String brand, BigDecimal price,
                         String description, LocalDateTime createTime) {
}
