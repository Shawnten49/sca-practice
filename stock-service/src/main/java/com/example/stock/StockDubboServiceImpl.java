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
        int updated = jdbcTemplate.update(
                "update stock set quantity = quantity - ? where product_id = ? and quantity >= ?",
                count, productId, count);
        if (updated == 0) {
            throw new RuntimeException("dubbo:库存不足");
        }
        return "dubbo:扣减成功";
    }
}