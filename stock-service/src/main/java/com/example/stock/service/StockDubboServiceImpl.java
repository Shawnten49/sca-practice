package com.example.stock.service;

import com.example.api.StockDubboService;
import com.example.api.dto.StockDeductResult;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class StockDubboServiceImpl implements StockDubboService {

    private final StockService stockService;

    public StockDubboServiceImpl(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public StockDeductResult deduct(Long productId, Integer count) {
        stockService.deduct(productId, count);
        return StockDeductResult.ok();
    }
}
