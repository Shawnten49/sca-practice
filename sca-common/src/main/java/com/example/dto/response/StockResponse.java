package com.example.dto.response;

/** 库存接口出参（跨服务共享）。 */
public record StockResponse(Long id, Long productId, Integer quantity) {
}
