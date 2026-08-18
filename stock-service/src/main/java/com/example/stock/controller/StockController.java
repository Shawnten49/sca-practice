package com.example.stock.controller;

import com.example.common.Result;
import com.example.stock.converter.StockConverter;
import com.example.stock.dto.response.StockResponse;
import com.example.stock.service.StockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/stock/deduct")
    public String deduct(@RequestParam Long productId,
                         @RequestParam Integer count) {
        stockService.deduct(productId, count);
        return "http:扣减成功";
    }

    @GetMapping("/stock/query")
    public Result<StockResponse> query(@RequestParam Long productId) {
        return Result.ok(StockConverter.toResponse(stockService.query(productId)));
    }
}
