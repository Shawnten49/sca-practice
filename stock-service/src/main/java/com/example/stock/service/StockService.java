package com.example.stock.service;

import com.example.exception.BusinessException;
import com.example.exception.ErrorCode;
import com.example.exception.InsufficientStockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class StockService {

    private final JdbcTemplate jdbcTemplate;

    public StockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 扣减库存：原子条件更新防超卖；HTTP 与 Dubbo 两条入口共用这一份逻辑。 */
    public void deduct(Long productId, Integer count) {
        if (count == null || count <= 0) {
            throw new IllegalArgumentException("count 必须大于 0");
        }
        int updated = jdbcTemplate.update(
                "update stock set quantity = quantity - ? where product_id = ? and quantity >= ?",
                count, productId, count);
        if (updated == 0) {
            throw new InsufficientStockException("库存不足");
        }
    }

    public Map<String, Object> query(Long productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select product_id, quantity from stock where product_id = ?", productId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "商品不存在: productId=" + productId);
        }
        return rows.get(0);
    }
}
