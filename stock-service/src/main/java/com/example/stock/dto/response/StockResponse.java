package com.example.stock.dto.response;

/** 库存接口出参。 */
public record StockResponse(Long id, Long productId, Integer quantity) {
}
