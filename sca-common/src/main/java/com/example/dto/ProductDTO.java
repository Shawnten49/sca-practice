package com.example.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品跨服务传输对象（Redis key: task:product:{id} 的 value）。
 * 由 task-service 刷入缓存，供各业务服务读取。
 */
public record ProductDTO(Long id, String name, String brand, BigDecimal price,
                         String description, LocalDateTime createTime) {
}
