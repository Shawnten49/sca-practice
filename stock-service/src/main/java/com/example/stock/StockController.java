package com.example.stock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StockController {

    private final JdbcTemplate jdbcTemplate;

    public StockController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/stock/deduct")
    public String deduct(@RequestParam Long productId,
                         @RequestParam Integer count) {
        int updated = jdbcTemplate.update(
                "update stock set quantity = quantity - ? where product_id = ? and quantity >= ?",
                count, productId, count);
        if (updated == 0) {
            throw new RuntimeException("http:库存不足");
        }
        return "http:扣减成功";
    }

    @GetMapping("/stock/query")
    public Map<String, Object> query(@RequestParam Long productId) {
        return jdbcTemplate.queryForMap(
                "select product_id, quantity from stock where product_id = ?", productId);
    }
}