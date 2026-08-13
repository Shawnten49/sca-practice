package com.example.order;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "stock-service")
public interface StockClient {

    @GetMapping("/stock/deduct")
    String deduct(@RequestParam("productId") Long productId,
                  @RequestParam("count") Integer count);
}