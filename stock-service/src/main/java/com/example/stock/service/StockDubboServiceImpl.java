package com.example.stock.service;

import com.example.api.StockDubboService;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class StockDubboServiceImpl implements StockDubboService {

    private final StockService stockService;

    public StockDubboServiceImpl(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    public String deduct(Long productId, Integer count) {
        stockService.deduct(productId, count);
        return "dubbo:扣减成功";
    }
}
