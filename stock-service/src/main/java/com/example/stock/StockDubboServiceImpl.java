package com.example.stock;

import com.example.api.StockDubboService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.jdbc.core.JdbcTemplate;

@DubboService
public class StockDubboServiceImpl implements StockDubboService {

    private final JdbcTemplate jdbcTemplate;

    public StockDubboServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String deduct(Long productId, Integer count) {
        if (count == null || count <= 0) {
            throw new IllegalArgumentException("count 必须大于 0");
        }
        int updated = jdbcTemplate.update(
                "update stock set quantity = quantity - ? where product_id = ? and quantity >= ?",
                count, productId, count);
        if (updated == 0) {
            throw new RuntimeException("dubbo:库存不足");
        }
        return "dubbo:扣减成功";
    }
}