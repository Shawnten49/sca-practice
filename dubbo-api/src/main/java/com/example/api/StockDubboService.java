package com.example.api;


public interface StockDubboService {
    String deduct(Long productId, Integer count);
}