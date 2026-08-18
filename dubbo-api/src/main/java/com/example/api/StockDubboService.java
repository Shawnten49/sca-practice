package com.example.api;

import com.example.api.dto.StockDeductResult;

public interface StockDubboService {
    StockDeductResult deduct(Long productId, Integer count);
}
