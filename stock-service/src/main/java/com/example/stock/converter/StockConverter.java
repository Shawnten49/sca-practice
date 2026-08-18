package com.example.stock.converter;

import com.example.stock.dto.response.StockResponse;
import com.example.stock.entity.Stock;

/** Stock 实体 → StockResponse 转换。 */
public final class StockConverter {

    private StockConverter() {
    }

    public static StockResponse toResponse(Stock stock) {
        return new StockResponse(stock.getId(), stock.getProductId(), stock.getQuantity());
    }
}
