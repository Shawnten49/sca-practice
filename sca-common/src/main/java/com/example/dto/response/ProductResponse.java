package com.example.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品接口出参（跨服务共享）。 */
public record ProductResponse(Long id, String name, String brand, BigDecimal price,
                              String description, LocalDateTime createTime) {
}
